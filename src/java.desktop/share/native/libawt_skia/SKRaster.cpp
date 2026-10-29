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
#include "java_awt_AlphaComposite.h"
#include "BufImgSurfaceData.h"
#include "include/core/SkFont.h"
#include "include/core/SkSamplingOptions.h"

#include <algorithm>
#include <array>
#include <cstdlib>
#include <vector>
#include <atomic>

namespace {

// Java's PathIterator segment types
enum { SEG_MOVETO = 0, SEG_LINETO = 1, SEG_QUADTO = 2, SEG_CUBICTO = 3, SEG_CLOSE = 4 };

// The Java ints are B, G, R, A bytes in memory on little-endian machines. A destination declared in
// Skia's native 32-bit order (kN32) takes Skia's hand-written blitters, any other order its generic
// raster-pipeline ones — measured 1.8 µs against 0.2 µs for a small antialiased rectangle. So the
// pixels are always declared N32. Skia's N32 is R, G, B, A on the macOS and Linux builds (SK_R32_SHIFT 0)
// and B, G, R, A on the Windows build (SkTypes.h: SK_R32_SHIFT 16 under SK_BUILD_FOR_WIN): where it
// is RGBA every colour that enters is swizzled (red and blue exchanged; blending is per channel, so
// the result is exact), where it is BGRA Java's ints are Skia's pixels as they are.
static_assert(kN32_SkColorType == kRGBA_8888_SkColorType || kN32_SkColorType == kBGRA_8888_SkColorType,
              "Java's ARGB ints are handled for an RGBA or a BGRA Skia build");
constexpr bool kSwizzle = kN32_SkColorType == kRGBA_8888_SkColorType;
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
// A device point packed by SKRasterShapePipe.packed: x in the high half, y in the low half.
inline jint px(jint p) { return (jint) ((uint32_t) p >> 16); }
inline jint py(jint p) { return p & 0xffff; }

inline SkColor swizzled(jint argb) {   // Java's ARGB as Skia sees it in BGRA memory declared N32
    if (!kSwizzle) return (SkColor) (uint32_t) argb;   // BGRA build: SkColor is A, R, G, B in an int, as Java's
    return SkColorSetARGB((argb >> 24) & 0xff, argb & 0xff, (argb >> 8) & 0xff, (argb >> 16) & 0xff);
}

// One surface's canvas, kept by the drawing thread between operations.
// The pixel geometry Skia's LCD text needs to render sub-pixel masks at all (unknown geometry
// downgrades sub-pixel edging to grayscale): horizontal stripes in the display's order
// (SKBackend_LcdPixelGeometry: the desktop hint's, or sun.java2d.skia.lcdOrder). On an RGBA
// build the pixels are declared RGBA over Java's BGRA memory — Skia's blue channel is Java's red,
// so the declared order is the mirror of the display's; on a BGRA build the channels are Java's.
static SkSurfaceProps surfaceProps() {
    const bool bgr = SKBackend_LcdPixelGeometry() == kBGR_H_SkPixelGeometry;
    return SkSurfaceProps(0, (bgr != kSwizzle) ? kBGR_H_SkPixelGeometry : kRGB_H_SkPixelGeometry);
}

// Write locks on buffered images, counted: SKRasterSurfaceData.trackWrites wraps the surface's
// Lock so every SD_LOCK_WRITE by any pipe on any thread bumps the serial; a thread's mirror remembers
// the serial of its own last lock and stays valid while nobody else locked for writing since (and
// Java wrote nothing, which the Java side tracks). Read locks leave it alone: the mirror is written
// out after every operation, so readers always see current pixels.
static LockFunc *gOriginalLock = nullptr;
static std::atomic<uint64_t> gWriteLocks{0};
static thread_local uint64_t tLastWriteLock = 0;   // the serial this thread's last write lock produced

static jint TrackedLock(JNIEnv *env, SurfaceDataOps *ops, SurfaceDataRasInfo *rasInfo, jint lockflags) {
    if (lockflags & SD_LOCK_WRITE) tLastWriteLock = gWriteLocks.fetch_add(1, std::memory_order_relaxed) + 1;
    return gOriginalLock(env, ops, rasInfo, lockflags);
}

constexpr int kTile = 16;   // the mirror's import granularity: a small operation after an invalidation imports little more than its box

struct RasterTarget {
    SurfaceDataOps *ops = nullptr;
    void *pixels = nullptr;          // what the lock returned last time; the canvas draws there
    SkImageInfo info;
    size_t rowBytes = 0;
    std::unique_ptr<SkCanvas> canvas;
    SkBitmap mirror;                 // INT_ARGB: the premultiplied copy of the whole image the canvas draws into
    uint32_t stamp = 0;              // for the least-recently-used replacement
    std::vector<uint8_t> tiles;      // INT_ARGB: which kTile × kTile tiles of the mirror hold the image's pixels
    int tilesAcross = 0, tilesDown = 0;
    uint64_t epoch = 0;              // the write-lock serial the tiles are valid for (this thread's own last lock)

    void invalidateMirror() { std::fill(tiles.begin(), tiles.end(), 0); }
    bool mirrorCurrent(bool mirrorKept) const {
        return mirrorKept && ops != nullptr && ops->Lock == TrackedLock && epoch != 0 && gWriteLocks.load(std::memory_order_relaxed) == epoch;
    }
    // the box's tiles not yet current are imported; afterwards the box is current in the mirror
    void prepare(const SkIRect &box) {
        for (int ty = box.fTop / kTile; ty <= (box.fBottom - 1) / kTile; ty++) {
            for (int tx = box.fLeft / kTile; tx <= (box.fRight - 1) / kTile; tx++) {
                uint8_t &t = tiles[(size_t) ty * tilesAcross + tx];
                if (t) continue;
                SkIRect tile = SkIRect::MakeXYWH(tx * kTile, ty * kTile, kTile, kTile);
                if (tile.intersect(SkIRect::MakeWH(info.width(), info.height()))) mirrorIn(tile);
                t = 1;
            }
        }
    }

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
            tilesAcross = (i.width() + kTile - 1) / kTile; tilesDown = (i.height() + kTile - 1) / kTile;
            tiles.assign((size_t) tilesAcross * tilesDown, 0);
            epoch = 0;
            const SkSurfaceProps props = surfaceProps();
            canvas = SkCanvas::MakeRasterDirect(mirror.info(), mirror.getPixels(), mirror.rowBytes(), &props);
        } else {
            mirror.reset();
            const SkSurfaceProps props = surfaceProps();
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

RasterTarget *peekTarget(SurfaceDataOps *ops) {
    for (RasterTarget &t : targets) if (t.ops == ops && t.canvas) return &t;
    return nullptr;
}

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
    // kClipped: the canvas clipped to the box (a shape may reach outside it); kBox: the canvas unclipped,
    // the caller draws the box and nothing else (save, clip and restore are a third of a small
    // operation's cost); kDirect: the lock alone — the caller stores into the pixels, no canvas, no mirror
    enum Mode { kClipped, kBox, kDirect };
    JNIEnv *env; SurfaceDataOps *ops = nullptr; SurfaceDataRasInfo ras{}; RasterTarget *target = nullptr; SkIRect box; Mode mode; bool ok = false;

    Operation(JNIEnv *e, jobject sData, jint pixelFormat, jint x1, jint y1, jint x2, jint y2, Mode m = kClipped, bool mirrorKept = false) : env(e), mode(m) {
        ops = SurfaceData_GetOps(env, sData);
        if (ops == nullptr) return;
        // the image's size, from the buffered image's own ops (SKRasterSurfaceData is one)
        const SurfaceDataBounds &image = ((BufImgSDOps *) ops)->rasbounds;
        const jint width = image.x2 - image.x1, height = image.y2 - image.y1;
        ras.bounds.x1 = x1; ras.bounds.y1 = y1; ras.bounds.x2 = x2; ras.bounds.y2 = y2;
        // before this thread's own lock bumps the serial: is the thread's mirror of this surface still current?
        RasterTarget *known = mode == kDirect ? nullptr : peekTarget(ops);
        const bool keep = known != nullptr && known->mirrorCurrent(mirrorKept);
        if (ops->Lock(env, ops, &ras, SD_LOCK_WRITE) != SD_SUCCESS) { ops = nullptr; return; }
        ops->GetRasInfo(env, ops, &ras);
        if (ras.rasBase == nullptr || ras.bounds.x1 >= ras.bounds.x2 || ras.bounds.y1 >= ras.bounds.y2) return;
        box = SkIRect::MakeLTRB(ras.bounds.x1, ras.bounds.y1, ras.bounds.x2, ras.bounds.y2);
        if (mode == kDirect) { ok = true; return; }
        if (probe < 2) return;
        // rasBase points at pixel (0, 0); the info describes the whole image, so the canvas (and the
        // mirror) outlive the operation — a box-sized info rebuilt them for every call
        target = targetFor(ops, ras.rasBase, infoFor(pixelFormat, width, height), (size_t) ras.scanStride);
        if (target == nullptr) return;
        if (target->viaMirror()) {
            if (!(keep && target == known)) target->invalidateMirror();
            target->prepare(box);
        }
        if (mode == kClipped) {
            target->canvas->save();
            target->canvas->clipIRect(box);
        }
        ok = true;
    }
    /** The row of the operation's pixels at y, from the box's left edge (Java's own pixel layout). */
    uint32_t *row(int y) { return (uint32_t *) ((char *) ras.rasBase + (size_t) y * ras.scanStride) + box.fLeft; }
    SkCanvas *canvas() { return target->canvas.get(); }
    void clipSpans(const jint *spans, size_t count) {   // device coordinates: the mirror's origin is the image's
        SkRegion region;
        for (size_t i = 0; i + 3 < count; i += 4) region.op(SkIRect::MakeLTRB(spans[i], spans[i + 1], spans[i + 2], spans[i + 3]), SkRegion::kUnion_Op);
        target->canvas->clipRegion(region);
    }
    ~Operation() {
        if (ok && target != nullptr) {
            if (mode == kClipped) target->canvas->restore();
            if (target->viaMirror()) { target->mirrorOut(box); target->epoch = tLastWriteLock; }
        }
        if (ops != nullptr) { SurfaceData_InvokeRelease(env, ops, &ras); SurfaceData_InvokeUnlock(env, ops, &ras); }
    }
};

// The paint descriptor the Java side builds (SKRasterShapePipe.paintOf): d[0] kind, d[1] extra alpha,
// d[2] cycle method, d[3] linear colour space, d[4] stop count, d[5..10] the graphics transform,
// d[11..16] the paint's own transform, then the geometry and the stop fractions; colours ARGB.
enum { PAINT_COLOR = 0, PAINT_GRADIENT = 1, PAINT_LINEAR = 2, PAINT_RADIAL = 3 };

SkColor4f swizzledColor4f(jint argb) { return SkColor4f::FromColor(swizzled(argb)); }

SkMatrix matrixOf(const jfloat *m) { return SkMatrix::MakeAll(m[0], m[2], m[4], m[1], m[3], m[5], 0, 0, 1); }

SkPaint paintFor(const jfloat *d, jint dCount, const jint *colors, jint compositeRule, bool antialias = true) {
    SkPaint paint;
    paint.setAntiAlias(antialias);
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
    SkPaint paint(jint compositeRule, bool antialias) { return paintFor(d.data, (jint) d.size, colors.data, compositeRule, antialias); }
};


} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_sun_java2d_skia_SKRasterShapePipe_fillRect(JNIEnv *env, jclass clazz, jobject sData, jint pixelFormat,
                                                 jfloat x1, jfloat y1, jfloat x2, jfloat y2, jint flags, jfloatArray paintArray, jintArray colorsArray,
                                                 jint clipLeftTop, jint clipRightBottom, jintArray spansArray)
{
    const jint compositeRule = flags & 0xff; const bool antialias = (flags >> 8) & 1, mirrorKept = (flags >> 15) & 1;
    const jint clipX1 = px(clipLeftTop), clipY1 = py(clipLeftTop), clipX2 = px(clipRightBottom), clipY2 = py(clipRightBottom);
    Buffer<64, jint> spans(spansArray != nullptr ? env->GetArrayLength(spansArray) : 0);
    if (spans.size) env->GetIntArrayRegion(spansArray, 0, (jsize) spans.size, spans.data);
    PaintData pd(env, paintArray, colorsArray);
    Operation op(env, sData, pixelFormat, clipX1, clipY1, clipX2, clipY2, Operation::kClipped, mirrorKept);
    if (!op.ok) return;
    if (spans.size) op.clipSpans(spans.data, spans.size);
    if (probe < 3) return;
    op.canvas()->drawRect(SkRect::MakeLTRB(x1, y1, x2, y2), pd.paint(compositeRule, antialias));
}

JNIEXPORT void JNICALL
Java_sun_java2d_skia_SKRasterShapePipe_fillRectColor(JNIEnv *env, jclass clazz, jobject sData, jint pixelFormat,
                                                      jfloat x1, jfloat y1, jfloat x2, jfloat y2, jint flags, jint argb, jint boxLeftTop, jint boxRightBottom)
{
    const jint compositeRule = flags & 0xff; const bool antialias = (flags >> 8) & 1, mirrorKept = (flags >> 15) & 1;
    const jint boxX1 = px(boxLeftTop), boxY1 = py(boxLeftTop), boxX2 = px(boxRightBottom), boxY2 = py(boxRightBottom);
    if (probe < 1) return;   // J2D_SKIA_RASTER_PROBE=0: the Java side and the call alone
    const bool opaque = ((argb >> 24) & 0xff) == 0xff;
    // the loops' FillRect for these is a store of the colour's pixel: so is ours, into Java's own layout
    const bool store = !antialias && (compositeRule == java_awt_AlphaComposite_SRC || (opaque && compositeRule == java_awt_AlphaComposite_SRC_OVER));
    Operation op(env, sData, pixelFormat, boxX1, boxY1, boxX2, boxY2, store ? Operation::kDirect : Operation::kBox, mirrorKept);
    if (!op.ok || probe < 2) return;   // 1: the lock alone
    if (store) {
        const uint32_t pixel = pixelFormat == sun_java2d_skia_SKSurfaceData_PF_INT_ARGB_PRE ? premultiplied((uint32_t) argb) : (uint32_t) argb;
        const int count = op.box.width();
        for (int y = op.box.fTop; y < op.box.fBottom; y++) std::fill_n(op.row(y), count, pixel);
        // this thread's mirror of the surface, if it has one, gets the same pixels and stays current
        if (RasterTarget *t = peekTarget(op.ops); t != nullptr && t->viaMirror()) {
            if (!t->mirrorCurrent(mirrorKept)) t->invalidateMirror();
            else { const uint32_t mp = premultiplied((uint32_t) argb); for (int y = op.box.fTop; y < op.box.fBottom; y++) std::fill_n(t->mirror.getAddr32(op.box.fLeft, y), count, mp); }
            t->epoch = tLastWriteLock;
        }
        return;
    }
    if (probe < 3) return;
    SkPaint paint;
    paint.setAntiAlias(antialias);
    paint.setColor(swizzled(argb));
    paint.setBlendMode(SKBackend_BlendModeForRule(compositeRule));
    // the box is the rectangle's pixels inside the clip: an antialiased edge keeps its fraction inside it
    SkRect rect = antialias ? SkRect::MakeLTRB(std::max(x1, (jfloat) op.box.fLeft), std::max(y1, (jfloat) op.box.fTop), std::min(x2, (jfloat) op.box.fRight), std::min(y2, (jfloat) op.box.fBottom))
                            : SkRect::Make(op.box);
    op.canvas()->drawRect(rect, paint);
}

JNIEXPORT void JNICALL
Java_sun_java2d_skia_SKRasterShapePipe_strokePath(JNIEnv *env, jclass clazz, jobject sData, jint pixelFormat,
                                                   jbyteArray typesArray, jint typeCount, jfloatArray coordsArray, jint coordCount, jint flags,
                                                   jfloatArray xformArray, jfloatArray paintArray, jintArray colorsArray, jint clipLeftTop, jint clipRightBottom, jintArray spansArray)
{
    const jint compositeRule = flags & 0xff; const bool antialias = (flags >> 8) & 1, mirrorKept = (flags >> 15) & 1; const jint windingRule = (flags >> 9) & 1, cap = (flags >> 10) & 3, join = (flags >> 12) & 3;
    const jint clipX1 = px(clipLeftTop), clipY1 = py(clipLeftTop), clipX2 = px(clipRightBottom), clipY2 = py(clipRightBottom);
    jfloat xf[8]; env->GetFloatArrayRegion(xformArray, 0, 8, xf);   // the transform, then the pen: line width and miter limit
    const jfloat lineWidth = xf[6], miterLimit = xf[7];
    Buffer<64, jbyte> types(typeCount); Buffer<256, jfloat> coords(coordCount);
    Buffer<64, jint> spans(spansArray != nullptr ? env->GetArrayLength(spansArray) : 0);
    env->GetByteArrayRegion(typesArray, 0, typeCount, types.data);
    env->GetFloatArrayRegion(coordsArray, 0, coordCount, coords.data);
    if (spans.size) env->GetIntArrayRegion(spansArray, 0, (jsize) spans.size, spans.data);
    SkPath path = pathOf(types.data, typeCount, coords.data, windingRule);
    // BasicStroke's cap and join constants: CAP_BUTT 0, CAP_ROUND 1, CAP_SQUARE 2; JOIN_MITER 0, JOIN_ROUND 1, JOIN_BEVEL 2
    static const SkPaint::Cap caps[] = { SkPaint::kButt_Cap, SkPaint::kRound_Cap, SkPaint::kSquare_Cap };
    static const SkPaint::Join joins[] = { SkPaint::kMiter_Join, SkPaint::kRound_Join, SkPaint::kBevel_Join };
    PaintData pd(env, paintArray, colorsArray);
    SkPaint paint = pd.paint(compositeRule, antialias);
    paint.setStyle(SkPaint::kStroke_Style);
    paint.setStrokeWidth(lineWidth);
    paint.setStrokeCap(caps[std::clamp((int) cap, 0, 2)]);
    paint.setStrokeJoin(joins[std::clamp((int) join, 0, 2)]);
    paint.setStrokeMiter(miterLimit);
    Operation op(env, sData, pixelFormat, clipX1, clipY1, clipX2, clipY2, Operation::kClipped, mirrorKept);
    if (!op.ok) return;
    if (spans.size) op.clipSpans(spans.data, spans.size);
    // the geometry is stroked in user space and drawn under the transform (Java's stroke semantics)
    op.canvas()->concat(SkMatrix::MakeAll(xf[0], xf[2], xf[4], xf[1], xf[3], xf[5], 0, 0, 1));
    op.canvas()->drawPath(path, paint);
}

JNIEXPORT void JNICALL
Java_sun_java2d_skia_SKRasterShapePipe_fillPath(JNIEnv *env, jclass clazz, jobject sData, jint pixelFormat,
                                                 jbyteArray typesArray, jint typeCount, jfloatArray coordsArray, jint coordCount, jint flags,
                                                 jfloatArray paintArray, jintArray colorsArray, jint clipLeftTop, jint clipRightBottom, jintArray spansArray)
{
    const jint compositeRule = flags & 0xff; const bool antialias = (flags >> 8) & 1, mirrorKept = (flags >> 15) & 1; const jint windingRule = (flags >> 9) & 1;
    const jint clipX1 = px(clipLeftTop), clipY1 = py(clipLeftTop), clipX2 = px(clipRightBottom), clipY2 = py(clipRightBottom);
    Buffer<64, jbyte> types(typeCount); Buffer<256, jfloat> coords(coordCount);
    Buffer<64, jint> spans(spansArray != nullptr ? env->GetArrayLength(spansArray) : 0);
    env->GetByteArrayRegion(typesArray, 0, typeCount, types.data);
    env->GetFloatArrayRegion(coordsArray, 0, coordCount, coords.data);
    if (spans.size) env->GetIntArrayRegion(spansArray, 0, (jsize) spans.size, spans.data);
    SkPath path = pathOf(types.data, typeCount, coords.data, windingRule);
    PaintData pd(env, paintArray, colorsArray);
    Operation op(env, sData, pixelFormat, clipX1, clipY1, clipX2, clipY2, Operation::kClipped, mirrorKept);
    if (!op.ok) return;
    if (spans.size) op.clipSpans(spans.data, spans.size);
    op.canvas()->drawPath(path, pd.paint(compositeRule, antialias));
}

JNIEXPORT void JNICALL
Java_sun_java2d_skia_SKRasterDrawImage_drawImage(JNIEnv *env, jclass clazz, jobject dstData, jint dstPixelFormat,
                                                 jobject srcData, jint srcPixelFormat, jint srcLeftTop, jint srcRightBottom,
                                                 jfloatArray xformArray, jint ruleAndInterpolation, jint clipLeftTop, jint clipRightBottom, jintArray spansArray)
{
    const jint compositeRule = ruleAndInterpolation & 0xff, interpType = (ruleAndInterpolation >> 8) & 0xff; const bool mirrorKept = (ruleAndInterpolation >> 16) & 1;
    const jint sx1 = px(srcLeftTop), sy1 = py(srcLeftTop), sx2 = px(srcRightBottom), sy2 = py(srcRightBottom);
    const jint clipX1 = px(clipLeftTop), clipY1 = py(clipLeftTop), clipX2 = px(clipRightBottom), clipY2 = py(clipRightBottom);
    Buffer<64, jint> spans(spansArray != nullptr ? env->GetArrayLength(spansArray) : 0);
    if (spans.size) env->GetIntArrayRegion(spansArray, 0, (jsize) spans.size, spans.data);
    jfloat xf[7]; env->GetFloatArrayRegion(xformArray, 0, 7, xf);   // the transform, then the extra alpha
    const jfloat extraAlpha = xf[6];
    SurfaceDataOps *srcOps = SurfaceData_GetOps(env, srcData);   // every JNI call before the pins
    if (srcOps == nullptr) return;
    // AffineTransformOp: 1 nearest neighbour, 2 bilinear, 3 bicubic (Java's kernel is Catmull-Rom's, a = -0.5)
    SkSamplingOptions sampling = interpType == 3 ? SkSamplingOptions(SkCubicResampler::CatmullRom())
                               : interpType == 2 ? SkSamplingOptions(SkFilterMode::kLinear) : SkSamplingOptions(SkFilterMode::kNearest);
    Operation op(env, dstData, dstPixelFormat, clipX1, clipY1, clipX2, clipY2, Operation::kClipped, mirrorKept);
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
Java_sun_java2d_skia_SKRasterTextPipe_drawGlyphs(JNIEnv *env, jclass clazz, jobject dstData, jint dstPixelFormat, jlongArray contextsArray, jint countAndFlags,
                                                 jfloat originX, jfloat originY, jintArray codesArray, jlongArray imagesArray, jfloatArray positionsArray,
                                                 jint argb, jint clipLeftTop, jint clipRightBottom, jintArray spansArray)
{
    const jint numGlyphs = countAndFlags & 0xffffff; const jboolean usePositions = (countAndFlags >> 24) & 1, smoothing = (countAndFlags >> 25) & 1;
    const jint compositeRule = (countAndFlags >> 26) & 0xf; const bool mirrorKept = (countAndFlags >> 30) & 1;
    const jint clipX1 = px(clipLeftTop), clipY1 = py(clipLeftTop), clipX2 = px(clipRightBottom), clipY2 = py(clipRightBottom);
    if (numGlyphs <= 0) return;
    // every Java array read before the pin
    Buffer<8, jlong> contexts(contextsArray != nullptr ? (size_t) env->GetArrayLength(contextsArray) : 0);
    if (!contexts.size) return;
    env->GetLongArrayRegion(contextsArray, 0, (jsize) contexts.size, contexts.data);
    Buffer<256, jint> codes((size_t) numGlyphs); env->GetIntArrayRegion(codesArray, 0, numGlyphs, codes.data);
    Buffer<256, jlong> images(imagesArray != nullptr ? (size_t) numGlyphs : 0);
    if (images.size) env->GetLongArrayRegion(imagesArray, 0, numGlyphs, images.data);
    Buffer<512, jfloat> positions(usePositions && positionsArray != nullptr ? (size_t) 2 * numGlyphs : 0);
    if (positions.size) env->GetFloatArrayRegion(positionsArray, 0, (jsize) positions.size, positions.data);
    Buffer<64, jint> spans(spansArray != nullptr ? env->GetArrayLength(spansArray) : 0);
    if (spans.size) env->GetIntArrayRegion(spansArray, 0, (jsize) spans.size, spans.data);
    Operation op(env, dstData, dstPixelFormat, clipX1, clipY1, clipX2, clipY2, Operation::kClipped, mirrorKept);
    if (!op.ok) return;
    if (spans.size) op.clipSpans(spans.data, spans.size);
    SkPaint paint;
    paint.setAntiAlias(true);
    paint.setColor(swizzled(argb));   // extra alpha already in the colour (SunGraphics2D.validateColor)
    paint.setBlendMode(SKBackend_BlendModeForRule(compositeRule));
    SKBackend_DrawGlyphsOnCanvas(op.canvas(), paint, (SkColor) argb, contexts.data, (jint) contexts.size, numGlyphs, usePositions, smoothing,
                                 originX, originY, codes.data, images.size ? images.data : nullptr, positions.size ? positions.data : nullptr);
}

JNIEXPORT void JNICALL
Java_sun_java2d_skia_SKRasterSurfaceData_trackWrites(JNIEnv *env, jobject self)
{
    SurfaceDataOps *ops = SurfaceData_GetOps(env, self);
    if (ops == nullptr || ops->Lock == TrackedLock) return;
    // every buffered image shares BufImgSurfaceData's Lock; a surface with another one stays untracked (its mirror is never kept)
    LockFunc *expected = nullptr;
    static std::atomic<LockFunc *> original{nullptr};
    original.compare_exchange_strong(expected, ops->Lock);
    gOriginalLock = original.load();
    if (gOriginalLock == ops->Lock) ops->Lock = TrackedLock;
}

} // extern "C"
