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
// The sources of image draws (SKImageSources.h).
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
#include <cstring>
#include <vector>

namespace {

// The int pixel formats this file handles are 4 bytes per pixel.
constexpr size_t BYTES_PER_PIXEL = 4;
// The alpha byte of an IntArgb-layout pixel, set for the formats that leave it undefined.
constexpr uint32_t OPAQUE_ALPHA = 0xff000000u;
// softwareSurfaceType's result for a surface that is not one of the int formats.
constexpr jint NO_SOFTWARE_TYPE = -1;
// A bound no surface reaches: imageOfSoftwareSurface asks for the whole surface, the lock clips.
constexpr jint WHOLE_SURFACE = 1 << 30;

// The staging buffer is kept between blits up to this many pixels; one oversized blit must not pin its buffer.
constexpr size_t OPAQUE_STAGING_KEPT_PIXELS = (size_t) 2048 * 2048;

// Skia's view of a software surface's PF_* type: the color type and how the alpha is read.
struct SoftwarePixelFormat {
    SkColorType colorType = kBGRA_8888_SkColorType;
    SkAlphaType alphaType = kPremul_SkAlphaType;
};

SoftwarePixelFormat softwarePixelFormat(jint sourceType) {
    switch (sourceType) {
        case sun_java2d_skia_SKSurfaceData_PF_INT_ARGB:     return { kBGRA_8888_SkColorType, kUnpremul_SkAlphaType };
        case sun_java2d_skia_SKSurfaceData_PF_INT_ARGB_PRE: return { kBGRA_8888_SkColorType, kPremul_SkAlphaType };
        case sun_java2d_skia_SKSurfaceData_PF_INT_RGB:
        case sun_java2d_skia_SKSurfaceData_PF_INT_RGBX:     return { kBGRA_8888_SkColorType, kOpaque_SkAlphaType };
        case sun_java2d_skia_SKSurfaceData_PF_INT_BGR:
        case sun_java2d_skia_SKSurfaceData_PF_INT_BGRX:     return { kRGBA_8888_SkColorType, kOpaque_SkAlphaType };
        default:                                            return {};
    }
}

// The PF_* type of a software surface from its Java SurfaceData's SurfaceType: the int formats
// imageFromSoftwareSurface uploads as they are; NO_SOFTWARE_TYPE for any other (or when it cannot be told).
jint softwareSurfaceType(JNIEnv *env, SurfaceDataOps *ops) {
    struct SurfaceTypeField { const char *field; jint type; };
    static constexpr std::array<SurfaceTypeField, 6> SURFACE_TYPES = {{
        { "IntArgb", sun_java2d_skia_SKSurfaceData_PF_INT_ARGB },
        { "IntArgbPre", sun_java2d_skia_SKSurfaceData_PF_INT_ARGB_PRE },
        { "IntRgb", sun_java2d_skia_SKSurfaceData_PF_INT_RGB },
        { "IntRgbx", sun_java2d_skia_SKSurfaceData_PF_INT_RGBX },
        { "IntBgr", sun_java2d_skia_SKSurfaceData_PF_INT_BGR },
        { "IntBgrx", sun_java2d_skia_SKSurfaceData_PF_INT_BGRX },
    }};
    static std::array<jobject, SURFACE_TYPES.size()> surfaceTypeObjects = {};   // global refs, flusher thread only
    static bool lookedUp = false;
    if (env == nullptr || ops->sdObject == nullptr) {
        return NO_SOFTWARE_TYPE;
    }
    if (!lookedUp) {
        lookedUp = true;
        jclass surfaceTypeClass = env->FindClass("sun/java2d/loops/SurfaceType");
        for (size_t index = 0; surfaceTypeClass != nullptr && index < SURFACE_TYPES.size(); index++) {
            jfieldID fieldID = env->GetStaticFieldID(surfaceTypeClass, SURFACE_TYPES[index].field, "Lsun/java2d/loops/SurfaceType;");
            jobject value = fieldID != nullptr ? env->GetStaticObjectField(surfaceTypeClass, fieldID) : nullptr;
            if (value != nullptr) {
                surfaceTypeObjects[index] = env->NewGlobalRef(value);
                env->DeleteLocalRef(value);
            }
        }
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
    }
    jobject surfaceData = env->NewLocalRef(ops->sdObject);   // a weak reference
    if (surfaceData == nullptr) {
        return NO_SOFTWARE_TYPE;
    }
    jobject surfaceType = JNU_CallMethodByName(env, nullptr, surfaceData, "getSurfaceType", "()Lsun/java2d/loops/SurfaceType;").l;
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

bool SKImageSources::isOpaqueSoftwareType(jint sourceType) {
    return sourceType == sun_java2d_skia_SKSurfaceData_PF_INT_RGB || sourceType == sun_java2d_skia_SKSurfaceData_PF_INT_RGBX
           || sourceType == sun_java2d_skia_SKSurfaceData_PF_INT_BGR || sourceType == sun_java2d_skia_SKSurfaceData_PF_INT_BGRX;
}

// Uploads a software surface's pixels to a texture of the current context.
SKOwnedImage SKImageSources::imageFromSoftwareSurface(JNIEnv *env, SurfaceDataOps *ops, jint sourceType,
                                                      jint sx1, jint sy1, jint sx2, jint sy2) {
    if (ops == nullptr || ops->Lock == nullptr) {
        return {};
    }
    SKContext &context = SKContext::current();
    if (SKSurfaceData::isSkiaOps(ops)) {
        // a Skia surface routed through a software loop: sample it, do not lock it
        J2dRlsTraceLn(J2D_TRACE_WARNING, "imageFromSoftwareSurface: source %p is a Skia surface", ops);
        return context.getImage((SKSDOps *) ops);
    }
    SurfaceDataRasInfo rasterInfo;
    memset(&rasterInfo, 0, sizeof(rasterInfo));
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
        const SoftwarePixelFormat format = softwarePixelFormat(sourceType);
        const int width = rasterInfo.bounds.x2 - rasterInfo.bounds.x1, height = rasterInfo.bounds.y2 - rasterInfo.bounds.y1;
        const char *base = (const char *) rasterInfo.rasBase + (ptrdiff_t) rasterInfo.bounds.y1 * rasterInfo.scanStride
                           + (ptrdiff_t) rasterInfo.bounds.x1 * rasterInfo.pixelStride;
        if (format.alphaType == kOpaque_SkAlphaType) {
            // IntRgb & co. leave the alpha byte undefined; set it instead of
            // relying on Graphite's opaque-image swizzle. The buffer is reused
            // across blits (flusher thread only); the caller's tiles bound it to
            // OPAQUE_TILE squared.
            static std::vector<uint32_t> opaque;
            opaque.resize((size_t) width * height);
            for (int row = 0; row < height; row++) {
                const uint32_t *sourceRow = (const uint32_t *) (base + (ptrdiff_t) row * rasterInfo.scanStride);
                uint32_t *destRow = opaque.data() + (size_t) row * width;
                for (int column = 0; column < width; column++) {
                    destRow[column] = sourceRow[column] | OPAQUE_ALPHA;
                }
            }
            image = context.uploadPixmap(SkPixmap(SkImageInfo::Make(width, height, format.colorType, kPremul_SkAlphaType), opaque.data(),
                                                  (size_t) width * BYTES_PER_PIXEL));
            if (opaque.capacity() > OPAQUE_STAGING_KEPT_PIXELS) {
                std::vector<uint32_t>().swap(opaque);
            }
        } else {
            image = context.uploadPixmap(SkPixmap(SkImageInfo::Make(width, height, format.colorType, format.alphaType), base,
                                                  (size_t) rasterInfo.scanStride));
        }
    }
    SurfaceData_InvokeRelease(env, ops, &rasterInfo);
    SurfaceData_InvokeUnlock(env, ops, &rasterInfo);
    return image;
}

SKOwnedImage SKImageSources::imageOfSoftwareSurface(JNIEnv *env, SurfaceDataOps *ops) {
    if (ops == nullptr || SKSurfaceData::isSkiaOps(ops)) {
        return {};
    }
    const jint type = softwareSurfaceType(env, ops);
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
bool SKImageSources::sourceUsable(SKSDOps *source, const char *caller) {
    SKGraphicsConfig *graphicsConfig = SKContext::current().graphicsConfig();
    SKPlatformConfig *sourceConfig = SKPlatform::configOf(source->platform);
    if (sourceConfig != graphicsConfig->platformConfig() && !SKPlatform::sameDevice(sourceConfig, graphicsConfig->platformConfig())) {
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
bool SKImageSources::isLinkedSource(const SKSDOps *source) {
    return source != nullptr && source->state != nullptr
           && source->state->graphicsConfig() == SKContext::current().graphicsConfig()
           && source->platform != nullptr && source->state->wraps(SKPlatform::textureHandle(source->platform));
}

// A Skia surface's linked image: Graphite records the source's pending draws ahead of the reads and a later draw
// into the source after them.
SKOwnedImage SKImageSources::blitSourceImage(SKSDOps *source) {
    sk_sp<SkImage> image = SkSurfaces::AsImage(source->state->surface());
    return image ? SKOwnedImage{std::move(image), source->state->graphicsConfig()} : SKOwnedImage{};
}
