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
// Skia rendering backend: contexts, surfaces, render state and primitives.
// All functions run on the Skia queue flusher thread unless noted.

#include "SKBackend.h"
#include "SKGlyphCache.h"

#include "jlong.h"
#include "fontscalerdefs.h"
#include "sun_font_StrikeCache.h"
#include "java_awt_AlphaComposite.h"
#include "sun_java2d_pipe_BufferedContext.h"
#include "sun_java2d_pipe_hw_AccelSurface.h"
#include "sun_java2d_skia_SKBlitLoops.h"
#include "sun_java2d_skia_SKSurfaceData.h"

#include "include/core/SkBitmap.h"
#include "include/core/SkBlender.h"
#include "include/core/SkData.h"
#include "include/effects/SkRuntimeEffect.h"
#include "include/core/SkColorSpace.h"
#include "include/core/SkMatrix.h"
#include "include/core/SkPathBuilder.h"
#include "include/gpu/graphite/ContextOptions.h"
#include "include/core/SkPixmap.h"
#include "include/core/SkRect.h"
#include "include/core/SkSamplingOptions.h"
#include "include/core/SkSpan.h"
#include "include/core/SkTileMode.h"
#include "include/effects/SkGradient.h"
#include "include/gpu/graphite/BackendTexture.h"
#include "include/gpu/graphite/GraphiteTypes.h"
#include "include/gpu/graphite/Image.h"
#include "include/gpu/graphite/Recording.h"
#include "include/gpu/graphite/Surface.h"
#include "include/gpu/graphite/mtl/MtlBackendContext.h"
#include "include/gpu/graphite/mtl/MtlGraphiteTypes.h"
#include "include/gpu/graphite/mtl/MtlGraphiteTypes_cpp.h"

#include <atomic>
#include <cmath>
#include <cstring>
#include <unordered_map>
#include <unordered_set>
#include <vector>

using namespace skgpu::graphite;

// ---------------------------------------------------------------------------
// Contexts
// ---------------------------------------------------------------------------

static std::unordered_map<void *, SKConfigContext *> gContexts;

// Drop references the render state holds to a context / surface about to go away.
static void forgetContext(SKConfigContext *cc);
static void forgetSurface(SKSDOps *ops, bool dispose);

SKConfigContext *SKBackend_ContextFor(SKMTLGraphicsConfigInfo *info) {
    if (info == nullptr || info->context == nil) {
        return nullptr;
    }
    auto it = gContexts.find(info);
    if (it != gContexts.end()) {
        return it->second;
    }
    id<SKMTLContextLike> mtlc = (id<SKMTLContextLike>) info->context;
    id<MTLDevice> device = [mtlc device];
    id<MTLCommandQueue> queue = [mtlc blitCommandQueue];
    if (device == nil || queue == nil) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "SKBackend_ContextFor: MTLContext has no device/queue");
        return nullptr;
    }
    // MTLLayer.blitTexture copies pTexture to the drawable on blitCommandQueue.
    // Submitting on the same queue makes Metal order the presentation blit after
    // our rendering without a CPU wait (commands on one queue execute in order).
    MtlBackendContext backend;
    backend.fDevice.retain((CFTypeRef) device);
    backend.fQueue.retain((CFTypeRef) queue);
    ContextOptions options;
    // ContextFactory::MakeMetal: see the note in SKEnvironment.mm on SkContexts::MakeGraphite.
    std::unique_ptr<Context> context = ContextFactory::MakeMetal(backend, options);
    if (!context) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "SKBackend_ContextFor: cannot create Graphite context");
        return nullptr;
    }
    auto *cc = new SKConfigContext();
    cc->info = info;
    cc->recorder = context->makeRecorder();
    cc->context = std::move(context);
    gContexts[info] = cc;
    J2dTraceLn(J2D_TRACE_INFO, "SKBackend_ContextFor: created Graphite/Metal context for config %p", info);
    return cc;
}

void SKBackend_DisposeContext(SKMTLGraphicsConfigInfo *info) {
    auto it = gContexts.find(info);
    if (it == gContexts.end()) {
        return;
    }
    SKConfigContext *cc = it->second;
    SKBackend_Submit(cc, true);
    gContexts.erase(it);
    forgetContext(cc);
    SKGlyphCache_Flush(cc->glyphCacheAA);
    SKGlyphCache_Flush(cc->glyphCacheLCD);
    SKGlyphCache_Free(cc->glyphCacheAA);
    SKGlyphCache_Free(cc->glyphCacheLCD);
    cc->recorder.reset();
    cc->context.reset();
    delete cc;
}

// ---------------------------------------------------------------------------
// Surfaces
// ---------------------------------------------------------------------------

static SKSurfaceState *stateOf(SKSDOps *ops) {
    if (ops->state == nullptr) {
        ops->state = new SKSurfaceState();
    }
    return ops->state;
}

static SKConfigContext *contextOf(SKSDOps *ops) {
    return ops->mtl != nullptr ? SKBackend_ContextFor(SKMTL_ConfigInfo(ops->mtl)) : nullptr;
}

static SKConfigContext *currentContext();   // the destination's context (render state, below)

sk_sp<SkSurface> SKBackend_GetSurface(SKSDOps *ops) {
    if (ops == nullptr || ops->mtl == nullptr || ops->mtl->pTexture == nullptr) {
        return nullptr;
    }
    SKSurfaceState *st = stateOf(ops);
    if (st->surface && st->wrappedTexture == ops->mtl->pTexture) {
        return st->surface;
    }
    SKConfigContext *cc = contextOf(ops);
    if (cc == nullptr) {
        return nullptr;
    }
    id<MTLTexture> tex = (id<MTLTexture>) ops->mtl->pTexture;
    BackendTexture bt = BackendTextures::MakeMetal(SkISize::Make((int) tex.width, (int) tex.height), (CFTypeRef) tex);
    st->surface = SkSurfaces::WrapBackendTexture(cc->recorder.get(), bt,
                                                 kBGRA_8888_SkColorType, nullptr, nullptr);
    if (!st->surface) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "SKBackend_GetSurface: cannot wrap MTLTexture %p (%lux%lu)",
                      tex, (unsigned long) tex.width, (unsigned long) tex.height);
        return nullptr;
    }
    st->wrappedTexture = ops->mtl->pTexture;
    st->cc = cc;
    return st->surface;
}

// An image view of the surface's texture (no copy) for drawing on the current
// destination. Valid until the texture is replaced. Graphite images belong to
// the recorder that wraps them, so the view is made with the drawing context's
// recorder, not the source's.
sk_sp<SkImage> SKBackend_GetImage(SKSDOps *ops) {
    if (ops == nullptr || ops->mtl == nullptr || ops->mtl->pTexture == nullptr) {
        return nullptr;
    }
    SKConfigContext *cc = currentContext() != nullptr ? currentContext() : contextOf(ops);
    if (cc == nullptr) {
        return nullptr;
    }
    id<MTLTexture> tex = (id<MTLTexture>) ops->mtl->pTexture;
    BackendTexture bt = BackendTextures::MakeMetal(SkISize::Make((int) tex.width, (int) tex.height), (CFTypeRef) tex);
    return SkImages::WrapTexture(cc->recorder.get(), bt,
                                 ops->isOpaque ? kOpaque_SkAlphaType : kPremul_SkAlphaType,
                                 nullptr, skgpu::Origin::kTopLeft);
}

void SKBackend_ReleaseSurface(SKSDOps *ops, bool dispose) {
    if (ops == nullptr || ops->state == nullptr) {
        return;
    }
    if (ops->state->cc != nullptr) {
        // make sure pending draws into this surface reach the GPU first
        SKBackend_Submit(ops->state->cc, false);
    }
    ops->state->surface.reset();
    ops->state->wrappedTexture = nullptr;
    forgetSurface(ops, dispose);
    if (dispose) {
        delete ops->state;
        ops->state = nullptr;
    }
}

// ---------------------------------------------------------------------------
// Render state
// ---------------------------------------------------------------------------

struct RenderState {
    SKConfigContext *cc = nullptr;
    SKSDOps *dst = nullptr;
    SkCanvas *canvas = nullptr;
    int baseSaveCount = 1;

    // paint
    SkColor color = SK_ColorBLACK;
    sk_sp<SkShader> shader;
    float extraAlpha = 1.0f;
    SkBlendMode blend = SkBlendMode::kSrcOver;
    bool xorMode = false;

    // clip (device space)
    bool hasRectClip = false;
    SkIRect rectClip = SkIRect::MakeEmpty();
    bool hasShapeClip = false;
    bool buildingShapeClip = false;
    SkPath shapeClip;
    SkPathBuilder shapeClipBuilder;

    // transform
    bool hasMatrix = false;
    SkMatrix matrix = SkMatrix::I();

    std::unordered_set<SKSDOps *> touchedWindows;
    std::unordered_set<SKConfigContext *> touchedContexts;
};

static RenderState g;

static SKConfigContext *currentContext() {
    return g.cc;
}

static void forgetContext(SKConfigContext *cc) {
    g.touchedContexts.erase(cc);
    if (g.cc == cc) {
        g.cc = nullptr;
        g.canvas = nullptr;
    }
}

static void forgetSurface(SKSDOps *ops, bool dispose) {
    if (g.dst == ops) {
        g.dst = nullptr;
        g.canvas = nullptr;
    }
    if (dispose) {
        g.touchedWindows.erase(ops);
    }
}
static bool gXorWarned = false;

static void flushGlyphBatches();

static void applyCanvasState() {
    if (g.canvas == nullptr) {
        return;
    }
    flushGlyphBatches();
    g.canvas->restoreToCount(g.baseSaveCount);
    g.canvas->save();
    // Java2D clips are in device space: apply before the transform.
    if (g.hasRectClip) {
        g.canvas->clipIRect(g.rectClip);
    }
    if (g.hasShapeClip) {
        g.canvas->clipPath(g.shapeClip, SkClipOp::kIntersect, false);
    }
    g.canvas->setMatrix(g.hasMatrix ? SkM44(g.matrix) : SkM44());
}

static void markTouched() {
    if (g.cc != nullptr) {
        g.cc->dirty = true;
        g.touchedContexts.insert(g.cc);
    }
    if (g.dst != nullptr && g.dst->type == sun_java2d_pipe_hw_AccelSurface_WINDOW) {
        g.touchedWindows.insert(g.dst);
    }
}

// Graphite does not draw raster images directly; everything is uploaded per
// draw. Texture from pixels the caller owns only for the duration of the call (a
// locked Java raster, an opcode buffer, a glyph image): Graphite copies them
// into its upload buffer while recording (verified with a standalone Graphite
// probe), so the pixmap is wrapped, not copied first.
static sk_sp<SkImage> uploadPixmap(const SkPixmap &pm) {
    if (g.cc == nullptr) {
        return SkImages::RasterFromPixmapCopy(pm);
    }
    sk_sp<SkImage> raster = SkImages::RasterFromPixmap(pm, nullptr, nullptr);
    return raster ? SkImages::TextureFromImage(g.cc->recorder.get(), raster.get()) : nullptr;
}

void SKBackend_SetDestination(SKSDOps *dst) {
    flushGlyphBatches();
    g.dst = dst;
    g.canvas = nullptr;
    if (dst == nullptr) {
        return;
    }
    g.cc = contextOf(dst);
    sk_sp<SkSurface> surface = SKBackend_GetSurface(dst);
    if (!surface) {
        g.dst = nullptr;
        return;
    }
    g.canvas = surface->getCanvas();
    // The canvas keeps the save()/clip applyCanvasState() left behind the last
    // time this surface was the destination; drop it before taking the base
    // count, or the stale clip would stick to this surface forever.
    g.canvas->restoreToCount(1);
    g.baseSaveCount = g.canvas->getSaveCount();
    applyCanvasState();
    if (dst->type == sun_java2d_pipe_hw_AccelSurface_WINDOW) {
        J2dTraceLn(J2D_TRACE_VERBOSE, "SKBackend_SetDestination: window ops=%p tex=%p layer=%p",
                   dst, dst->mtl->pTexture,
                   SKMTL_PrivOps(dst->mtl) != nullptr ? (void *) SKMTL_PrivOps(dst->mtl)->layer : nullptr);
    }
}

void SKBackend_SetScratch(SKMTLGraphicsConfigInfo *info) {
    g.cc = SKBackend_ContextFor(info);
    g.dst = nullptr;
    g.canvas = nullptr;
}

void SKBackend_InvalidateState() {
    g.dst = nullptr;
    g.canvas = nullptr;
}

SKSDOps *SKBackend_CurrentDestination() { return g.dst; }
SkCanvas *SKBackend_CurrentCanvas() { return g.canvas; }

// --- clip

void SKBackend_SetRectClip(jint x1, jint y1, jint x2, jint y2) {
    g.hasRectClip = true;
    g.rectClip = SkIRect::MakeLTRB(x1, y1, x2, y2);
    g.hasShapeClip = false;
    applyCanvasState();
}

void SKBackend_BeginShapeClip() {
    g.hasRectClip = false;   // replaces, not intersects, the rect clip (as MTLContext.beginShapeClip)
    g.buildingShapeClip = true;
    g.shapeClipBuilder.reset();
    g.shapeClipBuilder.setFillType(SkPathFillType::kWinding);
}

void SKBackend_AddShapeClipSpans(jint count, const jint *spans) {
    for (jint i = 0; i < count; i++) {
        jint x1 = spans[4 * i], y1 = spans[4 * i + 1], x2 = spans[4 * i + 2], y2 = spans[4 * i + 3];
        g.shapeClipBuilder.addRect(SkRect::MakeLTRB(x1, y1, x2, y2));
    }
}

void SKBackend_EndShapeClip() {
    g.buildingShapeClip = false;
    g.shapeClip = g.shapeClipBuilder.detach();
    g.hasShapeClip = true;
    applyCanvasState();
}

void SKBackend_ResetClip() {
    g.hasRectClip = false;
    g.hasShapeClip = false;
    g.buildingShapeClip = false;
    applyCanvasState();
}

// --- composite

static SkBlendMode blendModeForRule(jint rule) {
    switch (rule) {
        case java_awt_AlphaComposite_CLEAR:    return SkBlendMode::kClear;
        case java_awt_AlphaComposite_SRC:      return SkBlendMode::kSrc;
        case java_awt_AlphaComposite_SRC_OVER: return SkBlendMode::kSrcOver;
        case java_awt_AlphaComposite_DST_OVER: return SkBlendMode::kDstOver;
        case java_awt_AlphaComposite_SRC_IN:   return SkBlendMode::kSrcIn;
        case java_awt_AlphaComposite_DST_IN:   return SkBlendMode::kDstIn;
        case java_awt_AlphaComposite_SRC_OUT:  return SkBlendMode::kSrcOut;
        case java_awt_AlphaComposite_DST_OUT:  return SkBlendMode::kDstOut;
        case java_awt_AlphaComposite_DST:      return SkBlendMode::kDst;
        case java_awt_AlphaComposite_SRC_ATOP: return SkBlendMode::kSrcATop;
        case java_awt_AlphaComposite_DST_ATOP: return SkBlendMode::kDstATop;
        case java_awt_AlphaComposite_XOR:      return SkBlendMode::kXor;
        default:                               return SkBlendMode::kSrcOver;
    }
}

void SKBackend_SetAlphaComposite(jint rule, jfloat extraAlpha, jint flags) {
    g.blend = blendModeForRule(rule);
    g.extraAlpha = extraAlpha;
    g.xorMode = false;
}

void SKBackend_SetXorComposite(jint xorPixel) {
    // Bitwise XOR has no Skia equivalent; the Java side keeps XOR on the
    // software pipes, so this is only reached for ops routed here anyway.
    g.xorMode = true;
    if (!gXorWarned) {
        gXorWarned = true;
        J2dRlsTraceLn(J2D_TRACE_WARNING, "Skia backend: XOR composite is not supported, drawing with Src");
    }
}

void SKBackend_ResetComposite() {
    g.blend = SkBlendMode::kSrcOver;
    g.extraAlpha = 1.0f;
    g.xorMode = false;
}

// --- transform

void SKBackend_SetTransform(jdouble m00, jdouble m10, jdouble m01, jdouble m11, jdouble m02, jdouble m12) {
    g.hasMatrix = true;
    g.matrix = SkMatrix::MakeAll((SkScalar) m00, (SkScalar) m01, (SkScalar) m02,
                                 (SkScalar) m10, (SkScalar) m11, (SkScalar) m12,
                                 0, 0, 1);
    applyCanvasState();
}

void SKBackend_ResetTransform() {
    g.hasMatrix = false;
    g.matrix = SkMatrix::I();
    applyCanvasState();
}

// --- paint

// IntArgbPre -> IntArgb exactly as AlphaMath.c's div8table does it.
static inline uint32_t unpremulJava(uint32_t pre) {
    uint32_t a = pre >> 24;
    if (a == 0 || a == 255) {
        return pre;
    }
    const uint32_t inc = ((255u << 24) + a / 2) / a;
    auto div = [&](uint32_t c) -> uint32_t { return c >= a ? 255u : (((1u << 23) + c * inc) >> 24); };
    return (a << 24) | (div((pre >> 16) & 0xff) << 16) | (div((pre >> 8) & 0xff) << 8) | div(pre & 0xff);
}

// Java2D hands us premultiplied IntArgbPre pixels; Skia wants straight SkColor.
static SkColor unpremul(jint pixel) {
    if ((((unsigned) pixel) >> 24) == 0) {
        return SK_ColorTRANSPARENT;
    }
    return (SkColor) unpremulJava((uint32_t) pixel);
}

// Java's paint contexts and the Metal shaders (position - 0.5) evaluate a paint
// at the pixel's integer coordinates; Skia samples shaders at pixel centres.
// A half-pixel local translation makes Skia evaluate t(x, y) instead of
// t(x + 0.5, y + 0.5): visible as shifted seams in REPEAT gradients and as a
// small bias in every gradient and texture otherwise.
static sk_sp<SkShader> atPixelOrigin(sk_sp<SkShader> shader) {
    return shader ? shader->makeWithLocalMatrix(SkMatrix::Translate(0.5f, 0.5f)) : nullptr;
}

// Gradient stop pixels arrive as IntArgbPre (BufferedPaints); Skia wants straight colours.
static SkColor4f argbPreToColor4f(jint argbPre) {
    return SkColor4f::FromColor(unpremul(argbPre));
}

// Java (and the Metal shaders) interpolate the premultiplied stop pixels; with
// straight interpolation a transparent stop would fade through its colour.
static SkGradient::Interpolation premulInterpolation(bool linear = false) {
    SkGradient::Interpolation interp;
    interp.fInPremul = SkGradient::Interpolation::InPremul::kYes;
    if (linear) {
        // ColorSpaceType.LINEAR_RGB: Java interpolates in linear RGB and encodes the
        // result; the stops arrive linear (stopColorSpace), so interpolate there too.
        interp.fColorSpace = SkGradient::Interpolation::ColorSpace::kSRGBLinear;
    }
    return interp;
}

// With ColorSpaceType.LINEAR_RGB the stops are already linear; tell Skia so it
// interpolates in linear space and encodes back to the destination's sRGB.
static sk_sp<SkColorSpace> stopColorSpace(jboolean linear) {
    return linear ? SkColorSpace::MakeSRGBLinear() : nullptr;
}

static SkTileMode tileModeFor(jint cycleMethod) {
    switch (cycleMethod) {
        case 1:  return SkTileMode::kMirror;   // REFLECT
        case 2:  return SkTileMode::kRepeat;   // REPEAT
        default: return SkTileMode::kClamp;    // NO_CYCLE
    }
}

void SKBackend_SetColor(jint pixel) {
    g.color = unpremul(pixel);
    g.shader.reset();
}

void SKBackend_ResetPaint() {
    g.shader.reset();
}

// Java encodes the gradient axis as t = p0*x + p1*y + p3 (device space); recover
// the two points where t == t0 and t == t1 on the axis through the origin's projection.
static bool axisPoints(double p0, double p1, double p3, double t0, double t1, SkPoint pts[2]) {
    double len2 = p0 * p0 + p1 * p1;
    if (len2 <= 0) {
        return false;
    }
    double vx = p0 / len2, vy = p1 / len2;        // the point where t == 1, relative to t == 0
    double ox = -p3 * vx, oy = -p3 * vy;          // a point with t == 0
    pts[0] = SkPoint::Make((SkScalar) (ox + t0 * vx), (SkScalar) (oy + t0 * vy));
    pts[1] = SkPoint::Make((SkScalar) (ox + t1 * vx), (SkScalar) (oy + t1 * vy));
    return true;
}

void SKBackend_SetGradientPaint(jboolean cyclic, jdouble p0, jdouble p1, jdouble p3, jint pixel1, jint pixel2) {
    SkPoint pts[2];
    // BufferedPaints.setGradientPaint places point1 at t == 0.25 and point2 at t == 0.75.
    if (!axisPoints(p0, p1, p3, 0.25, 0.75, pts)) {
        g.shader.reset();
        return;
    }
    SkColor4f colors[2] = { argbPreToColor4f(pixel1), argbPreToColor4f(pixel2) };
    SkGradient grad(SkGradient::Colors(SkSpan(colors, 2), cyclic ? SkTileMode::kMirror : SkTileMode::kClamp),
                    premulInterpolation());
    g.shader = atPixelOrigin(SkShaders::LinearGradient(pts, grad));
}

void SKBackend_SetLinearGradientPaint(jboolean linear, jint cycleMethod, jint numStops,
                                        jfloat p0, jfloat p1, jfloat p3,
                                        const jfloat *fractions, const jint *pixels) {
    SkPoint pts[2];
    if (!axisPoints(p0, p1, p3, 0.0, 1.0, pts) || numStops <= 0) {
        g.shader.reset();
        return;
    }
    std::vector<SkColor4f> colors(numStops);
    for (int i = 0; i < numStops; i++) {
        colors[i] = argbPreToColor4f(pixels[i]);
    }
    SkGradient grad(SkGradient::Colors(SkSpan(colors.data(), numStops), SkSpan(fractions, numStops),
                                       tileModeFor(cycleMethod), stopColorSpace(linear)),
                    premulInterpolation(linear));
    g.shader = atPixelOrigin(SkShaders::LinearGradient(pts, grad));
}

void SKBackend_SetRadialGradientPaint(jboolean linear, jint cycleMethod, jint numStops,
                                        jfloat m00, jfloat m01, jfloat m02, jfloat m10, jfloat m11, jfloat m12,
                                        jfloat focusX, const jfloat *fractions, const jint *pixels) {
    if (numStops <= 0) {
        g.shader.reset();
        return;
    }
    // m* maps device space to the unit circle (center (0,0), radius 1, focus (focusX, 0)).
    SkMatrix toUnit = SkMatrix::MakeAll(m00, m01, m02, m10, m11, m12, 0, 0, 1);
    SkMatrix local;
    if (!toUnit.invert(&local)) {
        g.shader.reset();
        return;
    }
    std::vector<SkColor4f> colors(numStops);
    for (int i = 0; i < numStops; i++) {
        colors[i] = argbPreToColor4f(pixels[i]);
    }
    SkGradient grad(SkGradient::Colors(SkSpan(colors.data(), numStops), SkSpan(fractions, numStops),
                                       tileModeFor(cycleMethod), stopColorSpace(linear)),
                    premulInterpolation(linear));
    g.shader = atPixelOrigin(SkShaders::TwoPointConicalGradient(SkPoint::Make(focusX, 0), 0.0f,
                                                                SkPoint::Make(0, 0), 1.0f, grad, &local));
}

// Uploads a software surface's pixels to a texture of the current context.
static sk_sp<SkImage> imageFromSoftwareSurface(JNIEnv *env, SurfaceDataOps *ops, jint srctype,
                                               jint sx1, jint sy1, jint sx2, jint sy2) {
    if (ops == nullptr || ops->Lock == nullptr) {
        return nullptr;
    }
    if (SKSurfaceData_IsSkiaOps(ops)) {
        // a Skia surface routed through a software loop: sample it, do not lock it
        J2dRlsTraceLn(J2D_TRACE_WARNING, "imageFromSoftwareSurface: source %p is a Skia surface", ops);
        return SKBackend_GetImage((SKSDOps *) ops);
    }
    SurfaceDataRasInfo ri;
    memset(&ri, 0, sizeof(ri));
    ri.bounds.x1 = sx1; ri.bounds.y1 = sy1; ri.bounds.x2 = sx2; ri.bounds.y2 = sy2;
    if (ops->Lock(env, ops, &ri, SD_LOCK_READ) != SD_SUCCESS) {
        return nullptr;
    }
    sk_sp<SkImage> image;
    ops->GetRasInfo(env, ops, &ri);
    if (ri.rasBase != nullptr && ri.bounds.x2 > ri.bounds.x1 && ri.bounds.y2 > ri.bounds.y1) {
        SkColorType ct = kBGRA_8888_SkColorType;
        SkAlphaType at = kPremul_SkAlphaType;
        switch (srctype) {
            case sun_java2d_skia_SKSurfaceData_PF_INT_ARGB:     ct = kBGRA_8888_SkColorType; at = kUnpremul_SkAlphaType; break;
            case sun_java2d_skia_SKSurfaceData_PF_INT_ARGB_PRE: ct = kBGRA_8888_SkColorType; at = kPremul_SkAlphaType;   break;
            case sun_java2d_skia_SKSurfaceData_PF_INT_RGB:
            case sun_java2d_skia_SKSurfaceData_PF_INT_RGBX:     ct = kBGRA_8888_SkColorType; at = kOpaque_SkAlphaType;   break;
            case sun_java2d_skia_SKSurfaceData_PF_INT_BGR:
            case sun_java2d_skia_SKSurfaceData_PF_INT_BGRX:     ct = kRGBA_8888_SkColorType; at = kOpaque_SkAlphaType;   break;
            default: break;
        }
        int w = ri.bounds.x2 - ri.bounds.x1, h = ri.bounds.y2 - ri.bounds.y1;
        const char *base = (const char *) ri.rasBase + (long) ri.bounds.y1 * ri.scanStride
                           + (long) ri.bounds.x1 * ri.pixelStride;
        if (at == kOpaque_SkAlphaType) {
            // IntRgb & co. leave the alpha byte undefined; set it instead of
            // relying on Graphite's opaque-image swizzle. The buffer is reused
            // across blits (flusher thread only).
            static std::vector<uint32_t> opaque;
            opaque.resize((size_t) w * h);
            for (int row = 0; row < h; row++) {
                const uint32_t *srcRow = (const uint32_t *) (base + (long) row * ri.scanStride);
                uint32_t *dstRow = opaque.data() + (size_t) row * w;
                for (int col = 0; col < w; col++) {
                    dstRow[col] = srcRow[col] | 0xff000000u;
                }
            }
            image = uploadPixmap(SkPixmap(SkImageInfo::Make(w, h, ct, kPremul_SkAlphaType), opaque.data(), (size_t) w * 4));
        } else {
            image = uploadPixmap(SkPixmap(SkImageInfo::Make(w, h, ct, at), base, (size_t) ri.scanStride));
        }
    }
    SurfaceData_InvokeRelease(env, ops, &ri);
    SurfaceData_InvokeUnlock(env, ops, &ri);
    return image;
}

// Texture paint: Java encodes device -> image-normalized coordinates as
// u = xp0*x + xp1*y + xp3, v = yp0*x + yp1*y + yp3; the shader's local matrix
// maps device space to image pixels.
static void setTexturePaintShader(sk_sp<SkImage> image, jboolean filter,
                                  jdouble xp0, jdouble xp1, jdouble xp3, jdouble yp0, jdouble yp1, jdouble yp3) {
    // (u, v) = (xp0*x + xp1*y + xp3, yp0*x + yp1*y + yp3) in [0,1]: device -> image.
    SkMatrix toImage = SkMatrix::MakeAll((SkScalar)(xp0 * image->width()),  (SkScalar)(xp1 * image->width()),  (SkScalar)(xp3 * image->width()),
                                         (SkScalar)(yp0 * image->height()), (SkScalar)(yp1 * image->height()), (SkScalar)(yp3 * image->height()),
                                         0, 0, 1);
    SkMatrix local;
    if (!toImage.invert(&local)) {
        g.shader.reset();
        return;
    }
    SkSamplingOptions sampling = filter ? SkSamplingOptions(SkFilterMode::kLinear) : SkSamplingOptions(SkFilterMode::kNearest);
    g.shader = atPixelOrigin(image->makeShader(SkTileMode::kRepeat, SkTileMode::kRepeat, sampling, &local));
}

void SKBackend_SetTexturePaint(JNIEnv *env, jboolean filter, jlong pSrc,
                                 jdouble xp0, jdouble xp1, jdouble xp3, jdouble yp0, jdouble yp1, jdouble yp3) {
    // SKPaints accepts a TexturePaint only once the proxy has cached its image
    // as a Skia texture, so the source is always one (as in the Metal pipeline).
    SurfaceDataOps *src = (SurfaceDataOps *) jlong_to_ptr(pSrc);
    if (!SKSurfaceData_IsSkiaOps(src)) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "SKBackend_SetTexturePaint: source %p is not a Skia texture", src);
        g.shader.reset();
        return;
    }
    SKSDOps *skSrc = (SKSDOps *) src;
    if (skSrc->state != nullptr && skSrc->state->cc != nullptr && skSrc->state->cc != g.cc) {
        // another display's context and queue: its work must be complete
        SKBackend_Submit(skSrc->state->cc, true);
    }
    sk_sp<SkImage> image = SKBackend_GetImage(skSrc);
    if (!image) {
        g.shader.reset();
        return;
    }
    setTexturePaintShader(std::move(image), filter, xp0, xp1, xp3, yp0, yp1, yp3);
}

SkPaint SKBackend_MakePaint(bool aa) {
    SkPaint p;
    p.setAntiAlias(aa);
    p.setBlendMode(g.xorMode ? SkBlendMode::kSrc : g.blend);
    if (g.shader) {
        p.setShader(g.shader);
        p.setColor4f(SkColor4f{1, 1, 1, g.extraAlpha});
    } else {
        // SunGraphics2D.validateColor() already applied the composite's extra
        // alpha to the color pixel; do not apply it twice.
        p.setColor(g.color);
    }
    return p;
}

// ---------------------------------------------------------------------------
// Primitives
// ---------------------------------------------------------------------------

// Pending glyph batches of the current context are drawn before anything else
// touches the canvas, so draw order is preserved.
static void flushGlyphBatches() {
    if (g.cc != nullptr) {
        SKGlyphCache_Flush(g.cc->glyphCacheAA);
        SKGlyphCache_Flush(g.cc->glyphCacheLCD);
    }
}

#define REQUIRE_CANVAS() if (g.canvas == nullptr) { return; } markTouched(); flushGlyphBatches()
#define REQUIRE_CANVAS_TEXT() if (g.canvas == nullptr) { return; } markTouched()

void SKBackend_FillRect(jint x, jint y, jint w, jint h) {
    REQUIRE_CANVAS();
    if (w <= 0 || h <= 0) return;
    SkPaint p = SKBackend_MakePaint(false);
    g.canvas->drawIRect(SkIRect::MakeXYWH(x, y, w, h), p);
}

void SKBackend_DrawRect(jint x, jint y, jint w, jint h) {
    REQUIRE_CANVAS();
    if (w < 0 || h < 0) return;
    SkPaint p = SKBackend_MakePaint(false);
    if (w < 2 || h < 2) {
        // Java draws a (w+1)x(h+1) outline; degenerate outlines are solid.
        g.canvas->drawIRect(SkIRect::MakeXYWH(x, y, w + 1, h + 1), p);
        return;
    }
    g.canvas->drawIRect(SkIRect::MakeXYWH(x, y, w + 1, 1), p);           // top
    g.canvas->drawIRect(SkIRect::MakeXYWH(x, y + h, w + 1, 1), p);       // bottom
    g.canvas->drawIRect(SkIRect::MakeXYWH(x, y + 1, 1, h - 1), p);       // left
    g.canvas->drawIRect(SkIRect::MakeXYWH(x + w, y + 1, 1, h - 1), p);   // right
}

// Java2D's 1-pixel lines: the Bresenham stepping of the software loops
// (SETUP_BRESENHAM in DrawLine.c and the DRAWLINE loop), both endpoints
// inclusive unless `shorten` drops the pixel at (x2, y2) as DrawPolygons.c does
// for the inner joints of a polyline. The pixels are emitted as horizontal or
// vertical runs, one rect per run, so nothing depends on Skia's stroking.
static void bresenhamLine(SkCanvas *canvas, const SkPaint &p, jint x1, jint y1, jint x2, jint y2, bool shorten = false) {
    const jint dx = x2 - x1, dy = y2 - y1;
    const jint ax = std::abs(dx), ay = std::abs(dy);
    if (dy == 0 || dx == 0) {
        // SETORDERED: the shortened end is always the second point's
        jint a = dy == 0 ? x1 : y1, b = dy == 0 ? x2 : y2, lo, hi;
        if (a < b) { lo = a; hi = b - (shorten ? 1 : 0); } else { lo = b + (shorten ? 1 : 0); hi = a; }
        if (lo > hi) return;
        if (dy == 0) canvas->drawIRect(SkIRect::MakeLTRB(lo, y1, hi + 1, y1 + 1), p);
        else         canvas->drawIRect(SkIRect::MakeLTRB(x1, lo, x1 + 1, hi + 1), p);
        return;
    }
    const bool xMajor = ax >= ay;
    const jint errMajor = xMajor ? 2 * ay : 2 * ax;
    jint errMinor = xMajor ? 2 * ax : 2 * ay;
    jint steps = (xMajor ? ax : ay) + 1 - (shorten ? 1 : 0);
    if (steps <= 0) return;
    jint error = -(errMinor / 2) + errMajor;
    errMinor -= errMajor;
    const jint sx = dx < 0 ? -1 : 1, sy = dy < 0 ? -1 : 1;
    jint x = x1, y = y1, runStartX = x, runStartY = y;
    while (true) {
        const bool last = --steps == 0;
        const bool diagonal = error >= 0;     // the step after this pixel also advances the minor axis
        if (last || diagonal) {
            if (xMajor) canvas->drawIRect(SkIRect::MakeLTRB(std::min(runStartX, x), y, std::max(runStartX, x) + 1, y + 1), p);
            else        canvas->drawIRect(SkIRect::MakeLTRB(x, std::min(runStartY, y), x + 1, std::max(runStartY, y) + 1), p);
        }
        if (last) break;
        if (diagonal) { x += sx; y += sy; error -= errMinor; }
        else if (xMajor) { x += sx; error += errMajor; }
        else             { y += sy; error += errMajor; }
        if (diagonal) { runStartX = x; runStartY = y; }
    }
}

void SKBackend_DrawLine(jint x1, jint y1, jint x2, jint y2) {
    REQUIRE_CANVAS();
    SkPaint p = SKBackend_MakePaint(false);
    bresenhamLine(g.canvas, p, x1, y1, x2, y2);
}

void SKBackend_DrawPixel(jint x, jint y) {
    REQUIRE_CANVAS();
    SkPaint p = SKBackend_MakePaint(false);
    g.canvas->drawIRect(SkIRect::MakeXYWH(x, y, 1, 1), p);
}

void SKBackend_DrawScanlines(jint count, const jint *scanlines) {
    REQUIRE_CANVAS();
    SkPaint p = SKBackend_MakePaint(false);
    for (jint i = 0; i < count; i++) {
        jint x1 = scanlines[3 * i], x2 = scanlines[3 * i + 1], y = scanlines[3 * i + 2];
        g.canvas->drawIRect(SkIRect::MakeLTRB(x1, y, x2 + 1, y + 1), p);
    }
}

void SKBackend_FillSpans(jint count, const jint *spans) {
    REQUIRE_CANVAS();
    SkPaint p = SKBackend_MakePaint(false);
    for (jint i = 0; i < count; i++) {
        g.canvas->drawIRect(SkIRect::MakeLTRB(spans[4 * i], spans[4 * i + 1], spans[4 * i + 2], spans[4 * i + 3]), p);
    }
}

void SKBackend_DrawPoly(jint nPoints, jboolean isClosed, jint transX, jint transY,
                          const jint *xPoints, const jint *yPoints) {
    REQUIRE_CANVAS();
    if (nPoints < 2) {
        return;   // a single point draws nothing (DrawPolygons.c)
    }
    SkPaint p = SKBackend_MakePaint(false);
    // As DrawPolygons.c: every segment but the last of an open polyline drops
    // its end pixel (the next segment draws it); a closed polygon shortens all
    // of them and closes back to the first point, a degenerate one is a pixel.
    const jint x0 = xPoints[0] + transX, y0 = yPoints[0] + transY;
    jint px = x0, py = y0;
    bool empty = true;
    for (jint i = 1; i < nPoints; i++) {
        const jint x = xPoints[i] + transX, y = yPoints[i] + transY;
        empty = empty && px == x && py == y;
        bresenhamLine(g.canvas, p, px, py, x, y, i < nPoints - 1 || isClosed);
        px = x; py = y;
    }
    if (isClosed && (empty || px != x0 || py != y0)) {
        bresenhamLine(g.canvas, p, px, py, x0, y0, !empty);
    }
}

static void addParallelogram(SkPathBuilder &pb, float x, float y, float dx21, float dy21, float dx12, float dy12) {
    pb.moveTo(x, y);
    pb.lineTo(x + dx21, y + dy21);
    pb.lineTo(x + dx21 + dx12, y + dy21 + dy12);
    pb.lineTo(x + dx12, y + dy12);
    pb.close();
}

void SKBackend_FillParallelogram(bool aa, jfloat x11, jfloat y11, jfloat dx21, jfloat dy21, jfloat dx12, jfloat dy12) {
    REQUIRE_CANVAS();
    SkPathBuilder pb;
    addParallelogram(pb, x11, y11, dx21, dy21, dx12, dy12);
    g.canvas->drawPath(pb.detach(), SKBackend_MakePaint(aa));
}

void SKBackend_DrawParallelogram(bool aa, jfloat x11, jfloat y11, jfloat dx21, jfloat dy21, jfloat dx12, jfloat dy12,
                                   jfloat lwr21, jfloat lwr12) {
    REQUIRE_CANVAS();
    // The given parallelogram is the stroke centerline (see MTLRenderer_DrawParallelogram):
    // grow it by half the line width for the outer edge, shrink it for the inner one.
    float ldx21 = dx21 * lwr21, ldy21 = dy21 * lwr21;
    float ldx12 = dx12 * lwr12, ldy12 = dy12 * lwr12;
    float ox11 = x11 - (ldx21 + ldx12) / 2.0f, oy11 = y11 - (ldy21 + ldy12) / 2.0f;
    SkPathBuilder pb;
    pb.setFillType(SkPathFillType::kEvenOdd);
    addParallelogram(pb, ox11, oy11, dx21 + ldx21, dy21 + ldy21, dx12 + ldx12, dy12 + ldy12);
    if (lwr21 < 1.0f && lwr12 < 1.0f) {
        // the interior still has a hole
        addParallelogram(pb, x11 + (ldx21 + ldx12) / 2.0f, y11 + (ldy21 + ldy12) / 2.0f,
                         dx21 - ldx21, dy21 - ldy21, dx12 - ldx12, dy12 - ldy12);
    }
    g.canvas->drawPath(pb.detach(), SKBackend_MakePaint(aa));
}

void SKBackend_MaskFill(jint x, jint y, jint w, jint h, jint maskoff, jint maskscan, jint masklen,
                          const unsigned char *mask) {
    REQUIRE_CANVAS();
    if (w <= 0 || h <= 0) return;
    SkPaint p = SKBackend_MakePaint(false);
    if (mask == nullptr || masklen == 0) {
        g.canvas->drawIRect(SkIRect::MakeXYWH(x, y, w, h), p);
        return;
    }
    // Marlin's coverage tile as an alpha-only image; the paint supplies color/shader.
    SkPixmap pm(SkImageInfo::MakeA8(w, h), mask + maskoff, (size_t) maskscan);
    sk_sp<SkImage> cov = uploadPixmap(pm);
    if (cov) {
        g.canvas->drawImage(cov, (SkScalar) x, (SkScalar) y, SkSamplingOptions(SkFilterMode::kNearest), &p);
    }
}

void SKBackend_MaskBlit(jint dstx, jint dsty, jint width, jint height, const void *pixels) {
    REQUIRE_CANVAS();
    if (width <= 0 || height <= 0) return;
    SkPixmap pm(SkImageInfo::Make(width, height, kBGRA_8888_SkColorType, kPremul_SkAlphaType),
                pixels, (size_t) width * 4);
    sk_sp<SkImage> img = uploadPixmap(pm);
    if (!img) return;
    SkPaint p;
    p.setBlendMode(g.xorMode ? SkBlendMode::kSrc : g.blend);
    p.setAlphaf(g.extraAlpha);
    g.canvas->drawImage(img, (SkScalar) dstx, (SkScalar) dsty, SkSamplingOptions(SkFilterMode::kNearest), &p);
}

// Glyph images come from Java's font rasterizer (sun.font); Skia only composites them.
// LCD text, after MTLTextRenderer's lcd_color shader: per-channel coverage blends
// the gamma-adjusted text colour into the gamma-adjusted destination,
//   Cr = (Ag * Cs^g + (1 - Ag) * Cd^g) ^ (1/g),   g = contrast / 100.
static sk_sp<SkBlender> lcdBlender(jint contrast) {
    static const SkRuntimeEffect *effect = [] {
        auto result = SkRuntimeEffect::MakeForBlender(SkString(R"(
            uniform half3 srcAdj;
            uniform half3 gamma;
            uniform half3 invGamma;
            half4 main(half4 src, half4 dst) {
                half3 cov = src.rgb;
                if (cov.r == 0 && cov.g == 0 && cov.b == 0) {
                    return dst;
                }
                half3 d = pow(dst.rgb, gamma);
                half3 r = mix(d, srcAdj, cov);
                return half4(pow(r, invGamma), 1);
            }
        )"));
        if (!result.effect) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "SKBackend: LCD blender does not compile: %s", result.errorText.c_str());
        }
        return result.effect.release();
    }();
    if (effect == nullptr) {
        return nullptr;
    }
    static jint lastContrast = -1;
    static SkColor lastColor = 0;
    static sk_sp<SkBlender> last;
    if (!last || contrast != lastContrast || g.color != lastColor) {
        float gamma = contrast / 100.0f, inv = 1.0f / gamma;
        SkColor4f c = SkColor4f::FromColor(g.color);
        float uniforms[9] = { powf(c.fR, gamma), powf(c.fG, gamma), powf(c.fB, gamma),
                              gamma, gamma, gamma, inv, inv, inv };
        last = effect->makeBlender(SkData::MakeWithCopy(uniforms, sizeof(uniforms)));
        lastContrast = contrast;
        lastColor = g.color;
    }
    return last;
}

static SKGlyphCache *glyphCache(bool lcd) {
    SKGlyphCache *&slot = lcd ? g.cc->glyphCacheLCD : g.cc->glyphCacheAA;
    if (slot == nullptr) {
        slot = SKGlyphCache_Create(g.cc, lcd);
    }
    return slot;
}

// Glyphs too large for a cache cell, or LCD glyphs shifted by a third of a
// pixel, are uploaded and drawn individually (MTLTextRenderer's NoCache paths).
static void drawGlyphNoCache(const GlyphInfo *ginfo, jint subimage, float x, float y, const SkPaint &paint) {
    const unsigned char *data = ginfo->image + (size_t) ginfo->rowBytes * ginfo->height * subimage;
    SkPixmap pm(SkImageInfo::MakeA8(ginfo->width, ginfo->height), data, ginfo->rowBytes);
    if (sk_sp<SkImage> cov = uploadPixmap(pm)) {
        g.canvas->drawImage(cov, x, y, SkSamplingOptions(SkFilterMode::kNearest), &paint);
    }
}

static void drawLcdGlyphNoCache(const GlyphInfo *ginfo, jint rowBytesOffset, jboolean rgbOrder,
                                float x, float y, const SkPaint &paint) {
    const int w = ginfo->width, h = ginfo->height;
    std::vector<unsigned char> rgba((size_t) w * h * 4);
    for (int row = 0; row < h; row++) {
        const unsigned char *src = ginfo->image + (size_t) row * ginfo->rowBytes + rowBytesOffset;
        unsigned char *dst = rgba.data() + (size_t) row * w * 4;
        for (int col = 0; col < w; col++, src += 3, dst += 4) {
            dst[0] = rgbOrder ? src[0] : src[2];
            dst[1] = src[1];
            dst[2] = rgbOrder ? src[2] : src[0];
            dst[3] = 0xff;
        }
    }
    SkPixmap pm(SkImageInfo::Make(w, h, kRGBA_8888_SkColorType, kOpaque_SkAlphaType), rgba.data(), (size_t) w * 4);
    if (sk_sp<SkImage> cov = uploadPixmap(pm)) {
        g.canvas->drawImage(cov, x, y, SkSamplingOptions(SkFilterMode::kNearest), &paint);
    }
}

static void drawColorGlyph(const GlyphInfo *ginfo, float x, float y) {
    SkPixmap pm(SkImageInfo::Make(ginfo->width, ginfo->height, kBGRA_8888_SkColorType, kPremul_SkAlphaType),
                ginfo->image, ginfo->rowBytes);
    if (sk_sp<SkImage> img = uploadPixmap(pm)) {
        SkPaint p;
        p.setBlendMode(g.xorMode ? SkBlendMode::kSrc : g.blend);
        p.setAlphaf(g.extraAlpha);
        g.canvas->drawImage(img, x, y, SkSamplingOptions(SkFilterMode::kNearest), &p);
    }
}

// Sub-pixel strikes render rx*ry variants of each glyph; pick the one for the
// fractional position (MTLTextRenderer's ADJUST_SUBPIXEL_GLYPH_POSITION).
static inline float adjustSubpixel(float coord, int res) {
    return res > 1 ? coord + 0.5f / (float) res - 0.5f : coord;
}

void SKBackend_DrawGlyphList(jint numGlyphs, jboolean usePositions, jboolean subPixPos,
                             jboolean rgbOrder, jint lcdContrast,
                             jfloat glyphListOrigX, jfloat glyphListOrigY,
                             const jlong *images, const jfloat *positions) {
    REQUIRE_CANVAS_TEXT();
    SkPaint paint = SKBackend_MakePaint(false);
    SKGlyphCache *aa = nullptr, *lcd = nullptr;
    SkPaint lcdPaint;
    bool lcdPaintReady = false;

    for (jint i = 0; i < numGlyphs; i++) {
        const GlyphInfo *ginfo = (const GlyphInfo *) jlong_to_ptr(images[i]);
        if (ginfo == nullptr || ginfo == (const GlyphInfo *) -1) {
            break;
        }
        float gx, gy;
        if (usePositions) {
            gx = glyphListOrigX + positions[2 * i] + ginfo->topLeftX;
            gy = glyphListOrigY + positions[2 * i + 1] + ginfo->topLeftY;
        } else {
            gx = glyphListOrigX + ginfo->topLeftX;
            gy = glyphListOrigY + ginfo->topLeftY;
            glyphListOrigX += ginfo->advanceX;
            glyphListOrigY += ginfo->advanceY;
        }
        int rx = ginfo->subpixelResolutionX, ry = ginfo->subpixelResolutionY;
        gx = adjustSubpixel(gx, rx);
        gy = adjustSubpixel(gy, ry);
        float fx = floorf(gx), fy = floorf(gy);
        jint subimage = 0;
        if (!((rx == 1 && ry == 1) || rx <= 0 || ry <= 0)) {
            subimage = (int) ((gx - fx) * (float) rx) + (int) ((gy - fy) * (float) ry) * rx;
        }
        if (ginfo->image == nullptr || ginfo->width == 0 || ginfo->height == 0) {
            continue;
        }

        // Glyphs of one list are drawn in order: an immediate draw (uncached
        // glyph, colour glyph) or a switch between the AA and LCD caches first
        // draws whatever is batched.
        auto flushBatches = [&] {
            flushGlyphBatches();
            aa = nullptr;
            lcd = nullptr;
        };
        if (ginfo->format == sun_font_StrikeCache_PIXEL_FORMAT_GREYSCALE) {
            if (lcd != nullptr) {
                flushBatches();
            }
            if (aa == nullptr && (aa = glyphCache(false)) != nullptr) {
                SKGlyphCache_Begin(aa, g.canvas, paint);
            }
            if (SKGlyphCache_Fits(aa, ginfo) && SKGlyphCache_Draw(aa, (GlyphInfo *) ginfo, subimage, false, fx, fy)) {
                continue;
            }
            flushBatches();
            drawGlyphNoCache(ginfo, subimage, fx, fy, paint);
        } else if (ginfo->format == sun_font_StrikeCache_PIXEL_FORMAT_BGRA) {
            flushBatches();
            drawColorGlyph(ginfo, fx, fy);
        } else {
            if (aa != nullptr) {
                flushBatches();
            }
            // LCD: the three coverage channels are blended per channel against
            // the destination, in the destination's gamma.
            if (!lcdPaintReady) {
                lcdPaint.setColor(SK_ColorWHITE);
                lcdPaint.setBlender(lcdBlender(lcdContrast));
                lcdPaintReady = true;
            }
            jint rowBytesOffset = 0;
            if (subPixPos) {
                jint frac = (jint) ((gx - fx) * 3);
                if (frac != 0) {
                    rowBytesOffset = 3 - frac;
                    fx += 1;
                }
            }
            if (lcd == nullptr && (lcd = glyphCache(true)) != nullptr) {
                SKGlyphCache_Begin(lcd, g.canvas, lcdPaint);
            }
            if (rowBytesOffset == 0 && SKGlyphCache_Fits(lcd, ginfo)
                    && SKGlyphCache_Draw(lcd, (GlyphInfo *) ginfo, 0, rgbOrder, fx, fy)) {
                continue;
            }
            flushBatches();
            drawLcdGlyphNoCache(ginfo, rowBytesOffset, rgbOrder, fx, fy, lcdPaint);
        }
    }
    // batches stay open for the next glyph list; see flushGlyphBatches()
}

static SkSamplingOptions samplingForHint(jint hint) {
    // java.awt.image.AffineTransformOp: 1 = NEAREST_NEIGHBOR, 2 = BILINEAR, 3 = BICUBIC
    return hint == 1 ? SkSamplingOptions(SkFilterMode::kNearest) : SkSamplingOptions(SkFilterMode::kLinear);
}

// A software source is uploaded per blit. Upload only what can be visible (the
// destination rect clipped to the clip, when the canvas matrix is identity) and
// in pieces no larger than the texture limit: a 20000 px wide BufferedImage is
// legal in Java and must not fail or upload whole.
static void blitSoftwareSource(JNIEnv *env, SurfaceDataOps *src, jint srctype, jint hint,
                               jint sx1, jint sy1, jint sx2, jint sy2, SkRect dst) {
    if (sx2 <= sx1 || sy2 <= sy1 || dst.isEmpty()) {
        return;
    }
    const double scaleX = (sx2 - sx1) / (double) dst.width(), scaleY = (sy2 - sy1) / (double) dst.height();
    if (!g.hasMatrix) {
        SkRect clip = g.hasRectClip ? SkRect::Make(g.rectClip)
                                    : SkRect::MakeWH(g.canvas->imageInfo().width(), g.canvas->imageInfo().height());
        SkRect vis = dst;
        if (!vis.intersect(clip)) {
            return;
        }
        // source texels covering the visible part, one texel of margin for filtering
        auto toInt = [](double v) { return (jint) std::clamp(v, -1.0e9, 1.0e9); };   // extreme scales stay in range
        jint nx1 = std::max(sx1, toInt(std::floor(sx1 + (vis.left() - dst.left()) * scaleX)) - 1);
        jint ny1 = std::max(sy1, toInt(std::floor(sy1 + (vis.top() - dst.top()) * scaleY)) - 1);
        jint nx2 = std::min(sx2, toInt(std::ceil(sx1 + (vis.right() - dst.left()) * scaleX)) + 1);
        jint ny2 = std::min(sy2, toInt(std::ceil(sy1 + (vis.bottom() - dst.top()) * scaleY)) + 1);
        dst = SkRect::MakeLTRB((SkScalar) (dst.left() + (nx1 - sx1) / scaleX), (SkScalar) (dst.top() + (ny1 - sy1) / scaleY),
                               (SkScalar) (dst.left() + (nx2 - sx1) / scaleX), (SkScalar) (dst.top() + (ny2 - sy1) / scaleY));
        sx1 = nx1; sy1 = ny1; sx2 = nx2; sy2 = ny2;
        if (sx2 <= sx1 || sy2 <= sy1) {
            return;
        }
    }
    SkPaint p;
    p.setBlendMode(g.xorMode ? SkBlendMode::kSrc : g.blend);
    p.setAlphaf(g.extraAlpha);
    const jint maxTex = g.cc != nullptr && g.cc->recorder ? g.cc->recorder->maxTextureSize() : 4096;
    for (jint ty = sy1; ty < sy2; ty += maxTex) {
        jint th = std::min(maxTex, sy2 - ty);
        for (jint tx = sx1; tx < sx2; tx += maxTex) {
            jint tw = std::min(maxTex, sx2 - tx);
            sk_sp<SkImage> image = imageFromSoftwareSurface(env, src, srctype, tx, ty, tx + tw, ty + th);
            if (!image) {
                continue;
            }
            SkRect tileDst = SkRect::MakeLTRB((SkScalar) (dst.left() + (tx - sx1) / scaleX), (SkScalar) (dst.top() + (ty - sy1) / scaleY),
                                              (SkScalar) (dst.left() + (tx + tw - sx1) / scaleX), (SkScalar) (dst.top() + (ty + th - sy1) / scaleY));
            g.canvas->drawImageRect(image, SkRect::MakeWH(tw, th), tileDst, samplingForHint(hint), &p,
                                    SkCanvas::kStrict_SrcRectConstraint);
        }
    }
}

void SKBackend_Blit(JNIEnv *env, jint packedParams, jint sx1, jint sy1, jint sx2, jint sy2,
                      jdouble dx1, jdouble dy1, jdouble dx2, jdouble dy2, jlong pSrc, jlong pDst) {
    REQUIRE_CANVAS();
    jboolean isoblit = (packedParams >> sun_java2d_skia_SKBlitLoops_OFFSET_ISOBLIT) & 1;
    jint hint        = (packedParams >> sun_java2d_skia_SKBlitLoops_OFFSET_HINT) & 0xff;
    jint srctype     = (packedParams >> sun_java2d_skia_SKBlitLoops_OFFSET_SRCTYPE) & 0xff;

    sk_sp<SkImage> image;
    SkRect srcRect;
    if (isoblit) {
        SKSDOps *src = (SKSDOps *) jlong_to_ptr(pSrc);
        if (src != nullptr && src->state != nullptr && src->state->cc != nullptr && src->state->cc != g.cc) {
            // another display's context and queue: its work must be complete
            SKBackend_Submit(src->state->cc, true);
        }
        if (src != nullptr && src->state != nullptr && src->state->surface && src->state->cc == g.cc
                && src->mtl != nullptr && src->state->wrappedTexture == src->mtl->pTexture) {
            // An image view linked to the source's device: Graphite flushes the
            // source's pending draws before this recording samples it. A bare
            // WrapTexture of the same MTLTexture would not carry that dependency.
            image = SkSurfaces::AsImage(src->state->surface);
        } else {
            image = SKBackend_GetImage(src);
        }
        srcRect = SkRect::MakeLTRB(sx1, sy1, sx2, sy2);
    } else {
        blitSoftwareSource(env, (SurfaceDataOps *) jlong_to_ptr(pSrc), srctype, hint, sx1, sy1, sx2, sy2,
                           SkRect::MakeLTRB((SkScalar) dx1, (SkScalar) dy1, (SkScalar) dx2, (SkScalar) dy2));
        return;
    }
    if (!image) {
        return;
    }
    SkRect dstRect = SkRect::MakeLTRB((SkScalar) dx1, (SkScalar) dy1, (SkScalar) dx2, (SkScalar) dy2);
    SkPaint p;
    p.setBlendMode(g.xorMode ? SkBlendMode::kSrc : g.blend);
    p.setAlphaf(g.extraAlpha);
    // Strict: Java's transformed and scaled blits never sample outside the source
    // rect (edge texels extend), Skia's default would sample transparency there.
    g.canvas->drawImageRect(image, srcRect, dstRect, samplingForHint(hint), &p,
                            SkCanvas::kStrict_SrcRectConstraint);
}

// Graphite delivers the readback callback from checkAsyncWorkCompletion() in the
// normal case, but a failed command buffer stays "unfinished" and the callback
// then arrives from a later submit(). The request is therefore heap-allocated,
// owned jointly by the caller and the callback, and holds its own pixel copy.
struct ReadbackRequest {
    std::atomic<int> refs{2};
    std::vector<char> pixels;    // w * h * 4, tightly packed
    int w = 0, h = 0;
    bool done = false;           // callback ran (ok or failed)
    bool ok = false;

    void release() {
        if (--refs == 0) {
            delete this;
        }
    }
};

static void readbackCallback(void *ctx, std::unique_ptr<const SkImage::AsyncReadResult> result) {
    auto *r = (ReadbackRequest *) ctx;
    if (result) {
        const char *src = (const char *) result->data(0);
        size_t srcRowBytes = result->rowBytes(0);
        for (int row = 0; row < r->h; row++) {
            memcpy(r->pixels.data() + (size_t) row * r->w * 4, src + row * srcRowBytes, (size_t) r->w * 4);
        }
        r->ok = true;
    }
    r->done = true;
    r->release();
}

void SKBackend_SurfaceToSwBlit(JNIEnv *env, jint sx, jint sy, jint dx, jint dy, jint w, jint h,
                                 jint dsttype, jlong pSrc, jlong pDst) {
    SKSDOps *src = (SKSDOps *) jlong_to_ptr(pSrc);
    SurfaceDataOps *dst = (SurfaceDataOps *) jlong_to_ptr(pDst);
    if (src == nullptr || dst == nullptr || w <= 0 || h <= 0) {
        return;
    }
    sk_sp<SkSurface> surface = SKBackend_GetSurface(src);
    SKConfigContext *cc = src->state != nullptr ? src->state->cc : nullptr;
    if (!surface || cc == nullptr) {
        return;
    }
    SurfaceDataRasInfo ri;
    memset(&ri, 0, sizeof(ri));
    ri.bounds.x1 = dx; ri.bounds.y1 = dy; ri.bounds.x2 = dx + w; ri.bounds.y2 = dy + h;
    if (dst->Lock(env, dst, &ri, SD_LOCK_WRITE) != SD_SUCCESS) {
        return;
    }
    dst->GetRasInfo(env, dst, &ri);
    if (ri.rasBase != nullptr) {
        int rw = ri.bounds.x2 - ri.bounds.x1, rh = ri.bounds.y2 - ri.bounds.y1;
        if (rw > 0 && rh > 0) {
            // The destination lock clipped to the raster; clip the source rect to the
            // surface as well (the Metal loop does the same), reading back a rect that
            // leaves the texture is a GPU error.
            SkIRect srcRect = SkIRect::MakeXYWH(sx + (ri.bounds.x1 - dx), sy + (ri.bounds.y1 - dy), rw, rh);
            SkIRect clipped = srcRect;
            if (clipped.intersect(SkIRect::MakeWH(surface->width(), surface->height()))) {
                char *dstBase = (char *) ri.rasBase + (long) ri.bounds.y1 * ri.scanStride + (long) ri.bounds.x1 * ri.pixelStride
                                + (long) (clipped.y() - srcRect.y()) * ri.scanStride
                                + (long) (clipped.x() - srcRect.x()) * ri.pixelStride;
                // Read the premultiplied bytes as they are and unpremultiply like the
                // Java loops (div8table) do, so results equal the software pipeline's.
                SkImageInfo info = SkImageInfo::Make(clipped.width(), clipped.height(), kBGRA_8888_SkColorType, kPremul_SkAlphaType);
                auto *req = new ReadbackRequest();
                req->w = clipped.width();
                req->h = clipped.height();
                req->pixels.resize((size_t) req->w * req->h * 4);
                SKBackend_FlushRecording(cc);
                cc->context->asyncRescaleAndReadPixels(surface.get(), info, clipped,
                                                       SkImage::RescaleGamma::kSrc, SkImage::RescaleMode::kNearest,
                                                       readbackCallback, req);
                cc->context->submit(SyncToCpu::kYes);
                cc->context->checkAsyncWorkCompletion();
                cc->dirty = false;
                if (req->done && req->ok) {
                    const bool unpremul = dsttype == sun_java2d_skia_SKSurfaceData_PF_INT_ARGB;
                    for (int row = 0; row < req->h; row++) {
                        uint32_t *dstRow = (uint32_t *) (dstBase + (long) row * ri.scanStride);
                        const uint32_t *srcRow = (const uint32_t *) (req->pixels.data() + (size_t) row * req->w * 4);
                        if (!unpremul) {
                            memcpy(dstRow, srcRow, (size_t) req->w * 4);
                            continue;
                        }
                        for (int col = 0; col < req->w; col++) {
                            dstRow[col] = unpremulJava(srcRow[col]);
                        }
                    }
                } else {
                    J2dRlsTraceLn(J2D_TRACE_ERROR, "SKBackend_SurfaceToSwBlit: readback %s",
                                  req->done ? "failed" : "did not complete");
                }
                req->release();
            }
        }
    }
    SurfaceData_InvokeRelease(env, dst, &ri);
    SurfaceData_InvokeUnlock(env, dst, &ri);
}

void SKBackend_CopyArea(jint x, jint y, jint w, jint h, jint dx, jint dy) {
    REQUIRE_CANVAS();
    if (w <= 0 || h <= 0) return;
    sk_sp<SkSurface> surface = SKBackend_GetSurface(g.dst);
    if (!surface) return;
    // Source and destination overlap in general: copy the region out first.
    SkIRect subset = SkIRect::MakeXYWH(x, y, w, h);
    if (!subset.intersect(SkIRect::MakeWH(surface->width(), surface->height()))) return;
    sk_sp<SkImage> copy = SkSurfaces::AsImageCopy(surface, &subset);
    if (!copy) return;
    SkPaint p;
    p.setBlendMode(SkBlendMode::kSrc);
    g.canvas->drawImage(copy, (SkScalar) (subset.x() + dx), (SkScalar) (subset.y() + dy),
                        SkSamplingOptions(SkFilterMode::kNearest), &p);
}

// ---------------------------------------------------------------------------
// Submission / presentation
// ---------------------------------------------------------------------------

void SKBackend_FlushRecording(SKConfigContext *cc) {
    if (cc == nullptr || !cc->recorder) {
        return;
    }
    SKGlyphCache_Flush(cc->glyphCacheAA);
    SKGlyphCache_Flush(cc->glyphCacheLCD);
    std::unique_ptr<Recording> recording = cc->recorder->snap();
    if (recording) {
        InsertRecordingInfo info;
        info.fRecording = recording.get();
        if (!cc->context->insertRecording(info)) {
            J2dRlsTraceLn(J2D_TRACE_WARNING, "SKBackend_FlushRecording: insertRecording failed");
        }
    }
}

void SKBackend_Submit(SKConfigContext *cc, bool syncToCpu) {
    if (cc == nullptr || !cc->context) {
        return;
    }
    SKBackend_FlushRecording(cc);
    cc->context->submit(syncToCpu ? SyncToCpu::kYes : SyncToCpu::kNo);
    cc->dirty = false;
}

void SKBackend_EndOfFlush(bool syncToCpu) {
    for (SKConfigContext *cc : g.touchedContexts) {
        SKBackend_Submit(cc, syncToCpu);
    }
    g.touchedContexts.clear();
    // Ask the Metal layers of the windows we drew into to present the texture.
    for (SKSDOps *dst : g.touchedWindows) {
        if (dst->mtl != nullptr) {
            SKMTLSDOps *priv = SKMTL_PrivOps(dst->mtl);
            if (priv != nullptr && priv->layer != nil) {
                J2dTraceLn(J2D_TRACE_VERBOSE, "SKBackend_EndOfFlush: startRedraw layer=%p", (void *) priv->layer);
                [(id<SKMTLLayerLike>) priv->layer startRedraw];
            } else {
                J2dRlsTraceLn(J2D_TRACE_WARNING, "SKBackend_EndOfFlush: window surface %p has no layer", dst);
            }
        }
    }
    g.touchedWindows.clear();
}
