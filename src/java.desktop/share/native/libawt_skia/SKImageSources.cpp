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
// The image sources of the blits and the texture paint (SKImageSources.h).
// All functions run on the Skia queue flusher thread.

#include "SKImageSources.h"
#include "SKContext.h"
#include "SKGraphicsConfig.h"
#include "SKPlatform.h"
#include "SKPresentation.h"
#include "SKSurfaceData.h"

#include "jni_util.h"
#include "Trace.h"
#include "sun_java2d_skia_SKSurfaceData.h"

#include "include/core/SkPixmap.h"
#include "include/gpu/graphite/Surface.h"

#include <array>
#include <cstddef>
#include <vector>

namespace {

// The int pixel formats this file handles are 4 bytes per pixel.
constexpr size_t BYTES_PER_PIXEL = 4;
// The alpha byte of an IntArgb-layout pixel, set for the formats that leave it undefined.
constexpr uint32_t OPAQUE_ALPHA = 0xff000000u;
// softwareSurfaceFormat's result for a surface that is not one of the int formats.
constexpr jint NO_SOFTWARE_TYPE = -1;
// A bound no surface reaches: imageOfSoftwareSurface asks for the whole surface, the lock clips.
constexpr jint WHOLE_SURFACE = 1 << 30;

// The staging buffer is kept between blits up to this many pixels; one oversized blit must not pin its buffer.
constexpr size_t OPAQUE_STAGING_KEPT_PIXELS = static_cast<size_t>(2048) * 2048;

// Skia's view of a software surface's PF_* type: the color type and how the alpha is read.
struct SoftwarePixelFormat {
    SkColorType colorType = kBGRA_8888_SkColorType;
    SkAlphaType alphaType = kPremul_SkAlphaType;
};

SoftwarePixelFormat softwarePixelFormat(const jint sourceFormat) {
    switch (sourceFormat) {
        case sun_java2d_skia_SKSurfaceData_PF_INT_ARGB:
            return { .colorType = kBGRA_8888_SkColorType, .alphaType = kUnpremul_SkAlphaType };
        case sun_java2d_skia_SKSurfaceData_PF_INT_ARGB_PRE:
            return { .colorType = kBGRA_8888_SkColorType, .alphaType = kPremul_SkAlphaType };
        case sun_java2d_skia_SKSurfaceData_PF_INT_RGB:
        case sun_java2d_skia_SKSurfaceData_PF_INT_RGBX:
            return { .colorType = kBGRA_8888_SkColorType, .alphaType = kOpaque_SkAlphaType };
        case sun_java2d_skia_SKSurfaceData_PF_INT_BGR:
        case sun_java2d_skia_SKSurfaceData_PF_INT_BGRX:
            return { .colorType = kRGBA_8888_SkColorType, .alphaType = kOpaque_SkAlphaType };
        default:
            return {};
    }
}

// A software surface's PF_* type, from its Java SurfaceData's SurfaceType: the int formats
// imageFromSoftwareSurface uploads as they are; NO_SOFTWARE_TYPE for any other (or when it cannot be told).
jint softwareSurfaceFormat(JNIEnv *env, const SurfaceDataOps *ops) {
    struct SurfaceTypeField { const char *field; jint type; };
    static constexpr std::array<SurfaceTypeField, 6> SURFACE_TYPES = {{
        { .field = "IntArgb", .type = sun_java2d_skia_SKSurfaceData_PF_INT_ARGB },
        { .field = "IntArgbPre", .type = sun_java2d_skia_SKSurfaceData_PF_INT_ARGB_PRE },
        { .field = "IntRgb", .type = sun_java2d_skia_SKSurfaceData_PF_INT_RGB },
        { .field = "IntRgbx", .type = sun_java2d_skia_SKSurfaceData_PF_INT_RGBX },
        { .field = "IntBgr", .type = sun_java2d_skia_SKSurfaceData_PF_INT_BGR },
        { .field = "IntBgrx", .type = sun_java2d_skia_SKSurfaceData_PF_INT_BGRX },
    }};
    static std::array<jobject, SURFACE_TYPES.size()> surfaceTypeObjects = {};   // global refs, flusher thread only
    static bool lookedUp = false;
    if (env == nullptr || ops->sdObject == nullptr) {
        return NO_SOFTWARE_TYPE;
    }
    if (!lookedUp) {
        lookedUp = true;
        // NOLINTNEXTLINE(misc-misplaced-const): a JNI handle passed by value is const as every by-value parameter (the object it names is not)
        const jclass surfaceTypeClass = env->FindClass("sun/java2d/loops/SurfaceType");
        for (size_t index = 0; surfaceTypeClass != nullptr && index < SURFACE_TYPES.size(); index++) {
            // NOLINTNEXTLINE(misc-misplaced-const): a JNI handle passed by value is const as every by-value parameter (the object it names is not)
            const jfieldID fieldID = env->GetStaticFieldID(surfaceTypeClass, SURFACE_TYPES[index].field,
                                                           "Lsun/java2d/loops/SurfaceType;");
            if (fieldID == nullptr) {
                break;   // a NoSuchFieldError is pending (cleared below): no JNI call until then
            }
            // NOLINTNEXTLINE(misc-misplaced-const): a JNI handle passed by value is const as every by-value parameter (the object it names is not)
            if (const jobject value = env->GetStaticObjectField(surfaceTypeClass, fieldID); value != nullptr) {
                surfaceTypeObjects[index] = env->NewGlobalRef(value);
                env->DeleteLocalRef(value);
            }
        }
        if (surfaceTypeClass != nullptr) {
            env->DeleteLocalRef(surfaceTypeClass);
        }
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
    }
    // NOLINTNEXTLINE(misc-misplaced-const): a JNI handle passed by value is const as every by-value parameter (the object it names is not)
    const jobject surfaceData = env->NewLocalRef(ops->sdObject);   // a weak reference
    if (surfaceData == nullptr) {
        return NO_SOFTWARE_TYPE;
    }
    // NOLINTNEXTLINE(misc-misplaced-const): a JNI handle passed by value is const as every by-value parameter (the object it names is not)
    const jobject surfaceType = JNU_CallMethodByName(env, nullptr, surfaceData, "getSurfaceType",
                                                     "()Lsun/java2d/loops/SurfaceType;").l;
    env->DeleteLocalRef(surfaceData);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return NO_SOFTWARE_TYPE;
    }
    jint result = NO_SOFTWARE_TYPE;
    for (size_t index = 0; surfaceType != nullptr && index < SURFACE_TYPES.size(); index++) {
        if (surfaceTypeObjects[index] != nullptr && env->IsSameObject(surfaceType, surfaceTypeObjects[index])) {
            result = SURFACE_TYPES[index].type;
            break;
        }
    }
    if (surfaceType != nullptr) {
        env->DeleteLocalRef(surfaceType);
    }
    return result;
}

} // namespace

bool SKImageSources::isOpaqueSoftwareFormat(const jint sourceFormat) {
    return sourceFormat == sun_java2d_skia_SKSurfaceData_PF_INT_RGB || sourceFormat == sun_java2d_skia_SKSurfaceData_PF_INT_RGBX
           || sourceFormat == sun_java2d_skia_SKSurfaceData_PF_INT_BGR || sourceFormat == sun_java2d_skia_SKSurfaceData_PF_INT_BGRX;
}

// Uploads a software surface's pixels to a texture of the current context.
SKOwnedImage SKImageSources::imageFromSoftwareSurface(JNIEnv *env, SurfaceDataOps *ops, const jint sourceFormat,
                                                      const jint sx1, const jint sy1, const jint sx2, const jint sy2) {
    if (ops == nullptr || ops->Lock == nullptr) {
        return {};
    }
    SKContext &context = SKContext::current();
    if (const SKSurfaceDataOps *skiaOps = SKSurfaceDataOps::from(ops); skiaOps != nullptr) {
        // a Skia surface routed through a software loop: sample it, do not lock it
        J2dRlsTraceLn(J2D_TRACE_WARNING, "imageFromSoftwareSurface: source %p is a Skia surface", ops);
        return context.getImage(skiaOps);
    }
    SurfaceDataRasInfo rasterInfo{};
    rasterInfo.bounds.x1 = sx1;
    rasterInfo.bounds.y1 = sy1;
    rasterInfo.bounds.x2 = sx2;
    rasterInfo.bounds.y2 = sy2;
    if (ops->Lock(env, ops, &rasterInfo, SD_LOCK_READ) != SD_SUCCESS) {
        return {};
    }
    SKOwnedImage image;
    ops->GetRasInfo(env, ops, &rasterInfo);
    if (rasterInfo.rasBase != nullptr && rasterInfo.bounds.x2 > rasterInfo.bounds.x1 && rasterInfo.bounds.y2 > rasterInfo.bounds.y1) {
        const auto [colorType, alphaType] = softwarePixelFormat(sourceFormat);
        const int width = rasterInfo.bounds.x2 - rasterInfo.bounds.x1, height = rasterInfo.bounds.y2 - rasterInfo.bounds.y1;
        const char *base = static_cast<const char *>(rasterInfo.rasBase)
                           + static_cast<ptrdiff_t>(rasterInfo.bounds.y1) * rasterInfo.scanStride
                           + static_cast<ptrdiff_t>(rasterInfo.bounds.x1) * rasterInfo.pixelStride;
        // What one texture of the context takes: its size limit, and for the opaque formats the staging buffer
        // below, OPAQUE_TILE squared (64 MB). The blits' tiles stay within both (SKBlitLoops::blitSoftwareSource);
        // a texture paint's source is uploaded whole (imageOfSoftwareSurface) and is not drawn beyond them.
        const SKGraphicsConfig *graphicsConfig = context.graphicsConfig();
        const jint maxTextureSize = graphicsConfig != nullptr && graphicsConfig->recorder() != nullptr
                                    ? graphicsConfig->recorder()->maxTextureSize() : 0;
        if (const bool staged = alphaType == kOpaque_SkAlphaType;
                width > maxTextureSize || height > maxTextureSize
                || (staged && static_cast<size_t>(width) * height > static_cast<size_t>(OPAQUE_TILE) * OPAQUE_TILE)) {
            static bool traced = false;   // once; the paint's own trace (SKPaints::setTexturePaint) names the source
            if (!traced) {
                traced = true;
                J2dRlsTraceLn(J2D_TRACE_WARNING,
                              "imageFromSoftwareSurface: %dx%d exceeds one texture of the context (%d a side, %d squared staged)",
                              width, height, maxTextureSize, OPAQUE_TILE);
            }
        } else if (staged) {
            // IntRgb & co. leave the alpha byte undefined; set it instead of relying on Graphite's opaque-image
            // swizzle. The buffer is reused across blits (flusher thread only).
            static std::vector<uint32_t> opaque;
            opaque.resize(static_cast<size_t>(width) * height);
            for (int row = 0; row < height; row++) {
                const auto *sourceRow = reinterpret_cast<const uint32_t *>(
                        base + static_cast<ptrdiff_t>(row) * rasterInfo.scanStride);
                uint32_t *destRow = opaque.data() + static_cast<size_t>(row) * width;
                for (int column = 0; column < width; column++) {
                    destRow[column] = sourceRow[column] | OPAQUE_ALPHA;
                }
            }
            image = context.uploadPixmap(SkPixmap(SkImageInfo::Make(width, height, colorType, kPremul_SkAlphaType),
                                                  opaque.data(), static_cast<size_t>(width) * BYTES_PER_PIXEL));
            if (opaque.capacity() > OPAQUE_STAGING_KEPT_PIXELS) {
                std::vector<uint32_t>().swap(opaque);
            }
        } else {
            image = context.uploadPixmap(SkPixmap(SkImageInfo::Make(width, height, colorType, alphaType), base,
                                                  static_cast<size_t>(rasterInfo.scanStride)));
        }
    }
    SurfaceData_InvokeRelease(env, ops, &rasterInfo);
    SurfaceData_InvokeUnlock(env, ops, &rasterInfo);
    return image;
}

SKOwnedImage SKImageSources::imageOfSoftwareSurface(JNIEnv *env, SurfaceDataOps *ops) {
    if (ops == nullptr || SKSurfaceDataOps::from(ops) != nullptr) {
        return {};
    }
    const jint type = softwareSurfaceFormat(env, ops);
    if (type == NO_SOFTWARE_TYPE) {
        return {};
    }
    // all of it: the surface's lock clips the bounds to the surface
    return imageFromSoftwareSurface(env, ops, type, 0, 0, WHOLE_SURFACE, WHOLE_SURFACE);
}

// A source on another adapter cannot be wrapped by this context (a window
// dragged between the displays of two GPUs): nothing is drawn rather than a
// texture handle of a foreign device handed to the driver; such a blit would
// need a readback. A source on another display's context and queue is usable
// once its work is complete.
bool SKImageSources::sourceUsable(SKSurfaceDataOps *source, const char *caller) {
    const SKGraphicsConfig *graphicsConfig = SKContext::current().graphicsConfig();
    if (const SKPlatformConfig *sourceConfig = source->platform != nullptr ? source->platform->config() : nullptr;
            sourceConfig != graphicsConfig->platformConfig()
            && (sourceConfig == nullptr || !sourceConfig->sameDevice(*graphicsConfig->platformConfig()))) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "%s: source %p is on another device, skipped", caller, source);
        return false;
    }
    if (source->state != nullptr && source->state->graphicsConfig() != nullptr
            && source->state->graphicsConfig() != graphicsConfig) {
        SKPresentation::submit(source->state->graphicsConfig(), true);
    }
    return true;
}

// A Skia surface of this context is sampled through its linked image view: Graphite
// flushes the surface's pending draws before a recording samples it. A bare
// WrapTexture of the same texture (SKContext::getImage) carries no such dependency,
// so draws recorded into the source in the same flush (a texture just uploaded)
// may not be ordered before the sampling draw.
bool SKImageSources::isLinkedSource(const SKSurfaceDataOps *source) {
    return source != nullptr && source->state != nullptr
           && source->state->graphicsConfig() == SKContext::current().graphicsConfig()
           && source->platform != nullptr && source->state->wraps(source->platform->textureHandle());
}

// A Skia surface's linked image: Graphite records the source's pending draws ahead of the reads and a later draw
// into the source after them.
SKOwnedImage SKImageSources::blitSourceImage(const SKSurfaceDataOps *source) {
    sk_sp<SkImage> image = SkSurfaces::AsImage(source->state->surface());
    return image ? SKOwnedImage{.image = std::move(image), .graphicsConfig = source->state->graphicsConfig()}
                 : SKOwnedImage{};
}
