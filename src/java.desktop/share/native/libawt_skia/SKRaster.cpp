/*
 * Copyright 2026 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Skia's raster backend on a BufferedImage (sun.java2d.skia.SKRasterSurfaceData): the
// image's pixels are locked like the software loops lock them and wrapped in a raster
// SkCanvas for the duration of one drawing operation, on the calling thread.

#include "SKBackend.h"

#include "jni_util.h"
#include "SurfaceData.h"
#include "Trace.h"
#include "sun_java2d_skia_SKSurfaceData.h"

#include "include/core/SkBitmap.h"
#include "include/core/SkCanvas.h"
#include "include/core/SkPath.h"
#include "include/core/SkPathBuilder.h"
#include "include/core/SkRegion.h"

#include <vector>

namespace {

// Java's PathIterator segment types
enum { SEG_MOVETO = 0, SEG_LINETO = 1, SEG_QUADTO = 2, SEG_CUBICTO = 3, SEG_CLOSE = 4 };

SkPath pathFrom(const std::vector<jbyte> &types, const std::vector<jfloat> &coords, jint windingRule) {
    SkPathBuilder path;
    path.setFillType(windingRule == 0 /* WIND_EVEN_ODD */ ? SkPathFillType::kEvenOdd : SkPathFillType::kWinding);
    size_t c = 0;
    for (jbyte t : types) {
        switch (t) {
            case SEG_MOVETO:  path.moveTo(coords[c], coords[c + 1]); c += 2; break;
            case SEG_LINETO:  path.lineTo(coords[c], coords[c + 1]); c += 2; break;
            case SEG_QUADTO:  path.quadTo(coords[c], coords[c + 1], coords[c + 2], coords[c + 3]); c += 4; break;
            case SEG_CUBICTO: path.cubicTo(coords[c], coords[c + 1], coords[c + 2], coords[c + 3], coords[c + 4], coords[c + 5]); c += 6; break;
            case SEG_CLOSE:   path.close(); break;
        }
    }
    return path.detach();
}

// Skia's raster surfaces treat kUnpremul as kPremul (SkBitmap), so an INT_ARGB image is
// drawn through a premultiplied copy of the touched pixels and converted back.
struct Destination {
    SkImageInfo info;        // the image's own pixel format
    void *base;              // pixel (0, 0)
    size_t rowBytes;
    SkIRect box;             // the pixels this operation may touch
    SkBitmap premul;         // the copy for unpremultiplied images, box-sized
    bool viaCopy = false;

    std::unique_ptr<SkCanvas> canvas() {
        if (info.alphaType() != kUnpremul_SkAlphaType) {
            return SkCanvas::MakeRasterDirect(info, base, rowBytes);
        }
        viaCopy = true;
        premul.allocPixels(SkImageInfo::Make(box.width(), box.height(), info.colorType(), kPremul_SkAlphaType));
        SkPixmap src(info, base, rowBytes);
        src.readPixels(premul.pixmap(), box.fLeft, box.fTop);   // unpremultiplied → premultiplied
        std::unique_ptr<SkCanvas> c = SkCanvas::MakeRasterDirect(premul.info(), premul.getPixels(), premul.rowBytes());
        if (c) c->translate(-box.fLeft, -box.fTop);
        return c;
    }
    void finish() {
        if (!viaCopy) return;
        SkPixmap dst(SkImageInfo::Make(box.width(), box.height(), info.colorType(), kUnpremul_SkAlphaType),
                     (char *) base + box.fTop * rowBytes + box.fLeft * info.bytesPerPixel(), rowBytes);
        premul.pixmap().readPixels(dst, 0, 0);                  // premultiplied → unpremultiplied
    }
};

SkImageInfo infoFor(jint pixelFormat, jint w, jint h) {
    // the Java ints are B, G, R, A bytes in memory on little-endian machines
    switch (pixelFormat) {
        case sun_java2d_skia_SKSurfaceData_PF_INT_ARGB_PRE: return SkImageInfo::Make(w, h, kBGRA_8888_SkColorType, kPremul_SkAlphaType);
        case sun_java2d_skia_SKSurfaceData_PF_INT_RGB:      return SkImageInfo::Make(w, h, kBGRA_8888_SkColorType, kOpaque_SkAlphaType);
        default:                                            return SkImageInfo::Make(w, h, kBGRA_8888_SkColorType, kUnpremul_SkAlphaType);
    }
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_sun_java2d_skia_SKRasterShapePipe_fillPath(JNIEnv *env, jclass clazz, jobject sData, jint pixelFormat,
                                                 jbyteArray typesArray, jint typeCount, jfloatArray coordsArray, jint coordCount,
                                                 jint windingRule, jint argb, jint compositeRule,
                                                 jint clipX1, jint clipY1, jint clipX2, jint clipY2, jintArray spansArray)
{
    // every Java array is copied before the pixels are locked: the lock pins the image's array,
    // and no JNI call may happen while it is pinned
    std::vector<jbyte> types(typeCount); std::vector<jfloat> coords(coordCount); std::vector<jint> spans;
    env->GetByteArrayRegion(typesArray, 0, typeCount, types.data());
    env->GetFloatArrayRegion(coordsArray, 0, coordCount, coords.data());
    if (spansArray != nullptr) {
        spans.resize(env->GetArrayLength(spansArray));
        env->GetIntArrayRegion(spansArray, 0, (jsize) spans.size(), spans.data());
    }
    SkPath path = pathFrom(types, coords, windingRule);

    SurfaceDataOps *ops = SurfaceData_GetOps(env, sData);
    if (ops == nullptr) return;
    SurfaceDataRasInfo ras;
    ras.bounds.x1 = clipX1; ras.bounds.y1 = clipY1; ras.bounds.x2 = clipX2; ras.bounds.y2 = clipY2;
    if (ops->Lock(env, ops, &ras, SD_LOCK_WRITE) != SD_SUCCESS) return;
    ops->GetRasInfo(env, ops, &ras);
    if (ras.rasBase == nullptr || ras.bounds.x1 >= ras.bounds.x2 || ras.bounds.y1 >= ras.bounds.y2) {
        SurfaceData_InvokeRelease(env, ops, &ras);
        SurfaceData_InvokeUnlock(env, ops, &ras);
        return;
    }
    {
        // rasBase points at pixel (0, 0); the image's size is not in the RasInfo, so the canvas
        // covers the locked box only, placed at its device position
        Destination dst{infoFor(pixelFormat, ras.bounds.x2, ras.bounds.y2), ras.rasBase, (size_t) ras.scanStride,
                        SkIRect::MakeLTRB(ras.bounds.x1, ras.bounds.y1, ras.bounds.x2, ras.bounds.y2)};
        std::unique_ptr<SkCanvas> canvas = dst.canvas();
        if (canvas) {
            canvas->clipIRect(dst.box);
            if (!spans.empty()) {
                // clipRegion takes device coordinates and ignores the canvas matrix: on the copy the
                // device origin is the box's corner, so the spans move with it
                int ox = dst.viaCopy ? dst.box.fLeft : 0, oy = dst.viaCopy ? dst.box.fTop : 0;
                SkRegion region;
                for (size_t i = 0; i + 3 < spans.size(); i += 4) {
                    region.op(SkIRect::MakeLTRB(spans[i] - ox, spans[i + 1] - oy, spans[i + 2] - ox, spans[i + 3] - oy), SkRegion::kUnion_Op);
                }
                canvas->clipRegion(region);
            }
            SkPaint paint;
            paint.setAntiAlias(true);
            paint.setColor(SkColorSetARGB((argb >> 24) & 0xff, (argb >> 16) & 0xff, (argb >> 8) & 0xff, argb & 0xff));
            paint.setBlendMode(SKBackend_BlendModeForRule(compositeRule));
            canvas->drawPath(path, paint);
            canvas.reset();
            dst.finish();
        }
    }
    SurfaceData_InvokeRelease(env, ops, &ras);
    SurfaceData_InvokeUnlock(env, ops, &ras);
}

} // extern "C"
