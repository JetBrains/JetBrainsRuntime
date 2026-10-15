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
#include "SKMaskCache.h"
#include "SKQuadBatch.h"

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
#include "include/core/SkRRect.h"
#include "include/core/SkPathUtils.h"
#include "include/core/SkSpan.h"
#include "include/core/SkTileMode.h"
#include "include/core/SkVertices.h"
#include "include/effects/SkGradient.h"
#include "include/gpu/graphite/BackendTexture.h"
#include "include/gpu/graphite/GraphiteTypes.h"
#include "include/gpu/graphite/Image.h"
#include "include/gpu/graphite/Recording.h"
#include "include/gpu/graphite/Surface.h"

#include <atomic>
#include <cmath>
#include <cstring>
#include <unordered_map>
#include <unordered_set>
#include <array>
#include <cfloat>
#include <vector>

using namespace skgpu::graphite;

// ---------------------------------------------------------------------------
// Contexts
// ---------------------------------------------------------------------------

static std::unordered_map<void *, SKConfigContext *> gContexts;

// Drop references the render state holds to a context / surface about to go away.
static void forgetContext(SKConfigContext *cc);
static void forgetSurface(SKSDOps *ops);
static void flushAllBatches();

// Architecture-dependent configuration, see SKEnvironment.Configuration. With
// MSAA a pass multisamples every draw in it, so non-AA geometry that is not
// pixel aligned (rotated parallelograms, fractional or rotated blits) is
// decomposed into integer spans by the pixel-centre rule instead of relying on
// the rasterizer.
static struct {
    jint msaaSamples = 1;
    bool edgeAAQuads = false;
} gConfig;

void SKBackend_Configure(jint msaaSamples, bool edgeAAQuads) {
    gConfig.msaaSamples = msaaSamples;
    gConfig.edgeAAQuads = edgeAAQuads;
}

bool SKBackend_Multisampled() {
    return gConfig.msaaSamples > 1;
}

bool SKBackend_EdgeAAQuads() {
    return gConfig.edgeAAQuads;
}

static SampleCount sampleCount(jint samples) {
    switch (samples) {
        case 2:  return SampleCount::k2;
        case 4:  return SampleCount::k4;
        case 8:  return SampleCount::k8;
        default: return SampleCount::k1;
    }
}

SKConfigContext *SKBackend_ContextFor(SKPlatformConfig *config) {
    if (config == nullptr) {
        return nullptr;
    }
    auto it = gContexts.find(config);
    if (it != gContexts.end()) {
        return it->second;
    }
    ContextOptions options;
    // Default: no multisampled passes. Graphite renders a whole pass with MSAA
    // as soon as one draw in it needs it, which quantizes every antialiased
    // edge to the sample count; anti-aliased paths use the path atlas instead,
    // with full coverage precision like the Metal pipeline's shaders. Non-AA
    // geometry stays exact either way (span decomposition under MSAA).
    options.fInternalMultisampleCount = sampleCount(gConfig.msaaSamples);
    std::unique_ptr<Context> context = SKPlatform_MakeContext(config, options);
    if (!context) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "SKBackend_ContextFor: cannot create Graphite context");
        return nullptr;
    }
    auto *cc = new SKConfigContext();
    cc->config = config;
    cc->recorder = context->makeRecorder();
    cc->context = std::move(context);
    gContexts[config] = cc;
    J2dTraceLn(J2D_TRACE_INFO, "SKBackend_ContextFor: created Graphite context for config %p", config);
    return cc;
}

void SKBackend_DisposeContext(SKPlatformConfig *config) {
    auto it = gContexts.find(config);
    if (it == gContexts.end()) {
        return;
    }
    SKConfigContext *cc = it->second;
    J2dTraceLn(J2D_TRACE_VERBOSE, "SKBackend_DisposeContext: config=%p cc=%p surfaces=%zu", config, cc, cc->surfaces.size());
    SKBackend_Submit(cc, true);
    gContexts.erase(it);
    forgetContext(cc);
    // Surfaces wrapped with this recorder cannot be drawn to any more; they are
    // re-wrapped on their next use (the platform texture is still there). Submit
    // has drawn every pending batch.
    for (SKSurfaceState *st : cc->surfaces) {
        st->surface.reset();
        st->wrappedTexture = nullptr;
        st->cc = nullptr;
    }
    cc->surfaces.clear();
    SKMaskCache_Free(cc->maskCache);
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
    return ops->platform != nullptr ? SKBackend_ContextFor(SKPlatform_ConfigOf(ops->platform)) : nullptr;
}

static SKConfigContext *currentContext();   // the destination's context (render state, below)

sk_sp<SkSurface> SKBackend_GetSurface(SKSDOps *ops) {
    void *texture = ops != nullptr && ops->platform != nullptr ? SKPlatform_TextureHandle(ops->platform) : nullptr;
    if (texture == nullptr) {
        return nullptr;
    }
    SKSurfaceState *st = stateOf(ops);
    if (st->surface && st->wrappedTexture == texture) {
        return st->surface;
    }
    SKConfigContext *cc = contextOf(ops);
    if (cc == nullptr) {
        return nullptr;
    }
    if (st->surface) {
        // the texture was replaced: pending batches may still target the old
        // canvas, and so may the render state if this is the destination
        J2dTraceLn(J2D_TRACE_VERBOSE, "SKBackend_GetSurface: re-wrapping ops=%p (texture %p -> %p)",
                      ops, st->wrappedTexture, texture);
        flushAllBatches();
        forgetSurface(ops);
        st->surface.reset();
    }
    BackendTexture bt;
    SkColorType colorType;
    if (!SKPlatform_BackendTexture(ops->platform, &bt, &colorType)) {
        return nullptr;
    }
    st->surface = SkSurfaces::WrapBackendTexture(cc->recorder.get(), bt, colorType, nullptr, nullptr);
    if (!st->surface) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "SKBackend_GetSurface: cannot wrap texture %p (%dx%d)",
                      texture, bt.dimensions().width(), bt.dimensions().height());
        return nullptr;
    }
    st->wrappedTexture = texture;
    st->cc = cc;
    cc->surfaces.insert(st);
    return st->surface;
}

// An image view of the surface's texture (no copy) for drawing on the current
// destination. Valid until the texture is replaced. Graphite images belong to
// the recorder that wraps them, so the view is made with the drawing context's
// recorder, not the source's.
sk_sp<SkImage> SKBackend_GetImage(SKSDOps *ops) {
    if (ops == nullptr || ops->platform == nullptr || SKPlatform_TextureHandle(ops->platform) == nullptr) {
        return nullptr;
    }
    SKConfigContext *cc = currentContext() != nullptr ? currentContext() : contextOf(ops);
    if (cc == nullptr) {
        return nullptr;
    }
    BackendTexture bt;
    SkColorType colorType;
    if (!SKPlatform_BackendTexture(ops->platform, &bt, &colorType)) {
        return nullptr;
    }
    return SkImages::WrapTexture(cc->recorder.get(), bt,
                                 ops->isOpaque ? kOpaque_SkAlphaType : kPremul_SkAlphaType,
                                 nullptr, skgpu::Origin::kTopLeft);
}

void SKBackend_ReleaseSurface(SKSDOps *ops, bool dispose) {
    if (ops == nullptr || ops->state == nullptr) {
        return;
    }
    J2dTraceLn(J2D_TRACE_VERBOSE, "SKBackend_ReleaseSurface: ops=%p dispose=%d surface=%p", ops, dispose,
                  (void *) ops->state->surface.get());
    flushAllBatches();   // batches may refer to this surface's canvas
    if (ops->state->cc != nullptr) {
        // make sure pending draws into this surface reach the GPU first; no CPU
        // wait is needed before the platform frees the texture, since Graphite
        // retains the wrapped texture object for as long as a command buffer
        // that uses it is tracked
        SKBackend_Submit(ops->state->cc, false);
        ops->state->cc->surfaces.erase(ops->state);
    }
    ops->state->surface.reset();
    ops->state->wrappedTexture = nullptr;
    ops->state->cc = nullptr;
    forgetSurface(ops);
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
    jint xorPixel = 0;

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

static void forgetSurface(SKSDOps *ops) {
    if (g.dst == ops) {
        g.dst = nullptr;
        g.canvas = nullptr;
    }
    g.touchedWindows.erase(ops);   // a released window surface is not presented at the end of the flush
}

static void applyCanvasState() {
    if (g.canvas == nullptr) {
        return;
    }
    flushAllBatches();
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
        g.touchedContexts.insert(g.cc);
    }
    if (g.dst != nullptr && g.dst->type == sun_java2d_pipe_hw_AccelSurface_WINDOW) {
        g.touchedWindows.insert(g.dst);
    }
}

// Graphite does not draw raster images directly; images drawn repeatedly
// arrive as textures already (SKSurfaceDataProxy), everything else is uploaded
// per draw. Texture from pixels the caller owns only for the duration of the call (a
// locked Java raster, an opcode buffer, a glyph image): Graphite copies them
// into its upload buffer while recording (UploadInstance::Make copies the
// pixels), so the pixmap is wrapped, not copied first.
static sk_sp<SkImage> uploadPixmap(const SkPixmap &pm) {
    if (g.cc == nullptr) {
        return SkImages::RasterFromPixmapCopy(pm);
    }
    sk_sp<SkImage> raster = SkImages::RasterFromPixmap(pm, nullptr, nullptr);
    return raster ? SkImages::TextureFromImage(g.cc->recorder.get(), raster.get()) : nullptr;
}

void SKBackend_SetDestination(SKSDOps *dst) {
    flushAllBatches();
    g.dst = nullptr;
    g.canvas = nullptr;
    if (dst == nullptr) {
        return;
    }
    g.cc = contextOf(dst);
    // GetSurface may re-wrap a replaced texture and forget the previous
    // destination on the way, so the destination is taken after it
    sk_sp<SkSurface> surface = SKBackend_GetSurface(dst);
    if (!surface) {
        return;
    }
    g.dst = dst;
    g.canvas = surface->getCanvas();
    // The canvas keeps the save()/clip applyCanvasState() left behind the last
    // time this surface was the destination; drop it before taking the base
    // count, or the stale clip would stick to this surface forever.
    g.canvas->restoreToCount(1);
    g.baseSaveCount = g.canvas->getSaveCount();
    applyCanvasState();
    if (dst->type == sun_java2d_pipe_hw_AccelSurface_WINDOW) {
        J2dTraceLn(J2D_TRACE_VERBOSE, "SKBackend_SetDestination: window ops=%p tex=%p",
                   dst, SKPlatform_TextureHandle(dst->platform));
    }
}

void SKBackend_SetScratch(SKPlatformConfig *config) {
    flushAllBatches();
    // only an existing context: the scratch surface precedes disposals, which
    // must not create a context for a configuration that never rendered
    auto it = gContexts.find(config);
    g.cc = it != gContexts.end() ? it->second : nullptr;
    g.dst = nullptr;
    g.canvas = nullptr;
}

void SKBackend_InvalidateState() {
    g.dst = nullptr;
    g.canvas = nullptr;
}

SKSDOps *SKBackend_CurrentDestination() { return g.dst; }

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
    g.xorMode = true;
    g.xorPixel = xorPixel;
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

// REPEAT: Java evaluates t in float and the boundary pixel of a cycle usually
// lands on the integer and wraps to the first stop; Skia's evaluation order can
// leave it an ulp below and show the last stop instead. A bias of 1e-5 in t
// (a 20000th of a cycle) makes the boundary pixel wrap the same way.
static const float REPEAT_SEAM_BIAS = 1e-5f;

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
    if (cycleMethod == 2) {
        const SkVector d = pts[1] - pts[0];
        pts[0] -= d * REPEAT_SEAM_BIAS;
        pts[1] -= d * REPEAT_SEAM_BIAS;
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
    const float radius = cycleMethod == 2 ? 1.0f / (1.0f + REPEAT_SEAM_BIAS) : 1.0f;
    g.shader = atPixelOrigin(SkShaders::TwoPointConicalGradient(SkPoint::Make(focusX, 0), 0.0f,
                                                                SkPoint::Make(0, 0), radius, grad, &local));
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
            if (opaque.capacity() > (size_t) 4096 * 4096) {
                std::vector<uint32_t>().swap(opaque);   // one oversized blit must not pin its buffer
            }
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
    SKPlatformConfig *srcConfig = SKPlatform_ConfigOf(skSrc->platform);
    if (g.cc != nullptr && srcConfig != g.cc->config && !SKPlatform_SameDevice(srcConfig, g.cc->config)) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "SKBackend_SetTexturePaint: source %p is on another device, skipped", src);
        g.shader.reset();
        return;
    }
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

// Java2D's XOR mode, as the Metal pipeline implements it (frag_*_xorMode):
// for colour paints dst' = dst ^ ((color ^ xorPixel) * coverage), coverage being
// the source alpha (1 for fills, the glyph/mask value for text and shapes);
// for image sources dst' = dst ^ pixel ^ xorPixel. 8-bit channel arithmetic.
// Runtime blenders are restricted to ES2 SkSL, which has no integer bit
// operations, so the XOR is done bit by bit.
static sk_sp<SkBlender> xorBlender(jint xorPixel, SkColor color, bool colorPaint) {
    static const SkRuntimeEffect *effect = [] {
        auto result = SkRuntimeEffect::MakeForBlender(SkString(R"(
            uniform half3 xorColor;
            uniform half3 srcColor;
            uniform half colorPaint;
            float3 bxor(float3 a, float3 b) {
                float3 r = float3(0.0);
                float p = 1.0;
                for (int i = 0; i < 8; i++) {
                    float3 ab = mod(floor(a / p), 2.0);
                    float3 bb = mod(floor(b / p), 2.0);
                    r += mod(ab + bb, 2.0) * p;
                    p *= 2.0;
                }
                return r;
            }
            half4 main(half4 src, half4 dst) {
                float3 x = floor(float3(xorColor) * 255.0 + 0.5);
                float3 d = floor(float3(dst.rgb) * 255.0 + 0.5);
                float3 c;
                if (colorPaint > 0.5) {
                    float3 k = bxor(floor(float3(srcColor) * 255.0 + 0.5), x);
                    c = floor(k * float(src.a) + 0.5);
                } else {
                    c = bxor(floor(float3(src.rgb) * 255.0 + 0.5), x);
                }
                return half4(half3(bxor(d, c) / 255.0), src.a + (1.0 - src.a) * dst.a);
            }
        )"));
        if (!result.effect) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "SKBackend: XOR blender does not compile: %s", result.errorText.c_str());
        }
        return result.effect.release();
    }();
    if (effect == nullptr) {
        return nullptr;
    }
    static jint lastPixel = 0;
    static SkColor lastColor = 0;
    static bool lastColorPaint = false;
    static sk_sp<SkBlender> last;
    if (!last || lastPixel != xorPixel || lastColor != color || lastColorPaint != colorPaint) {
        float u[7] = { ((xorPixel >> 16) & 0xff) / 255.0f, ((xorPixel >> 8) & 0xff) / 255.0f, (xorPixel & 0xff) / 255.0f,
                       SkColorGetR(color) / 255.0f, SkColorGetG(color) / 255.0f, SkColorGetB(color) / 255.0f,
                       colorPaint ? 1.0f : 0.0f };
        last = effect->makeBlender(SkData::MakeWithCopy(u, sizeof(u)));
        lastPixel = xorPixel;
        lastColor = color;
        lastColorPaint = colorPaint;
    }
    return last;
}

// The current composite on a paint: a blend mode, or the XOR blender.
static void applyComposite(SkPaint &p, bool colorPaint = true) {
    if (g.xorMode) {
        p.setBlender(xorBlender(g.xorPixel, g.color, colorPaint && !g.shader));
    } else {
        p.setBlendMode(g.blend);
    }
}

SkPaint SKBackend_MakePaint(bool aa) {
    SkPaint p;
    p.setAntiAlias(aa);
    applyComposite(p);
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

// Pending glyph and mask batches of the current context are drawn before
// anything else touches the canvas, so draw order is preserved. A batched
// primitive flushes the other kinds only.
static void flushGlyphBatches() {
    if (g.cc != nullptr) {
        SKGlyphCache_Flush(g.cc->glyphCacheAA);
        SKGlyphCache_Flush(g.cc->glyphCacheLCD);
    }
}

static void flushTextBatches() {   // all batches
    flushGlyphBatches();
    if (g.cc != nullptr) {
        SKMaskCache_Flush(g.cc->maskCache);
    }
}

// Non-AA geometry of the current paint, drawn as one mesh (see SKQuadBatch.h).
static SKQuadBatch gRects;

static void flushAllBatches() {
    flushTextBatches();
    gRects.flush();
}

#define REQUIRE_CANVAS() if (g.canvas == nullptr) { return; } markTouched(); flushAllBatches()
#define REQUIRE_CANVAS_TEXT() if (g.canvas == nullptr) { return; } markTouched(); gRects.flush(); if (g.cc != nullptr) SKMaskCache_Flush(g.cc->maskCache)
#define REQUIRE_CANVAS_MASK() if (g.canvas == nullptr) { return; } markTouched(); gRects.flush(); flushGlyphBatches()
#define REQUIRE_CANVAS_RECT() if (g.canvas == nullptr) { return; } markTouched(); flushTextBatches()

void SKBackend_FillRect(jint x, jint y, jint w, jint h) {
    REQUIRE_CANVAS_RECT();
    if (w <= 0 || h <= 0) return;
    gRects.begin(g.canvas, SKBackend_MakePaint(false));
    gRects.addRect(x, y, x + w, y + h);
}

void SKBackend_DrawRect(jint x, jint y, jint w, jint h) {
    REQUIRE_CANVAS_RECT();
    if (w < 0 || h < 0) return;
    gRects.begin(g.canvas, SKBackend_MakePaint(false));
    if (w < 2 || h < 2) {
        // Java draws a (w+1)x(h+1) outline; degenerate outlines are solid.
        gRects.addRect(x, y, x + w + 1, y + h + 1);
        return;
    }
    gRects.addRect(x, y, x + w + 1, y + 1);              // top
    gRects.addRect(x, y + h, x + w + 1, y + h + 1);      // bottom
    gRects.addRect(x, y + 1, x + 1, y + h);              // left
    gRects.addRect(x + w, y + 1, x + w + 1, y + h);      // right
}

// Java2D's 1-pixel lines: the Bresenham stepping of the software loops
// (SETUP_BRESENHAM in DrawLine.c and the DRAWLINE loop), both endpoints
// inclusive unless `shorten` drops the pixel at (x2, y2) as DrawPolygons.c does
// for the inner joints of a polyline. The pixels are emitted as horizontal or
// vertical runs, one rect per run, so nothing depends on Skia's stroking.
static void bresenhamLine(jint x1, jint y1, jint x2, jint y2, bool shorten = false) {
    const jint dx = x2 - x1, dy = y2 - y1;
    const jint ax = std::abs(dx), ay = std::abs(dy);
    if (dy == 0 || dx == 0) {
        // SETORDERED: the shortened end is always the second point's
        jint a = dy == 0 ? x1 : y1, b = dy == 0 ? x2 : y2, lo, hi;
        if (a < b) { lo = a; hi = b - (shorten ? 1 : 0); } else { lo = b + (shorten ? 1 : 0); hi = a; }
        if (lo > hi) return;
        if (dy == 0) gRects.addRect(lo, y1, hi + 1, y1 + 1); else gRects.addRect(x1, lo, x1 + 1, hi + 1);
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
            if (xMajor) gRects.addRect(std::min(runStartX, x), y, std::max(runStartX, x) + 1, y + 1);
            else        gRects.addRect(x, std::min(runStartY, y), x + 1, std::max(runStartY, y) + 1);
        }
        if (last) break;
        if (diagonal) { x += sx; y += sy; error -= errMinor; }
        else if (xMajor) { x += sx; error += errMajor; }
        else             { y += sy; error += errMajor; }
        if (diagonal) { runStartX = x; runStartY = y; }
    }
}

void SKBackend_DrawLine(jint x1, jint y1, jint x2, jint y2) {
    REQUIRE_CANVAS_RECT();
    gRects.begin(g.canvas, SKBackend_MakePaint(false));
    bresenhamLine(x1, y1, x2, y2);
}

void SKBackend_DrawPixel(jint x, jint y) {
    REQUIRE_CANVAS_RECT();
    gRects.begin(g.canvas, SKBackend_MakePaint(false));
    gRects.addRect(x, y, x + 1, y + 1);
}

void SKBackend_DrawScanlines(jint count, const jint *scanlines) {
    REQUIRE_CANVAS_RECT();
    gRects.begin(g.canvas, SKBackend_MakePaint(false));
    for (jint i = 0; i < count; i++) {
        jint x1 = scanlines[3 * i], x2 = scanlines[3 * i + 1], y = scanlines[3 * i + 2];
        gRects.addRect(x1, y, x2 + 1, y + 1);
    }
}

void SKBackend_FillSpans(jint count, const jint *spans) {
    REQUIRE_CANVAS_RECT();
    gRects.begin(g.canvas, SKBackend_MakePaint(false));
    for (jint i = 0; i < count; i++) {
        gRects.addRect(spans[4 * i], spans[4 * i + 1], spans[4 * i + 2], spans[4 * i + 3]);
    }
}

void SKBackend_DrawPoly(jint nPoints, jboolean isClosed, jint transX, jint transY,
                          const jint *xPoints, const jint *yPoints) {
    REQUIRE_CANVAS_RECT();
    if (nPoints <= 0) return;
    if (nPoints < 2) {
        return;   // a single point draws nothing (DrawPolygons.c)
    }
    gRects.begin(g.canvas, SKBackend_MakePaint(false));
    // As DrawPolygons.c: every segment but the last of an open polyline drops
    // its end pixel (the next segment draws it); a closed polygon shortens all
    // of them and closes back to the first point, a degenerate one is a pixel.
    const jint x0 = xPoints[0] + transX, y0 = yPoints[0] + transY;
    jint px = x0, py = y0;
    bool empty = true;
    for (jint i = 1; i < nPoints; i++) {
        const jint x = xPoints[i] + transX, y = yPoints[i] + transY;
        empty = empty && px == x && py == y;
        bresenhamLine(px, py, x, y, i < nPoints - 1 || isClosed);
        px = x; py = y;
    }
    if (isClosed && (empty || px != x0 || py != y0)) {
        bresenhamLine(px, py, x0, y0, !empty);
    }
}

static void addParallelogram(SkPathBuilder &pb, float x, float y, float dx21, float dy21, float dx12, float dy12) {
    pb.moveTo(x, y);
    pb.lineTo(x + dx21, y + dy21);
    pb.lineTo(x + dx21 + dx12, y + dy21 + dy12);
    pb.lineTo(x + dx12, y + dy12);
    pb.close();
}

// The rows and columns whose centres fall inside a convex quad, half-open
// like the software loops (ParallelogramUtils.h): [ceil(v - 0.5), ...).
template <typename Row>
static void convexQuadSpans(const SkPoint pts[4], Row row) {
    float ymin = pts[0].fY, ymax = pts[0].fY;
    for (int i = 1; i < 4; i++) { ymin = std::min(ymin, pts[i].fY); ymax = std::max(ymax, pts[i].fY); }
    const jint y0 = (jint) std::ceil(ymin - 0.5f), y1 = (jint) std::ceil(ymax - 0.5f);
    for (jint y = y0; y < y1; y++) {
        const float cy = y + 0.5f;
        float xl = FLT_MAX, xr = -FLT_MAX;
        for (int i = 0; i < 4; i++) {
            const SkPoint &a = pts[i], &b = pts[(i + 1) % 4];
            if ((cy >= a.fY && cy < b.fY) || (cy >= b.fY && cy < a.fY)) {
                const float x = a.fX + (cy - a.fY) * (b.fX - a.fX) / (b.fY - a.fY);
                xl = std::min(xl, x); xr = std::max(xr, x);
            }
        }
        if (xl <= xr) {
            row(y, (jint) std::ceil(xl - 0.5f), (jint) std::ceil(xr - 0.5f));
        }
    }
}

static void parallelogramPoints(SkPoint pts[4], float x, float y, float dx21, float dy21, float dx12, float dy12) {
    pts[0] = {x, y};
    pts[1] = {x + dx21, y + dy21};
    pts[2] = {x + dx21 + dx12, y + dy21 + dy12};
    pts[3] = {x + dx12, y + dy12};
}

void SKBackend_FillParallelogram(bool aa, jfloat x11, jfloat y11, jfloat dx21, jfloat dy21, jfloat dx12, jfloat dy12) {
    if (!aa) {
        // non-AA: a quad in the mesh batch, like the other non-AA geometry
        REQUIRE_CANVAS_RECT();
        SkPoint pts[4];
        parallelogramPoints(pts, x11, y11, dx21, dy21, dx12, dy12);
        gRects.begin(g.canvas, SKBackend_MakePaint(false));
        if (SKBackend_Multisampled()) {
            convexQuadSpans(pts, [](jint y, jint l, jint r) { gRects.addRect(l, y, r, y + 1); });
        } else {
            gRects.addQuad(pts);
        }
        return;
    }
    REQUIRE_CANVAS();
    SkPathBuilder pb;
    addParallelogram(pb, x11, y11, dx21, dy21, dx12, dy12);
    g.canvas->drawPath(pb.detach(), SKBackend_MakePaint(aa));
}

void SKBackend_DrawParallelogram(bool aa, jfloat x11, jfloat y11, jfloat dx21, jfloat dy21, jfloat dx12, jfloat dy12,
                                   jfloat lwr21, jfloat lwr12) {
    if (aa) { REQUIRE_CANVAS(); } else { REQUIRE_CANVAS_RECT(); }
    // The given parallelogram is the stroke centerline (see MTLRenderer_DrawParallelogram):
    // grow it by half the line width for the outer edge, shrink it for the inner one.
    float ldx21 = dx21 * lwr21, ldy21 = dy21 * lwr21;
    float ldx12 = dx12 * lwr12, ldy12 = dy12 * lwr12;
    float ox11 = x11 - (ldx21 + ldx12) / 2.0f, oy11 = y11 - (ldy21 + ldy12) / 2.0f;
    bool hole = lwr21 < 1.0f && lwr12 < 1.0f; // otherwise the stroke covers the interior
    float ix11 = x11 + (ldx21 + ldx12) / 2.0f, iy11 = y11 + (ldy21 + ldy12) / 2.0f;
    if (!aa) {
        SkPoint outer[4], inner[4];
        parallelogramPoints(outer, ox11, oy11, dx21 + ldx21, dy21 + ldy21, dx12 + ldx12, dy12 + ldy12);
        gRects.begin(g.canvas, SKBackend_MakePaint(false));
        if (hole) {
            parallelogramPoints(inner, ix11, iy11, dx21 - ldx21, dy21 - ldy21, dx12 - ldx12, dy12 - ldy12);
        }
        if (SKBackend_Multisampled()) {
            // outer spans minus the inner spans of the same row
            std::vector<std::array<jint, 3>> innerSpans;
            if (hole) {
                convexQuadSpans(inner, [&](jint y, jint l, jint r) { innerSpans.push_back({y, l, r}); });
            }
            convexQuadSpans(outer, [&](jint y, jint l, jint r) {
                for (const auto &in : innerSpans) {
                    if (in[0] == y) {
                        gRects.addRect(l, y, std::min(r, in[1]), y + 1);
                        gRects.addRect(std::max(l, in[2]), y, r, y + 1);
                        return;
                    }
                }
                gRects.addRect(l, y, r, y + 1);
            });
        } else if (hole) {
            gRects.addRing(outer, inner);
        } else {
            gRects.addQuad(outer);
        }
        return;
    }
    SkPathBuilder pb;
    pb.setFillType(SkPathFillType::kEvenOdd);
    addParallelogram(pb, ox11, oy11, dx21 + ldx21, dy21 + ldy21, dx12 + ldx12, dy12 + ldy12);
    if (hole) {
        addParallelogram(pb, ix11, iy11, dx21 - ldx21, dy21 - ldy21, dx12 - ldx12, dy12 - ldy12);
    }
    g.canvas->drawPath(pb.detach(), SKBackend_MakePaint(aa));
}

void SKBackend_DrawShape(bool fill, jint kind, const jfloat *xform, const jfloat *stroke,
                         const jfloat *params, jint windingRule, jint numTypes, const jint *types,
                         jint numCoords, const jfloat *coords) {
    REQUIRE_CANVAS();
    SkPaint p = SKBackend_MakePaint(true);
    if (!fill) {
        // BasicStroke: width, cap, join, miter limit, dash phase, dash count, dashes
        p.setStyle(SkPaint::kStroke_Style);
        p.setStrokeWidth(stroke[0]);
        static const SkPaint::Cap caps[] = { SkPaint::kButt_Cap, SkPaint::kRound_Cap, SkPaint::kSquare_Cap };
        static const SkPaint::Join joins[] = { SkPaint::kMiter_Join, SkPaint::kRound_Join, SkPaint::kBevel_Join };
        p.setStrokeCap(caps[std::clamp((int) stroke[1], 0, 2)]);
        p.setStrokeJoin(joins[std::clamp((int) stroke[2], 0, 2)]);
        p.setStrokeMiter(stroke[3]);
        // stroke[4] (dash phase) and stroke[5] (dash count) are always 0: dashed
        // strokes are dashed by Java's stroker and arrive as fills
    }
    const SkMatrix m = SkMatrix::MakeAll(xform[0], xform[2], xform[4],
                                         xform[1], xform[3], xform[5],
                                         0, 0, 1);
    const bool identity = m.isIdentity();
    if (!identity) {
        // The paint (BufferedPaints) is in device space; keep it there while the
        // geometry is drawn under the user-space transform.
        SkMatrix inverse;
        if (p.getShader() != nullptr && m.invert(&inverse)) {
            p.setShader(p.refShader()->makeWithLocalMatrix(inverse));
        }
        g.canvas->save();
        g.canvas->concat(m);
    }
    switch (kind) {
        case 0:
            g.canvas->drawRect(SkRect::MakeXYWH(params[0], params[1], params[2], params[3]), p);
            break;
        case 1:
            g.canvas->drawRRect(SkRRect::MakeRectXY(SkRect::MakeXYWH(params[0], params[1], params[2], params[3]),
                                                    params[4] / 2, params[5] / 2), p);
            break;
        case 2:
            g.canvas->drawOval(SkRect::MakeXYWH(params[0], params[1], params[2], params[3]), p);
            break;
        default: {
            SkPathBuilder pb(windingRule == 0 ? SkPathFillType::kEvenOdd : SkPathFillType::kWinding);
            const jfloat *c = coords;
            for (jint i = 0; i < numTypes; i++) {
                switch (types[i]) {
                    case 0: pb.moveTo(c[0], c[1]); c += 2; break;                                  // SEG_MOVETO
                    case 1: pb.lineTo(c[0], c[1]); c += 2; break;                                  // SEG_LINETO
                    case 2: pb.quadTo(c[0], c[1], c[2], c[3]); c += 4; break;                      // SEG_QUADTO
                    case 3: pb.cubicTo(c[0], c[1], c[2], c[3], c[4], c[5]); c += 6; break;         // SEG_CUBICTO
                    default: pb.close(); break;                                                     // SEG_CLOSE
                }
            }
            SkPath path = pb.detach();
            if (fill) {
                g.canvas->drawPath(path, p);
            } else {
                // Stroke like Marlin does: the stroked outline is filled (dashed
                // strokes were dashed by Java's stroker and arrive as fills). Skia's
                // rasterizer would otherwise draw strokes of one device pixel or
                // less as hairlines with a different coverage rule.
                bool isFill = false;
                SkPath outline = skpathutils::FillPathWithPaint(path, p, &isFill);
                SkPaint fillPaint = p;
                fillPaint.setStyle(SkPaint::kFill_Style);
                g.canvas->drawPath(outline, fillPaint);
            }
            break;
        }
    }
    if (!identity) {
        g.canvas->restore();
    }
}

void SKBackend_MaskFill(jint x, jint y, jint w, jint h, jint maskoff, jint maskscan, jint masklen,
                          const unsigned char *mask) {
    REQUIRE_CANVAS_MASK();
    if (w <= 0 || h <= 0) return;
    SkPaint p = SKBackend_MakePaint(false);
    if (mask == nullptr || masklen == 0) {
        SKMaskCache_Flush(g.cc->maskCache);
        g.canvas->drawIRect(SkIRect::MakeXYWH(x, y, w, h), p);
        return;
    }
    if (SKMaskCache_Fits(w, h)) {
        if (g.cc->maskCache == nullptr) {
            g.cc->maskCache = SKMaskCache_Create(g.cc);
        }
        if (g.cc->maskCache != nullptr) {
            SKMaskCache_Draw(g.cc->maskCache, g.canvas, p, x, y, w, h, mask + maskoff, maskscan);
            return;
        }
    }
    // Oversized tile: upload it on its own.
    SKMaskCache_Flush(g.cc->maskCache);
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
    applyComposite(p, false);
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
        applyComposite(p, false);
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
    // batches stay open for the next glyph list; see flushTextBatches()
}

static SkSamplingOptions samplingForHint(jint hint) {
    // java.awt.image.AffineTransformOp: 1 = NEAREST_NEIGHBOR, 2 = BILINEAR, 3 = BICUBIC
    return hint == 1 ? SkSamplingOptions(SkFilterMode::kNearest) : SkSamplingOptions(SkFilterMode::kLinear);
}

// Java2D blits are hard-edged quads sampling the source with edge extension
// (the Metal pipeline draws textured quads). Graphite anti-aliases every rect
// that is not pixel-aligned in device space whatever the paint says, so a
// transformed drawImageRect gets coverage edges; a mesh is rasterized without
// coverage. The sample coordinates are kept half a texel inside the source rect
// so filtering never blends in neighbouring texels (SrcRectConstraint strict).
static void drawImageQuad(const sk_sp<SkImage> &image, const SkRect &srcRect, const SkRect &dstRect,
                          const SkSamplingOptions &sampling, SkPaint paint) {
    // Alternative: Graphite's per-edge-AA image draw with no AA
    // edges instead of the mesh (sun.java2d.skia.blitQuads=edgeAA).
    if (SKBackend_EdgeAAQuads()) {
        SkCanvas::ImageSetEntry entry(image, srcRect, dstRect, 1.0f, SkCanvas::kNone_QuadAAFlags);
        g.canvas->experimental_DrawEdgeAAImageSet(&entry, 1, nullptr, nullptr, sampling, &paint,
                                                  SkCanvas::kStrict_SrcRectConstraint);
        return;
    }
    sk_sp<SkShader> shader = image->makeShader(SkTileMode::kClamp, SkTileMode::kClamp, sampling);
    SkRect inset = srcRect;
    inset.inset(std::min(0.5f, srcRect.width() / 2), std::min(0.5f, srcRect.height() / 2));
    shader = SkShaders::CoordClamp(std::move(shader), inset);
    paint.setShader(std::move(shader));
    paint.setAntiAlias(false);
    static const uint16_t quadIndices[6] = {0, 1, 2, 0, 2, 3};
    if (!SKBackend_Multisampled()) {
        const SkPoint positions[4] = {{dstRect.fLeft, dstRect.fTop}, {dstRect.fRight, dstRect.fTop},
                                      {dstRect.fRight, dstRect.fBottom}, {dstRect.fLeft, dstRect.fBottom}};
        const SkPoint texCoords[4] = {{srcRect.fLeft, srcRect.fTop}, {srcRect.fRight, srcRect.fTop},
                                      {srcRect.fRight, srcRect.fBottom}, {srcRect.fLeft, srcRect.fBottom}};
        g.canvas->drawVertices(SkVertices::MakeCopy(SkVertices::kTriangles_VertexMode, 4, positions, texCoords,
                                                    nullptr, 6, quadIndices),
                               SkBlendMode::kModulate, paint);
        return;
    }
    // MSAA: the destination quad in device space, decomposed into the pixel
    // spans whose centres it covers; texture coordinates come from mapping
    // each span corner back through the canvas matrix and the dst->src map.
    const SkMatrix toDevice = g.canvas->getLocalToDevice().asM33();
    SkMatrix toLocal;
    if (!toDevice.invert(&toLocal)) {
        return;
    }
    const SkMatrix toSource = SkMatrix::Concat(SkMatrix::RectToRect(dstRect, srcRect), toLocal);   // device -> src
    SkPoint quad[4] = {{dstRect.fLeft, dstRect.fTop}, {dstRect.fRight, dstRect.fTop},
                       {dstRect.fRight, dstRect.fBottom}, {dstRect.fLeft, dstRect.fBottom}};
    toDevice.mapPoints(SkSpan<SkPoint>(quad, 4));
    std::vector<SkPoint> positions, texCoords;
    std::vector<uint16_t> indices;
    auto addSpanQuad = [&](float l, float t, float r, float b) {
        const uint16_t base = (uint16_t) positions.size();
        SkPoint corners[4] = {{l, t}, {r, t}, {r, b}, {l, b}};
        positions.insert(positions.end(), corners, corners + 4);
        toSource.mapPoints(SkSpan<SkPoint>(corners, 4));
        texCoords.insert(texCoords.end(), corners, corners + 4);
        for (uint16_t i : quadIndices) indices.push_back(base + i);
    };
    if (toDevice.rectStaysRect()) {
        SkRect dev = toDevice.mapRect(dstRect);
        const float l = std::ceil(dev.fLeft - 0.5f), t = std::ceil(dev.fTop - 0.5f);
        const float r = std::ceil(dev.fRight - 0.5f), b = std::ceil(dev.fBottom - 0.5f);
        if (r > l && b > t) addSpanQuad(l, t, r, b);
    } else {
        convexQuadSpans(quad, [&](jint y, jint l, jint r) {
            if (r > l) addSpanQuad(l, y, r, y + 1);
            if (positions.size() + 4 > 0xffff) {
                g.canvas->save(); g.canvas->resetMatrix();
                g.canvas->drawVertices(SkVertices::MakeCopy(SkVertices::kTriangles_VertexMode, (int) positions.size(), positions.data(),
                                                            texCoords.data(), nullptr, (int) indices.size(), indices.data()),
                                       SkBlendMode::kModulate, paint);
                g.canvas->restore();
                positions.clear(); texCoords.clear(); indices.clear();
            }
        });
    }
    if (!indices.empty()) {
        g.canvas->save();
        g.canvas->resetMatrix();
        g.canvas->drawVertices(SkVertices::MakeCopy(SkVertices::kTriangles_VertexMode, (int) positions.size(), positions.data(),
                                                    texCoords.data(), nullptr, (int) indices.size(), indices.data()),
                               SkBlendMode::kModulate, paint);
        g.canvas->restore();
    }
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
    applyComposite(p, false);
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
            drawImageQuad(image, SkRect::MakeWH(tw, th), tileDst, samplingForHint(hint), p);
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
        if (src != nullptr) {
            SKPlatformConfig *srcConfig = SKPlatform_ConfigOf(src->platform);
            if (srcConfig != g.cc->config && !SKPlatform_SameDevice(srcConfig, g.cc->config)) {
                // Another adapter's texture cannot be wrapped by this context (a
                // window dragged between the displays of two GPUs): nothing is
                // drawn rather than a texture handle of a foreign device handed
                // to the driver. Such a blit would need a readback.
                J2dRlsTraceLn(J2D_TRACE_WARNING, "SKBackend_Blit: source %p is on another device, skipped", src);
                return;
            }
            if (src->state != nullptr && src->state->cc != nullptr && src->state->cc != g.cc) {
                // another display's context and queue: its work must be complete
                SKBackend_Submit(src->state->cc, true);
            }
        }
        if (src != nullptr && src->state != nullptr && src->state->surface && src->state->cc == g.cc
                && src->platform != nullptr && src->state->wrappedTexture == SKPlatform_TextureHandle(src->platform)) {
            // An image view linked to the source's device: Graphite flushes the
            // source's pending draws before this recording samples it. A bare
            // WrapTexture of the same texture would not carry that dependency.
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
    applyComposite(p, false);
    p.setAlphaf(g.extraAlpha);
    drawImageQuad(image, srcRect, dstRect, samplingForHint(hint), p);
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
    SKMaskCache_Flush(cc->maskCache);
    gRects.flush();
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
}

void SKBackend_EndOfFlush(bool syncToCpu) {
    for (SKConfigContext *cc : g.touchedContexts) {
        SKBackend_Submit(cc, syncToCpu);
    }
    g.touchedContexts.clear();
    // Ask the platform to present the windows we drew into.
    for (SKSDOps *dst : g.touchedWindows) {
        if (dst->platform != nullptr) {
            SKPlatform_RequestRedraw(dst->platform);
        }
    }
    g.touchedWindows.clear();
}
