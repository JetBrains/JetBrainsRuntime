/*
 * Copyright 2026 JetBrains s.r.o.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
// Blits, copyArea and readbacks (SKBlitLoops.h).
// All functions run on the Skia queue flusher thread.

#include "SKBlitLoops.h"
#include "SKContext.h"
#include "SKGraphicsConfig.h"
#include "SKImageSources.h"
#include "SKPaints.h"
#include "SKSurfaceData.h"

#include "jlong.h"
#include "Trace.h"
#include "java_awt_image_AffineTransformOp.h"
#include "sun_java2d_skia_SKBlitLoops.h"
#include "sun_java2d_skia_SKSurfaceData.h"

#include "include/core/SkRect.h"
#include "include/core/SkSamplingOptions.h"
#include "include/core/SkTileMode.h"
#include "include/core/SkVertices.h"
#include "include/gpu/graphite/GraphiteTypes.h"
#include "include/gpu/graphite/Surface.h"

#include <algorithm>
// ReSharper disable once CppUnusedIncludeDirective - std::floor and std::ceil are used here
#include <cmath>
#include <cstddef>
// ReSharper disable once CppUnusedIncludeDirective - memcpy is used here
#include <cstring>
#include <vector>

using namespace skgpu::graphite;

namespace {

// The int pixel formats this file handles are 4 bytes per pixel.
constexpr size_t BYTES_PER_PIXEL = 4;
// SKBlitLoops packs the source type and the hint into a byte each of packedParams.
constexpr jint PACKED_BYTE_MASK = 0xff;
// The texture limit assumed when the context has no recorder to ask (every GPU allows this).
constexpr jint DEFAULT_MAX_TEXTURE_SIZE = 4096;
// Device coordinates are clamped to this before an int conversion: extreme scales stay in range.
constexpr double COORDINATE_LIMIT = 1.0e9;
// A GPU texel's value sits at its center.
constexpr float HALF_TEXEL = 0.5f;

// The interpolation hint as AffineTransformOp numbers it: nearest neighbor, or linear for bilinear and bicubic.
SkSamplingOptions samplingForHint(const jint hint) {
    return hint == java_awt_image_AffineTransformOp_TYPE_NEAREST_NEIGHBOR ? SkSamplingOptions(SkFilterMode::kNearest)
                                                                            : SkSamplingOptions(SkFilterMode::kLinear);
}

// Java2D blits are hard-edged quads sampling the source with edge extension
// (the Metal pipeline draws textured quads). Graphite anti-aliases every rect
// that is not pixel-aligned in device space whatever the paint says, so a
// transformed drawImageRect gets coverage edges; a mesh is rasterized without
// coverage. The sample coordinates are kept half a texel inside the source rect
// so filtering never blends in neighboring texels (SrcRectConstraint strict).
void drawImageQuad(const sk_sp<SkImage> &image, const SkRect &sourceRect, const SkRect &destRect,
                   const SkSamplingOptions &sampling, SkPaint paint) {
    const SKContext &context = SKContext::current();
    sk_sp<SkShader> shader = image->makeShader(SkTileMode::kClamp, SkTileMode::kClamp, sampling);
    SkRect inset = sourceRect;
    inset.inset(std::min(HALF_TEXEL, sourceRect.width() / 2), std::min(HALF_TEXEL, sourceRect.height() / 2));
    shader = SkShaders::CoordClamp(std::move(shader), inset);
    paint.setShader(std::move(shader));
    paint.setAntiAlias(false);
    static const uint16_t quadIndices[6] = {0, 1, 2, 0, 2, 3};   // two triangles
    const SkPoint positions[4] = {SkPoint{.fX = destRect.fLeft, .fY = destRect.fTop},
                                  SkPoint{.fX = destRect.fRight, .fY = destRect.fTop},
                                  SkPoint{.fX = destRect.fRight, .fY = destRect.fBottom},
                                  SkPoint{.fX = destRect.fLeft, .fY = destRect.fBottom}};
    const SkPoint texCoords[4] = {SkPoint{.fX = sourceRect.fLeft, .fY = sourceRect.fTop},
                                  SkPoint{.fX = sourceRect.fRight, .fY = sourceRect.fTop},
                                  SkPoint{.fX = sourceRect.fRight, .fY = sourceRect.fBottom},
                                  SkPoint{.fX = sourceRect.fLeft, .fY = sourceRect.fBottom}};
    context.canvas()->drawVertices(SkVertices::MakeCopy(SkVertices::kTriangles_VertexMode, 4, positions, texCoords,
                                                        nullptr, 6, quadIndices),
                                   SkBlendMode::kModulate, paint);
}

// A software source is uploaded per blit. Upload only what can be visible (the
// destination rect clipped to the clip, when the canvas matrix is identity) and
// in pieces no larger than the texture limit: a 20000 px wide BufferedImage is
// legal in Java and must not fail or upload whole.
void blitSoftwareSource(JNIEnv *env, SurfaceDataOps *source, const jint sourceFormat, const jint hint,
                        jint sx1, jint sy1, jint sx2, jint sy2, SkRect destRect) {
    const SKContext &context = SKContext::current();
    if (sx2 <= sx1 || sy2 <= sy1 || destRect.isEmpty()) {
        return;
    }
    const double scaleX = (sx2 - sx1) / static_cast<double>(destRect.width()),
                 scaleY = (sy2 - sy1) / static_cast<double>(destRect.height());
    if (!context.hasMatrix()) {
        const SkRect clip = context.hasRectClip()
                ? SkRect::Make(context.rectClip())
                : SkRect::MakeIWH(context.canvas()->imageInfo().width(), context.canvas()->imageInfo().height());
        SkRect visible = destRect;
        if (!visible.intersect(clip)) {
            return;
        }
        // source texels covering the visible part, one texel of margin for filtering
        auto toInt = [](const double value) {
            return static_cast<jint>(std::clamp(value, -COORDINATE_LIMIT, COORDINATE_LIMIT));
        };
        const jint visibleSx1 = std::max(sx1, toInt(std::floor(sx1 + (visible.left() - destRect.left()) * scaleX)) - 1);
        const jint visibleSy1 = std::max(sy1, toInt(std::floor(sy1 + (visible.top() - destRect.top()) * scaleY)) - 1);
        const jint visibleSx2 = std::min(sx2, toInt(std::ceil(sx1 + (visible.right() - destRect.left()) * scaleX)) + 1);
        const jint visibleSy2 = std::min(sy2, toInt(std::ceil(sy1 + (visible.bottom() - destRect.top()) * scaleY)) + 1);
        destRect = SkRect::MakeLTRB(static_cast<SkScalar>(destRect.left() + (visibleSx1 - sx1) / scaleX),
                                    static_cast<SkScalar>(destRect.top() + (visibleSy1 - sy1) / scaleY),
                                    static_cast<SkScalar>(destRect.left() + (visibleSx2 - sx1) / scaleX),
                                    static_cast<SkScalar>(destRect.top() + (visibleSy2 - sy1) / scaleY));
        sx1 = visibleSx1;
        sy1 = visibleSy1;
        sx2 = visibleSx2;
        sy2 = visibleSy2;
        if (sx2 <= sx1 || sy2 <= sy1) {
            return;
        }
    }
    const SkPaint paint = SKPaints::imageSourcePaint();
    // Sources without alpha are converted into a staging buffer first (imageFromSoftwareSurface): their tiles stay
    // at SKImageSources::OPAQUE_TILE (64 MB of staging), not the texture limit (a GB at 16384).
    const SKGraphicsConfig *graphicsConfig = context.graphicsConfig();
    jint maxTextureSize = graphicsConfig != nullptr && graphicsConfig->recorder() != nullptr ? graphicsConfig->recorder()->maxTextureSize()
                                                                                            : DEFAULT_MAX_TEXTURE_SIZE;
    if (SKImageSources::isOpaqueSoftwareFormat(sourceFormat)) {
        maxTextureSize = std::min(maxTextureSize, SKImageSources::OPAQUE_TILE);
    }
    for (jint tileY = sy1; tileY < sy2; tileY += maxTextureSize) {
        const jint tileHeight = std::min(maxTextureSize, sy2 - tileY);
        for (jint tileX = sx1; tileX < sx2; tileX += maxTextureSize) {
            const jint tileWidth = std::min(maxTextureSize, sx2 - tileX);
            sk_sp<SkImage> image = SKImageSources::imageFromSoftwareSurface(env, source, sourceFormat, tileX, tileY, tileX + tileWidth, tileY + tileHeight).image;
            if (!image) {
                continue;
            }
            const SkRect tileDest =
                    SkRect::MakeLTRB(static_cast<SkScalar>(destRect.left() + (tileX - sx1) / scaleX),
                                     static_cast<SkScalar>(destRect.top() + (tileY - sy1) / scaleY),
                                     static_cast<SkScalar>(destRect.left() + (tileX + tileWidth - sx1) / scaleX),
                                     static_cast<SkScalar>(destRect.top() + (tileY + tileHeight - sy1) / scaleY));
            drawImageQuad(image, SkRect::MakeIWH(tileWidth, tileHeight), tileDest, samplingForHint(hint), paint);
        }
    }
}

// A copy of the destination's `region`, clipped to the surface: sampling the render target in the
// pass that draws into it is a feedback loop (the draw reads nothing, or what it writes), so the
// source region is copied out first. Null if the region misses the surface or the copy fails. Made
// with the destination's recorder and consumed by the draw that follows, within the call: no
// SKOwnedImage, nothing keeps it.
sk_sp<SkImage> copyOfDestination(const sk_sp<SkSurface> &surface, SkIRect *region) {
    if (!region->intersect(SkIRect::MakeWH(surface->width(), surface->height()))) {
        return nullptr;
    }
    return SkSurfaces::AsImageCopy(surface, region);
}

} // namespace

void SKBlitLoops::blit(JNIEnv *env, const jint packedParams, const jint sx1, const jint sy1, const jint sx2,
                       const jint sy2, const jdouble dx1, const jdouble dy1, const jdouble dx2, const jdouble dy2,
                       const jlong sourceOps, const jlong destinationOps) {
    SKContext &context = SKContext::current();
    const jboolean isoblit = (packedParams >> sun_java2d_skia_SKBlitLoops_OFFSET_ISOBLIT) & 1;
    const jint hint        = (packedParams >> sun_java2d_skia_SKBlitLoops_OFFSET_HINT) & PACKED_BYTE_MASK;
    const jint sourceFormat  = (packedParams >> sun_java2d_skia_SKBlitLoops_OFFSET_SRCTYPE) & PACKED_BYTE_MASK;
    if (!context.beginDraw()) {
        return;
    }
    if (static_cast<SKSurfaceDataOps *>(jlong_to_ptr(destinationOps)) != context.destination()) {
        // never: Java validates the destination with the source right before the blit (SET_SURFACES)
        J2dRlsTraceLn(J2D_TRACE_ERROR, "SKBlitLoops::blit: never: destination %p is not the render state's %p",
                      jlong_to_ptr(destinationOps), static_cast<void *>(context.destination()));
        return;
    }

    sk_sp<SkImage> image;
    SkRect sourceRect;
    if (isoblit) {
        auto *source = static_cast<SKSurfaceDataOps *>(jlong_to_ptr(sourceOps));
        if (source != nullptr && !SKImageSources::sourceUsable(source, "SKBlitLoops::blit")) {
            return;
        }
        sourceRect = SkRect::Make(SkIRect::MakeLTRB(sx1, sy1, sx2, sy2));
        if (source != nullptr && source == context.destination()) {
            // the destination drawn into itself: the source region is copied out first, as copyArea does
            const sk_sp<SkSurface> surface = context.destinationSurface();
            if (!surface) {
                return;
            }
            SkIRect region = sourceRect.roundOut();
            image = copyOfDestination(surface, &region);
            sourceRect.offset(static_cast<SkScalar>(-region.x()), static_cast<SkScalar>(-region.y()));
        } else if (SKImageSources::isLinkedSource(source)) {
            image = SKImageSources::blitSourceImage(source).image;
        } else {
            image = context.getImage(source).image;
        }
    } else {
        blitSoftwareSource(env, static_cast<SurfaceDataOps *>(jlong_to_ptr(sourceOps)), sourceFormat, hint,
                           sx1, sy1, sx2, sy2,
                           SkRect::MakeLTRB(static_cast<SkScalar>(dx1), static_cast<SkScalar>(dy1),
                                            static_cast<SkScalar>(dx2), static_cast<SkScalar>(dy2)));
        return;
    }
    if (!image) {
        return;
    }
    const SkRect destRect = SkRect::MakeLTRB(static_cast<SkScalar>(dx1), static_cast<SkScalar>(dy1),
                                             static_cast<SkScalar>(dx2), static_cast<SkScalar>(dy2));
    const SkPaint paint = SKPaints::imageSourcePaint();
    drawImageQuad(image, sourceRect, destRect, samplingForHint(hint), paint);
}

namespace {

// Graphite delivers the readback callback from checkAsyncWorkCompletion() in the
// normal case, but a failed command buffer stays "unfinished" and the callback
// then arrives from a later submit(). The request is therefore heap-allocated,
// owned jointly by the caller and the callback, and holds its own pixel copy.
// Flusher thread only: Graphite runs the callback on the thread that calls it.
struct SKReadbackRequest {
    int referenceCount = 2;   // the caller and the callback
    std::vector<char> pixels;             // width * height * BYTES_PER_PIXEL, tightly packed
    int width = 0, height = 0;
    bool done = false;                    // callback ran (succeeded or failed)
    bool succeeded = false;

    void release() {
        if (--referenceCount == 0) {
            delete this;
        }
    }
};

// ReSharper disable once CppPassValueParameterByConstReference - the signature is SkImage::ReadPixelsCallback's
void readbackCallback(void *context, std::unique_ptr<const SkImage::AsyncReadResult> result) {
    auto *request = static_cast<SKReadbackRequest *>(context);
    if (result) {
        const auto *sourcePixels = static_cast<const char *>(result->data(0));
        const size_t sourceRowBytes = result->rowBytes(0);
        const size_t rowBytes = static_cast<size_t>(request->width) * BYTES_PER_PIXEL;
        for (int row = 0; row < request->height; row++) {
            memcpy(request->pixels.data() + static_cast<size_t>(row) * rowBytes, sourcePixels + row * sourceRowBytes,
                   rowBytes);
        }
        request->succeeded = true;
    }
    request->done = true;
    request->release();
}

// Reads `rect` of `surface` back into a request's pixels (premultiplied BGRA, tightly packed): the context's
// recording ends, the read is submitted and waited for. The request is the caller's to release; `done` says
// whether the callback ran, `succeeded` whether it brought pixels (SKReadbackRequest: a failed command buffer
// leaves `done` false until a later submit).
SKReadbackRequest *readPixels(SKGraphicsConfig *graphicsConfig, const SkSurface *surface, const SkIRect &rect) {
    auto *request = new SKReadbackRequest();
    request->width = rect.width();
    request->height = rect.height();
    request->pixels.resize(static_cast<size_t>(request->width) * request->height * BYTES_PER_PIXEL);
    const SkImageInfo info = SkImageInfo::Make(rect.width(), rect.height(), kBGRA_8888_SkColorType, kPremul_SkAlphaType);
    graphicsConfig->endRecording();
    graphicsConfig->context()->asyncRescaleAndReadPixels(surface, info, rect, SkImage::RescaleGamma::kSrc,
                                                         SkImage::RescaleMode::kNearest, readbackCallback, request);
    graphicsConfig->context()->submit(SyncToCpu::kYes);
    graphicsConfig->context()->checkAsyncWorkCompletion();
    return request;
}

} // namespace

void SKBlitLoops::surfaceToSoftwareBlit(JNIEnv *env, const jint sx, const jint sy, const jint dx, const jint dy,
                                        const jint width, const jint height, const jint destinationFormat,
                                        const jlong sourceOps, const jlong destinationOps) {
    auto *source = static_cast<SKSurfaceDataOps *>(jlong_to_ptr(sourceOps));
    auto *destination = static_cast<SurfaceDataOps *>(jlong_to_ptr(destinationOps));
    if (source == nullptr || destination == nullptr || width <= 0 || height <= 0) {
        return;
    }
    const sk_sp<SkSurface> surface = SKContext::current().getSurface(source);
    SKGraphicsConfig *graphicsConfig = source->state != nullptr ? source->state->graphicsConfig() : nullptr;
    if (!surface || graphicsConfig == nullptr) {
        return;
    }
    SurfaceDataRasInfo rasterInfo{};
    rasterInfo.bounds.x1 = dx;
    rasterInfo.bounds.y1 = dy;
    rasterInfo.bounds.x2 = dx + width;
    rasterInfo.bounds.y2 = dy + height;
    if (destination->Lock(env, destination, &rasterInfo, SD_LOCK_WRITE) != SD_SUCCESS) {
        return;
    }
    // The destination lock clipped to the raster; clip the source rect to the surface as well (the Metal loop
    // does the same), reading back a rect that leaves the texture is a GPU error.
    const SkIRect sourceRect = SkIRect::MakeXYWH(sx + (rasterInfo.bounds.x1 - dx), sy + (rasterInfo.bounds.y1 - dy),
                                                 rasterInfo.bounds.x2 - rasterInfo.bounds.x1,
                                                 rasterInfo.bounds.y2 - rasterInfo.bounds.y1);
    if (SkIRect clipped = sourceRect;
            !clipped.isEmpty() && clipped.intersect(SkIRect::MakeWH(surface->width(), surface->height()))) {
        // The read-back before the raster is pinned (GetRasInfo, a JNI critical region): it waits for the GPU to
        // finish everything submitted so far, and the collector must not wait along.
        SKReadbackRequest *request = readPixels(graphicsConfig, surface.get(), clipped);
        destination->GetRasInfo(env, destination, &rasterInfo);
        if (rasterInfo.rasBase != nullptr) {
            if (request->done && request->succeeded) {
                char *destBase = static_cast<char *>(rasterInfo.rasBase)
                                 + static_cast<ptrdiff_t>(rasterInfo.bounds.y1) * rasterInfo.scanStride
                                 + static_cast<ptrdiff_t>(rasterInfo.bounds.x1) * rasterInfo.pixelStride
                                 + static_cast<ptrdiff_t>(clipped.y() - sourceRect.y()) * rasterInfo.scanStride
                                 + static_cast<ptrdiff_t>(clipped.x() - sourceRect.x()) * rasterInfo.pixelStride;
                // The premultiplied bytes as they are, or unpremultiplied for an IntArgb destination.
                const bool unpremultiply = destinationFormat == sun_java2d_skia_SKSurfaceData_PF_INT_ARGB;
                const size_t rowBytes = static_cast<size_t>(request->width) * BYTES_PER_PIXEL;
                for (int row = 0; row < request->height; row++) {
                    auto *destRow = reinterpret_cast<uint32_t *>(destBase + static_cast<ptrdiff_t>(row) * rasterInfo.scanStride);
                    const auto *sourceRow = reinterpret_cast<const uint32_t *>(request->pixels.data()
                                                                               + static_cast<size_t>(row) * rowBytes);
                    if (!unpremultiply) {
                        memcpy(destRow, sourceRow, rowBytes);
                        continue;
                    }
                    for (int column = 0; column < request->width; column++) {
                        destRow[column] = SKPaints::unpremultiply(sourceRow[column]);
                    }
                }
            } else {
                J2dRlsTraceLn(J2D_TRACE_ERROR, "SKBlitLoops::surfaceToSoftwareBlit: readback %s",
                              request->done ? "failed" : "did not complete");
            }
        }
        SurfaceData_InvokeRelease(env, destination, &rasterInfo);
        request->release();
    }
    SurfaceData_InvokeUnlock(env, destination, &rasterInfo);
}

void SKBlitLoops::copyArea(const jint x, const jint y, const jint width, const jint height, const jint dx,
                           const jint dy) {
    SKContext &context = SKContext::current();
    if (!context.beginDraw()) {
        return;
    }
    if (width <= 0 || height <= 0) return;
    const sk_sp<SkSurface> surface = context.destinationSurface();
    if (!surface) return;
    // Source and destination overlap in general: copy the region out first.
    SkIRect region = SkIRect::MakeXYWH(x, y, width, height);
    const sk_sp<SkImage> copy = copyOfDestination(surface, &region);
    if (!copy) return;
    // Under the current composite and its extra alpha, as the software loops' copyArea blits (the D3D and
    // OpenGL pipelines copy with Src whatever the composite).
    const SkPaint paint = SKPaints::imageSourcePaint();
    context.canvas()->drawImage(copy, static_cast<SkScalar>(region.x() + dx), static_cast<SkScalar>(region.y() + dy),
                                SkSamplingOptions(SkFilterMode::kNearest), &paint);
}
