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
#include "Trace.h"
#include "SKPlatform.h"
#include "SKGlyphCache.h"
#include "SKFontScaler.h"
#include "SKLuminanceTypeface.h"
#include "sun_font_SKFontScaler.h"
#include "SKMaskCache.h"
#include "SKQuadBatch.h"

#include "jlong.h"
#include "fontscalerdefs.h"
#include "sun_font_StrikeCache.h"
#include "java_awt_AlphaComposite.h"
#include "sun_java2d_pipe_hw_AccelSurface.h"
#include "sun_java2d_skia_SKBlitLoops.h"
#include "sun_java2d_skia_SKShapePipe.h"
#include "sun_java2d_skia_SKSurfaceData.h"

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
#include "include/core/SkTextBlob.h"
#include "modules/skshaper/include/SkShaper.h"
#include "modules/skshaper/include/SkShaper_harfbuzz.h"
#include "modules/skshaper/include/SkShaper_skunicode.h"
#include "modules/skunicode/include/SkUnicode.h"
#include "modules/skunicode/include/SkUnicode_icu.h"
#include "modules/skparagraph/include/FontCollection.h"
#include "modules/skparagraph/include/ParagraphBuilder.h"
#include "modules/skparagraph/include/ParagraphStyle.h"
#include "modules/skparagraph/include/TextStyle.h"
#include "modules/skparagraph/include/TypefaceFontProvider.h"
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
#include <map>
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
    SKLcdMode lcdMode = SKLcdMode::Blend;
    bool framebufferFetch = false;
} gConfig;

static SKQuadBatch gRects;   // non-AA geometry of the current paint, drawn as one mesh (see SKQuadBatch.h)

void SKBackend_Configure(jint msaaSamples, bool edgeAAQuads, SKLcdMode lcdMode, bool framebufferFetch) {
    gConfig.msaaSamples = msaaSamples;
    gConfig.edgeAAQuads = edgeAAQuads;
    gConfig.lcdMode = lcdMode;
    gConfig.framebufferFetch = framebufferFetch;
}

bool SKBackend_FramebufferFetch() {
    return gConfig.framebufferFetch;
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
    std::unique_ptr<Recorder> recorder = context->makeRecorder();
    if (!recorder) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "SKBackend_ContextFor: cannot create a Graphite recorder");
        return nullptr;
    }
    auto *cc = new SKConfigContext();
    cc->config = config;
    cc->recorder = std::move(recorder);
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
    cc->lcdTables.clear();
    cc->lcdCoverage.reset();
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
        return nullptr;   // every caller has a destination, and with it a context
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

// MultipleGradientPaint.CycleMethod ordinals as sent by BufferedPaints.
static const jint CYCLE_METHOD_REFLECT = 1, CYCLE_METHOD_REPEAT = 2;

static SkTileMode tileModeFor(jint cycleMethod) {
    switch (cycleMethod) {
        case CYCLE_METHOD_REFLECT: return SkTileMode::kMirror;
        case CYCLE_METHOD_REPEAT:  return SkTileMode::kRepeat;
        default:                   return SkTileMode::kClamp;   // NO_CYCLE
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
    if (cycleMethod == CYCLE_METHOD_REPEAT) {
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
    // t = d / r, so a slightly smaller radius biases t upward at the cycle boundary
    const float radius = cycleMethod == CYCLE_METHOD_REPEAT ? 1.0f / (1.0f + REPEAT_SEAM_BIAS) : 1.0f;
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

// A source on another adapter cannot be wrapped by this context (a window
// dragged between the displays of two GPUs): nothing is drawn rather than a
// texture handle of a foreign device handed to the driver; such a blit would
// need a readback. A source on another display's context and queue is usable
// once its work is complete.
static bool sourceUsable(SKSDOps *src, const char *who) {
    SKPlatformConfig *srcConfig = SKPlatform_ConfigOf(src->platform);
    if (srcConfig != g.cc->config && !SKPlatform_SameDevice(srcConfig, g.cc->config)) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "%s: source %p is on another device, skipped", who, src);
        return false;
    }
    if (src->state != nullptr && src->state->cc != nullptr && src->state->cc != g.cc) {
        SKBackend_Submit(src->state->cc, true);
    }
    return true;
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
    if (g.cc != nullptr && !sourceUsable(skSrc, "SKBackend_SetTexturePaint")) {
        g.shader.reset();
        return;
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

// The glyph and Marlin tile batches of a context, then the rect batch.
static void flushBatchesOf(SKConfigContext *cc) {
    if (cc != nullptr) {
        SKGlyphCache_Flush(cc->glyphCacheAA);
        SKGlyphCache_Flush(cc->glyphCacheLCD);
        SKMaskCache_Flush(cc->maskCache);
    }
    gRects.flush();
}

static void flushAtlasBatches() {
    flushGlyphBatches();
    if (g.cc != nullptr) {
        SKMaskCache_Flush(g.cc->maskCache);
    }
}

static void flushAllBatches() {
    flushBatchesOf(g.cc);
}

#define REQUIRE_CANVAS() if (g.canvas == nullptr) { return; } markTouched(); flushAllBatches()
#define REQUIRE_CANVAS_TEXT() if (g.canvas == nullptr) { return; } markTouched(); gRects.flush(); if (g.cc != nullptr) SKMaskCache_Flush(g.cc->maskCache)
#define REQUIRE_CANVAS_MASK() if (g.canvas == nullptr) { return; } markTouched(); gRects.flush(); flushGlyphBatches()
#define REQUIRE_CANVAS_RECT() if (g.canvas == nullptr) { return; } markTouched(); flushAtlasBatches()

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
        // BasicStroke: width, cap, join, miter limit (dashed strokes arrive as fills)
        p.setStyle(SkPaint::kStroke_Style);
        p.setStrokeWidth(stroke[0]);
        static const SkPaint::Cap caps[] = { SkPaint::kButt_Cap, SkPaint::kRound_Cap, SkPaint::kSquare_Cap };
        static const SkPaint::Join joins[] = { SkPaint::kMiter_Join, SkPaint::kRound_Join, SkPaint::kBevel_Join };
        p.setStrokeCap(caps[std::clamp((int) stroke[1], 0, 2)]);
        p.setStrokeJoin(joins[std::clamp((int) stroke[2], 0, 2)]);
        p.setStrokeMiter(stroke[3]);
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
        case sun_java2d_skia_SKShapePipe_KIND_RECT:
            g.canvas->drawRect(SkRect::MakeXYWH(params[0], params[1], params[2], params[3]), p);
            break;
        case sun_java2d_skia_SKShapePipe_KIND_ROUND_RECT:
            g.canvas->drawRRect(SkRRect::MakeRectXY(SkRect::MakeXYWH(params[0], params[1], params[2], params[3]),
                                                    params[4] / 2, params[5] / 2), p);
            break;
        case sun_java2d_skia_SKShapePipe_KIND_OVAL:
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
    const bool solidTile = mask == nullptr || masklen == 0;
    if (solidTile && (g.shader || !SKMaskCache_Fits(w, h))) {
        // a solid tile under a shader paint, or an oversized one, is a plain rect
        SKMaskCache_Flush(g.cc->maskCache);
        g.canvas->drawIRect(SkIRect::MakeXYWH(x, y, w, h), p);
        return;
    }
    if (SKMaskCache_Fits(w, h)) {
        if (g.cc->maskCache == nullptr && !g.cc->maskCacheFailed) {
            g.cc->maskCache = SKMaskCache_Create(g.cc);
            g.cc->maskCacheFailed = g.cc->maskCache == nullptr;
        }
        if (g.cc->maskCache != nullptr) {
            if (solidTile) {
                // a shape's interior tiles stay in the mesh with its edge tiles
                SKMaskCache_DrawSolid(g.cc->maskCache, g.canvas, p, x, y, w, h);
            } else {
                SKMaskCache_Draw(g.cc->maskCache, g.canvas, p, x, y, w, h, mask + maskoff, maskscan);
            }
            return;
        }
    }
    if (solidTile) {
        g.canvas->drawIRect(SkIRect::MakeXYWH(x, y, w, h), p);
        return;
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

// LCD text without a destination read (sun.java2d.skia.lcd=twopass and
// accumulate): the blend  Cr = Cd * (1 - cov) + Cs * cov  per channel as two
// fixed-function passes, kModulate with (1 - cov) and kPlus with Cs * cov,
// which is how DirectWrite composites ClearType (dual-source blending does both
// at once; Graphite does not expose it). The destination's gamma cannot be
// applied without reading it; instead the coverage is corrected so that the
// linear blend reproduces the gamma blend on a white and on a black
// background, mixed by the channel's text value (dark text is mostly read on
// light backgrounds): exact for black on white and white on black, up to about
// fifteen levels off at glyph edges for light text on dark mid-tones. The
// correction depends on the coverage and the text colour only, so it is a
// 256-entry table per channel, built once per colour and contrast and sampled
// as a 256x1 texture (lcdTable). The coverage arrives in the atlas' RGB
// channels. Both passes write alpha 1 over the whole quad and ignore the
// paint's alpha, which holds because LCD text is only requested for an opaque
// colour on an opaque destination (SKSurfaceData.canRenderLCDText).

// The linear weight that reproduces the gamma blend on white and on black (the
// result's distance from the background over the text colour's), mixed by the
// channel's text value.
static float lcdCorrectedCoverage(float cov, float src, float gamma) {
    const float srcGamma = powf(src, gamma), invGamma = 1.0f / gamma;
    const float onWhite = 1.0f - powf(1.0f + (srcGamma - 1.0f) * cov, invGamma);
    const float onBlack = powf(srcGamma * cov, invGamma);
    const float w = ((1.0f - src) * onWhite + src * onBlack) / ((1.0f - src) * (1.0f - src) + src * src);
    return std::clamp(w, 0.0f, 1.0f);
}

static sk_sp<SkImage> lcdTable(SkColor color, jint contrast) {
    const uint64_t key = ((uint64_t) (uint32_t) color << 32) | (uint32_t) contrast;
    auto &tables = g.cc->lcdTables;
    auto found = tables.find(key);
    if (found != tables.end()) {
        return found->second;
    }
    if (tables.size() >= 64) {
        tables.clear();   // a frame uses a handful of text colours
    }
    const float gamma = contrast / 100.0f;
    SkColor4f c = SkColor4f::FromColor(color);
    const float src[3] = { c.fR, c.fG, c.fB };
    uint8_t pixels[256 * 4];
    for (int i = 0; i < 256; i++) {
        for (int ch = 0; ch < 3; ch++) {
            pixels[i * 4 + ch] = (uint8_t) lroundf(lcdCorrectedCoverage(i / 255.0f, src[ch], gamma) * 255.0f);
        }
        pixels[i * 4 + 3] = 0xff;
    }
    sk_sp<SkImage> table = uploadPixmap(SkPixmap(SkImageInfo::Make(256, 1, kRGBA_8888_SkColorType, kOpaque_SkAlphaType),
                                                 pixels, 256 * 4));
    if (table) {
        tables[key] = table;   // a failed upload is retried by the next glyph list
    }
    return table;
}

static sk_sp<SkShader> lcdPassShader(const sk_sp<SkShader> &coverage, const SkPaint &paint, jint contrast, bool second) {
    static const SkRuntimeEffect *effects[2] = { nullptr, nullptr };
    static bool compiled = false;
    if (!compiled) {
        compiled = true;
        const char *common = R"(
            uniform shader atlas;
            uniform shader table;
            uniform half3 src;
            half3 coverage(float2 xy) {
                half3 cov = atlas.eval(xy).rgb;
                return half3(table.eval(float2(cov.r * 255 + 0.5, 0.5)).r,
                             table.eval(float2(cov.g * 255 + 0.5, 0.5)).g,
                             table.eval(float2(cov.b * 255 + 0.5, 0.5)).b);
            }
        )";
        const char *mains[2] = {
            "half4 main(float2 xy) { return half4(1 - coverage(xy), 1); }",
            "half4 main(float2 xy) { return half4(src * coverage(xy), 1); }",
        };
        for (int i = 0; i < 2; i++) {
            auto result = SkRuntimeEffect::MakeForShader(SkStringPrintf("%s%s", common, mains[i]));
            if (!result.effect) {
                J2dRlsTraceLn(J2D_TRACE_ERROR, "SKBackend: LCD two-pass shader does not compile: %s", result.errorText.c_str());
                break;
            }
            effects[i] = result.effect.release();
        }
    }
    if (effects[1] == nullptr) {
        return nullptr;
    }
    sk_sp<SkImage> table = lcdTable(paint.getColor(), contrast);
    if (!table) {
        return nullptr;
    }
    SkColor4f c = SkColor4f::FromColor(paint.getColor());
    const float u[3] = { c.fR, c.fG, c.fB };   // the shader's `src` uniform
    SkRuntimeEffect::ChildPtr children[2] = {
        SkRuntimeEffect::ChildPtr(coverage),
        SkRuntimeEffect::ChildPtr(table->makeShader(SkTileMode::kClamp, SkTileMode::kClamp,
                                                    SkSamplingOptions(SkFilterMode::kNearest))),
    };
    return effects[second ? 1 : 0]->makeShader(SkData::MakeWithCopy(u, sizeof(u)), SkSpan(children, 2));
}

// The batch's paint keys the colour, the key the contrast of the glyph list
// being drawn.
static SKQuadBatchTwoPass gLcdTwoPass = {
    [](const sk_sp<SkShader> &atlas, const SkPaint &paint) { return lcdPassShader(atlas, paint, gLcdTwoPass.key, false); },
    [](const sk_sp<SkShader> &atlas, const SkPaint &paint) { return lcdPassShader(atlas, paint, gLcdTwoPass.key, true); },
};

// The two passes over a coverage image (device coordinates) within `bounds`,
// with the batch's blend modes.
static void drawLcdPasses(SkCanvas *dst, const sk_sp<SkShader> &coverage, const SkRect &bounds, const SkPaint &paint,
                          jint contrast) {
    SkPaint p = paint;
    p.setShader(lcdPassShader(coverage, paint, contrast, false));
    p.setBlendMode(gLcdTwoPass.firstBlend);
    dst->drawRect(bounds, p);
    p.setShader(lcdPassShader(coverage, paint, contrast, true));
    p.setBlendMode(gLcdTwoPass.secondBlend);
    dst->drawRect(bounds, p);
}

// sun.java2d.skia.lcd=accumulate: the glyphs of a batch are drawn into a
// destination-sized coverage texture with kScreen (1 - (1 - a)(1 - b), the
// union of coverages, which is what compositing same-coloured glyphs in
// sequence leaves; fixed-function and ordered, so overlapping glyphs need no
// splitting), and the batch's bounds are then composited onto the destination
// in the two passes. Two draws per batch and a clear, whatever the glyph
// count or overlap depth; a scratch texture per context in return.
// The accumulating batch's hooks run when the batch is drawn, which may be
// after the destination has left the render state (a surface disposed behind
// queued text), so they keep their own destination and context.
static struct {
    SkCanvas *dst = nullptr;
    SKConfigContext *cc = nullptr;
    jint contrast = 0;   // set with the destination, per glyph list
} gLcdAccumulate;

static SkCanvas *lcdCoverageCanvas() {
    const int w = g.canvas->imageInfo().width(), h = g.canvas->imageInfo().height();
    sk_sp<SkSurface> &scratch = g.cc->lcdCoverage;
    if (!scratch || scratch->width() < w || scratch->height() < h) {
        SKGlyphCache_Flush(g.cc->glyphCacheLCD);   // a pending batch draws into the old scratch
        const int sw = scratch ? std::max(scratch->width(), w) : w, sh = scratch ? std::max(scratch->height(), h) : h;
        scratch = SkSurfaces::RenderTarget(g.cc->recorder.get(),
                                           SkImageInfo::Make(sw, sh, kRGBA_8888_SkColorType, kOpaque_SkAlphaType));
        if (!scratch) {
            static bool reported = false;
            if (!reported) {
                reported = true;
                J2dRlsTraceLn(J2D_TRACE_ERROR, "SKBackend: cannot create the %dx%d LCD coverage surface", sw, sh);
            }
            return nullptr;
        }
    }
    return scratch->getCanvas();
}

static void clearLcdCoverage(SKConfigContext *cc, const SkRect &bounds) {
    SkPaint clear;
    clear.setColor(SK_ColorBLACK);
    clear.setBlendMode(SkBlendMode::kSrc);
    cc->lcdCoverage->getCanvas()->drawRect(bounds, clear);
}

static sk_sp<SkShader> lcdCoverageShader(SKConfigContext *cc) {
    sk_sp<SkImage> image = SkSurfaces::AsImage(cc->lcdCoverage);
    return image ? image->makeShader(SkTileMode::kClamp, SkTileMode::kClamp, SkSamplingOptions(SkFilterMode::kNearest))
                 : nullptr;
}

static void compositeLcdCoverage(const SkRect &bounds, const SkPaint &paint) {
    drawLcdPasses(gLcdAccumulate.dst, lcdCoverageShader(gLcdAccumulate.cc), bounds, paint, gLcdAccumulate.contrast);
}

// A glyph list accumulates: the LCD cache's batch draws into the coverage
// surface, clears its bounds first and composites them afterwards.
static SkCanvas *beginLcdAccumulation(SKGlyphCache *lcd, SkPaint &lcdPaint, jint lcdContrast) {
    SkCanvas *scratch = lcdCoverageCanvas();
    if (scratch == nullptr) {
        return nullptr;
    }
    if (gLcdAccumulate.contrast != lcdContrast || gLcdAccumulate.dst != g.canvas || gLcdAccumulate.cc != g.cc) {
        SKGlyphCache_Flush(lcd);   // neither is part of the batch key
        gLcdAccumulate.contrast = lcdContrast;
        gLcdAccumulate.dst = g.canvas;
        gLcdAccumulate.cc = g.cc;
    }
    SKQuadBatch &batch = SKGlyphCache_Batch(lcd);
    if (!batch.afterDraw) {
        batch.beforeDraw = [](const SkRect &bounds) { clearLcdCoverage(gLcdAccumulate.cc, bounds); };
        batch.afterDraw = [](const SkRect &bounds, const SkPaint &paint) { compositeLcdCoverage(bounds, paint); };
    }
    lcdPaint.setColor(g.color);   // the key; the atlas shader ignores it
    lcdPaint.setBlendMode(SkBlendMode::kScreen);
    return scratch;
}

// An uncached LCD glyph image without a destination read: in two passes with
// the image shader standing in for the atlas, positioned at (x, y), or through
// the coverage surface.
static void drawLcdImageNoDstRead(const sk_sp<SkImage> &cov, float x, float y, const SkPaint &paint, jint contrast) {
    SkRect rect = SkRect::MakeXYWH(x, y, (float) cov->width(), (float) cov->height());
    if (gConfig.lcdMode == SKLcdMode::Accumulate) {
        SkCanvas *scratch = lcdCoverageCanvas();
        if (scratch == nullptr) {
            return;
        }
        clearLcdCoverage(g.cc, rect);
        SkPaint screen;
        screen.setBlendMode(SkBlendMode::kScreen);
        scratch->drawImage(cov, x, y, SkSamplingOptions(SkFilterMode::kNearest), &screen);
        drawLcdPasses(g.canvas, lcdCoverageShader(g.cc), rect, paint, contrast);
        return;
    }
    SkMatrix at = SkMatrix::Translate(x, y);
    sk_sp<SkShader> image = cov->makeShader(SkTileMode::kClamp, SkTileMode::kClamp, SkSamplingOptions(SkFilterMode::kNearest), &at);
    drawLcdPasses(g.canvas, image, rect, paint, contrast);
}

static SKGlyphCache *glyphCache(bool lcd) {
    SKGlyphCache *&slot = lcd ? g.cc->glyphCacheLCD : g.cc->glyphCacheAA;
    bool &failed = lcd ? g.cc->glyphCacheLCDFailed : g.cc->glyphCacheAAFailed;
    if (slot == nullptr && !failed) {
        slot = SKGlyphCache_Create(g.cc, lcd);
        failed = slot == nullptr;
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

static void drawLcdGlyphNoCache(const GlyphInfo *ginfo, jint subimage, jint rowBytesOffset, jboolean rgbOrder,
                                float x, float y, const SkPaint &paint) {
    const int w = ginfo->width, h = ginfo->height;
    std::vector<unsigned char> rgba((size_t) w * h * 4);
    const unsigned char *image = ginfo->image + (size_t) ginfo->rowBytes * h * subimage;
    for (int row = 0; row < h; row++) {
        const unsigned char *src = image + (size_t) row * ginfo->rowBytes + rowBytesOffset;
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
        if (gConfig.lcdMode != SKLcdMode::Blend) {
            drawLcdImageNoDstRead(cov, x, y, paint, gLcdTwoPass.key);
        } else {
            g.canvas->drawImage(cov, x, y, SkSamplingOptions(SkFilterMode::kNearest), &paint);
        }
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
// The pipes' shared variant rule (fontscalerdefs.h), as an expression.
static inline float adjustSubpixel(float coord, int res) {
    return res > 1 ? coord + 0.5f / (float) res - 0.5f + SUBPIXEL_TIE_NUDGE : coord;
}

static std::atomic<jlong> gGlyphsDrawnBySkia{0};   // written by the flusher, read by any Java thread

jlong SKBackend_GlyphsDrawnBySkia() {
    return gGlyphsDrawnBySkia.load(std::memory_order_relaxed);
}

// The cg grayscale route on Skia's text path (sun.java2d.skia.text.gray=cg): the strike's
// typeface is replaced by the luminance proxy for the paint colour's class (the Java side's
// classes: 16 levels, the class representative at its middle), so the masks in Skia's
// strike and atlas are CoreGraphics' smoothed rendering for that colour.
// SunGraphics2D.textLuminance() computes the same class on the Java side; keep the two in step.
static int luminanceClass(const SkPaint &paint) {
    const SkColor c = paint.getColor();
    const int luminance = (SkColorGetR(c) * 299 + SkColorGetG(c) * 587 + SkColorGetB(c) * 114) / 1000;
    return (luminance & ~0xf) | 0x8;
}

static bool takesGrayRoute(const SkFont &font, jlong pScalerContext) {
    return SKFontScaler_GrayMode(pScalerContext) == sun_font_SKFontScaler_GRAY_CG
           && font.getEdging() == SkFont::Edging::kAntiAlias && font.getTypeface() != nullptr;
}

static void applyGrayRoute(SkFont &font, jlong pScalerContext, const SkPaint &paint) {
    if (takesGrayRoute(font, pScalerContext)) {
        font.setTypeface(SKLuminanceTypeface_For(font.refTypeface(), luminanceClass(paint)));
    }
}

// The font manager for the fallback runs: the platform's, or its luminance proxy when the
// strike takes the cg route, so the characters the strike's font lacks get the route too.
static sk_sp<SkFontMgr> fallbackManager(const SkFont &font, jlong pScalerContext, const SkPaint &paint) {
    sk_sp<SkFontMgr> platform = SKPlatform_FontManager();
    return takesGrayRoute(font, pScalerContext) ? SKLuminanceFontMgr_For(platform, luminanceClass(paint)) : platform;
}

void SKBackend_DrawGlyphsBySkia(jlong pScalerContext, jint numGlyphs, jboolean usePositions, jboolean smoothing,
                                jfloat originX, jfloat originY, const jint *codes, const jlong *images,
                                const jfloat *positions) {
    REQUIRE_CANVAS_TEXT();
    flushGlyphBatches();   // glyph-image draws queued before this list stay in order
    SkPaint paint = SKBackend_MakePaint(true);
    // runs of glyphs sharing a font (the strike's own, or a platform fallback font)
    std::vector<SkGlyphID> ids;
    std::vector<SkPoint> points;
    SkFont runFont;
    const SkTypeface *runTypeface = nullptr;
    auto flushRun = [&] {
        if (!ids.empty()) {
            g.canvas->drawGlyphs(SkSpan<const SkGlyphID>(ids.data(), ids.size()), SkSpan<const SkPoint>(points.data(), points.size()), {0, 0}, runFont, paint);
            gGlyphsDrawnBySkia.fetch_add((jlong) ids.size(), std::memory_order_relaxed);
            ids.clear();
            points.clear();
        }
    };
    float penX = originX, penY = originY;
    for (jint i = 0; i < numGlyphs; i++) {
        float gx, gy;
        if (usePositions) {
            gx = originX + positions[2 * i];
            gy = originY + positions[2 * i + 1];
        } else {
            gx = penX;
            gy = penY;
            const GlyphInfo *ginfo = images != nullptr ? (const GlyphInfo *) jlong_to_ptr(images[i]) : nullptr;
            if (ginfo != nullptr && ginfo != (const GlyphInfo *) -1) {
                penX += ginfo->advanceX;
                penY += ginfo->advanceY;
            }
        }
        SkFont font;
        SkGlyphID glyph;
        if (!SKFontScaler_ResolveGlyph(pScalerContext, codes[i], &font, &glyph)) {
            continue;
        }
        applyGrayRoute(font, pScalerContext, paint);
        if (font.getTypeface() != runTypeface) {
            flushRun();
            runFont = font;
            if (smoothing) {
                runFont.setHinting(SkFontHinting::kNormal);   // the CoreText host: masks from CoreGraphics with font smoothing on
            }
            runTypeface = font.getTypeface();
        }
        ids.push_back(glyph);
        points.push_back({gx, gy});
    }
    flushRun();
}

static std::atomic<jlong> gGlyphsShapedBySkia{0};   // written by the flusher, read by any Java thread

jlong SKBackend_GlyphsShapedBySkia() {
    return gGlyphsShapedBySkia.load(std::memory_order_relaxed);
}

namespace {

// Collects the shaper's runs into one text blob with the baseline at y = 0 and the
// runs following each other from x = 0, so the blob is drawn at Java's baseline
// origin. (SkTextBlobBuilderRunHandler puts the first baseline one ascent below
// its origin, the top of the line.)
class BaselineRunHandler final : public SkShaper::RunHandler {
public:
    void beginLine() override { pen = {0, 0}; }
    void runInfo(const RunInfo &) override {}
    void commitRunInfo() override {}
    Buffer runBuffer(const RunInfo &info) override {
        const SkTextBlobBuilder::RunBuffer &run = builder.allocRunPos(info.fFont, (int) info.glyphCount);
        return {run.glyphs, run.points(), nullptr, nullptr, pen};
    }
    void commitRunBuffer(const RunInfo &info) override {
        pen += info.fAdvance;
        glyphs += info.glyphCount;
    }
    void commitLine() override {}
    sk_sp<SkTextBlob> makeBlob() { return builder.make(); }

    size_t glyphs = 0;
private:
    SkTextBlobBuilder builder;
    SkPoint pen = {0, 0};
};

// HarfBuzz through SkShaper, with Skia's ICU for the bidi and script runs and the
// platform font manager for the characters the strike's font lacks. Built once, on
// the render thread.
struct Shaping {
    sk_sp<SkUnicode> unicode;
    std::unique_ptr<SkShaper> shaper;
};

const Shaping &shaping() {
    static const Shaping instance = [] {
        Shaping s;
        s.unicode = SkUnicodes::ICU::Make();
        if (s.unicode) {
            s.shaper = SkShapers::HB::ShaperDrivenWrapper(s.unicode, SKPlatform_FontManager());
        }
        if (!s.shaper) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "SKBackend: Skia's shaper is unavailable (no ICU or HarfBuzz)");
        }
        return s;
    }();
    return instance;
}

} // namespace

void SKBackend_DrawShapedBySkia(jlong pScalerContext, jboolean smoothing, jfloat originX, jfloat originY,
                                const jchar *chars, jint length) {
    REQUIRE_CANVAS_TEXT();
    flushGlyphBatches();   // glyph-image draws queued before this text stay in order
    SkFont font;
    if (length <= 0 || !SKFontScaler_Font(pScalerContext, &font)) {
        return;
    }
    const Shaping &s = shaping();
    if (!s.shaper) {
        return;
    }
    if (smoothing) {
        font.setHinting(SkFontHinting::kNormal);   // as DrawGlyphsBySkia: the CoreText host's smoothed masks
    }
    const SkPaint paint = SKBackend_MakePaint(true);
    sk_sp<SkFontMgr> fallback = fallbackManager(font, pScalerContext, paint);
    applyGrayRoute(font, pScalerContext, paint);
    const SkString utf8 = SkUnicode::convertUtf16ToUtf8((const char16_t *) chars, length);
    // Paragraph direction left to right (the shaper reorders right-to-left runs within it);
    // Java's drawString takes it from the first strong character.
    auto fonts = SkShaper::MakeFontMgrRunIterator(utf8.c_str(), utf8.size(), font, fallback);
    auto bidi = SkShapers::unicode::BidiRunIterator(s.unicode, utf8.c_str(), utf8.size(), 0);
    auto script = SkShapers::HB::ScriptRunIterator(utf8.c_str(), utf8.size());
    auto language = SkShaper::MakeStdLanguageRunIterator(utf8.c_str(), utf8.size());
    if (!fonts || !bidi || !script || !language) {
        return;
    }
    // Java's drawString applies no kerning and no ligatures (TextAttribute.KERNING and
    // LIGATURES go through TextLayout, i.e. a glyph vector); HarfBuzz turns them on by
    // default, so they are switched off here to keep Java's line widths.
    static const SkShaper::Feature javaDefaults[] = {
        {SkSetFourByteTag('k', 'e', 'r', 'n'), 0, 0, ~0u},
        {SkSetFourByteTag('l', 'i', 'g', 'a'), 0, 0, ~0u},
        {SkSetFourByteTag('c', 'l', 'i', 'g'), 0, 0, ~0u},
        {SkSetFourByteTag('c', 'a', 'l', 't'), 0, 0, ~0u},
    };
    BaselineRunHandler handler;
    s.shaper->shape(utf8.c_str(), utf8.size(), *fonts, *bidi, *script, *language,
                    javaDefaults, std::size(javaDefaults), SK_ScalarMax, &handler);
    sk_sp<SkTextBlob> blob = handler.makeBlob();
    if (!blob) {
        return;
    }
    g.canvas->drawTextBlob(blob, originX, originY, paint);
    gGlyphsShapedBySkia.fetch_add((jlong) handler.glyphs, std::memory_order_relaxed);
    gGlyphsDrawnBySkia.fetch_add((jlong) handler.glyphs, std::memory_order_relaxed);
}

static std::atomic<jlong> gParagraphsBySkia{0};   // written by the flusher, read by any Java thread

jlong SKBackend_ParagraphsBySkia() {
    return gParagraphsBySkia.load(std::memory_order_relaxed);
}

namespace {

namespace tl = skia::textlayout;

// SkParagraph's font collection: the strikes' typefaces registered under an alias each
// (TextStyle selects fonts by family name), the platform font manager for fallback.
struct Paragraphs {
    sk_sp<tl::TypefaceFontProvider> provider;
    std::map<const SkFontMgr *, sk_sp<tl::FontCollection>> collections;   // by fallback manager
    std::unordered_set<SkTypefaceID> registered;

    const sk_sp<tl::FontCollection> &collection(const sk_sp<SkFontMgr> &fallback) {
        sk_sp<tl::FontCollection> &c = collections[fallback.get()];
        if (!c) {
            c = sk_make_sp<tl::FontCollection>();
            c->setAssetFontManager(provider);
            c->setDefaultFontManager(fallback);
            c->enableFontFallback();
        }
        return c;
    }
};

Paragraphs &paragraphs() {
    static Paragraphs instance = [] {
        Paragraphs p;
        p.provider = sk_make_sp<tl::TypefaceFontProvider>();
        return p;
    }();
    return instance;
}

SkString typefaceAlias(const SkTypeface *typeface) {
    return SkStringPrintf("jb-typeface-%u", (unsigned) typeface->uniqueID());
}

} // namespace

void SKBackend_DrawParagraphBySkia(jboolean smoothing, jfloat originX, jfloat originY, const jchar *chars, jint length,
                                   jint runCount, const jint *runEnds, const jlong *contexts, const jint *colours,
                                   const jint *flags) {
    REQUIRE_CANVAS_TEXT();
    flushGlyphBatches();
    const Shaping &s = shaping();
    if (length <= 0 || runCount <= 0 || !s.unicode) {
        return;
    }
    Paragraphs &p = paragraphs();
    tl::ParagraphStyle style;
    style.setTextAlign(tl::TextAlign::kLeft);
    style.setTextDirection(tl::TextDirection::kLtr);
    style.setApplyRoundingHack(false);
    const SkPaint paint = SKBackend_MakePaint(true);
    // the fallback manager of the first run decides for the paragraph (runs of other colours
    // get its luminance class for their fallback glyphs)
    sk_sp<SkFontMgr> fallback = SKPlatform_FontManager();
    {
        SkFont first;
        SkPaint firstPaint = paint;
        if (flags[0] & SK_PARAGRAPH_COLOUR) {
            firstPaint.setColor((SkColor) colours[0]);
        }
        if (SKFontScaler_Font(contexts[0], &first)) {
            fallback = fallbackManager(first, contexts[0], firstPaint);
        }
    }
    std::unique_ptr<tl::ParagraphBuilder> builder = tl::ParagraphBuilder::make(style, p.collection(fallback), s.unicode);
    if (!builder) {
        return;
    }
    jint start = 0;
    for (jint r = 0; r < runCount; r++) {
        const jint end = std::min(runEnds[r], length);
        if (end <= start) {
            continue;
        }
        SkFont font;
        if (!SKFontScaler_Font(contexts[r], &font) || !font.getTypeface()) {
            J2dRlsTraceLn(J2D_TRACE_WARNING, "SKBackend_DrawParagraphBySkia: run %d has no font, skipped", r);
            start = end;   // SKTextRenderer checked every strike; a missing font here is a disposed one
            continue;
        }
        if (smoothing) {
            font.setHinting(SkFontHinting::kNormal);
        }
        SkPaint runPaint = paint;
        runPaint.setAntiAlias(font.getEdging() != SkFont::Edging::kAlias);   // the decorations follow the text's edging
        if (flags[r] & SK_PARAGRAPH_COLOUR) {
            runPaint.setColor((SkColor) colours[r]);
        }
        applyGrayRoute(font, contexts[r], runPaint);
        sk_sp<SkTypeface> typeface = font.refTypeface();
        if (p.registered.insert(typeface->uniqueID()).second) {
            p.provider->registerTypeface(typeface, typefaceAlias(typeface.get()));
        }
        tl::TextStyle ts;
        ts.setFontFamilies({typefaceAlias(typeface.get())});
        ts.setFontSize(font.getSize());
        ts.setFontStyle(typeface->fontStyle());
        ts.setFontEdging(font.getEdging());
        ts.setSubpixel(font.isSubpixel());
        ts.setFontHinting(font.getHinting());
        // as DrawShapedBySkia: Java's drawString applies no kerning and no ligatures
        for (const char *feature : {"kern", "liga", "clig", "calt"}) {
            ts.addFontFeature(SkString(feature), 0);
        }
        ts.setForegroundPaint(runPaint);
        unsigned decoration = tl::kNoDecoration;
        if (flags[r] & SK_PARAGRAPH_UNDERLINE) {
            decoration |= tl::kUnderline;
        }
        if (flags[r] & SK_PARAGRAPH_STRIKETHROUGH) {
            decoration |= tl::kLineThrough;
        }
        ts.setDecoration((tl::TextDecoration) decoration);
        ts.setDecorationMode(tl::kThrough);   // Java's underline runs through the descenders
        ts.setDecorationStyle(tl::kSolid);
        ts.setDecorationColor(runPaint.getColor());
        builder->pushStyle(ts);
        const SkString utf8 = SkUnicode::convertUtf16ToUtf8((const char16_t *) chars + start, end - start);
        builder->addText(utf8.c_str(), utf8.size());
        builder->pop();
        start = end;
    }
    std::unique_ptr<tl::Paragraph> paragraph = builder->Build();
    if (!paragraph) {
        return;
    }
    paragraph->layout(1e9f);   // one line: no wrapping
    paragraph->paint(g.canvas, originX, originY - paragraph->getAlphabeticBaseline());
    gParagraphsBySkia.fetch_add(1, std::memory_order_relaxed);
    gGlyphsShapedBySkia.fetch_add(length, std::memory_order_relaxed);   // the character count stands in for the glyph count
    gGlyphsDrawnBySkia.fetch_add(length, std::memory_order_relaxed);
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
    SkCanvas *lcdCanvas = g.canvas;   // the coverage surface when accumulating
    bool lcdBegun = false;

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
            lcdBegun = false;
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
            // the destination, in the destination's gamma (blender), in two
            // passes without reading it, or accumulated in a coverage texture
            // first; in the latter two the paint carries the colour, which keys
            // the batch.
            if (!lcdPaintReady) {
                gLcdTwoPass.key = lcdContrast;
                switch (gConfig.lcdMode) {
                    case SKLcdMode::TwoPass:
                        lcdPaint.setColor(g.color);
                        break;
                    case SKLcdMode::Accumulate:
                        if (lcd == nullptr) {
                            lcd = glyphCache(true);
                        }
                        lcdCanvas = lcd != nullptr ? beginLcdAccumulation(lcd, lcdPaint, lcdContrast) : nullptr;
                        if (lcdCanvas == nullptr) {
                            lcd = nullptr;
                            continue;   // no coverage surface: the glyph is skipped
                        }
                        break;
                    case SKLcdMode::Blend:
                        lcdPaint.setColor(SK_ColorWHITE);
                        lcdPaint.setBlender(lcdBlender(lcdContrast));
                        break;
                }
                lcdPaintReady = true;
            }
            // the scaler's own variants (the platform's placement grid) take precedence over
            // the JDK's channel shift by thirds
            jint rowBytesOffset = 0;
            if (subPixPos && rx <= 1) {
                jint frac = (jint) ((gx - fx) * 3);
                if (frac != 0) {
                    rowBytesOffset = 3 - frac;
                    fx += 1;
                }
            }
            if (lcd == nullptr) {
                lcd = glyphCache(true);
            }
            if (lcd != nullptr && !lcdBegun) {
                SKGlyphCache_Begin(lcd, lcdCanvas, lcdPaint, gConfig.lcdMode == SKLcdMode::TwoPass ? &gLcdTwoPass : nullptr);
                lcdBegun = true;
            }
            if (rowBytesOffset == 0 && SKGlyphCache_Fits(lcd, ginfo)
                    && SKGlyphCache_Draw(lcd, (GlyphInfo *) ginfo, subimage, rgbOrder, fx, fy)) {
                continue;
            }
            flushBatches();
            drawLcdGlyphNoCache(ginfo, subimage, rowBytesOffset, rgbOrder, fx, fy, lcdPaint);
        }
    }
    // batches stay open for the next glyph list; see flushAtlasBatches()
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
    auto drawSpans = [&]() {   // the mesh so far, in device space
        g.canvas->save();
        g.canvas->resetMatrix();
        g.canvas->drawVertices(SkVertices::MakeCopy(SkVertices::kTriangles_VertexMode, (int) positions.size(), positions.data(),
                                                    texCoords.data(), nullptr, (int) indices.size(), indices.data()),
                               SkBlendMode::kModulate, paint);
        g.canvas->restore();
        positions.clear(); texCoords.clear(); indices.clear();
    };
    if (toDevice.rectStaysRect()) {
        SkRect dev = toDevice.mapRect(dstRect);
        const float l = std::ceil(dev.fLeft - 0.5f), t = std::ceil(dev.fTop - 0.5f);
        const float r = std::ceil(dev.fRight - 0.5f), b = std::ceil(dev.fBottom - 0.5f);
        if (r > l && b > t) addSpanQuad(l, t, r, b);
    } else {
        convexQuadSpans(quad, [&](jint y, jint l, jint r) {
            if (r > l) addSpanQuad(l, y, r, y + 1);
            if (positions.size() + 4 > 0xffff) {   // 16-bit indices
                drawSpans();
            }
        });
    }
    if (!indices.empty()) {
        drawSpans();
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
        if (src != nullptr && !sourceUsable(src, "SKBackend_Blit")) {
            return;
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
    flushBatchesOf(cc);
    cc->recordingGeneration++;
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
