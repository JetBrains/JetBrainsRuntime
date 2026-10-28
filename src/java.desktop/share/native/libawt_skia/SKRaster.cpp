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

// Skia's raster backend on a BufferedImage (sun.java2d.skia.SKRasterSurfaceData): the image's
// pixels are locked like the software loops lock them and drawn through a raster SkCanvas, on
// the calling thread, one operation at a time.
//
// The fixed cost per operation is what matters for the IDE's thousands of small fills, so
// nothing is allocated on the way: each thread keeps the canvases of the last few surfaces it
// drew into (a canvas is bound to the pixel pointer the lock returned; it is rebuilt only when
// the pointer, size or stride change), the path and clip data come through stack buffers, and
// an unpremultiplied INT_ARGB image is drawn through a persistent premultiplied mirror of the
// whole image — Skia's raster treats unpremultiplied as premultiplied — with only the touched
// box converted in and out per operation.

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
#include "include/effects/SkGradient.h"
#include "include/core/SkShader.h"
#include "include/core/SkImage.h"
#include "include/core/SkSurfaceProps.h"
#include "include/core/SkFont.h"
#include "include/core/SkSamplingOptions.h"

#include <algorithm>
#include <array>
#include <cstdlib>
#include <vector>

namespace {

// Java's PathIterator segment types
enum { SEG_MOVETO = 0, SEG_LINETO = 1, SEG_QUADTO = 2, SEG_CUBICTO = 3, SEG_CLOSE = 4 };

// The Java ints are B, G, R, A bytes in memory on little-endian machines. This Skia build's native
// 32-bit order (kN32, SK_R32_SHIFT 0) is R, G, B, A: a destination declared BGRA takes Skia's generic
// raster-pipeline blitters, a destination declared N32 its hand-written ones — measured 1.8 µs against
// 0.2 µs for a small antialiased rectangle. So the pixels are declared N32 and every colour that
// enters is swizzled (red and blue exchanged): blending is per channel, so the result is exact.
static_assert(kN32_SkColorType == kRGBA_8888_SkColorType, "the swizzle below assumes an RGBA Skia build");
SkImageInfo infoFor(jint pixelFormat, jint w, jint h) {
    switch (pixelFormat) {
        case sun_java2d_skia_SKSurfaceData_PF_INT_ARGB_PRE: return SkImageInfo::MakeN32(w, h, kPremul_SkAlphaType);
        case sun_java2d_skia_SKSurfaceData_PF_INT_RGB:      return SkImageInfo::MakeN32(w, h, kOpaque_SkAlphaType);
        default:                                            return SkImageInfo::MakeN32(w, h, kUnpremul_SkAlphaType);
    }
}
inline uint32_t premultiplied(uint32_t p) {   // Java's unpremultiplied ARGB int to its premultiplied form, the loops' rounding
    uint32_t a = p >> 24;
    if (a == 0xff) return p;
    if (a == 0) return 0;
    uint32_t c0 = ((p & 0xff) * a + 127) / 255, c1 = (((p >> 8) & 0xff) * a + 127) / 255, c2 = (((p >> 16) & 0xff) * a + 127) / 255;
    return (a << 24) | (c2 << 16) | (c1 << 8) | c0;
}
inline SkColor swizzled(jint argb) {   // Java's ARGB as Skia sees it in BGRA memory declared RGBA
    return SkColorSetARGB((argb >> 24) & 0xff, argb & 0xff, (argb >> 8) & 0xff, (argb >> 16) & 0xff);
}

// One surface's canvas, kept by the drawing thread between operations.
// The pixel geometry Skia's LCD text needs to render sub-pixel masks at all (unknown geometry
// downgrades sub-pixel edging to grayscale): horizontal stripes, declared BGR because the pixels
// are declared RGBA over Java's BGRA memory — Skia's blue channel is Java's red, so a BGR mask
// lands in memory as Java's HRGB order.
static const SkSurfaceProps props(0, kBGR_H_SkPixelGeometry);

struct RasterTarget {
    SurfaceDataOps *ops = nullptr;
    void *pixels = nullptr;          // what the lock returned last time; the canvas draws there
    SkImageInfo info;
    size_t rowBytes = 0;
    std::unique_ptr<SkCanvas> canvas;
    SkBitmap mirror;                 // INT_ARGB: the premultiplied copy of the whole image the canvas draws into
    uint32_t stamp = 0;              // for the least-recently-used replacement

    bool matches(SurfaceDataOps *o, void *p, const SkImageInfo &i, size_t rb) const {
        return ops == o && pixels == p && rowBytes == rb && info.width() == i.width() && info.height() == i.height()
               && info.alphaType() == i.alphaType();
    }
    bool bind(SurfaceDataOps *o, void *p, const SkImageInfo &i, size_t rb) {
        ops = o; pixels = p; info = i; rowBytes = rb; canvas.reset();
        if (i.alphaType() == kUnpremul_SkAlphaType) {
            if (mirror.width() != i.width() || mirror.height() != i.height()) {
                mirror.reset();
                if (!mirror.tryAllocPixels(SkImageInfo::Make(i.width(), i.height(), i.colorType(), kPremul_SkAlphaType))) return false;
            }
            canvas = SkCanvas::MakeRasterDirect(mirror.info(), mirror.getPixels(), mirror.rowBytes(), &props);
        } else {
            mirror.reset();
            canvas = SkCanvas::MakeRasterDirect(i, p, rb, &props);
        }
        return canvas != nullptr;
    }
    bool viaMirror() const { return info.alphaType() == kUnpremul_SkAlphaType; }
    // The touched box into the mirror (unpremultiplied → premultiplied) and back out. Plain
    // loops: Skia's conversion goes through its raster pipeline at ≈ 10 ns per pixel, which
    // made a 30 × 20 fill cost 14 µs; opaque pixels, the common case, are a copy.
    void mirrorIn(const SkIRect &box) {
        for (int y = box.fTop; y < box.fBottom; y++) {
            const uint32_t *src = (const uint32_t *) ((const char *) pixels + y * rowBytes) + box.fLeft;
            uint32_t *dst = mirror.getAddr32(box.fLeft, y);
            for (int x = box.fLeft; x < box.fRight; x++, src++, dst++) *dst = premultiplied(*src);
        }
    }
    void mirrorOut(const SkIRect &box) {
        for (int y = box.fTop; y < box.fBottom; y++) {
            const uint32_t *src = mirror.getAddr32(box.fLeft, y);
            uint32_t *dst = (uint32_t *) ((char *) pixels + y * rowBytes) + box.fLeft;
            for (int x = box.fLeft; x < box.fRight; x++, src++, dst++) {
                uint32_t p = *src, a = p >> 24;
                if (a == 0xff) { *dst = p; continue; }
                if (a == 0) { *dst = 0; continue; }
                uint32_t c0 = std::min<uint32_t>(255, ((p & 0xff) * 255 + a / 2) / a), c1 = std::min<uint32_t>(255, (((p >> 8) & 0xff) * 255 + a / 2) / a),
                         c2 = std::min<uint32_t>(255, (((p >> 16) & 0xff) * 255 + a / 2) / a);
                *dst = (a << 24) | (c2 << 16) | (c1 << 8) | c0;
            }
        }
    }
};

// J2D_SKIA_RASTER_PROBE (measurement only): 1 = lock and unlock, nothing else; 2 = + the canvas clip; 3 = everything
static const int probe = getenv("J2D_SKIA_RASTER_PROBE") ? atoi(getenv("J2D_SKIA_RASTER_PROBE")) : 3;

constexpr int kTargets = 4;
thread_local std::array<RasterTarget, kTargets> targets;
thread_local uint32_t targetStamp = 0;

RasterTarget *targetFor(SurfaceDataOps *ops, void *pixels, const SkImageInfo &info, size_t rowBytes) {
    RasterTarget *victim = &targets[0];
    for (RasterTarget &t : targets) {
        if (t.matches(ops, pixels, info, rowBytes)) { t.stamp = ++targetStamp; return &t; }
        if (t.stamp < victim->stamp) victim = &t;
    }
    victim->stamp = ++targetStamp;
    return victim->bind(ops, pixels, info, rowBytes) ? victim : nullptr;
}

// The locked destination for one operation: lock, draw through the thread's canvas, unlock.
struct Operation {
    JNIEnv *env; SurfaceDataOps *ops = nullptr; SurfaceDataRasInfo ras{}; RasterTarget *target = nullptr; SkIRect box; bool ok = false;

    Operation(JNIEnv *e, jobject sData, jint pixelFormat, jint width, jint height, jint x1, jint y1, jint x2, jint y2) : env(e) {
        ops = SurfaceData_GetOps(env, sData);
        if (ops == nullptr) return;
        ras.bounds.x1 = x1; ras.bounds.y1 = y1; ras.bounds.x2 = x2; ras.bounds.y2 = y2;
        if (ops->Lock(env, ops, &ras, SD_LOCK_WRITE) != SD_SUCCESS) { ops = nullptr; return; }
        ops->GetRasInfo(env, ops, &ras);
        if (ras.rasBase == nullptr || ras.bounds.x1 >= ras.bounds.x2 || ras.bounds.y1 >= ras.bounds.y2) return;
        if (probe < 2) return;
        // rasBase points at pixel (0, 0); the info describes the whole image, so the canvas (and the
        // mirror) outlive the operation — a box-sized info rebuilt them for every call
        target = targetFor(ops, ras.rasBase, infoFor(pixelFormat, width, height), (size_t) ras.scanStride);
        if (target == nullptr) return;
        box = SkIRect::MakeLTRB(ras.bounds.x1, ras.bounds.y1, ras.bounds.x2, ras.bounds.y2);
        if (target->viaMirror()) target->mirrorIn(box);
        target->canvas->save();
        target->canvas->clipIRect(box);
        ok = true;
    }
    SkCanvas *canvas() { return target->canvas.get(); }
    void clipSpans(const jint *spans, size_t count) {   // device coordinates: the mirror's origin is the image's
        SkRegion region;
        for (size_t i = 0; i + 3 < count; i += 4) region.op(SkIRect::MakeLTRB(spans[i], spans[i + 1], spans[i + 2], spans[i + 3]), SkRegion::kUnion_Op);
        target->canvas->clipRegion(region);
    }
    ~Operation() {
        if (ok) { target->canvas->restore(); if (target->viaMirror()) target->mirrorOut(box); }
        if (ops != nullptr) { SurfaceData_InvokeRelease(env, ops, &ras); SurfaceData_InvokeUnlock(env, ops, &ras); }
    }
};

// The paint descriptor the Java side builds (SKRasterShapePipe.paintOf): d[0] kind, d[1] extra alpha,
// d[2] cycle method, d[3] linear colour space, d[4] stop count, d[5..10] the graphics transform,
// d[11..16] the paint's own transform, then the geometry and the stop fractions; colours ARGB.
enum { PAINT_COLOR = 0, PAINT_GRADIENT = 1, PAINT_LINEAR = 2, PAINT_RADIAL = 3 };

SkColor4f swizzledColor4f(jint argb) { return SkColor4f::FromColor(swizzled(argb)); }

SkMatrix matrixOf(const jfloat *m) { return SkMatrix::MakeAll(m[0], m[2], m[4], m[1], m[3], m[5], 0, 0, 1); }

SkPaint paintFor(const jfloat *d, jint dCount, const jint *colors, jint compositeRule) {
    SkPaint paint;
    paint.setAntiAlias(true);
    paint.setBlendMode(SKBackend_BlendModeForRule(compositeRule));
    const int kind = (int) d[0];
    if (kind == PAINT_COLOR || dCount < 17) {
        paint.setColor(swizzled(colors[0]));
        return paint;
    }
    const float extraAlpha = d[1]; const int cycle = (int) d[2]; const bool linear = d[3] != 0; const int stops = (int) d[4];
    // the gradient lives in user space: local matrix = graphics transform × the paint's own transform;
    // Java's paint contexts evaluate a paint at the pixel's integer coordinates, Skia at pixel centres, so
    // the device-space result is shifted by half a pixel (the GPU pipe's atPixelOrigin)
    SkMatrix local = matrixOf(d + 5); local.preConcat(matrixOf(d + 11)); local.postTranslate(0.5f, 0.5f);
    const jfloat *geo = d + 17;
    std::vector<SkColor4f> c4(stops);
    for (int i = 0; i < stops; i++) c4[i] = swizzledColor4f(colors[i]);
    // Java's software gradients interpolate the unpremultiplied stop components (the GPU pipe follows
    // its shaders' premultiplied interpolation instead: a recorded difference)
    SkGradient::Interpolation interp;
    if (linear) interp.fColorSpace = SkGradient::Interpolation::ColorSpace::kSRGBLinear;
    sk_sp<SkShader> shader;
    if (kind == PAINT_GRADIENT) {
        SkPoint pts[2] = { SkPoint::Make(geo[0], geo[1]), SkPoint::Make(geo[2], geo[3]) };
        SkGradient grad(SkGradient::Colors(SkSpan(c4.data(), 2), cycle != 0 ? SkTileMode::kMirror : SkTileMode::kClamp), interp);
        shader = SkShaders::LinearGradient(pts, grad, &local);
    } else if (kind == PAINT_LINEAR) {
        SkPoint pts[2] = { SkPoint::Make(geo[0], geo[1]), SkPoint::Make(geo[2], geo[3]) };
        const jfloat *fractions = geo + 4;
        if (cycle == 2) { const SkVector v = pts[1] - pts[0]; pts[0] -= v * SKBackend_RepeatSeamBias(); pts[1] -= v * SKBackend_RepeatSeamBias(); }
        SkGradient grad(SkGradient::Colors(SkSpan(c4.data(), stops), SkSpan(fractions, stops), SKBackend_TileModeFor(cycle), SKBackend_StopColorSpace(linear)), interp);
        shader = SkShaders::LinearGradient(pts, grad, &local);
    } else {
        // centre, radius, focus; Java keeps the focus inside 97 % of the radius
        SkPoint centre = SkPoint::Make(geo[0], geo[1]); float radius = geo[2]; SkPoint focus = SkPoint::Make(geo[3], geo[4]);
        SkVector f = focus - centre; float fl = f.length();
        if (fl > radius * 0.97f) { f.scale(radius * 0.97f / fl); focus = centre + f; }
        const jfloat *fractions = geo + 5;
        if (cycle == 2) radius /= 1.0f + SKBackend_RepeatSeamBias();
        SkGradient grad(SkGradient::Colors(SkSpan(c4.data(), stops), SkSpan(fractions, stops), SKBackend_TileModeFor(cycle), SKBackend_StopColorSpace(linear)), interp);
        shader = SkShaders::TwoPointConicalGradient(focus, 0.0f, centre, radius, grad, &local);
    }
    paint.setShader(std::move(shader));
    paint.setAlphaf(std::clamp(extraAlpha, 0.0f, 1.0f));
    return paint;
}

SkPath pathOf(const jbyte *types, jint typeCount, const jfloat *c, jint windingRule) {
    SkPathBuilder builder;
    builder.setFillType(windingRule == 0 /* WIND_EVEN_ODD */ ? SkPathFillType::kEvenOdd : SkPathFillType::kWinding);
    for (jint i = 0; i < typeCount; i++) {
        switch (types[i]) {
            case SEG_MOVETO:  builder.moveTo(c[0], c[1]); c += 2; break;
            case SEG_LINETO:  builder.lineTo(c[0], c[1]); c += 2; break;
            case SEG_QUADTO:  builder.quadTo(c[0], c[1], c[2], c[3]); c += 4; break;
            case SEG_CUBICTO: builder.cubicTo(c[0], c[1], c[2], c[3], c[4], c[5]); c += 6; break;
            case SEG_CLOSE:   builder.close(); break;
        }
    }
    return builder.detach();
}

// Java arrays into stack buffers (the heap for the rare long path); read before the lock pins the image
template <size_t N, typename T> struct Buffer {
    std::array<T, N> small; std::vector<T> large; T *data; size_t size;
    Buffer(size_t n) : size(n) { if (n <= N) data = small.data(); else { large.resize(n); data = large.data(); } }
};

struct PaintData {
    Buffer<48, jfloat> d; Buffer<16, jint> colors;
    PaintData(JNIEnv *env, jfloatArray da, jintArray ca) : d(env->GetArrayLength(da)), colors(env->GetArrayLength(ca)) {
        env->GetFloatArrayRegion(da, 0, (jsize) d.size, d.data); env->GetIntArrayRegion(ca, 0, (jsize) colors.size, colors.data);
    }
    SkPaint paint(jint compositeRule) { return paintFor(d.data, (jint) d.size, colors.data, compositeRule); }
};


} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_sun_java2d_skia_SKRasterShapePipe_fillRect(JNIEnv *env, jclass clazz, jobject sData, jint pixelFormat, jint width, jint height,
                                                 jfloat x1, jfloat y1, jfloat x2, jfloat y2, jfloatArray paintArray, jintArray colorsArray, jint compositeRule,
                                                 jint clipX1, jint clipY1, jint clipX2, jint clipY2, jintArray spansArray)
{
    Buffer<64, jint> spans(spansArray != nullptr ? env->GetArrayLength(spansArray) : 0);
    if (spans.size) env->GetIntArrayRegion(spansArray, 0, (jsize) spans.size, spans.data);
    PaintData pd(env, paintArray, colorsArray);
    Operation op(env, sData, pixelFormat, width, height, clipX1, clipY1, clipX2, clipY2);
    if (!op.ok) return;
    if (spans.size) op.clipSpans(spans.data, spans.size);
    if (probe < 3) return;
    op.canvas()->drawRect(SkRect::MakeLTRB(x1, y1, x2, y2), pd.paint(compositeRule));
}

JNIEXPORT void JNICALL
Java_sun_java2d_skia_SKRasterShapePipe_strokePath(JNIEnv *env, jclass clazz, jobject sData, jint pixelFormat, jint width, jint height,
                                                   jbyteArray typesArray, jint typeCount, jfloatArray coordsArray, jint coordCount, jint windingRule,
                                                   jfloatArray xformArray, jfloat lineWidth, jint cap, jint join, jfloat miterLimit,
                                                   jfloatArray paintArray, jintArray colorsArray, jint compositeRule, jint clipX1, jint clipY1, jint clipX2, jint clipY2, jintArray spansArray)
{
    Buffer<64, jbyte> types(typeCount); Buffer<256, jfloat> coords(coordCount);
    Buffer<64, jint> spans(spansArray != nullptr ? env->GetArrayLength(spansArray) : 0);
    env->GetByteArrayRegion(typesArray, 0, typeCount, types.data);
    env->GetFloatArrayRegion(coordsArray, 0, coordCount, coords.data);
    if (spans.size) env->GetIntArrayRegion(spansArray, 0, (jsize) spans.size, spans.data);
    jfloat xf[6]; env->GetFloatArrayRegion(xformArray, 0, 6, xf);
    SkPath path = pathOf(types.data, typeCount, coords.data, windingRule);
    // BasicStroke's cap and join constants: CAP_BUTT 0, CAP_ROUND 1, CAP_SQUARE 2; JOIN_MITER 0, JOIN_ROUND 1, JOIN_BEVEL 2
    static const SkPaint::Cap caps[] = { SkPaint::kButt_Cap, SkPaint::kRound_Cap, SkPaint::kSquare_Cap };
    static const SkPaint::Join joins[] = { SkPaint::kMiter_Join, SkPaint::kRound_Join, SkPaint::kBevel_Join };
    PaintData pd(env, paintArray, colorsArray);
    SkPaint paint = pd.paint(compositeRule);
    paint.setStyle(SkPaint::kStroke_Style);
    paint.setStrokeWidth(lineWidth);
    paint.setStrokeCap(caps[std::clamp((int) cap, 0, 2)]);
    paint.setStrokeJoin(joins[std::clamp((int) join, 0, 2)]);
    paint.setStrokeMiter(miterLimit);
    Operation op(env, sData, pixelFormat, width, height, clipX1, clipY1, clipX2, clipY2);
    if (!op.ok) return;
    if (spans.size) op.clipSpans(spans.data, spans.size);
    // the geometry is stroked in user space and drawn under the transform (Java's stroke semantics)
    op.canvas()->concat(SkMatrix::MakeAll(xf[0], xf[2], xf[4], xf[1], xf[3], xf[5], 0, 0, 1));
    op.canvas()->drawPath(path, paint);
}

JNIEXPORT void JNICALL
Java_sun_java2d_skia_SKRasterShapePipe_fillPath(JNIEnv *env, jclass clazz, jobject sData, jint pixelFormat, jint width, jint height,
                                                 jbyteArray typesArray, jint typeCount, jfloatArray coordsArray, jint coordCount,
                                                 jint windingRule, jfloatArray paintArray, jintArray colorsArray, jint compositeRule,
                                                 jint clipX1, jint clipY1, jint clipX2, jint clipY2, jintArray spansArray)
{
    Buffer<64, jbyte> types(typeCount); Buffer<256, jfloat> coords(coordCount);
    Buffer<64, jint> spans(spansArray != nullptr ? env->GetArrayLength(spansArray) : 0);
    env->GetByteArrayRegion(typesArray, 0, typeCount, types.data);
    env->GetFloatArrayRegion(coordsArray, 0, coordCount, coords.data);
    if (spans.size) env->GetIntArrayRegion(spansArray, 0, (jsize) spans.size, spans.data);
    SkPath path = pathOf(types.data, typeCount, coords.data, windingRule);
    PaintData pd(env, paintArray, colorsArray);
    Operation op(env, sData, pixelFormat, width, height, clipX1, clipY1, clipX2, clipY2);
    if (!op.ok) return;
    if (spans.size) op.clipSpans(spans.data, spans.size);
    op.canvas()->drawPath(path, pd.paint(compositeRule));
}

JNIEXPORT void JNICALL
Java_sun_java2d_skia_SKRasterDrawImage_drawImage(JNIEnv *env, jclass clazz, jobject dstData, jint dstPixelFormat, jint dstWidth, jint dstHeight,
                                                 jobject srcData, jint srcPixelFormat, jint srcWidth, jint srcHeight, jint sx1, jint sy1, jint sx2, jint sy2,
                                                 jfloatArray xformArray, jint interpType, jfloat extraAlpha, jint compositeRule,
                                                 jint clipX1, jint clipY1, jint clipX2, jint clipY2, jintArray spansArray)
{
    Buffer<64, jint> spans(spansArray != nullptr ? env->GetArrayLength(spansArray) : 0);
    if (spans.size) env->GetIntArrayRegion(spansArray, 0, (jsize) spans.size, spans.data);
    jfloat xf[6]; env->GetFloatArrayRegion(xformArray, 0, 6, xf);
    SurfaceDataOps *srcOps = SurfaceData_GetOps(env, srcData);   // every JNI call before the pins
    if (srcOps == nullptr) return;
    // AffineTransformOp: 1 nearest neighbour, 2 bilinear, 3 bicubic (Java's kernel is Catmull-Rom's, a = -0.5)
    SkSamplingOptions sampling = interpType == 3 ? SkSamplingOptions(SkCubicResampler::CatmullRom())
                               : interpType == 2 ? SkSamplingOptions(SkFilterMode::kLinear) : SkSamplingOptions(SkFilterMode::kNearest);
    Operation op(env, dstData, dstPixelFormat, dstWidth, dstHeight, clipX1, clipY1, clipX2, clipY2);
    if (!op.ok) return;
    if (spans.size) op.clipSpans(spans.data, spans.size);
    // the source, locked for reading (a nested pin: no JNI call until it is released)
    SurfaceDataRasInfo sr{};
    sr.bounds.x1 = sx1; sr.bounds.y1 = sy1; sr.bounds.x2 = sx2; sr.bounds.y2 = sy2;
    if (srcOps->Lock(env, srcOps, &sr, SD_LOCK_READ) != SD_SUCCESS) return;
    srcOps->GetRasInfo(env, srcOps, &sr);
    const int sw = sx2 - sx1, sh = sy2 - sy1;
    Buffer<4096, uint32_t> premul(srcPixelFormat == sun_java2d_skia_SKSurfaceData_PF_INT_ARGB ? (size_t) sw * sh : 0);
    if (sr.rasBase != nullptr && sw > 0 && sh > 0) {
        // the source rectangle only (a filter's neighbours clamp to its edge, as in Java's transform helper).
        // An unpremultiplied source is premultiplied first, as the helper does: Skia would filter the stored
        // values and premultiply the result, which differs wherever alpha varies between neighbours
        const void *pixels = (const char *) sr.rasBase + (size_t) sy1 * sr.scanStride + (size_t) sx1 * 4;
        size_t rowBytes = (size_t) sr.scanStride;
        SkImageInfo info = infoFor(srcPixelFormat, sw, sh);
        if (premul.size) {
            for (int y = 0; y < sh; y++) {
                const uint32_t *in = (const uint32_t *) ((const char *) pixels + y * rowBytes);
                uint32_t *out = premul.data + (size_t) y * sw;
                for (int x = 0; x < sw; x++) out[x] = premultiplied(in[x]);
            }
            pixels = premul.data; rowBytes = (size_t) sw * 4; info = info.makeAlphaType(kPremul_SkAlphaType);
        }
        sk_sp<SkImage> image = SkImages::RasterFromPixmap(SkPixmap(info, pixels, rowBytes), nullptr, nullptr);   // no copy: the pixels live until the unlock
        if (image) {
            SkPaint paint;
            paint.setBlendMode(SKBackend_BlendModeForRule(compositeRule));
            paint.setAlphaf(std::clamp(extraAlpha, 0.0f, 1.0f));
            // the transform maps the source rectangle's origin to the destination. Java's transform helper
            // draws the destination pixels whose centre maps inside the source and clamps the neighbours a
            // filter reads to the source's edge: a non-antialiased rectangle (a pixel is in when its centre
            // is) over a clamped image shader does both
            paint.setShader(image->makeShader(SkTileMode::kClamp, SkTileMode::kClamp, sampling));
            op.canvas()->concat(matrixOf(xf));
            op.canvas()->drawRect(SkRect::MakeWH(sw, sh), paint);
            image.reset();
        }
    }
    SurfaceData_InvokeRelease(env, srcOps, &sr);
    SurfaceData_InvokeUnlock(env, srcOps, &sr);
}

JNIEXPORT void JNICALL
Java_sun_java2d_skia_SKRasterTextPipe_drawGlyphs(JNIEnv *env, jclass clazz, jobject dstData, jint dstPixelFormat, jint dstWidth, jint dstHeight,
                                                 jlong scalerContext, jint numGlyphs, jboolean usePositions, jboolean smoothing,
                                                 jfloat originX, jfloat originY, jintArray codesArray, jlongArray imagesArray, jfloatArray positionsArray,
                                                 jint argb, jint compositeRule, jint clipX1, jint clipY1, jint clipX2, jint clipY2, jintArray spansArray)
{
    if (numGlyphs <= 0) return;
    // every Java array read before the pin
    Buffer<256, jint> codes((size_t) numGlyphs); env->GetIntArrayRegion(codesArray, 0, numGlyphs, codes.data);
    Buffer<256, jlong> images(imagesArray != nullptr ? (size_t) numGlyphs : 0);
    if (images.size) env->GetLongArrayRegion(imagesArray, 0, numGlyphs, images.data);
    Buffer<512, jfloat> positions(usePositions && positionsArray != nullptr ? (size_t) 2 * numGlyphs : 0);
    if (positions.size) env->GetFloatArrayRegion(positionsArray, 0, (jsize) positions.size, positions.data);
    Buffer<64, jint> spans(spansArray != nullptr ? env->GetArrayLength(spansArray) : 0);
    if (spans.size) env->GetIntArrayRegion(spansArray, 0, (jsize) spans.size, spans.data);
    Operation op(env, dstData, dstPixelFormat, dstWidth, dstHeight, clipX1, clipY1, clipX2, clipY2);
    if (!op.ok) return;
    if (spans.size) op.clipSpans(spans.data, spans.size);
    SkPaint paint;
    paint.setAntiAlias(true);
    paint.setColor(swizzled(argb));   // extra alpha already in the colour (SunGraphics2D.validateColor)
    paint.setBlendMode(SKBackend_BlendModeForRule(compositeRule));
    SKBackend_DrawGlyphsOnCanvas(op.canvas(), paint, (SkColor) argb, scalerContext, numGlyphs, usePositions, smoothing,
                                 originX, originY, codes.data, images.size ? images.data : nullptr, positions.size ? positions.data : nullptr);
}

} // extern "C"
