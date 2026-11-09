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
#include "SKConfiguration.h"
#include "SKContext.h"
#include "SKGraphicsConfig.h"
#include "SKImageAtlas.h"
#include "SKImageSources.h"
#include "SKPaints.h"
#include "SKPendingBatches.h"
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
#include <atomic>
#include <cmath>
#include <cstddef>
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
SkSamplingOptions samplingForHint(jint hint) {
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
    SKContext &context = SKContext::current();
    sk_sp<SkShader> shader = image->makeShader(SkTileMode::kClamp, SkTileMode::kClamp, sampling);
    SkRect inset = sourceRect;
    inset.inset(std::min(HALF_TEXEL, sourceRect.width() / 2), std::min(HALF_TEXEL, sourceRect.height() / 2));
    shader = SkShaders::CoordClamp(std::move(shader), inset);
    paint.setShader(std::move(shader));
    paint.setAntiAlias(false);
    static const uint16_t quadIndices[6] = {0, 1, 2, 0, 2, 3};   // two triangles
    const SkPoint positions[4] = {{destRect.fLeft, destRect.fTop}, {destRect.fRight, destRect.fTop},
                                  {destRect.fRight, destRect.fBottom}, {destRect.fLeft, destRect.fBottom}};
    const SkPoint texCoords[4] = {{sourceRect.fLeft, sourceRect.fTop}, {sourceRect.fRight, sourceRect.fTop},
                                  {sourceRect.fRight, sourceRect.fBottom}, {sourceRect.fLeft, sourceRect.fBottom}};
    context.canvas()->drawVertices(SkVertices::MakeCopy(SkVertices::kTriangles_VertexMode, 4, positions, texCoords,
                                                        nullptr, 6, quadIndices),
                                   SkBlendMode::kModulate, paint);
}

// A software source is uploaded per blit. Upload only what can be visible (the
// destination rect clipped to the clip, when the canvas matrix is identity) and
// in pieces no larger than the texture limit: a 20000 px wide BufferedImage is
// legal in Java and must not fail or upload whole.
void blitSoftwareSource(JNIEnv *env, SurfaceDataOps *source, jint sourceType, jint hint,
                        jint sx1, jint sy1, jint sx2, jint sy2, SkRect destRect) {
    SKContext &context = SKContext::current();
    if (sx2 <= sx1 || sy2 <= sy1 || destRect.isEmpty()) {
        return;
    }
    const double scaleX = (sx2 - sx1) / (double) destRect.width(), scaleY = (sy2 - sy1) / (double) destRect.height();
    if (!context.hasMatrix()) {
        SkRect clip = context.hasRectClip()
                ? SkRect::Make(context.rectClip())
                : SkRect::MakeWH(context.canvas()->imageInfo().width(), context.canvas()->imageInfo().height());
        SkRect visible = destRect;
        if (!visible.intersect(clip)) {
            return;
        }
        // source texels covering the visible part, one texel of margin for filtering
        auto toInt = [](double value) { return (jint) std::clamp(value, -COORDINATE_LIMIT, COORDINATE_LIMIT); };
        const jint visibleSx1 = std::max(sx1, toInt(std::floor(sx1 + (visible.left() - destRect.left()) * scaleX)) - 1);
        const jint visibleSy1 = std::max(sy1, toInt(std::floor(sy1 + (visible.top() - destRect.top()) * scaleY)) - 1);
        const jint visibleSx2 = std::min(sx2, toInt(std::ceil(sx1 + (visible.right() - destRect.left()) * scaleX)) + 1);
        const jint visibleSy2 = std::min(sy2, toInt(std::ceil(sy1 + (visible.bottom() - destRect.top()) * scaleY)) + 1);
        destRect = SkRect::MakeLTRB((SkScalar) (destRect.left() + (visibleSx1 - sx1) / scaleX),
                                    (SkScalar) (destRect.top() + (visibleSy1 - sy1) / scaleY),
                                    (SkScalar) (destRect.left() + (visibleSx2 - sx1) / scaleX),
                                    (SkScalar) (destRect.top() + (visibleSy2 - sy1) / scaleY));
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
    SKGraphicsConfig *graphicsConfig = context.graphicsConfig();
    jint maxTextureSize = graphicsConfig != nullptr && graphicsConfig->recorder() != nullptr ? graphicsConfig->recorder()->maxTextureSize()
                                                                                            : DEFAULT_MAX_TEXTURE_SIZE;
    if (SKImageSources::isOpaqueSoftwareType(sourceType)) {
        maxTextureSize = std::min(maxTextureSize, SKImageSources::OPAQUE_TILE);
    }
    for (jint tileY = sy1; tileY < sy2; tileY += maxTextureSize) {
        const jint tileHeight = std::min(maxTextureSize, sy2 - tileY);
        for (jint tileX = sx1; tileX < sx2; tileX += maxTextureSize) {
            const jint tileWidth = std::min(maxTextureSize, sx2 - tileX);
            sk_sp<SkImage> image = SKImageSources::imageFromSoftwareSurface(env, source, sourceType, tileX, tileY, tileX + tileWidth, tileY + tileHeight).image;
            if (!image) {
                continue;
            }
            const SkRect tileDest = SkRect::MakeLTRB((SkScalar) (destRect.left() + (tileX - sx1) / scaleX),
                                                     (SkScalar) (destRect.top() + (tileY - sy1) / scaleY),
                                                     (SkScalar) (destRect.left() + (tileX + tileWidth - sx1) / scaleX),
                                                     (SkScalar) (destRect.top() + (tileY + tileHeight - sy1) / scaleY));
            drawImageQuad(image, SkRect::MakeWH(tileWidth, tileHeight), tileDest, samplingForHint(hint), paint);
        }
    }
}

// The image atlas (on unless sun.java2d.skia.imageAtlas=false): a blit the caller found may be a
// sprite (SKBlitLoops::blit), of a small Skia surface of this context, unscaled and pixel-aligned, with a plain SrcOver
// composite, becomes a sprite of the context's image atlas (SKImageAtlas.h): one mesh for the small images drawn in
// a row instead of a draw each, each with its own image paint key. False: the caller draws the blit itself.
bool imageAtlasBlit(SKSDOps *source, const sk_sp<SkImage> &image, const SkPaint &paint, jint sx1, jint sy1, jint sx2, jint sy2,
                    jdouble dx1, jdouble dy1, jdouble dx2, jdouble dy2) {
    SKContext &context = SKContext::current();
    SKGraphicsConfig *graphicsConfig = context.graphicsConfig();
    if (source == nullptr || source->state == nullptr || graphicsConfig == nullptr
            || sx2 - sx1 != dx2 - dx1 || sy2 - sy1 != dy2 - dy1 || dx1 != std::floor(dx1) || dy1 != std::floor(dy1)
            || !SKImageAtlas::fits(sx2 - sx1, sy2 - sy1) || paint.asBlendMode() != SkBlendMode::kSrcOver
            || paint.getShader() != nullptr || paint.getColorFilter() != nullptr) {
        return false;
    }
    SKImageAtlas *imageAtlas = graphicsConfig->imageAtlas();
    if (imageAtlas == nullptr) {
        return false;
    }
    SkPaint sprite = paint;
    sprite.setColor4f(SkColor4f{1, 1, 1, context.extraAlpha()});   // the color modulates the atlas texel: white, the extra alpha
    return imageAtlas->draw(context.canvas(), sprite, source->state, image,
                            sx1, sy1, sx2 - sx1, sy2 - sy1, (jint) dx1, (jint) dy1);
}

// The destination's surface for a draw of the destination into itself. SKContext::getSurface
// re-wraps a replaced texture and forgets the destination on the way: the new surface is set as
// the destination again, with the render state applied to it. Null if there is no surface.
sk_sp<SkSurface> destinationSurface(SKSDOps *destination) {
    SKContext &context = SKContext::current();
    sk_sp<SkSurface> surface = context.getSurface(destination);
    if (surface && context.canvas() == nullptr) {
        context.setDestination(destination);
    }
    return surface && context.canvas() != nullptr ? surface : nullptr;
}

// A copy of the destination's `region`, clipped to the surface: sampling the render target in the
// pass that draws into it is a feedback loop (the draw reads nothing, or what it writes), so the
// source region is copied out first. Null if the region misses the surface or the copy fails.
sk_sp<SkImage> copyOfDestination(const sk_sp<SkSurface> &surface, SkIRect *region) {
    if (!region->intersect(SkIRect::MakeWH(surface->width(), surface->height()))) {
        return nullptr;
    }
    return SkSurfaces::AsImageCopy(surface, region);
}

} // namespace

void SKBlitLoops::blit(JNIEnv *env, jint packedParams, jint sx1, jint sy1, jint sx2, jint sy2,
                        jdouble dx1, jdouble dy1, jdouble dx2, jdouble dy2, jlong sourceOps, jlong destinationOps) {
    SKContext &context = SKContext::current();
    const jboolean isoblit = (packedParams >> sun_java2d_skia_SKBlitLoops_OFFSET_ISOBLIT) & 1;
    const jint hint        = (packedParams >> sun_java2d_skia_SKBlitLoops_OFFSET_HINT) & PACKED_BYTE_MASK;
    const jint sourceType  = (packedParams >> sun_java2d_skia_SKBlitLoops_OFFSET_SRCTYPE) & PACKED_BYTE_MASK;
    // A blit that may become an image atlas sprite (of a Skia surface, no transform, no XOR, not from the
    // destination) draws only the other kinds' batches, so consecutive sprites share the atlas batch; any other blit
    // draws everything pending first, and so does one that turns out not to be a sprite (below).
    const bool maySprite = SKConfiguration::imageAtlas() && isoblit && !context.xorMode()
            && !context.hasMatrix() && (SKSDOps *) jlong_to_ptr(sourceOps) != context.destination()
            && dx1 < dx2 && dy1 < dy2 && sx1 < sx2 && sy1 < sy2;
    if (!(maySprite ? context.beginDraw(SKBatchKind::ImageSprites) : context.beginDraw())) {
        return;
    }

    sk_sp<SkImage> image;
    SKSDOps *linkedSource = nullptr;   // a Skia surface of this context, sampled through its linked image
    SkRect sourceRect;
    if (isoblit) {
        SKSDOps *source = (SKSDOps *) jlong_to_ptr(sourceOps);
        if (source != nullptr && !SKImageSources::sourceUsable(source, "SKBlitLoops::blit")) {
            return;
        }
        sourceRect = SkRect::MakeLTRB(sx1, sy1, sx2, sy2);
        if (source != nullptr && source == context.destination()) {
            // the destination drawn into itself: the source region is copied out first, as copyArea does
            sk_sp<SkSurface> surface = destinationSurface(source);
            if (!surface) {
                return;
            }
            SkIRect region = sourceRect.roundOut();
            image = copyOfDestination(surface, &region);
            sourceRect.offset((SkScalar) -region.x(), (SkScalar) -region.y());
        } else if (SKImageSources::isLinkedSource(source)) {
            image = SKImageSources::blitSourceImage(source).image;
            linkedSource = source;
        } else {
            image = context.getImage(source).image;
        }
    } else {
        blitSoftwareSource(env, (SurfaceDataOps *) jlong_to_ptr(sourceOps), sourceType, hint, sx1, sy1, sx2, sy2,
                           SkRect::MakeLTRB((SkScalar) dx1, (SkScalar) dy1, (SkScalar) dx2, (SkScalar) dy2));
        return;
    }
    if (!image) {
        return;
    }
    const SkRect destRect = SkRect::MakeLTRB((SkScalar) dx1, (SkScalar) dy1, (SkScalar) dx2, (SkScalar) dy2);
    const SkPaint paint = SKPaints::imageSourcePaint();
    if (maySprite && imageAtlasBlit(linkedSource, image, paint, sx1, sy1, sx2, sy2, dx1, dy1, dx2, dy2)) {
        return;
    }
    if (maySprite) {
        context.flushAllBatches();   // not a sprite: everything pending before it first
    }
    drawImageQuad(image, sourceRect, destRect, samplingForHint(hint), paint);
}

namespace {

// Graphite delivers the readback callback from checkAsyncWorkCompletion() in the
// normal case, but a failed command buffer stays "unfinished" and the callback
// then arrives from a later submit(). The request is therefore heap-allocated,
// owned jointly by the caller and the callback, and holds its own pixel copy.
struct SKReadbackRequest {
    std::atomic<int> referenceCount{2};   // the caller and the callback
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

void readbackCallback(void *context, std::unique_ptr<const SkImage::AsyncReadResult> result) {
    auto *request = (SKReadbackRequest *) context;
    if (result) {
        const char *sourcePixels = (const char *) result->data(0);
        const size_t sourceRowBytes = result->rowBytes(0);
        const size_t rowBytes = (size_t) request->width * BYTES_PER_PIXEL;
        for (int row = 0; row < request->height; row++) {
            memcpy(request->pixels.data() + (size_t) row * rowBytes, sourcePixels + row * sourceRowBytes, rowBytes);
        }
        request->succeeded = true;
    }
    request->done = true;
    request->release();
}

} // namespace

void SKBlitLoops::surfaceToSoftwareBlit(JNIEnv *env, jint sx, jint sy, jint dx, jint dy, jint width, jint height,
                                        jint destType, jlong sourceOps, jlong destinationOps) {
    SKSDOps *source = (SKSDOps *) jlong_to_ptr(sourceOps);
    SurfaceDataOps *destination = (SurfaceDataOps *) jlong_to_ptr(destinationOps);
    if (source == nullptr || destination == nullptr || width <= 0 || height <= 0) {
        return;
    }
    sk_sp<SkSurface> surface = SKContext::current().getSurface(source);
    SKGraphicsConfig *graphicsConfig = source->state != nullptr ? source->state->graphicsConfig() : nullptr;
    if (!surface || graphicsConfig == nullptr) {
        return;
    }
    SurfaceDataRasInfo rasterInfo;
    memset(&rasterInfo, 0, sizeof(rasterInfo));
    rasterInfo.bounds.x1 = dx;
    rasterInfo.bounds.y1 = dy;
    rasterInfo.bounds.x2 = dx + width;
    rasterInfo.bounds.y2 = dy + height;
    if (destination->Lock(env, destination, &rasterInfo, SD_LOCK_WRITE) != SD_SUCCESS) {
        return;
    }
    destination->GetRasInfo(env, destination, &rasterInfo);
    if (rasterInfo.rasBase != nullptr) {
        const int rasterWidth = rasterInfo.bounds.x2 - rasterInfo.bounds.x1, rasterHeight = rasterInfo.bounds.y2 - rasterInfo.bounds.y1;
        if (rasterWidth > 0 && rasterHeight > 0) {
            // The destination lock clipped to the raster; clip the source rect to the
            // surface as well (the Metal loop does the same), reading back a rect that
            // leaves the texture is a GPU error.
            const SkIRect sourceRect = SkIRect::MakeXYWH(sx + (rasterInfo.bounds.x1 - dx), sy + (rasterInfo.bounds.y1 - dy),
                                                         rasterWidth, rasterHeight);
            SkIRect clipped = sourceRect;
            if (clipped.intersect(SkIRect::MakeWH(surface->width(), surface->height()))) {
                char *destBase = (char *) rasterInfo.rasBase
                                 + (ptrdiff_t) rasterInfo.bounds.y1 * rasterInfo.scanStride
                                 + (ptrdiff_t) rasterInfo.bounds.x1 * rasterInfo.pixelStride
                                 + (ptrdiff_t) (clipped.y() - sourceRect.y()) * rasterInfo.scanStride
                                 + (ptrdiff_t) (clipped.x() - sourceRect.x()) * rasterInfo.pixelStride;
                // Read the premultiplied bytes as they are and unpremultiply like the
                // Java loops (div8table) do, so results equal the software pipeline's.
                const SkImageInfo info = SkImageInfo::Make(clipped.width(), clipped.height(), kBGRA_8888_SkColorType, kPremul_SkAlphaType);
                auto *request = new SKReadbackRequest();
                request->width = clipped.width();
                request->height = clipped.height();
                request->pixels.resize((size_t) request->width * request->height * BYTES_PER_PIXEL);
                graphicsConfig->endRecording();
                graphicsConfig->context()->asyncRescaleAndReadPixels(surface.get(), info, clipped,
                                                                     SkImage::RescaleGamma::kSrc, SkImage::RescaleMode::kNearest,
                                                                     readbackCallback, request);
                graphicsConfig->context()->submit(SyncToCpu::kYes);
                graphicsConfig->context()->checkAsyncWorkCompletion();
                if (request->done && request->succeeded) {
                    const bool unpremultiply = destType == sun_java2d_skia_SKSurfaceData_PF_INT_ARGB;
                    const size_t rowBytes = (size_t) request->width * BYTES_PER_PIXEL;
                    for (int row = 0; row < request->height; row++) {
                        uint32_t *destRow = (uint32_t *) (destBase + (ptrdiff_t) row * rasterInfo.scanStride);
                        const uint32_t *sourceRow = (const uint32_t *) (request->pixels.data() + (size_t) row * rowBytes);
                        if (!unpremultiply) {
                            memcpy(destRow, sourceRow, rowBytes);
                            continue;
                        }
                        for (int column = 0; column < request->width; column++) {
                            destRow[column] = SKPaints::unpremulJava(sourceRow[column]);
                        }
                    }
                } else {
                    J2dRlsTraceLn(J2D_TRACE_ERROR, "SKBlitLoops::surfaceToSoftwareBlit: readback %s",
                                  request->done ? "failed" : "did not complete");
                }
                request->release();
            }
        }
    }
    SurfaceData_InvokeRelease(env, destination, &rasterInfo);
    SurfaceData_InvokeUnlock(env, destination, &rasterInfo);
}

void SKBlitLoops::copyArea(jint x, jint y, jint width, jint height, jint dx, jint dy) {
    SKContext &context = SKContext::current();
    if (!context.beginDraw()) {
        return;
    }
    if (width <= 0 || height <= 0) return;
    sk_sp<SkSurface> surface = destinationSurface(context.destination());
    if (!surface) return;
    // Source and destination overlap in general: copy the region out first.
    SkIRect region = SkIRect::MakeXYWH(x, y, width, height);
    sk_sp<SkImage> copy = copyOfDestination(surface, &region);
    if (!copy) return;
    SkPaint paint;
    paint.setBlendMode(SkBlendMode::kSrc);
    context.canvas()->drawImage(copy, (SkScalar) (region.x() + dx), (SkScalar) (region.y() + dy),
                                SkSamplingOptions(SkFilterMode::kNearest), &paint);
}
