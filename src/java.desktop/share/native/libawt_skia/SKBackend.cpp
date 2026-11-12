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
#include "SKImageAtlas.h"
#include "SKQuadBatch.h"
#include "SKPipelineCache.h"
#include "SKStats.h"

#include "jlong.h"
#include "jni_util.h"
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
#include "include/core/SkTraceMemoryDump.h"
#include <map>
#include <chrono>
#include <string>
#include <cstring>
#include "include/core/SkPixmap.h"
#include "include/core/SkRect.h"
#include "include/core/SkSamplingOptions.h"
#include "include/core/SkRRect.h"
#include "include/core/SkPathUtils.h"
#include "include/core/SkSpan.h"
#include "include/core/SkTextBlob.h"
#include "include/core/SkMaskFilter.h"
#include "include/core/SkBlurTypes.h"
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
#include "include/core/SkSurfaceProps.h"
#include "include/gpu/graphite/Surface.h"

#include <algorithm>
#include <atomic>
#include <cstdlib>
#include <fstream>
#include <mutex>
#include <thread>
#include <cmath>
#include <cstring>
#include <map>
#include <list>
#include <string>
#include <string_view>
#include <unordered_map>
#include <unordered_set>
#include <array>
#include <cfloat>
#include <climits>
#include <limits>
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
    size_t glyphAtlasBytes = 0;   // Graphite's glyph-atlas budget, 0 = Skia's default
    jint glyphCacheTexels = 1024;  // the side of SKGlyphCache's textures (see SKEnvironment.glyphCacheTexels)
    jint luminanceClasses = 16;    // FontUtilities.textLuminanceClasses: the cg route's luminance classes
    bool presentPerCycle = false;   // sun.java2d.skia.present=cycle: present on FLUSH_BUFFER, SYNC and the timeout flush only
    bool submitPerPresent = false;  // sun.java2d.skia.submit=present: submit only for a present, a wait or the cap
    SKLcdOrder lcdOrder = SKLcdOrder::System;   // sun.java2d.skia.lcdOrder: the sub-pixel order the surfaces declare
    bool imageAtlas = true;           // sun.java2d.skia.imageAtlas: small image sources drawn from an atlas (SKImageAtlas.h)
} gConfig;

SkPixelGeometry SKBackend_LcdPixelGeometry() {
    const bool bgr = gConfig.lcdOrder == SKLcdOrder::BGR
                     || (gConfig.lcdOrder == SKLcdOrder::System && SKPlatform_TextDefaults().lcdOrderBGR);
    return bgr ? kBGR_H_SkPixelGeometry : kRGB_H_SkPixelGeometry;
}

static SKQuadBatch gRects;   // non-AA geometry of the current paint, drawn as one mesh (see SKQuadBatch.h)

// Graphics pipelines Graphite has built so far, over all contexts (flusher thread only).
static uint64_t gPipelinesBuilt = 0;

namespace {

// Offers the context's pipeline data to sun.java2d.skia.pipelineCache (Graphite's
// native Vulkan backend has some, Dawn and Metal none): every 10 s and on dispose.
void syncPipelineData(SKConfigContext *cc, bool force) {
    if (!cc->context || !SKPipelineCache_Enabled()) {
        return;
    }
    const uint64_t now = SKStats_Now();
    if (force || now - cc->lastPipelineSync > 10ull * 1000 * 1000 * 1000) {
        cc->lastPipelineSync = now;
        cc->context->syncPipelineData(16u << 20);
    }
}

} // namespace

static void pipelineCacheEvent(ContextOptions::PipelineCallbackContext, ContextOptions::PipelineCacheOp op,
                               const std::string &label, uint32_t keyHash, bool fromPrecompile, sk_sp<SkData> key) {
    const bool added = op == ContextOptions::PipelineCacheOp::kAddingPipeline;
    if (added) {
        gPipelinesBuilt++;
    }
    SKStats_PipelineEvent(added, fromPrecompile, label.c_str(), keyHash);
}

void SKBackend_Configure(jint msaaSamples, bool edgeAAQuads, SKLcdMode lcdMode, bool framebufferFetch, size_t glyphAtlasBytes,
                         jint glyphCacheTexels, jint luminanceClasses, bool presentPerCycle,
                         bool submitPerPresent, SKLcdOrder lcdOrder, bool imageAtlas) {
    gConfig.imageAtlas = imageAtlas;
    gConfig.submitPerPresent = submitPerPresent;
    gConfig.lcdOrder = lcdOrder;
    gConfig.presentPerCycle = presentPerCycle;
    if (luminanceClasses >= 1 && luminanceClasses <= 256 && (luminanceClasses & (luminanceClasses - 1)) == 0) {
        gConfig.luminanceClasses = luminanceClasses;
    }
    gConfig.msaaSamples = msaaSamples;
    gConfig.edgeAAQuads = edgeAAQuads;
    gConfig.lcdMode = lcdMode;
    gConfig.framebufferFetch = framebufferFetch;
    gConfig.glyphAtlasBytes = glyphAtlasBytes;
    if (glyphCacheTexels >= 32 && glyphCacheTexels <= 16384 && glyphCacheTexels % 32 == 0) {
        gConfig.glyphCacheTexels = glyphCacheTexels;
    }
}

jint SKBackend_GlyphCacheTexels() {
    return gConfig.glyphCacheTexels;
}

jint SKBackend_LuminanceClasses() {
    return gConfig.luminanceClasses;
}

size_t SKBackend_GlyphAtlasBudget() {
    return gConfig.glyphAtlasBytes != 0 ? gConfig.glyphAtlasBytes : ContextOptions().fGlyphCacheTextureMaximumBytes;
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
    if (gConfig.glyphAtlasBytes != 0) {
        options.fGlyphCacheTextureMaximumBytes = gConfig.glyphAtlasBytes;
    }
    // Every pipeline Graphite builds or finds: the count and the labels of the built ones,
    // for J2D_SKIA_STATS (compile waits) and as the input of a precompile list.
    options.fPipelineCachingCallback = pipelineCacheEvent;
    // sun.java2d.skia.pipelineCache for the backends Graphite caches for itself (Vulkan); one file,
    // Graphite checks vendor, device and cache UUID before using it.
    options.fPersistentPipelineStorage = SKPipelineCache_Storage("graphite");
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
    syncPipelineData(cc, true);
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
    SKImageAtlas_Free(cc->imageAtlas);
    for (int b = 0; b < SK_GLYPH_BUCKETS; b++) {
        SKGlyphCache_Free(cc->glyphCachesAA[b]);
        SKGlyphCache_Free(cc->glyphCachesLCD[b]);
    }
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
        static uint64_t surfaceUids = 0;   // under the entry mutex (SKBackend_EntryMutex)
        ops->state = new SKSurfaceState();
        ops->state->uid = ++surfaceUids;
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
    // The surface's pixel geometry: without one Skia's glyph painter downgrades every LCD request
    // to grayscale before Graphite's LCD text step (A565 masks) is reached. Horizontal stripes in
    // the display's sub-pixel order (the desktop hint's LCD_HRGB or LCD_HBGR, or the property's);
    // a draw whose own LCD hint names the other order gets the surface's — a geometry is a
    // surface's, not a draw's (SkiaTextPathLcdTest).
    const SkSurfaceProps props(0, SKBackend_LcdPixelGeometry());
    st->surface = SkSurfaces::WrapBackendTexture(cc->recorder.get(), bt, colorType, nullptr, &props);
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
    ops->state->presentTargets.clear();
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

    std::unordered_set<SKSDOps *> touchedWindows;     // drawn into and not presented yet
    std::unordered_set<SKSDOps *> requestedWindows;   // whose paint cycle ended in this flush (FLUSH_BUFFER)
    std::unordered_set<SKConfigContext *> touchedContexts;
};

// sun.java2d.skia.submit=present: contexts whose recordings were inserted but not
// submitted, in insertion order. All contexts of an adapter share one queue, so a
// later submit of another context must not overtake them: every submit sends these
// first (submitPending). Bounded by MAX_DEFERRED_FLUSHES and the flusher's timeout.
static std::vector<SKConfigContext *> gPendingSubmit;
static int gDeferredFlushes = 0;
constexpr int MAX_DEFERRED_FLUSHES = 16;   // upload buffers and scratch textures recycle only after a submit
static void submitPending(SKConfigContext *except, bool syncToCpu);

static RenderState g;

static SKConfigContext *currentContext() {
    return g.cc;
}

static void forgetContext(SKConfigContext *cc) {
    g.touchedContexts.erase(cc);
    gPendingSubmit.erase(std::remove(gPendingSubmit.begin(), gPendingSubmit.end(), cc), gPendingSubmit.end());
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
    g.requestedWindows.erase(ops);
}

// The canvas clip and transform follow the render state as it changes: a change draws the pending
// batches first (their quads were added under the previous state), then applies the new one.
static void applyCanvasClip() {
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

static void applyCanvasState() {
    if (g.canvas == nullptr) {
        return;
    }
    SKStats_SetDrawCause(SK_COUNT_MESH_DRAWS_AT_STATE);
    flushAllBatches();
    SKStats_SetDrawCause(SK_COUNT_COUNT);
    applyCanvasClip();
}

static void markTouched() {
    if (g.cc != nullptr) {
        g.touchedContexts.insert(g.cc);
    }
    if (g.dst != nullptr && g.dst->state != nullptr && g.cc != nullptr) {
        g.dst->state->drawnSinceBlit = true;
        g.dst->state->drawnGeneration = g.cc->recordingGeneration;
        g.dst->state->contentVersion++;
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
    SKStatsScope uploadStats(SK_SPAN_IMAGE_UPLOAD);
    sk_sp<SkImage> raster = SkImages::RasterFromPixmap(pm, nullptr, nullptr);
    return raster ? SkImages::TextureFromImage(g.cc->recorder.get(), raster.get()) : nullptr;
}

void SKBackend_SetDestination(SKSDOps *dst) {
    // The same destination again, its texture unchanged: a redundant state change. BufferedContext.validate sends
    // SET_SURFACES for a new source as well (before a blit of another image, and after it), and sends the clip and
    // the rest of the render state again only for a new destination, so the canvas, its clip and the pending
    // batches stay as they are.
    if (dst != nullptr && dst == g.dst && g.canvas != nullptr && dst->state != nullptr && dst->state->surface
            && dst->state->cc == g.cc && dst->platform != nullptr
            && dst->state->wrappedTexture == SKPlatform_TextureHandle(dst->platform)) {
        return;
    }
    // Another destination: the pending batches belong to the canvas being left; they are drawn, and the render
    // state is applied to the new canvas.
    SKStats_SetDrawCause(SK_COUNT_MESH_DRAWS_AT_SURFACE);
    flushAllBatches();
    SKStats_SetDrawCause(SK_COUNT_COUNT);
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

SkBlendMode SKBackend_BlendModeForRule(jint rule) { return blendModeForRule(rule); }

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

SkTileMode SKBackend_TileModeFor(jint cycleMethod) { return tileModeFor(cycleMethod); }
sk_sp<SkColorSpace> SKBackend_StopColorSpace(bool linear) { return stopColorSpace(linear); }
float SKBackend_RepeatSeamBias() { return REPEAT_SEAM_BIAS; }

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

// A Skia surface's image for sampling in the current recording (a blit's source, a texture paint's image):
// a view linked to the surface's device when it has one on this context, so Graphite records the surface's
// pending draws — a cached texture's upload among them — ahead of the reads, and a later draw into it after
// them; a bare WrapTexture of the same texture carries no such dependency and may read it before the upload.
// Otherwise (no device yet, another context's surface) the texture wrapped.
static sk_sp<SkImage> sampledImage(SKSDOps *src, bool *linked) {
    *linked = src != nullptr && src->state != nullptr && src->state->surface && src->state->cc == g.cc
              && src->platform != nullptr && src->state->wrappedTexture == SKPlatform_TextureHandle(src->platform);
    return *linked ? SkSurfaces::AsImage(src->state->surface) : SKBackend_GetImage(src);
}

static bool wholeTextureStep(double increment, int size);

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
    // the image steps per device pixel, in texels, as TexturePaintContext walks them
    const int exactAxes = (wholeTextureStep(xp0 * image->width(), image->width()) && wholeTextureStep(xp1 * image->width(), image->width()) ? 1 : 0)
                        | (wholeTextureStep(yp0 * image->height(), image->height()) && wholeTextureStep(yp1 * image->height(), image->height()) ? 2 : 0);
    SKBackend_TexturePaintSampling(&local, filter, exactAxes);
    SkSamplingOptions sampling = filter ? SkSamplingOptions(SkFilterMode::kLinear) : SkSamplingOptions(SkFilterMode::kNearest);
    g.shader = atPixelOrigin(image->makeShader(SkTileMode::kRepeat, SkTileMode::kRepeat, sampling, &local));
}

// Whether TexturePaintContext steps an image axis by whole texels (its fractAsInt of the increment modulo the
// image size is 0); the plane equation reaches here in another order of operations than the context's, so a
// whole step may arrive a few ulps off one.
static bool wholeTextureStep(double increment, int size) {
    double mod = std::fmod(increment, (double) size);
    if (mod < 0) mod += size;
    const double fraction = mod - std::floor(mod);
    return fraction < 1e-9 || fraction > 1 - 1e-9;
}

// TexturePaintContext's sampling (SKBackend.h), on a local matrix that maps image texels to device pixels for a
// shader evaluated at the pixel's corner (the raster pipe's postTranslate, the GPU pipe's atPixelOrigin):
// filtering, the context blends a texel with its right and lower neighbours by the point's fractions, so its
// texel centres sit at integer coordinates, half a texel off Skia's; nearest, sampling the corner puts texel
// boundaries exactly on sample points, where Skia takes the texel below and the context the one above along an
// axis it steps exactly, the one below along an axis whose truncated 31-bit fixed-point steps run short. The
// point moves a 1024th of a texel to the context's side (any bias from 1/10000 on decides a tie). Left over:
// the first pixel of each of the loops' 32-pixel paint tiles, where the context starts exactly on an inexact axis.
void SKBackend_TexturePaintSampling(SkMatrix *local, bool filter, int exactAxes) {
    constexpr float kTieBias = 1.0f / 1024;
    if (filter) {
        local->preTranslate(-0.5f, -0.5f);
    } else {
        local->preTranslate((exactAxes & 1) ? -kTieBias : kTieBias, (exactAxes & 2) ? -kTieBias : kTieBias);
    }
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
    bool linked;
    sk_sp<SkImage> image = sampledImage(skSrc, &linked);
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
static void flushGlyphCaches(SKConfigContext *cc, bool aa = true, bool lcd = true);

// Every bucket's batch of a context: grayscale, LCD, or both.
static void flushGlyphCaches(SKConfigContext *cc, bool aa, bool lcd) {
    if (cc != nullptr) {
        for (int b = 0; b < SK_GLYPH_BUCKETS; b++) {
            if (aa) {
                SKGlyphCache_Flush(cc->glyphCachesAA[b]);
            }
            if (lcd) {
                SKGlyphCache_Flush(cc->glyphCachesLCD[b]);
            }
        }
    }
}

static void flushGlyphBatches() {
    flushGlyphCaches(g.cc);
}

// The glyph and Marlin tile batches of a context, then the rect batch.
static void flushBatchesOf(SKConfigContext *cc) {
    SKStatsScope batchStats(SK_SPAN_BATCHES);
    if (cc != nullptr) {
        flushGlyphCaches(cc);
        SKMaskCache_Flush(cc->maskCache);
        SKImageAtlas_Flush(cc->imageAtlas);
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


#define REQUIRE_CANVAS() if (g.canvas == nullptr) { return; } markTouched(); { SKDrawCauseScope cause_(SK_COUNT_MESH_DRAWS_AT_CANVAS_OP); flushAllBatches(); }

// Before a batched op of one kind (rects and lines 0, glyphs 1, Marlin's coverage tiles 2, image atlas sprites 3): the
// other kinds' pending batches are drawn first, so the batches pending at any time are of one kind and draw in Java's
// order, as in any in-order batching renderer.
static void drawOtherKinds(int kind) {
    SKStats_SetDrawCause(SK_COUNT_MESH_DRAWS_AT_KIND);
    if (kind != 0) {
        gRects.flush();
    }
    if (kind != 1) {
        flushGlyphBatches();
    }
    if (kind != 2 && g.cc != nullptr) {
        SKMaskCache_Flush(g.cc->maskCache);
    }
    if (kind != 3 && g.cc != nullptr) {
        SKImageAtlas_Flush(g.cc->imageAtlas);
    }
    SKStats_SetDrawCause(SK_COUNT_COUNT);
}

#define REQUIRE_CANVAS_TEXT() if (g.canvas == nullptr) { return; } markTouched(); drawOtherKinds(1)
#define REQUIRE_CANVAS_MASK() if (g.canvas == nullptr) { return; } markTouched(); drawOtherKinds(2)
#define REQUIRE_CANVAS_RECT() if (g.canvas == nullptr) { return; } markTouched(); drawOtherKinds(0)

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

// A diagonal 1-pixel line as one quad: per pixel column (row, for a y-major
// line) a band one pixel high centred on the ideal line through the end
// points' pixel centres, so the rasterizer's pixel-centre rule picks the pixel
// nearest to the line in every column, as the Bresenham stepping does. The
// band is shifted towards the end point by a quarter of the smallest distance
// a pixel centre can have from it, so a tie (the line half-way between two
// pixel centres) goes the way the error term sends it. Exact while that
// distance stays above the rasterizer's vertex precision (1/256 px on D3D11):
// to a major extent of about 128 px; past it an occasional pixel moves by one
// across the line (LineConformance: 3.5 % of lines at 256 px, a few pixels
// each), still far closer to the loops than the line primitives of the other
// pipelines. One quad instead of one per run: a long diagonal costs what a
// short one does.
static void lineAsBand(jint x1, jint y1, jint x2, jint y2, bool shorten, bool xMajor, jint amajor) {
    // the major-axis extent in pixel edges; the end point dropped if shortened
    const jint m1 = xMajor ? x1 : y1, m2 = xMajor ? x2 : y2, step = (m2 > m1) ? 1 : -1;
    const jint last = shorten ? m2 - step : m2;
    if ((last - m1) * step < 0) {
        return;
    }
    const float lo = (float) std::min(m1, last), hi = (float) std::max(m1, last) + 1.0f;
    const jint dmajor = xMajor ? x2 - x1 : y2 - y1, dminor = xMajor ? y2 - y1 : x2 - x1;
    const float slope = (float) dminor / (float) dmajor;
    const float bias = (dminor > 0 ? 1.0f : -1.0f) / (4.0f * (float) amajor);
    const float minor1 = (float) (xMajor ? y1 : x1) + 0.5f, major1 = (float) m1 + 0.5f;
    const float a = minor1 + (lo - major1) * slope + bias, b = minor1 + (hi - major1) * slope + bias;
    SkPoint pts[4];
    if (xMajor) {
        pts[0] = {lo, a - 0.5f}; pts[1] = {hi, b - 0.5f}; pts[2] = {hi, b + 0.5f}; pts[3] = {lo, a + 0.5f};
    } else {
        pts[0] = {a - 0.5f, lo}; pts[1] = {a + 0.5f, lo}; pts[2] = {b + 0.5f, hi}; pts[3] = {b - 0.5f, hi};
    }
    gRects.addQuad(pts);
}

// Java2D's 1-pixel lines: the Bresenham stepping of the software loops
// (SETUP_BRESENHAM in DrawLine.c and the DRAWLINE loop), both endpoints
// inclusive unless `shorten` drops the pixel at (x2, y2) as DrawPolygons.c does
// for the inner joints of a polyline. Horizontal and vertical lines are one
// rect; diagonal ones one quad (lineAsBand), except under MSAA, which would
// cover the quad's slanted edges partially: there, and only there, the pixels
// are emitted as horizontal or vertical runs, one rect per run, as the other
// non-aligned non-AA geometry is decomposed into spans under MSAA.
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
    if (!SKBackend_Multisampled()) {
        lineAsBand(x1, y1, x2, y2, shorten, xMajor, xMajor ? ax : ay);
        return;
    }
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
    // The given parallelogram is the stroke centerline (see MTLRenderer_DrawParallelogram):
    // grow it by half the line width for the outer edge, shrink it for the inner one.
    float ldx21 = dx21 * lwr21, ldy21 = dy21 * lwr21;
    float ldx12 = dx12 * lwr12, ldy12 = dy12 * lwr12;
    float ox11 = x11 - (ldx21 + ldx12) / 2.0f, oy11 = y11 - (ldy21 + ldy12) / 2.0f;
    if (aa) { REQUIRE_CANVAS(); } else { REQUIRE_CANVAS_RECT(); }
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
        { SKDrawCauseScope cause(SK_COUNT_MESH_DRAWS_AT_DIRECT); flushAllBatches(); }   // drawn through the canvas: every pending batch first, of any kind
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
    { SKDrawCauseScope cause(SK_COUNT_MESH_DRAWS_AT_DIRECT); flushAllBatches(); }    // the rest draws through the canvas: every pending batch first, of any kind
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
// fixed-function passes, kModulate with (1 - cov) and an addition of Cs * cov,
// which is how DirectWrite composites ClearType (dual-source blending does both
// at once; Graphite uses it for its own LCD text only, no mesh or runtime effect has a second
// output, 2026-11-03). The destination's gamma cannot be
// applied without reading it; instead the coverage is corrected so that the
// linear blend reproduces the gamma blend on a white and on a black
// background, mixed by the channel's text value (dark text is mostly read on
// light backgrounds): exact for black on white and white on black, up to about
// fifteen levels off at glyph edges for light text on dark mid-tones. The
// correction depends on the coverage and the text colour only, so it is a
// 256-entry table per channel, built once per colour and contrast and sampled
// as a 256x1 texture (lcdTable). The coverage arrives in the atlas' RGB
// channels. The addition is kSrcOver with a source alpha of 0 (Cs * cov + Cd), not kPlus: Graphite
// blends kPlus in the shader wherever the destination is cheap to read (Vulkan's input attachment,
// Metal's framebuffer fetch; ContextUtils.cpp, CanUseHardwareBlending), which cost a destination read
// per draw and lost the second pass of overlap layers on Vulkan (2026-10-28); kSrcOver without
// coverage is fixed-function everywhere, and the unorm destination clamps the sum as kPlus does.
// Both passes leave the destination's alpha (1) as it is over the whole quad and ignore the
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
            "half4 main(float2 xy) { return half4(src * coverage(xy), 0); }",   // alpha 0: kSrcOver adds
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
    SkBlendMode::kModulate,
    SkBlendMode::kSrcOver,   // the second shader's alpha is 0: an addition (above)
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
        flushGlyphCaches(g.cc, false, true);   // a pending batch draws into the old scratch
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

// A glyph list accumulates: the LCD caches' batches draw into the coverage
// surface, clear its bounds first and composite them afterwards
// (hookLcdAccumulation on each bucket's batch).
static SkCanvas *beginLcdAccumulation(SkPaint &lcdPaint, jint lcdContrast) {
    SkCanvas *scratch = lcdCoverageCanvas();
    if (scratch == nullptr) {
        return nullptr;
    }
    if (gLcdAccumulate.contrast != lcdContrast || gLcdAccumulate.dst != g.canvas || gLcdAccumulate.cc != g.cc) {
        flushGlyphCaches(g.cc, false, true);   // neither is part of the batch key
        gLcdAccumulate.contrast = lcdContrast;
        gLcdAccumulate.dst = g.canvas;
        gLcdAccumulate.cc = g.cc;
    }
    lcdPaint.setColor(g.color);   // the key; the atlas shader ignores it
    lcdPaint.setBlendMode(SkBlendMode::kScreen);
    return scratch;
}

static void hookLcdAccumulation(SKGlyphCache *lcd) {
    SKQuadBatch &batch = SKGlyphCache_Batch(lcd);
    if (!batch.afterDraw) {
        batch.beforeDraw = [](const SkRect &bounds) { clearLcdCoverage(gLcdAccumulate.cc, bounds); };
        batch.afterDraw = [](const SkRect &bounds, const SkPaint &paint) { compositeLcdCoverage(bounds, paint); };
    }
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

// The bucket's cache for a glyph image (SKGlyphCache_BucketFor), created on first use;
// nullptr if the image exceeds the largest cell or the creation failed. The configured
// side (SKBackend_GlyphCacheTexels) is the 32-pixel bucket's; the 16-pixel bucket has
// half the side (the same cell count), the larger buckets the same side or whatever
// holds 256 cells (a font's glyphs times their sub-pixel variants), whichever is more.
static SKGlyphCache *glyphCache(bool lcd, const GlyphInfo *ginfo) {
    const int b = SKGlyphCache_BucketFor(ginfo);
    if (b < 0 || g.cc == nullptr) {
        return nullptr;
    }
    SKGlyphCache *&slot = lcd ? g.cc->glyphCachesLCD[b] : g.cc->glyphCachesAA[b];
    bool &failed = lcd ? g.cc->glyphCachesLCDFailed[b] : g.cc->glyphCachesAAFailed[b];
    if (slot == nullptr && !failed) {
        const jint cell = SK_GLYPH_BUCKET_CELLS[b];
        const jint configured = SKBackend_GlyphCacheTexels();
        const jint side = cell < 32 ? configured / 2 : std::max(configured, cell * 16);
        slot = SKGlyphCache_Create(g.cc, lcd, cell, side);
        failed = slot == nullptr;
    }
    return slot;
}

// Glyphs too large for a cache cell, or LCD glyphs shifted by a third of a
// pixel, are uploaded and drawn individually (MTLTextRenderer's NoCache paths).
static void drawGlyphNoCache(const GlyphInfo *ginfo, jint subimage, float x, float y, const SkPaint &paint) {
    { SKDrawCauseScope cause(SK_COUNT_MESH_DRAWS_AT_DIRECT); flushAllBatches(); }    // drawn through the canvas, not batched: every pending batch first, of any kind
    SKGlyphCache_CountStat(SK_GLYPHS_UNCACHED);
    const unsigned char *data = ginfo->image + (size_t) ginfo->rowBytes * ginfo->height * subimage;
    SkPixmap pm(SkImageInfo::MakeA8(ginfo->width, ginfo->height), data, ginfo->rowBytes);
    if (sk_sp<SkImage> cov = uploadPixmap(pm)) {
        g.canvas->drawImage(cov, x, y, SkSamplingOptions(SkFilterMode::kNearest), &paint);
    }
}

// A grayscale glyph under a blend other than SrcOver (translucent Src, Clear, ...) or a paint's shader: the glyph image is the coverage
// (a clip shader), the glyph's box filled with the paint through it, so the destination is lerped towards the blend's
// result by the glyph's coverage — MaskFill's meaning, as the software loops have it. The atlas batches draw the image's
// alpha as the colour's, which under Src or Clear would replace or clear the whole box.
static void drawGlyphAsCoverage(const GlyphInfo *ginfo, jint subimage, float x, float y, const SkPaint &paint) {
    { SKDrawCauseScope cause(SK_COUNT_MESH_DRAWS_AT_DIRECT); flushAllBatches(); }
    SKGlyphCache_CountStat(SK_GLYPHS_UNCACHED);
    const unsigned char *data = ginfo->image + (size_t) ginfo->rowBytes * ginfo->height * subimage;
    SkPixmap pm(SkImageInfo::MakeA8(ginfo->width, ginfo->height), data, ginfo->rowBytes);
    sk_sp<SkImage> cov = uploadPixmap(pm);
    if (!cov) {
        return;
    }
    SkAutoCanvasRestore restore(g.canvas, true);
    g.canvas->clipShader(cov->makeShader(SkTileMode::kDecal, SkTileMode::kDecal, SkSamplingOptions(SkFilterMode::kNearest),
                                         SkMatrix::Translate(x, y)));
    SkPaint box = paint;
    box.setAntiAlias(false);
    g.canvas->drawRect(SkRect::MakeXYWH(x, y, (float) ginfo->width, (float) ginfo->height), box);
}

static void drawLcdGlyphNoCache(const GlyphInfo *ginfo, jint subimage, jint rowBytesOffset, jboolean rgbOrder,
                                float x, float y, const SkPaint &paint) {
    { SKDrawCauseScope cause(SK_COUNT_MESH_DRAWS_AT_DIRECT); flushAllBatches(); }    // drawn through the canvas, not batched: every pending batch first, of any kind
    SKGlyphCache_CountStat(SK_GLYPHS_UNCACHED);
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

// Colour glyphs (emoji) under a rule other than SrcOver: their alpha is the coverage and their own colours, at the
// composite's extra alpha, the source — as for the outline glyphs, whose masks Skia takes as coverage: the destination
// lerped towards the rule's result, dst' = mix(dst, B(s, dst), a), MaskFill's meaning. Drawn as images under such a
// rule, a colour glyph applied it to its whole box (Src replaced it, Clear cleared it, SrcIn cleared around the glyph).
// A runtime blender recovers the coverage and the colour from what Skia hands it: the image's premultiplied colour
// under the paint's alpha (the extra alpha), src = (c * a * ea, a * ea).
static bool splitsColourGlyphs(const SkPaint &paint) {
    const std::optional<SkBlendMode> mode = paint.asBlendMode();
    return mode.has_value() && *mode != SkBlendMode::kSrcOver && (int) *mode <= (int) SkBlendMode::kXor;   // the twelve AlphaComposite rules
}

static sk_sp<SkBlender> colourGlyphBlender(SkBlendMode mode, float extraAlpha) {
    static const SkRuntimeEffect *effect = [] {
        auto result = SkRuntimeEffect::MakeForBlender(SkString(R"(
            uniform float ea;
            uniform float rule;   // SkBlendMode's Porter-Duff modes, kClear (0) to kXor (11)
            half4 main(half4 src, half4 dst) {
                float4 d = float4(dst);
                float a = ea > 0 ? float(src.a) / ea : 0;          // the glyph's coverage
                if (a <= 0) {
                    return dst;
                }
                float4 sc = float4(float3(src.rgb) / a, ea);       // its colour at the extra alpha, premultiplied
                float4 b;
                int r = int(rule + 0.5);
                if      (r == 0)  b = float4(0);                                    // Clear
                else if (r == 1)  b = sc;                                           // Src
                else if (r == 2)  b = d;                                            // Dst
                else if (r == 3)  b = sc + d * (1 - sc.a);                          // SrcOver
                else if (r == 4)  b = d + sc * (1 - d.a);                           // DstOver
                else if (r == 5)  b = sc * d.a;                                     // SrcIn
                else if (r == 6)  b = d * sc.a;                                     // DstIn
                else if (r == 7)  b = sc * (1 - d.a);                               // SrcOut
                else if (r == 8)  b = d * (1 - sc.a);                               // DstOut
                else if (r == 9)  b = sc * d.a + d * (1 - sc.a);                    // SrcATop
                else if (r == 10) b = d * sc.a + sc * (1 - d.a);                    // DstATop
                else              b = sc * (1 - d.a) + d * (1 - sc.a);              // Xor
                return half4(mix(d, b, a));
            }
        )"));
        if (!result.effect) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "SKBackend: colour-glyph blender does not compile: %s", result.errorText.c_str());
        }
        return result.effect.release();
    }();
    if (effect == nullptr) {
        return nullptr;
    }
    const float uniforms[2] = {extraAlpha, (float) (int) mode};
    return effect->makeBlender(SkData::MakeWithCopy(uniforms, sizeof(uniforms)));
}

// The colour glyphs' paint: their own colours at the extra alpha, under the coverage blender for a rule other than
// SrcOver; on a raster surface's canvas (BGRA memory declared N32, every colour of ours swizzled for it) their pixels,
// which come from the font host as they are, get red and blue swapped too.
static SkPaint colourGlyphPaint(const SkPaint &paint, float extraAlpha, bool swizzled) {
    SkPaint p = paint;
    p.setShader(nullptr);
    p.setColor4f(SkColor4f{1, 1, 1, extraAlpha});   // the glyph's own colours, at the extra alpha
    if (splitsColourGlyphs(paint)) {
        if (sk_sp<SkBlender> blender = colourGlyphBlender(*paint.asBlendMode(), extraAlpha)) {
            p.setBlender(std::move(blender));
        }
    }
    if (swizzled) {
        static const float swap[20] = {0, 0, 1, 0, 0,   0, 1, 0, 0, 0,   1, 0, 0, 0, 0,   0, 0, 0, 1, 0};
        p.setColorFilter(SkColorFilters::Matrix(swap));
    }
    return p;
}

static void drawColorGlyph(const GlyphInfo *ginfo, float x, float y) {
    { SKDrawCauseScope cause(SK_COUNT_MESH_DRAWS_AT_DIRECT); flushAllBatches(); }    // drawn through the canvas, not batched: every pending batch first, of any kind
    SkPixmap pm(SkImageInfo::Make(ginfo->width, ginfo->height, kBGRA_8888_SkColorType, kPremul_SkAlphaType),
                ginfo->image, ginfo->rowBytes);
    if (sk_sp<SkImage> img = uploadPixmap(pm)) {
        SkPaint p;
        applyComposite(p, false);
        p.setAlphaf(g.extraAlpha);
        if (splitsColourGlyphs(p)) {
            p = colourGlyphPaint(p, g.extraAlpha, false);   // the glyph's alpha as coverage (see colourGlyphPaint)
        }
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
// classes, FontUtilities.textLuminanceClasses: 16 by default, the class representative at
// its middle), so the masks in Skia's strike and atlas are CoreGraphics' smoothed rendering
// for that colour. With one class the luminance dependence is off and every colour gets the
// strike's fixed luminance (SKFontScaler.GRAY_LUMINANCE). FontUtilities.luminanceClass()
// computes the same class on the Java side; keep the two in step.
static int luminanceClass(SkColor c, jlong pScalerContext) {   // c: the text colour as Java sees it (not a swizzled raster colour)
    if (gConfig.luminanceClasses <= 1) {
        return SKFontScaler_GrayLuminance(pScalerContext);
    }
    const int luminance = (SkColorGetR(c) * 299 + SkColorGetG(c) * 587 + SkColorGetB(c) * 114) / 1000;
    // as FontUtilities.luminanceClass: representatives 0, 255/(N-1), ..., 255 — black and white exact
    const int steps = gConfig.luminanceClasses - 1;
    const int cls = (luminance * steps + 127) / 255;
    return (cls * 255 + steps / 2) / steps;
}

// The proxy a font's typeface is drawn through on Skia's text path (SKLuminanceTypeface): the luminance class of the
// colour under the gray route (blend system, antialiased), SK_OUTLINE_LCD for LCD text where Skia rasterizes it from
// the outlines (SKPlatform_LcdFromOutlines), else NO_PROXY.
constexpr int NO_PROXY = INT_MIN;

static int proxyClass(const SkFont &font, jlong pScalerContext, SkColor textColour) {
    if (font.getTypeface() == nullptr) {
        return NO_PROXY;
    }
    if (font.getEdging() == SkFont::Edging::kSubpixelAntiAlias) {
        return SKPlatform_LcdFromOutlines() ? SK_OUTLINE_LCD : NO_PROXY;
    }
    return SKFontScaler_BlendMode(pScalerContext) == sun_font_SKFontScaler_BLEND_SYSTEM && font.getEdging() == SkFont::Edging::kAntiAlias
           ? luminanceClass(textColour, pScalerContext) : NO_PROXY;
}

static void applyProxy(SkFont &font, jlong pScalerContext, SkColor textColour) {
    const int proxy = proxyClass(font, pScalerContext, textColour);
    if (proxy != NO_PROXY) {
        font.setTypeface(SKLuminanceTypeface_For(font.refTypeface(), proxy));
    }
}

// The typefaces behind the shaper's and the paragraph engine's family names ("jb-typeface-<id>", typefaceAlias), for
// the cascade manager below: a run's characters its font lacks fall back from that font.
namespace {
std::mutex gAliasLock;
std::unordered_map<uint32_t, sk_sp<SkTypeface>> gAliasTypefaces;
constexpr char kAliasPrefix[] = "jb-typeface-";
}

SkString typefaceAlias(const SkTypeface *typeface) {
    return SkStringPrintf("%s%u", kAliasPrefix, (unsigned) typeface->uniqueID());
}

// typefaceAlias, the typeface registered for the cascade manager
static SkString registeredAlias(const sk_sp<SkTypeface> &typeface) {
    {
        std::lock_guard<std::mutex> guard(gAliasLock);
        gAliasTypefaces.emplace(typeface->uniqueID(), typeface);
    }
    return typefaceAlias(typeface.get());
}

// The fallback for a character a run's font lacks, as the platform's text falls back (SKPlatform_CascadeTypeface:
// macOS CTFontCreateForString from the run's own font, as CTLine and TextKit 2 draw, and as the glyph-list routes
// resolve it, SKPlatform_FallbackTypeface): the run's font comes as its alias in the family name (the shaper's
// request name, the paragraph's text-style family). Without a cascade, or for another name, the inner manager's.
namespace {
class CascadeFontMgr final : public SkFontMgr {
public:
    explicit CascadeFontMgr(sk_sp<SkFontMgr> inner) : fInner(std::move(inner)) {}

protected:
    int onCountFamilies() const override { return fInner->countFamilies(); }
    void onGetFamilyName(int index, SkString *familyName) const override { fInner->getFamilyName(index, familyName); }
    sk_sp<SkFontStyleSet> onCreateStyleSet(int index) const override { return fInner->createStyleSet(index); }
    sk_sp<SkFontStyleSet> onMatchFamily(const char familyName[]) const override { return fInner->matchFamily(familyName); }
    sk_sp<SkTypeface> onMatchFamilyStyle(const char familyName[], const SkFontStyle &style) const override {
        return fInner->matchFamilyStyle(familyName, style);
    }
    sk_sp<SkTypeface> onMatchFamilyStyleCharacter(const char familyName[], const SkFontStyle &style, const char *bcp47[],
                                                  int bcp47Count, SkUnichar character) const override {
        if (familyName != nullptr && std::strncmp(familyName, kAliasPrefix, sizeof(kAliasPrefix) - 1) == 0) {
            sk_sp<SkTypeface> base;
            {
                std::lock_guard<std::mutex> guard(gAliasLock);
                auto it = gAliasTypefaces.find((uint32_t) std::strtoul(familyName + sizeof(kAliasPrefix) - 1, nullptr, 10));
                if (it != gAliasTypefaces.end()) base = it->second;
            }
            if (base) {
                if (sk_sp<SkTypeface> cascade = SKPlatform_CascadeTypeface(base.get(), character)) {
                    return cascade;
                }
            }
        }
        return fInner->matchFamilyStyleCharacter(familyName, style, bcp47, bcp47Count, character);
    }
    sk_sp<SkTypeface> onMakeFromData(sk_sp<SkData> data, int ttcIndex) const override { return fInner->makeFromData(std::move(data), ttcIndex); }
    sk_sp<SkTypeface> onMakeFromStreamIndex(std::unique_ptr<SkStreamAsset> stream, int ttcIndex) const override {
        return fInner->makeFromStream(std::move(stream), ttcIndex);
    }
    sk_sp<SkTypeface> onMakeFromStreamArgs(std::unique_ptr<SkStreamAsset> stream, const SkFontArguments &args) const override {
        return fInner->makeFromStream(std::move(stream), args);
    }
    sk_sp<SkTypeface> onMakeFromFile(const char path[], int ttcIndex) const override { return fInner->makeFromFile(path, ttcIndex); }
    sk_sp<SkTypeface> onLegacyMakeTypeface(const char familyName[], SkFontStyle style) const override {
        return fInner->legacyMakeTypeface(familyName, style);
    }

private:
    sk_sp<SkFontMgr> fInner;
};
}

// The font manager for the fallback runs: the platform's under the cascade (CascadeFontMgr), or its proxy manager
// when the strike's font takes a proxy (proxyClass), so the characters the strike's font lacks get the proxy too.
static sk_sp<SkFontMgr> cascadeManager() {
    static sk_sp<SkFontMgr> cascade = sk_make_sp<CascadeFontMgr>(SKPlatform_FontManager());
    return cascade;
}

static sk_sp<SkFontMgr> fallbackManager(const SkFont &font, jlong pScalerContext, SkColor textColour) {
    const int proxy = proxyClass(font, pScalerContext, textColour);
    return proxy != NO_PROXY ? SKLuminanceFontMgr_For(cascadeManager(), proxy) : cascadeManager();
}

// The text blob cache (SKBackend_SetTextBlobCache): least recently used first out. A key is the bytes of a
// call's runs: per run the typeface's identity (a cached blob holds a reference to it, so the address is not
// reused while the key lives), the font's parameters, the glyph ids and their positions relative to the
// call's origin. The paint is not part of a blob and not of the key.
namespace {
struct TextBlobCache {
    size_t capacity = 0;
    std::list<std::pair<std::string, sk_sp<SkTextBlob>>> lru;   // most recently used first
    std::unordered_map<std::string_view, decltype(lru)::iterator> index;   // views into the keys in lru
    jlong hits = 0, misses = 0;

    sk_sp<SkTextBlob> find(const std::string &key) {
        auto it = index.find(std::string_view(key));
        if (it == index.end()) {
            return nullptr;
        }
        lru.splice(lru.begin(), lru, it->second);
        return it->second->second;
    }
    void add(std::string key, sk_sp<SkTextBlob> blob) {
        lru.emplace_front(std::move(key), std::move(blob));
        index.emplace(std::string_view(lru.front().first), lru.begin());
        while (lru.size() > capacity) {
            index.erase(std::string_view(lru.back().first));
            lru.pop_back();
        }
    }
    void clear() {
        index.clear();
        lru.clear();
    }
};
TextBlobCache gTextBlobs;   // the flusher's, under the entry mutex

template <typename T> void appendBytes(std::string &key, const T &value) {
    key.append(reinterpret_cast<const char *>(&value), sizeof(T));
}

void appendFont(std::string &key, const SkFont &font) {
    appendBytes(key, (uintptr_t) font.getTypeface());
    appendBytes(key, font.getSize());
    appendBytes(key, font.getScaleX());
    appendBytes(key, font.getSkewX());
    const uint32_t flags = (uint32_t) font.getEdging() | (uint32_t) font.getHinting() << 4
                           | (uint32_t) font.isSubpixel() << 8 | (uint32_t) font.isLinearMetrics() << 9
                           | (uint32_t) font.isEmbolden() << 10 | (uint32_t) font.isBaselineSnap() << 11
                           | (uint32_t) font.isForceAutoHinting() << 12 | (uint32_t) font.isEmbeddedBitmaps() << 13;
    appendBytes(key, flags);
}
} // namespace

void SKBackend_SetTextBlobCache(jint entries) {
    gTextBlobs.capacity = entries > 0 ? (size_t) entries : 0;
    gTextBlobs.clear();
}

void SKBackend_TextBlobCacheStats(jlong *hits, jlong *misses) {
    *hits = gTextBlobs.hits;
    *misses = gTextBlobs.misses;
}

// A run of glyphs under the paint, the colour glyphs (no outline: the scaler's rule) apart under Src or Clear
static void drawGlyphsSplit(SkCanvas *canvas, const SkGlyphID *glyphs, const SkPoint *points, size_t count, SkPoint origin,
                            const SkFont &font, const SkPaint &paint, float extraAlpha, bool swizzled) {
    if (!splitsColourGlyphs(paint) && !swizzled) {
        canvas->drawGlyphs(SkSpan<const SkGlyphID>(glyphs, count), SkSpan<const SkPoint>(points, count), origin, font, paint);
        return;
    }
    std::vector<SkGlyphID> outlineIds, colourIds;
    std::vector<SkPoint> outlinePoints, colourPoints;
    for (size_t i = 0; i < count; i++) {
        const bool colour = !font.getPath(glyphs[i]).has_value();
        (colour ? colourIds : outlineIds).push_back(glyphs[i]);
        (colour ? colourPoints : outlinePoints).push_back(points[i]);
    }
    if (!outlineIds.empty()) {
        canvas->drawGlyphs(SkSpan<const SkGlyphID>(outlineIds.data(), outlineIds.size()), SkSpan<const SkPoint>(outlinePoints.data(), outlinePoints.size()), origin, font, paint);
    }
    if (!colourIds.empty()) {
        canvas->drawGlyphs(SkSpan<const SkGlyphID>(colourIds.data(), colourIds.size()), SkSpan<const SkPoint>(colourPoints.data(), colourPoints.size()), origin, font,
                           colourGlyphPaint(paint, extraAlpha, swizzled));
    }
}

// A text blob likewise (the shaper's, the paragraph's runs)
static void drawTextBlobSplit(SkCanvas *canvas, const sk_sp<SkTextBlob> &blob, float x, float y, const SkPaint &paint, float extraAlpha,
                              bool swizzled) {
    if (!blob) {
        return;   // a line without glyphs (SkParagraph's after a line terminator): SkCanvas::drawTextBlob ignores it too
    }
    if (!splitsColourGlyphs(paint) && !swizzled) {
        canvas->drawTextBlob(blob, x, y, paint);
        return;
    }
    SkTextBlob::Iter it(*blob);
    SkTextBlob::Iter::ExperimentalRun run;
    while (it.experimentalNext(&run)) {
        if (run.positions == nullptr) {
            canvas->drawTextBlob(blob, x, y, paint);   // a run without full positions (not built by the shaper or the paragraph)
            return;
        }
    }
    SkTextBlob::Iter again(*blob);
    while (again.experimentalNext(&run)) {
        drawGlyphsSplit(canvas, run.glyphs, run.positions, (size_t) run.count, {x, y}, run.font, paint, extraAlpha, swizzled);
    }
}

namespace {
// A paragraph's runs when their proxies differ: the paint of each and its proxy (proxyClass)
struct ParagraphRuns {
    std::vector<SkPaint> paints;
    std::vector<int> classes;
};

// The blob with every typeface that is not yet a proxy replaced by its proxy for the class (proxyClass)
sk_sp<SkTextBlob> withLuminanceProxies(const sk_sp<SkTextBlob> &blob, int luminance) {
    if (!blob) {
        return blob;
    }
    SkTextBlob::Iter it(*blob);
    SkTextBlob::Iter::ExperimentalRun run;
    bool plain = false;
    while (it.experimentalNext(&run)) {
        if (run.positions == nullptr) {
            return blob;   // not the paragraph's (its runs carry full positions)
        }
        plain = plain || (run.font.getTypeface() != nullptr && !SKLuminanceTypeface_IsProxy(run.font.getTypeface()));
    }
    if (!plain) {
        return blob;
    }
    SkTextBlobBuilder builder;
    SkTextBlob::Iter again(*blob);
    while (again.experimentalNext(&run)) {
        SkFont font = run.font;
        if (font.getTypeface() != nullptr && !SKLuminanceTypeface_IsProxy(font.getTypeface())) {
            font.setTypeface(SKLuminanceTypeface_For(font.refTypeface(), luminance));
        }
        const SkTextBlobBuilder::RunBuffer &buffer = builder.allocRunPos(font, run.count);
        std::memcpy(buffer.glyphs, run.glyphs, sizeof(SkGlyphID) * (size_t) run.count);
        std::memcpy(buffer.points(), run.positions, sizeof(SkPoint) * (size_t) run.count);
    }
    return builder.make();
}

// The paragraph painted onto a canvas with its text blobs split as above (SkParagraph's own canvas painter otherwise)
// A paragraph whose runs differ in their luminance class (ParagraphRuns) names each run's paint by the run's index: the
// fallback typefaces of a run (plain, from the cascade) become the luminance proxies of that run's class here, as the
// run's own typeface is (applyProxy) and as Skia's text path draws a run's fallback glyphs.
struct SplittingParagraphPainter final : skia::textlayout::ParagraphPainter {
    SkCanvas *canvas;
    float extraAlpha;
    bool swizzled;
    const ParagraphRuns *runs;
    SplittingParagraphPainter(SkCanvas *c, float ea, bool sw, const ParagraphRuns *r) : canvas(c), extraAlpha(ea), swizzled(sw), runs(r) {}
    SkPaint paintOf(const SkPaintOrID &p) const {
        if (const SkPaint *paint = std::get_if<SkPaint>(&p)) return *paint;
        const PaintID id = std::get<PaintID>(p);
        return runs != nullptr && id >= 0 && (size_t) id < runs->paints.size() ? runs->paints[id] : SkPaint();
    }
    void drawTextBlob(const sk_sp<SkTextBlob> &blob, SkScalar x, SkScalar y, const SkPaintOrID &paint) override {
        const PaintID *id = std::get_if<PaintID>(&paint);
        const int proxy = id != nullptr && runs != nullptr && *id >= 0 && (size_t) *id < runs->classes.size() ? runs->classes[*id] : NO_PROXY;
        drawTextBlobSplit(canvas, proxy != NO_PROXY ? withLuminanceProxies(blob, proxy) : blob, x, y, paintOf(paint), extraAlpha, swizzled);
    }
    void drawTextShadow(const sk_sp<SkTextBlob> &blob, SkScalar x, SkScalar y, SkColor color, SkScalar blurSigma) override {
        SkPaint p;
        p.setColor(color);
        if (blurSigma > 0) p.setMaskFilter(SkMaskFilter::MakeBlur(kNormal_SkBlurStyle, blurSigma, false));
        canvas->drawTextBlob(blob, x, y, p);
    }
    void drawRect(const SkRect &rect, const SkPaintOrID &paint) override { canvas->drawRect(rect, paintOf(paint)); }
    void drawFilledRect(const SkRect &rect, const DecorationStyle &style) override { canvas->drawRect(rect, style.skPaint()); }
    void drawPath(const SkPath &path, const DecorationStyle &style) override { canvas->drawPath(path, style.skPaint()); }
    void drawLine(SkScalar x0, SkScalar y0, SkScalar x1, SkScalar y1, const DecorationStyle &style) override { canvas->drawLine(x0, y0, x1, y1, style.skPaint()); }
    void clipRect(const SkRect &rect) override { canvas->clipRect(rect); }
    void translate(SkScalar dx, SkScalar dy) override { canvas->translate(dx, dy); }
    void save() override { canvas->save(); }
    void restore() override { canvas->restore(); }
};
} // namespace

// The device baseline a horizontal run's glyphs are painted from (sun.java2d.pipe.GlyphListPipe.glyphRow): on macOS the
// row below a fractional baseline, as CoreText places glyphs (any fraction past 1/1024 of a point); elsewhere the
// baseline itself. Decorations keep the exact baseline.
static float glyphRow(float y, double displayScale) {
#if defined(__APPLE__)
    return (float) std::ceil(y - displayScale / 1024);
#else
    (void) displayScale;
    return y;
#endif
}

// a paragraph onto the canvas: SkParagraph's own painter, or ours under another rule, on a raster canvas, or for runs
// of several luminance classes (runs: their paints, named by index)
static void paintParagraph(skia::textlayout::Paragraph &paragraph, SkCanvas *canvas, float x, float y, const SkPaint &paint, float extraAlpha,
                           bool swizzled, const ParagraphRuns *runs) {
    if (!splitsColourGlyphs(paint) && !swizzled && runs == nullptr) {
        paragraph.paint(canvas, x, y);
        return;
    }
    SplittingParagraphPainter painter(canvas, extraAlpha, swizzled, runs);
    paragraph.paint(&painter, x, y);
}

jlong SKBackend_DrawGlyphsOnCanvas(SkCanvas *canvas, const SkPaint &paint, SkColor textColour, float extraAlpha, bool swizzled,
                                   const jlong *contexts, jint contextCount, jint numGlyphs, jboolean usePositions, jboolean smoothing,
                                   jfloat originX, jfloat originY, const jint *codes, const jlong *images,
                                   const jfloat *positions, bool cacheBlobs) {
    jlong drawn = 0;
    if (contextCount <= 0) return 0;
    // A context whose glyph transform goes beyond its size (rotated, sheared or unevenly scaled text) draws its runs
    // under that remainder, the positions mapped back through it, so Skia renders the glyphs under the whole
    // transform; such a call draws its runs one by one (the blob cache holds axis-aligned runs, and a cached blob is
    // drawn after the uncached runs, which would reorder them)
    bool transformed = false;
    for (jint c = 0; c < contextCount && !transformed; c++) {
        SkMatrix unused;
        transformed = contexts[c] != 0 && SKFontScaler_Remainder(contexts[c], &unused);
    }
    cacheBlobs = cacheBlobs && !transformed && gTextBlobs.capacity > 0 && !splitsColourGlyphs(paint) && !swizzled;
    // runs of glyphs sharing a font (the strike's own, or a platform fallback font) and a remainder
    std::vector<SkGlyphID> ids;
    std::vector<SkPoint> points;
    SkFont runFont;
    const SkTypeface *runTypeface = nullptr;
    jlong runContext = 0;
    SkMatrix runRemainder;
    bool runTransformed = false;
    // cached: the runs collected (positions relative to the origin) into one blob, looked up by their bytes
    struct Run {
        SkFont font;
        size_t first, count;
    };
    std::vector<Run> runs;
    std::vector<SkGlyphID> allIds;
    std::vector<SkPoint> allPoints;
    auto flushRun = [&] {
        if (ids.empty()) {
            return;
        }
        if (cacheBlobs) {
            runs.push_back(Run{runFont, allIds.size(), ids.size()});
            allIds.insert(allIds.end(), ids.begin(), ids.end());
            for (const SkPoint &p : points) {
                allPoints.push_back({p.fX - originX, p.fY - originY});
            }
        } else if (runTransformed) {
            SkMatrix inverse;
            if (runRemainder.invert(&inverse)) {
                inverse.mapPoints(SkSpan<SkPoint>(points.data(), points.size()));
                canvas->save();
                canvas->concat(runRemainder);
                drawGlyphsSplit(canvas, ids.data(), points.data(), ids.size(), {0, 0}, runFont, paint, extraAlpha, swizzled);
                canvas->restore();
            }
        } else {
            drawGlyphsSplit(canvas, ids.data(), points.data(), ids.size(), {0, 0}, runFont, paint, extraAlpha, swizzled);
        }
        drawn += (jlong) ids.size();
        ids.clear();
        points.clear();
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
        // the code's slot (bits 24–31) selects the strike's context; the invisible glyph keeps its advance only
        jint code = codes[i];
        if (code == 0xffff) continue;
        jlong pScalerContext = contexts[0];
        if (code >= 0) {
            const jint slot = (jint) ((uint32_t) code >> 24);
            pScalerContext = contexts[slot < contextCount ? slot : 0];
            code &= 0xffffff;
        }
        if (pScalerContext == 0) continue;
        SkFont font;
        SkGlyphID glyph;
        if (!SKFontScaler_ResolveGlyph(pScalerContext, code, &font, &glyph)) {
            continue;
        }
        applyProxy(font, pScalerContext, textColour);
        if (font.getTypeface() != runTypeface || (transformed && pScalerContext != runContext)) {
            flushRun();
            runFont = font;
            runTypeface = font.getTypeface();
            runContext = pScalerContext;
            runTransformed = transformed && SKFontScaler_Remainder(pScalerContext, &runRemainder);
        }
        ids.push_back(glyph);
        points.push_back({gx, gy});
    }
    flushRun();
    if (cacheBlobs && !runs.empty()) {
        std::string key;
        key.reserve(runs.size() * 40 + allIds.size() * (sizeof(SkGlyphID) + sizeof(SkPoint)));
        for (const Run &run : runs) {
            appendFont(key, run.font);
            appendBytes(key, run.count);
            key.append(reinterpret_cast<const char *>(allIds.data() + run.first), run.count * sizeof(SkGlyphID));
            key.append(reinterpret_cast<const char *>(allPoints.data() + run.first), run.count * sizeof(SkPoint));
        }
        sk_sp<SkTextBlob> blob = gTextBlobs.find(key);
        if (blob) {
            gTextBlobs.hits++;
            SKStats_Count(SK_COUNT_TEXT_BLOB_HITS);
        } else {
            SkTextBlobBuilder builder;
            for (const Run &run : runs) {
                const SkTextBlobBuilder::RunBuffer &buffer = builder.allocRunPos(run.font, (int) run.count);
                memcpy(buffer.glyphs, allIds.data() + run.first, run.count * sizeof(SkGlyphID));
                memcpy(buffer.points(), allPoints.data() + run.first, run.count * sizeof(SkPoint));
            }
            blob = builder.make();
            gTextBlobs.misses++;
            SKStats_Count(SK_COUNT_TEXT_BLOB_MISSES);
            if (blob) {
                gTextBlobs.add(std::move(key), blob);
            }
        }
        if (blob) {
            canvas->drawTextBlob(blob, originX, originY, paint);
        }
    }
    return drawn;
}

void SKBackend_DrawGlyphsBySkia(const jlong *contexts, jint contextCount, jint numGlyphs, jboolean usePositions, jboolean smoothing,
                                jfloat originX, jfloat originY, const jint *codes, const jlong *images,
                                const jfloat *positions) {
    REQUIRE_CANVAS_TEXT();
    // drawn through the canvas: every pending batch first, of any kind
    { SKDrawCauseScope cause(SK_COUNT_MESH_DRAWS_AT_DIRECT); flushAllBatches(); }     // glyph-image draws queued before this list stay in order
    SkPaint paint = SKBackend_MakePaint(true);
    jlong drawn = SKBackend_DrawGlyphsOnCanvas(g.canvas, paint, paint.getColor(), g.extraAlpha, false, contexts, contextCount, numGlyphs, usePositions, smoothing,
                                               originX, originY, codes, images, positions, true);
    gGlyphsDrawnBySkia.fetch_add(drawn, std::memory_order_relaxed);
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

// The shaper and the paragraph engine share singletons (SkShaper's HarfBuzz buffers, the paragraph font collection
// and its registrations) that the flusher and the raster surfaces' callers on any Java thread both reach
static std::mutex gTextLayoutMutex;

// A Java ARGB colour as a canvas over BGRA memory declared N32 sees it (the raster surface's: swizzled), or as it is
static inline SkColor canvasColour(SkColor argb, bool swizzled) {
    return swizzled ? SkColorSetARGB(SkColorGetA(argb), SkColorGetB(argb), SkColorGetG(argb), SkColorGetR(argb)) : argb;
}

// The paint for a colour of the paragraph's own (a run's FOREGROUND, a BACKGROUND, a swapped colour): the graphics'
// paint without its gradient or texture, the colour with the composite's extra alpha (SunGraphics2D.validateColor's
// Math.round). The GPU pipe's own colour arrives as the surface's IntArgbPre pixel (PixelConverter.ArgbPre, then
// unpremul), so a translucent colour takes the same round trip there, or it lands a level off TextLayout's; the raster
// surfaces take Java's straight colour (swizzled: no round trip).
static inline SkPaint colouredPaint(const SkPaint &base, SkColor javaArgb, float extraAlpha, bool swizzled) {
    SkPaint p = base;
    p.setShader(nullptr);
    const uint32_t a = (uint32_t) std::lround(SkColorGetA(javaArgb) * extraAlpha);
    SkColor colour = SkColorSetA(javaArgb, (U8CPU) a);
    if (!swizzled && a != 255) {
        const uint32_t a2 = a + (a >> 7);
        const uint32_t pre = a << 24 | ((SkColorGetR(javaArgb) * a2) >> 8) << 16 | ((SkColorGetG(javaArgb) * a2) >> 8) << 8 | ((SkColorGetB(javaArgb) * a2) >> 8);
        colour = unpremul((jint) pre);
    }
    p.setColor(canvasColour(colour, swizzled));
    return p;
}

void SKBackend_DrawShapedBySkia(jlong pScalerContext, jboolean smoothing, jboolean rightToLeft, jfloat originX, jfloat originY,
                                const jchar *chars, jint length) {
    REQUIRE_CANVAS_TEXT();
    // drawn through the canvas: every pending batch first, of any kind
    { SKDrawCauseScope cause(SK_COUNT_MESH_DRAWS_AT_DIRECT); flushAllBatches(); }     // glyph-image draws queued before this text stay in order
    const SkPaint paint = SKBackend_MakePaint(true);
    SKBackend_DrawShapedOnCanvas([](const SkIRect &) { return g.canvas; }, paint, paint.getColor(), g.extraAlpha, false,
                                 pScalerContext, smoothing, rightToLeft, originX, originY, chars, length);
}

jlong SKBackend_DrawShapedOnCanvas(const SKCanvasForBounds &canvasFor, const SkPaint &paint, SkColor textColour, float extraAlpha, bool swizzled,
                                   jlong pScalerContext,
                                   jboolean smoothing, jboolean rightToLeft, jfloat originX, jfloat originY, const jchar *chars, jint length) {
    std::lock_guard<std::mutex> lock(gTextLayoutMutex);
    SkFont font;
    if (length <= 0 || !SKFontScaler_Font(pScalerContext, &font)) {
        return 0;
    }
    const Shaping &s = shaping();
    if (!s.shaper) {
        return 0;
    }
    sk_sp<SkFontMgr> fallback = fallbackManager(font, pScalerContext, textColour);
    applyProxy(font, pScalerContext, textColour);
    // whole-pixel advances of a system font round in user space (SKFontScaler_LayoutScale): the shaping
    // runs at the user size and the blob is painted under the scale, so the glyphs land where Java's do
    const float layoutScale = (float) SKFontScaler_LayoutScale(pScalerContext);
    if (layoutScale != 1) {
        font.setSize(font.getSize() / layoutScale);
    }
    const SkString utf8 = SkUnicode::convertUtf16ToUtf8((const char16_t *) chars, length);
    // the paragraph direction Java's layout takes (the first strong character, SKTextRenderer); the shaper's bidi
    // reorders the runs within it and lays them out visually from the origin, as TextLayout does
    // the characters the font lacks from the platform's cascade from that font (CascadeFontMgr: the alias names it)
    auto language = SkShaper::MakeStdLanguageRunIterator(utf8.c_str(), utf8.size());
    const SkString alias = registeredAlias(font.refTypeface());
    auto fonts = SkShaper::MakeFontMgrRunIterator(utf8.c_str(), utf8.size(), font, fallback, alias.c_str(), font.getTypeface()->fontStyle(),
                                                  language.get());
    auto bidi = SkShapers::unicode::BidiRunIterator(s.unicode, utf8.c_str(), utf8.size(), rightToLeft ? 1 : 0);
    auto script = SkShapers::HB::ScriptRunIterator(utf8.c_str(), utf8.size());
    if (!fonts || !bidi || !script || !language) {
        return 0;
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
        return 0;
    }
    // the blob's paint transform: the origin, the strike's transform beyond its size (rotated, sheared or unevenly
    // scaled text, shaped in the font's own space as the text path draws such a run), the layout scale
    SkMatrix remainder;
    const bool transformed = SKFontScaler_Remainder(pScalerContext, &remainder);
    SkMatrix toDevice = SkMatrix::Translate(originX, originY);
    if (transformed) toDevice.preConcat(remainder);
    toDevice.preScale(layoutScale, layoutScale);
    SkCanvas *canvas = canvasFor(toDevice.mapRect(blob->bounds()).roundOut().makeOutset(2, 2));
    if (canvas == nullptr) {
        return 0;
    }
    if (transformed) {
        SkAutoCanvasRestore restore(canvas, true);
        canvas->translate(originX, originY);
        canvas->concat(remainder);
        canvas->scale(layoutScale, layoutScale);
        drawTextBlobSplit(canvas, blob, 0, 0, paint, extraAlpha, swizzled);
    } else if (layoutScale != 1) {
        SkAutoCanvasRestore restore(canvas, true);
        canvas->scale(layoutScale, layoutScale);
        drawTextBlobSplit(canvas, blob, originX / layoutScale, glyphRow(originY, SKFontScaler_DisplayScale(pScalerContext)) / layoutScale, paint, extraAlpha, swizzled);
    } else {
        drawTextBlobSplit(canvas, blob, originX, glyphRow(originY, SKFontScaler_DisplayScale(pScalerContext)), paint, extraAlpha, swizzled);
    }
    gGlyphsShapedBySkia.fetch_add((jlong) handler.glyphs, std::memory_order_relaxed);
    gGlyphsDrawnBySkia.fetch_add((jlong) handler.glyphs, std::memory_order_relaxed);
    return (jlong) handler.glyphs;
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
    std::unordered_map<SkTypefaceID, sk_sp<SkTypeface>> registered;   // the provider's, under their aliases

    // The provider has no unregistering: it is made again without the typefaces, and the collections over it
    void forget(const std::vector<SkTypefaceID> &ids) {
        bool changed = false;
        for (SkTypefaceID id : ids) changed |= registered.erase(id) > 0;
        if (!changed) return;
        provider = sk_make_sp<tl::TypefaceFontProvider>();
        for (const auto &[id, typeface] : registered) provider->registerTypeface(typeface, typefaceAlias(typeface.get()));
        collections.clear();
    }

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

} // namespace

void SKBackend_TypefacesHeld(jlong out[4]) {
    out[0] = SKFontScaler_Scalers();
    out[1] = (jlong) SKLuminanceTypeface_Held();
    {
        std::lock_guard<std::mutex> guard(gAliasLock);
        out[2] = (jlong) gAliasTypefaces.size();
    }
    std::lock_guard<std::mutex> lock(gTextLayoutMutex);
    out[3] = (jlong) paragraphs().registered.size();
}

void SKBackend_ForgetTypeface(SkTypefaceID typeface) {
    std::vector<SkTypefaceID> ids = SKLuminanceTypeface_Forget(typeface);
    ids.push_back(typeface);
    std::vector<sk_sp<SkTypeface>> released;   // destroyed outside the locks
    {
        std::lock_guard<std::mutex> lock(gTextLayoutMutex);
        paragraphs().forget(ids);
    }
    std::lock_guard<std::mutex> guard(gAliasLock);
    for (SkTypefaceID id : ids) {
        auto it = gAliasTypefaces.find(id);
        if (it != gAliasTypefaces.end()) {
            released.push_back(std::move(it->second));
            gAliasTypefaces.erase(it);
        }
    }
}

// Text decorations as the operating system's text draws them, for the paragraph's runs that asked
// for them (SK_PARAGRAPH_SYSTEM_DECORATIONS; SkParagraph painted none): the same rule and arithmetic
// as sun.font.TextDecorations, by the platform's rule (SKPlatform_TextDefaults().decorationRule) —
// CoreText's: the line's top row is the rounded top of the stroked line, at least one row thick, and an
// underline leaves a gap wherever the glyphs' outlines cross the band, with one device pixel of
// clearance on each side; Direct2D's (Windows): the underline's top row rounded, its height the font's
// thickness kept fractional, the strikethrough the unsnapped rectangle of the font's metrics, no gaps
// (DirectWrite draws through the ink). The runs come from the paragraph's visitor in device space; a
// run's UTF-8 start maps it onto Java's run.
namespace {
constexpr float kDecorationClearance = 1.0f;   // in user-space pixels: CoreText clears one point beside the ink (two device pixels at 2x)

// CoreText's line-end whitespace, undecorated at either end of a line: the space separators (Zs) and the line
// terminators, not the tab (sun.font.TextDecorations.lineEndWhitespace, measured 2026-11-10); and U+200B, which
// stands in for the line terminators here (SKTextRenderer.skiaText; the tab's stand-in, U+2060, is ink as the tab)
bool lineEndWhitespace(jchar c) {
    return c == 0x20 || c == 0xA0 || c == 0x1680 || (c >= 0x2000 && c <= 0x200A) || c == 0x202F || c == 0x205F || c == 0x3000
           || c == 0x0A || c == 0x0D || c == 0x85 || c == 0x2028 || c == 0x2029 || c == 0x200B;
}

// The UTF-8 offsets of the zero-width stand-ins for Java's ignorable whitespace (SKTextRenderer.skiaText: U+200B for
// the line terminators, U+2060 for the tab): SkParagraph places them without advance but draws them as the font's
// space glyph, whose own width must not count in a decoration's extent
std::vector<uint32_t> zeroWidthUtf8(const jchar *chars, jint length) {
    std::vector<uint32_t> offsets;
    uint32_t utf8 = 0;
    for (jint i = 0; i < length; i++) {
        const jchar c = chars[i];
        if (c == 0x200B || c == 0x2060) offsets.push_back(utf8);
        utf8 += c < 0x80 ? 1 : c < 0x800 ? 2 : (c >= 0xD800 && c < 0xDC00) ? 4 : (c >= 0xDC00 && c < 0xE000) ? 0 : 3;
    }
    return offsets;
}

bool zeroWidthAt(const std::vector<uint32_t> &offsets, uint32_t utf8) {
    return std::binary_search(offsets.begin(), offsets.end(), utf8);
}

// The UTF-8 range of the line's characters a decoration spans: under CoreText's rule from the first character that
// is not line-end whitespace to the last, else the whole line (the paragraph is one line)
std::pair<uint32_t, uint32_t> decoratedUtf8Range(const jchar *chars, jint length) {
    if (SKPlatform_TextDefaults().decorationRule != SKDecorationRule::CoreText) {
        return {0, std::numeric_limits<uint32_t>::max()};
    }
    jint first = 0, last = length;
    while (first < last && lineEndWhitespace(chars[first])) first++;
    while (last > first && lineEndWhitespace(chars[last - 1])) last--;
    auto utf8 = [&](jint n) { return n > 0 ? (uint32_t) SkUnicode::convertUtf16ToUtf8((const char16_t *) chars, n).size() : 0u; };
    return {utf8(first), utf8(last)};
}

void addCrossing(std::vector<std::pair<float, float>> &ranges, SkPoint a, SkPoint b, float yTop, float yBottom) {
    const float lo = std::min(a.fY, b.fY), hi = std::max(a.fY, b.fY);
    if (hi <= yTop || lo >= yBottom) return;   // strictly inside the band: glyph bottoms resting on its edge do not cross
    float xa = a.fX, xb = b.fX;
    if (a.fY != b.fY) {
        const float t0 = std::clamp((yTop - a.fY) / (b.fY - a.fY), 0.0f, 1.0f), t1 = std::clamp((yBottom - a.fY) / (b.fY - a.fY), 0.0f, 1.0f);
        xa = a.fX + (b.fX - a.fX) * std::min(t0, t1);
        xb = a.fX + (b.fX - a.fX) * std::max(t0, t1);
    }
    ranges.emplace_back(std::min(xa, xb), std::max(xa, xb));
}

// the x ranges where the glyph outlines of a run cross the band, merged
std::vector<std::pair<float, float>> inkCrossings(const SkFont &font, const SkGlyphID *glyphs, const SkPoint *positions, int count,
                                                  SkPoint runOrigin, float yTop, float yBottom, bool roundedY = true) {
    std::vector<std::pair<float, float>> ranges;
    for (int i = 0; i < count; i++) {
        const std::optional<SkPath> path = font.getPath(glyphs[i]);
        if (!path || path->isEmpty()) continue;
        // where the glyph is drawn: SkParagraph's line baseline is fractional (its line metrics are not
        // rounded) but horizontal glyphs render at the rounded y, as the decoration's rows are counted
        // (under a transform the run is in layout space, the glyphs where the layout puts them: roundedY false)
        const float x = runOrigin.fX + positions[i].fX, y = roundedY ? (float) std::lround(runOrigin.fY + positions[i].fY) : runOrigin.fY + positions[i].fY;
        const SkRect bounds = path->getBounds().makeOffset(x, y);
        if (bounds.fBottom < yTop || bounds.fTop > yBottom) continue;
        const SkPath placed = path->makeOffset(x, y);
        SkPath::Iter it(placed, true);
        SkPoint pts[4];
        for (SkPath::Verb v = it.next(pts); v != SkPath::kDone_Verb; v = it.next(pts)) {
            switch (v) {
                case SkPath::kLine_Verb: addCrossing(ranges, pts[0], pts[1], yTop, yBottom); break;
                case SkPath::kQuad_Verb: case SkPath::kConic_Verb: {
                    SkPoint prev = pts[0];
                    for (int k = 1; k <= 8; k++) { const float t = k / 8.0f; SkPoint q = {(1-t)*(1-t)*pts[0].fX + 2*(1-t)*t*pts[1].fX + t*t*pts[2].fX, (1-t)*(1-t)*pts[0].fY + 2*(1-t)*t*pts[1].fY + t*t*pts[2].fY}; addCrossing(ranges, prev, q, yTop, yBottom); prev = q; }
                    break; }
                case SkPath::kCubic_Verb: {
                    SkPoint prev = pts[0];
                    for (int k = 1; k <= 8; k++) { const float t = k / 8.0f, u = 1 - t; SkPoint q = {u*u*u*pts[0].fX + 3*u*u*t*pts[1].fX + 3*u*t*t*pts[2].fX + t*t*t*pts[3].fX, u*u*u*pts[0].fY + 3*u*u*t*pts[1].fY + 3*u*t*t*pts[2].fY + t*t*t*pts[3].fY}; addCrossing(ranges, prev, q, yTop, yBottom); prev = q; }
                    break; }
                default: break;
            }
        }
    }
    std::sort(ranges.begin(), ranges.end());
    std::vector<std::pair<float, float>> merged;
    for (const auto &r : ranges) {
        if (!merged.empty() && r.first <= merged.back().second) merged.back().second = std::max(merged.back().second, r.second);
        else merged.push_back(r);
    }
    return merged;
}

void fillBand(SkCanvas *canvas, const SkPaint &paint, float x1, float x2, float top, float height,
              const std::vector<std::pair<float, float>> *gaps, float clearance) {
    const int left = (int) std::lround(x1), right = (int) std::lround(x2);
    if (right <= left || height <= 0) return;
    // the band's ends are whole, its height whole rows under CoreText's rule (from the fractional baseline: split
    // over two rows when it falls between) and fractional under Direct2D's; a gap's
    // edges are fractional and antialiased, as CoreText's
    SkPaint solid = paint;
    solid.setAntiAlias(true);
    float start = (float) left;
    if (gaps != nullptr) {
        for (const auto &gap : *gaps) {
            const float gx1 = gap.first - clearance, gx2 = gap.second + clearance;
            if (gx2 <= start || gx1 >= right) continue;
            if (gx1 > start) canvas->drawRect(SkRect::MakeLTRB(start, top, gx1, top + height), solid);
            start = std::max(start, gx2);
        }
    }
    if (start < right) canvas->drawRect(SkRect::MakeLTRB(start, top, (float) right, top + height), solid);
}

// the font's decoration metrics as sun.font.TextDecorations.fontMetrics resolves them: the font's own,
// else Java's nominal ones (Font2D.getStyleMetrics) in the same convention
struct DecorationMetrics { float underlinePosition, underlineThickness, strikethroughCentre, strikethroughThickness, ascent, descent; };
DecorationMetrics decorationMetrics(const SkFont &font) {
    SkFontMetrics metrics;
    font.getMetrics(&metrics);
    const float size = font.getSize();
    const float nominalThickness = size / 12;
    DecorationMetrics m = {nominalThickness / 1.5f - nominalThickness / 2, nominalThickness, metrics.fAscent / 2.5f, nominalThickness, -metrics.fAscent, metrics.fDescent};
    float position, thickness;
    if (metrics.hasUnderlinePosition(&position) && metrics.hasUnderlineThickness(&thickness) && thickness > 0) {
        m.underlinePosition = position;
        m.underlineThickness = thickness;
    }
#if defined(__APPLE__)
    // AppKit's strikethrough, as the scalers report it (SKFontScaler.cpp strikeMetrics): centred half the x-height
    // above the baseline, the underline's thickness
    if (metrics.fXHeight > 0 && metrics.hasUnderlineThickness(&thickness) && thickness > 0) {
        m.strikethroughCentre = -metrics.fXHeight / 2;
        m.strikethroughThickness = thickness;
    }
#else
    if (metrics.hasStrikeoutPosition(&position) && metrics.hasStrikeoutThickness(&thickness) && thickness > 0) {
        // sun.font.TextDecorations.strikethroughCentre: Skia names the position the stroke's bottom; the
        // DirectWrite host hands on the OS/2 table's top of the stroke
        const bool directWrite = SKPlatform_TextDefaults().decorationRule == SKDecorationRule::DirectWrite;
        m.strikethroughCentre = directWrite ? position + thickness / 2 : position - thickness / 2;
        m.strikethroughThickness = thickness;
    }
#endif
    return m;
}

// the device band of an underline below the baseline: sun.font.TextDecorations.underlineBand (CoreText's rows,
// or Direct2D's rounded top with the fractional thickness; both measured), from the font's metrics times scale in
// device pixels and pointScale device pixels per user-space unit (CoreText's 2-point descent threshold is in points)
struct DecorationBand { float top; float height; };
DecorationBand underlineBand(const DecorationMetrics &m, float scale, float pointScale) {
    const float position = m.underlinePosition * scale, thickness = m.underlineThickness * scale;
    if (SKPlatform_TextDefaults().decorationRule == SKDecorationRule::DirectWrite) {
        return {(float) std::lround(position), thickness};
    }
    if (thickness < 0.35f) {
        return {std::max(1.0f, position - thickness / 2), thickness};
    }
    // TextDecorations' tolerance
    int rows = std::max(1, (int) std::ceil(thickness - 1e-3f));
    const float descent = m.descent * scale, points = pointScale > 0 ? descent / pointScale : descent;
    const float room = points >= 2 ? descent : 0.3f * (m.ascent + m.descent) * scale;
    int top = std::min((int) std::floor(position + 0.5f - rows / 2.0f + 1e-3f), (int) std::floor(room + 1e-3f) - rows);
    top = std::max(top, rows == 1 && position > 1 && room > 4 ? 2 : rows);
    if (rows >= 2 && (room > 4 ? room <= rows : room <= 2 * rows - 1.5f)) {
        rows--;
    }
    return {(float) top, (float) rows};
}

// the device band of a strikethrough centred centre (device pixels, negative above the baseline):
// sun.font.TextDecorations.strikethroughBand (CoreText's rows, or Direct2D's unsnapped rectangle)
DecorationBand strikethroughBand(float centre, float thickness) {
    if (SKPlatform_TextDefaults().decorationRule == SKDecorationRule::DirectWrite || thickness < 0.35f) {
        return {centre - thickness / 2, thickness};
    }
    const int rows = std::max(1, (int) std::ceil(thickness - 1e-3f));
    return {-std::floor(-centre + rows / 2.0f + 0.5f + 1e-3f), (float) rows};   // a tie away from the baseline
}

// A run's font in the paragraph's layout units: its scaler context's at the size over the layout scale.
static bool layoutFont(jlong context, float layoutScale, SkFont *font) {
    if (!SKFontScaler_Font(context, font)) return false;
    font->setSize(font->getSize() / layoutScale);
    return true;
}

// The underline of each run as its line gives it (sun.font.TextDecorations.lineContexts, TextKit 2's): under
// CoreText's rule a stretch of adjacent underlined runs takes the deepest position (with each run's baseline shift
// from the line's baseline) and the thickest thickness; else, and for a run outside a stretch, its own at its shift.
// Layout units, from the line's baseline; ascent and descent the stretch's, the shifts in.
static std::vector<DecorationMetrics> lineUnderlines(jint runCount, const jlong *contexts, const jint *flags, const std::vector<float> &shift,
                                                     float layoutScale) {
    std::vector<DecorationMetrics> own(runCount);
    for (jint r = 0; r < runCount; r++) {
        SkFont font;
        if (!layoutFont(contexts[r], layoutScale, &font)) continue;
        own[r] = decorationMetrics(font);
        own[r].underlinePosition += shift[r];
        own[r].ascent -= shift[r];
        own[r].descent += shift[r];
    }
    if (SKPlatform_TextDefaults().decorationRule == SKDecorationRule::DirectWrite) return own;
    std::vector<DecorationMetrics> out = own;
    for (jint r = 0; r < runCount;) {
        if (!(flags[r] & SK_PARAGRAPH_UNDERLINE)) { r++; continue; }
        jint end = r;
        DecorationMetrics line = own[r];
        for (; end < runCount && (flags[end] & SK_PARAGRAPH_UNDERLINE); end++) {
            line.underlinePosition = std::max(line.underlinePosition, own[end].underlinePosition);
            line.underlineThickness = std::max(line.underlineThickness, own[end].underlineThickness);
            line.ascent = std::max(line.ascent, own[end].ascent);
            line.descent = std::max(line.descent, own[end].descent);
        }
        for (jint k = r; k < end; k++) {
            out[k].underlinePosition = line.underlinePosition;
            out[k].underlineThickness = line.underlineThickness;
            out[k].ascent = line.ascent;
            out[k].descent = line.descent;
        }
        r = end;
    }
    return out;
}

// The metrics scaled (layout to device units).
static DecorationMetrics scaledMetrics(DecorationMetrics m, float scale) {
    m.underlinePosition *= scale; m.underlineThickness *= scale; m.strikethroughCentre *= scale; m.strikethroughThickness *= scale;
    m.ascent *= scale; m.descent *= scale;
    return m;
}

// The line's background band from its baseline, layout units (TextDecorations.lineContexts): the tallest run's ascent
// (no higher than the ascent rounded to a user-space point, the top of TextKit 2's line fragment) to the deepest run's
// descent, the shifts in, no leading. userPerLayout converts layout units to points.
static std::pair<float, float> lineBackgroundBand(jint runCount, const jlong *contexts, const std::vector<float> &shift, float layoutScale,
                                                   float userPerLayout) {
    float ascent = 0, descent = 0;
    for (jint r = 0; r < runCount; r++) {
        SkFont font;
        if (!layoutFont(contexts[r], layoutScale, &font)) continue;
        SkFontMetrics metrics;
        font.getMetrics(&metrics);
        ascent = std::max(ascent, -metrics.fAscent - shift[r]);
        descent = std::max(descent, metrics.fDescent + shift[r]);
    }
    const float rounded = userPerLayout > 0 ? std::round(ascent * userPerLayout) / userPerLayout : ascent;
    return {-std::min(ascent, rounded), descent};
}

// originX and paragraphTop in the paragraph's layout space, layoutScale maps it to device space
// The line's backgrounds (SK_PARAGRAPH_LINE_BACKGROUND, TextDecorations.lineContexts): behind each background run its
// advances' extent over the line's band, in the paragraph's layout space through the canvas's matrix, before the
// glyphs; x0 the paint origin's x, lineBaseline the line's baseline (layout space).
void drawLineBackgrounds(tl::Paragraph &paragraph, SkCanvas *canvas, float x0, float lineBaseline, const jchar *chars, jint length, jint runCount,
                         const jint *runEnds, const jint *flags, const jint *backgrounds, std::pair<float, float> band, const SkPaint &paint,
                         float extraAlpha, bool swizzled) {
    bool any = false;
    for (jint r = 0; r < runCount; r++) any |= (flags[r] & SK_PARAGRAPH_LINE_BACKGROUND) != 0;
    if (!any) return;
    std::vector<uint32_t> runUtf8Start(runCount + 1, 0);
    jint start = 0;
    for (jint r = 0; r < runCount; r++) {
        const jint end = std::min(runEnds[r], length);
        runUtf8Start[r + 1] = runUtf8Start[r] + (end > start ? (uint32_t) SkUnicode::convertUtf16ToUtf8((const char16_t *) chars + start, end - start).size() : 0);
        start = std::max(start, end);
    }
    paragraph.visit([&](int, const tl::Paragraph::VisitorInfo *info) {
        if (info == nullptr || info->count <= 0) return;
        auto runOf = [&](uint32_t utf8) {
            jint r = 0;
            while (r + 1 < runCount && runUtf8Start[r + 1] <= utf8) r++;
            return r;
        };
        for (int i = 0; i < info->count;) {
            const jint r = runOf(info->utf8Starts[i]);
            int j = i + 1;
            while (j < info->count && runOf(info->utf8Starts[j]) == r) j++;
            if (flags[r] & SK_PARAGRAPH_LINE_BACKGROUND) {
                float x1 = std::numeric_limits<float>::max(), x2 = std::numeric_limits<float>::lowest();
                for (int k = i; k < j; k++) {
                    SkScalar advance = 0;
                    info->font.getWidths({&info->glyphs[k], 1}, {&advance, 1});
                    x1 = std::min(x1, x0 + info->origin.fX + info->positions[k].fX);
                    x2 = std::max(x2, x0 + info->origin.fX + info->positions[k].fX + advance);
                }
                SkPaint fill = colouredPaint(paint, (SkColor) backgrounds[r], extraAlpha, swizzled);
                fill.setAntiAlias((flags[r] & SK_PARAGRAPH_BACKGROUND_AA) != 0);
                canvas->drawRect(SkRect::MakeLTRB(x1, lineBaseline + band.first, x2, lineBaseline + band.second), fill);
            }
            i = j;
        }
    });
}

// lineBaseline the line's baseline in layout space (the runs' shifts apart), shift each run's baseline shift
void drawSystemDecorations(tl::Paragraph &paragraph, SkCanvas *canvas, float originX, float paragraphTop, float lineBaseline, float layoutScale,
                           const jchar *chars, jint length, jint runCount, const jint *runEnds, const jlong *contexts, const jint *colours,
                           const jint *flags, const std::vector<float> &shift, const SkPaint &paint, float extraAlpha, bool swizzled) {
    bool any = false;
    for (jint r = 0; r < runCount; r++) any |= (flags[r] & SK_PARAGRAPH_SYSTEM_DECORATIONS) != 0;
    if (!any) return;
    const std::vector<DecorationMetrics> lineUnderline = lineUnderlines(runCount, contexts, flags, shift, layoutScale);
    // Java's runs in UTF-8 offsets (the paragraph was built from the runs' UTF-8)
    std::vector<uint32_t> runUtf8Start(runCount + 1, 0);
    jint start = 0;
    for (jint r = 0; r < runCount; r++) {
        const jint end = runEnds[r];
        runUtf8Start[r + 1] = runUtf8Start[r] + (uint32_t) SkUnicode::convertUtf16ToUtf8((const char16_t *) chars + start, end - start).size();
        start = end;
    }
    const bool skipInk = SKPlatform_TextDefaults().decorationGaps;
    const std::pair<uint32_t, uint32_t> decorated = decoratedUtf8Range(chars, length);
    const std::vector<uint32_t> zeroWidth = zeroWidthUtf8(chars, length);
    SkAutoCanvasRestore restore(canvas, true);
    canvas->resetMatrix();   // the bands are device rectangles (the text canvas carries no matrix; be sure)
    paragraph.visit([&](int, const tl::Paragraph::VisitorInfo *info) {
        if (info == nullptr || info->count <= 0) return;
        auto runOf = [&](uint32_t utf8) {
            jint r = 0;
            while (r + 1 < runCount && runUtf8Start[r + 1] <= utf8) r++;
            return r;
        };
        // the run in device space: the layout's font, origin and positions scaled by the layout scale (1 when the
        // paragraph was laid out in device space); the baseline row is the rounded baseline, as TextDecorations
        // rounds the graphics' one
        SkFont deviceFont = info->font;
        deviceFont.setSize(info->font.getSize() * layoutScale);
        const DecorationMetrics m = decorationMetrics(deviceFont);
        const SkPoint runOrigin = {(originX + info->origin.fX) * layoutScale, (paragraphTop + info->origin.fY) * layoutScale};
        std::vector<SkPoint> devicePositions(info->positions, info->positions + info->count);
        for (SkPoint &pt : devicePositions) pt.scale(layoutScale);
        const SkPoint *positions = devicePositions.data();
        // the band's baseline: under Direct2D's rule the glyphs' row (the rounded baseline), under CoreText's the
        // fractional baseline itself, as AppKit and CTLineDraw leave it (TextDecorations.baselineSnap)
        const bool directWriteRule = SKPlatform_TextDefaults().decorationRule == SKDecorationRule::DirectWrite;
        const float baselineRow = directWriteRule ? (float) std::lround(runOrigin.fY) : runOrigin.fY;
        const DecorationBand band = underlineBand(m, 1, (float) SKFontScaler_DisplayScale(contexts[runOf(info->utf8Starts[0])]));
        const float underlineTop = baselineRow + band.top;
        std::vector<std::pair<float, float>> gaps;
        bool gapsReady = false;
        // SkParagraph's visitor runs are split by font and colour, not by decoration: the glyphs grouped by
        // Java's run (each glyph's UTF-8 start names it), the decorations of each group over its glyphs' extent
        for (int i = 0; i < info->count;) {
            const jint r = runOf(info->utf8Starts[i]);
            int j = i + 1;
            while (j < info->count && runOf(info->utf8Starts[j]) == r) j++;
            if (flags[r] & SK_PARAGRAPH_SYSTEM_DECORATIONS) {
                SkPaint runPaint = paint;
                if (flags[r] & SK_PARAGRAPH_COLOUR) runPaint = colouredPaint(runPaint, (SkColor) colours[r], extraAlpha, swizzled);
                float x1 = std::numeric_limits<float>::max(), x2 = std::numeric_limits<float>::lowest();
                for (int k = i; k < j; k++) {
                    if (info->utf8Starts[k] < decorated.first || info->utf8Starts[k] >= decorated.second) continue;   // line-end whitespace
                    SkScalar advance = 0;
                    if (!zeroWidthAt(zeroWidth, info->utf8Starts[k])) deviceFont.getWidths({&info->glyphs[k], 1}, {&advance, 1});
                    x1 = std::min(x1, runOrigin.fX + positions[k].fX);
                    x2 = std::max(x2, runOrigin.fX + positions[k].fX + advance);
                }
                if (x1 > x2) {   // the group is all line-end whitespace
                    i = j;
                    continue;
                }
                if (flags[r] & SK_PARAGRAPH_UNDERLINE) {
                    // under CoreText's rule the band of the run's stretch (lineUnderlines) from the line's baseline
                    float top = underlineTop, height = band.height;
                    if (!directWriteRule) {
                        const DecorationBand line = underlineBand(scaledMetrics(lineUnderline[r], layoutScale), 1, (float) SKFontScaler_DisplayScale(contexts[r]));
                        top = lineBaseline * layoutScale + line.top;
                        height = line.height;
                        gapsReady = false;
                    }
                    if (skipInk && !gapsReady) {   // the whole visitor run's ink: a neighbour's descender may cross this group's line
                        gaps = inkCrossings(deviceFont, info->glyphs, positions, info->count, runOrigin, top, top + height);
                        gapsReady = true;
                    }
                    fillBand(canvas, runPaint, x1, x2, top, height, skipInk ? &gaps : nullptr,
                             kDecorationClearance * (float) SKFontScaler_DisplayScale(contexts[r]));
                }
                if (flags[r] & SK_PARAGRAPH_STRIKETHROUGH) {
                    const DecorationBand strike = strikethroughBand(m.strikethroughCentre, m.strikethroughThickness);
                    fillBand(canvas, runPaint, x1, x2, baselineRow + strike.top, strike.height, nullptr, 0);
                }
            }
            i = j;
        }
    });
}
}

// The scale across the baseline of the layout-to-device matrix (device pixels per layout unit perpendicular to
// the layout's x axis), and the layout-space offset from the baseline to the band's (under Direct2D's rule the
// nearest device row or column when the baseline runs along them, else 0): TextDecorations.scaleAcross and baselineSnap.
static float scaleAcross(const SkMatrix &m) {
    const float length = std::hypot(m.getScaleX(), m.getSkewY());
    return length == 0 ? 0 : std::fabs(m.getScaleX() * m.getScaleY() - m.getSkewX() * m.getSkewY()) / length;
}

static float baselineSnap(const SkMatrix &m, float y) {
    if (SKPlatform_TextDefaults().decorationRule == SKDecorationRule::CoreText) {
        return 0;   // AppKit's and CTLineDraw's band stays at the fractional baseline
    }
    if (m.getSkewY() == 0 && m.getScaleY() != 0) {
        const float device = m.getScaleY() * y + m.getTranslateY();
        return (std::floor(device + 0.5f) - device) / m.getScaleY();
    }
    if (m.getScaleX() == 0 && m.getSkewX() != 0) {
        const float device = m.getSkewX() * y + m.getTranslateX();
        return (std::floor(device + 0.5f) - device) / m.getSkewX();
    }
    return 0;
}

// a band's end on a device pixel edge when the matrix keeps the axes (TextDecorations.roundedEnd)
static float roundedEnd(const SkMatrix &m, float x) {
    if (m.getSkewY() == 0 && m.getSkewX() == 0 && m.getScaleX() != 0) {
        return (std::floor(m.getScaleX() * x + m.getTranslateX() + 0.5f) - m.getTranslateX()) / m.getScaleX();
    }
    if (m.getScaleX() == 0 && m.getScaleY() == 0 && m.getSkewY() != 0) {
        return (std::floor(m.getSkewY() * x + m.getTranslateY() + 0.5f) - m.getTranslateY()) / m.getSkewY();
    }
    return x;
}

// the band [top, top + height) from x1 to x2 in the canvas's space, antialiased, around the gaps widened by clearance
static void fillLayoutBand(SkCanvas *canvas, const SkPaint &paint, float x1, float x2, float top, float height,
                           const std::vector<std::pair<float, float>> *gaps, float clearance) {
    if (x2 <= x1 || height <= 0) return;
    SkPaint solid = paint;
    solid.setAntiAlias(true);
    float start = x1;
    if (gaps != nullptr) {
        for (const auto &gap : *gaps) {
            const float gx1 = gap.first - clearance, gx2 = gap.second + clearance;
            if (gx2 <= start || gx1 >= x2) continue;
            if (gx1 > start) canvas->drawRect(SkRect::MakeLTRB(start, top, gx1, top + height), solid);
            start = std::max(start, gx2);
        }
    }
    if (start < x2) canvas->drawRect(SkRect::MakeLTRB(start, top, x2, top + height), solid);
}

// The system decorations of a paragraph under a transform beyond its size (rotated, sheared, unevenly scaled text),
// as TextDecorations draws TextLayout's there: the band the rule makes at the scale across the baseline, in the
// paragraph's layout space (the canvas carries the transform), filled antialiased; its rows on device rows (or
// columns) when the baseline runs along them. CoreText's own behaviour (measured 2026-11-10: rotated, sheared, and
// rotated and scaled, its band is the axis-aligned one at that scale, antialiased).
void drawTransformedDecorations(tl::Paragraph &paragraph, SkCanvas *canvas, float paragraphTop, float lineBaseline, float layoutScale,
                                const jchar *chars, jint length, jint runCount, const jint *runEnds, const jlong *contexts, const jint *colours,
                                const jint *flags, const std::vector<float> &shift, const SkPaint &paint, float extraAlpha, bool swizzled) {
    bool any = false;
    for (jint r = 0; r < runCount; r++) any |= (flags[r] & SK_PARAGRAPH_SYSTEM_DECORATIONS) != 0;
    if (!any) return;
    const std::vector<DecorationMetrics> lineUnderline = lineUnderlines(runCount, contexts, flags, shift, layoutScale);
    const bool directWriteRule = SKPlatform_TextDefaults().decorationRule == SKDecorationRule::DirectWrite;
    const SkMatrix toDevice = canvas->getTotalMatrix();
    const float scale = scaleAcross(toDevice);
    const float along = std::hypot(toDevice.getScaleX(), toDevice.getSkewY());   // device pixels per layout unit along the baseline
    if (scale == 0 || along == 0) return;
    std::vector<uint32_t> runUtf8Start(runCount + 1, 0);
    jint start = 0;
    for (jint r = 0; r < runCount; r++) {
        const jint end = std::min(runEnds[r], length);
        runUtf8Start[r + 1] = runUtf8Start[r] + (end > start ? (uint32_t) SkUnicode::convertUtf16ToUtf8((const char16_t *) chars + start, end - start).size() : 0);
        start = std::max(start, end);
    }
    const bool skipInk = SKPlatform_TextDefaults().decorationGaps;
    const std::pair<uint32_t, uint32_t> decorated = decoratedUtf8Range(chars, length);
    const std::vector<uint32_t> zeroWidth = zeroWidthUtf8(chars, length);
    paragraph.visit([&](int, const tl::Paragraph::VisitorInfo *info) {
        if (info == nullptr || info->count <= 0) return;
        auto runOf = [&](uint32_t utf8) {
            jint r = 0;
            while (r + 1 < runCount && runUtf8Start[r + 1] <= utf8) r++;
            return r;
        };
        const DecorationMetrics m = decorationMetrics(info->font);
        const float exact = paragraphTop + info->origin.fY;
        const float baseline = exact + baselineSnap(toDevice, exact);
        const DecorationBand band = underlineBand(m, scale, (float) SKFontScaler_DisplayScale(contexts[runOf(info->utf8Starts[0])]));
        const float underlineTop = baseline + band.top / scale, underlineHeight = band.height / scale;
        std::vector<std::pair<float, float>> gaps;
        bool gapsReady = false;
        for (int i = 0; i < info->count;) {
            const jint r = runOf(info->utf8Starts[i]);
            int j = i + 1;
            while (j < info->count && runOf(info->utf8Starts[j]) == r) j++;
            if (flags[r] & SK_PARAGRAPH_SYSTEM_DECORATIONS) {
                SkPaint runPaint = paint;
                if (flags[r] & SK_PARAGRAPH_COLOUR) runPaint = colouredPaint(runPaint, (SkColor) colours[r], extraAlpha, swizzled);
                float x1 = std::numeric_limits<float>::max(), x2 = std::numeric_limits<float>::lowest();
                for (int k = i; k < j; k++) {
                    if (info->utf8Starts[k] < decorated.first || info->utf8Starts[k] >= decorated.second) continue;   // line-end whitespace
                    SkScalar advance = 0;
                    if (!zeroWidthAt(zeroWidth, info->utf8Starts[k])) info->font.getWidths({&info->glyphs[k], 1}, {&advance, 1});
                    x1 = std::min(x1, info->origin.fX + info->positions[k].fX);
                    x2 = std::max(x2, info->origin.fX + info->positions[k].fX + advance);
                }
                if (x1 > x2) {   // the group is all line-end whitespace
                    i = j;
                    continue;
                }
                x1 = roundedEnd(toDevice, x1);
                x2 = roundedEnd(toDevice, x2);
                if (flags[r] & SK_PARAGRAPH_UNDERLINE) {
                    // under CoreText's rule the band of the run's stretch (lineUnderlines) from the line's baseline
                    float top = underlineTop, height = underlineHeight;
                    if (!directWriteRule) {
                        const DecorationBand line = underlineBand(lineUnderline[r], scale, (float) SKFontScaler_DisplayScale(contexts[r]));
                        top = lineBaseline + line.top / scale;
                        height = line.height / scale;
                        gapsReady = false;
                    }
                    if (skipInk && !gapsReady) {
                        gaps = inkCrossings(info->font, info->glyphs, info->positions, info->count, {info->origin.fX, exact},
                                            top, top + height, false);
                        gapsReady = true;
                    }
                    fillLayoutBand(canvas, runPaint, x1, x2, top, height, skipInk ? &gaps : nullptr,
                                   kDecorationClearance * (float) SKFontScaler_DisplayScale(contexts[r]) / along);
                }
                if (flags[r] & SK_PARAGRAPH_STRIKETHROUGH) {
                    const DecorationBand strike = strikethroughBand(m.strikethroughCentre * scale, m.strikethroughThickness * scale);
                    fillLayoutBand(canvas, runPaint, x1, x2, baseline + strike.top / scale, strike.height / scale, nullptr, 0);
                }
            }
            i = j;
        }
    });
}

void SKBackend_DrawParagraphBySkia(jboolean smoothing, jboolean rightToLeft, jfloat originX, jfloat originY, const jchar *chars, jint length,
                                   jint runCount, const jint *runEnds, const jlong *contexts, const jint *colours,
                                   const jint *flags, const jint *trackingBits, const jint *shiftBits, const jint *backgrounds) {
    REQUIRE_CANVAS_TEXT();
    // drawn through the canvas: every pending batch first, of any kind
    { SKDrawCauseScope cause(SK_COUNT_MESH_DRAWS_AT_DIRECT); flushAllBatches(); }
    const SkPaint paint = SKBackend_MakePaint(true);
    SKBackend_DrawParagraphOnCanvas([](const SkIRect &) { return g.canvas; }, paint, paint.getColor(), g.extraAlpha, false, smoothing, rightToLeft, originX, originY,
                                    chars, length, runCount, runEnds, contexts, colours, flags, trackingBits, shiftBits, backgrounds);
}

jlong SKBackend_DrawParagraphOnCanvas(const SKCanvasForBounds &canvasFor, const SkPaint &paint, SkColor textColour, float extraAlpha, bool swizzled,
                                      jboolean smoothing, jboolean rightToLeft, jfloat originX, jfloat originY,
                                      const jchar *chars, jint length, jint runCount, const jint *runEnds, const jlong *contexts, const jint *colours,
                                      const jint *flags, const jint *trackingBits, const jint *shiftBits, const jint *backgrounds) {
    std::lock_guard<std::mutex> lock(gTextLayoutMutex);
    const Shaping &s = shaping();
    if (length <= 0 || runCount <= 0 || !s.unicode) {
        return 0;
    }
    Paragraphs &p = paragraphs();
    tl::ParagraphStyle style;
    style.setTextAlign(tl::TextAlign::kLeft);
    style.setTextDirection(rightToLeft ? tl::TextDirection::kRtl : tl::TextDirection::kLtr);   // Java's base direction (SKTextRenderer)
    style.setApplyRoundingHack(false);
    // no synthetic bold or italic for a fallback face of another style: the text path draws a fallback font's glyphs
    // in its own style (a regular Apple Color Emoji in a bold run), and an emboldened bitmap glyph came out empty
    // through the luminance proxy (Skia renders an emboldened glyph from its outline, which an emoji does not have)
    style.setFakeMissingFontStyles(false);
    // the fallback manager: the first run's when every run has its proxy (proxyClass), or none;
    // else the plain cascade, and the painter gives each run's fallback glyphs that run's class (ParagraphRuns)
    sk_sp<SkFontMgr> fallback = SKPlatform_FontManager();
    std::optional<ParagraphRuns> perRun;
    {
        std::vector<int> classes(runCount, NO_PROXY);
        for (jint r = 0; r < runCount; r++) {
            SkFont font;
            const SkColor colour = (flags[r] & SK_PARAGRAPH_COLOUR) ? (SkColor) colours[r] : textColour;
            if (SKFontScaler_Font(contexts[r], &font)) {
                classes[r] = proxyClass(font, contexts[r], colour);
            }
        }
        if (std::any_of(classes.begin(), classes.end(), [&](int c) { return c != classes[0]; })) {
            perRun.emplace();
            perRun->classes = std::move(classes);
            perRun->paints.resize(runCount);
            fallback = cascadeManager();
        } else {
            SkFont first;
            const SkColor firstColour = (flags[0] & SK_PARAGRAPH_COLOUR) ? (SkColor) colours[0] : textColour;
            if (SKFontScaler_Font(contexts[0], &first)) {
                fallback = fallbackManager(first, contexts[0], firstColour);
            }
        }
    }
    // whole-pixel advances of a system font round in user space (SKFontScaler_LayoutScale): the paragraph
    // is laid out at the user size and painted under the scale, so the glyphs land where Java's layout puts
    // them; the first run's strike decides for the paragraph (one graphics, one scale)
    const float layoutScale = (float) SKFontScaler_LayoutScale(contexts[0]);
    std::vector<float> shift(runCount, 0);   // superscripts and subscripts: the baseline shift in layout pixels
    for (jint r = 0; r < runCount; r++) {
        std::memcpy(&shift[r], &shiftBits[r], sizeof(float));
    }
    std::unique_ptr<tl::ParagraphBuilder> builder = tl::ParagraphBuilder::make(style, p.collection(fallback), s.unicode);
    if (!builder) {
        return 0;
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
        SkPaint runPaint = paint;
        runPaint.setAntiAlias(font.getEdging() != SkFont::Edging::kAlias);   // the decorations follow the text's edging
        const SkColor runColour = (flags[r] & SK_PARAGRAPH_COLOUR) ? (SkColor) colours[r] : textColour;   // Java's, for the luminance
        if (flags[r] & SK_PARAGRAPH_COLOUR) {
            runPaint = colouredPaint(runPaint, runColour, extraAlpha, swizzled);
        }
        applyProxy(font, contexts[r], runColour);
        sk_sp<SkTypeface> typeface = font.refTypeface();
        if (p.registered.emplace(typeface->uniqueID(), typeface).second) {
            p.provider->registerTypeface(typeface, registeredAlias(typeface));
        }
        tl::TextStyle ts;
        ts.setFontFamilies({typefaceAlias(typeface.get())});
        ts.setFontSize(font.getSize() / layoutScale);
        float tracking;
        std::memcpy(&tracking, &trackingBits[r], sizeof(tracking));
        if (tracking != 0) {
            ts.setLetterSpacing(tracking * ts.getFontSize());   // Java's tracking: em of the run's font per glyph advance
        }
        if (shift[r] != 0) {
            // Java's superscript / subscript offset (Font.getTransform); SkParagraph puts the line's baseline and each
            // shifted run's on whole layout pixels, where the text path rounds Java's exact position with the draw:
            // a subscript may sit a row off the text path's (accepted, JBR-10654 ledger §13.1)
            ts.setBaselineShift(shift[r]);
        }
        ts.setFontStyle(typeface->fontStyle());
        ts.setFontEdging(font.getEdging());
        ts.setSubpixel(font.isSubpixel());
        ts.setFontHinting(font.getHinting());
        // Java's layout features for the run (FontAccess.getFeatures): kern from KERNING, liga and calt from LIGATURES,
        // every other feature at the font's default (clig included, as TextLayout leaves it)
        const int kern = (flags[r] & SK_PARAGRAPH_KERNING) ? 1 : 0, liga = (flags[r] & SK_PARAGRAPH_LIGATURES) ? 1 : 0;
        ts.addFontFeature(SkString("kern"), kern);
        ts.addFontFeature(SkString("liga"), liga);
        ts.addFontFeature(SkString("calt"), liga);
        if (perRun) {
            perRun->paints[r] = runPaint;
            ts.setForegroundPaintID(r);
        } else {
            ts.setForegroundPaint(runPaint);
        }
        if ((flags[r] & SK_PARAGRAPH_BACKGROUND) && !(flags[r] & SK_PARAGRAPH_LINE_BACKGROUND)) {
            // TextAttribute.BACKGROUND (or the swapped foreground): SkParagraph's box behind the run, filled as the
            // graphics fills a shape (Java fills the run's logical bounds, Decoration.drawTextAndDecorations); the
            // line's band is ours (drawLineBackgrounds)
            SkPaint backgroundPaint = colouredPaint(paint, (SkColor) backgrounds[r], extraAlpha, swizzled);
            backgroundPaint.setAntiAlias((flags[r] & SK_PARAGRAPH_BACKGROUND_AA) != 0);
            ts.setBackgroundPaint(backgroundPaint);
        }
        unsigned decoration = tl::kNoDecoration;
        if (flags[r] & SK_PARAGRAPH_UNDERLINE) {
            decoration |= tl::kUnderline;
        }
        if (flags[r] & SK_PARAGRAPH_STRIKETHROUGH) {
            decoration |= tl::kLineThrough;
        }
        // decorations=system: SkParagraph draws none; ours follow after the paint (drawSystemDecorations),
        // by the rule Java's TextLayout uses (sun.font.TextDecorations) — the two routes render alike
        const bool ownDecorations = (flags[r] & SK_PARAGRAPH_SYSTEM_DECORATIONS) != 0;
        ts.setDecoration(ownDecorations ? tl::TextDecoration::kNoDecoration : (tl::TextDecoration) decoration);
        ts.setDecorationMode(tl::kThrough);   // Java's underline runs through the descenders
        ts.setDecorationStyle(tl::kSolid);
        ts.setDecorationColor(runPaint.getColor());
        builder->pushStyle(ts);
        const SkString utf8 = SkUnicode::convertUtf16ToUtf8((const char16_t *) chars + start, end - start);
        builder->addText(utf8.c_str(), utf8.size());
        builder->pop();
        start = end;
    }
    float largestFont = 0;   // layout units: the ink's reach beyond the line box (overhangs, accents, shifted runs)
    for (jint r = 0; r < runCount; r++) {
        SkFont f;
        if (SKFontScaler_Font(contexts[r], &f)) largestFont = std::max(largestFont, f.getSize() / layoutScale);
    }
    std::unique_ptr<tl::Paragraph> paragraph = builder->Build();
    if (!paragraph) {
        return 0;
    }
    paragraph->layout(1e9f);   // one line: no wrapping
    // the line's baseline in paragraph space from the first glyph run itself: getAlphabeticBaseline() sits
    // 0.48 px off it at 13 px (SkParagraph rounds its line metrics), which the device rounding of glyph rows
    // hid at scale 1 and which a layout under a scale would turn into a whole row
    // the first unshifted run decides (a shifted one sits off the line's baseline); with every run shifted, the
    // first run's less its shift (the shift's rounding to a whole layout pixel then moves the line)
    float baseline = paragraph->getAlphabeticBaseline();
    float shiftedBaseline = NAN;
    std::vector<uint32_t> runUtf8Ends(runCount, 0);
    {
        jint s = 0; uint32_t total = 0;
        for (jint r = 0; r < runCount; r++) {
            const jint e = std::min(runEnds[r], length);
            if (e > s) total += (uint32_t) SkUnicode::convertUtf16ToUtf8((const char16_t *) chars + s, e - s).size();
            runUtf8Ends[r] = total;
            s = std::max(s, e);
        }
    }
    bool baselineFound = false;
    paragraph->visit([&](int, const tl::Paragraph::VisitorInfo *info) {
        if (info == nullptr || info->count <= 0 || baselineFound) return;
        jint r = 0;
        while (r + 1 < runCount && runUtf8Ends[r] <= info->utf8Starts[0]) r++;
        const float y = info->origin.fY + info->positions[0].fY;
        if (shift[r] == 0) {
            baseline = y;
            baselineFound = true;
        } else if (std::isnan(shiftedBaseline)) {
            shiftedBaseline = y - shift[r];
        }
    });
    if (!baselineFound && !std::isnan(shiftedBaseline)) {
        baseline = shiftedBaseline;
    }
    // the paragraph's device bounds for the caller's lock: its line box from the paint origin, grown by the largest
    // run's size, under the paint transform (the origin, the strike's transform beyond its size, the layout scale)
    SkMatrix remainder;
    const bool transformed = SKFontScaler_Remainder(contexts[0], &remainder);
    const float paragraphTop = originY / layoutScale - baseline;   // in layout space
    SkMatrix toDevice;
    SkRect box;
    if (transformed) {
        toDevice = SkMatrix::Translate(originX, originY);
        toDevice.preConcat(remainder);
        toDevice.preScale(layoutScale, layoutScale);
        box = SkRect::MakeXYWH(0, -baseline, paragraph->getLongestLine(), paragraph->getHeight());
    } else {
        toDevice = SkMatrix::Scale(layoutScale, layoutScale);
        box = SkRect::MakeXYWH(originX / layoutScale, paragraphTop, paragraph->getLongestLine(), paragraph->getHeight());
    }
    SkCanvas *canvas = canvasFor(toDevice.mapRect(box.makeOutset(largestFont, largestFont)).roundOut().makeOutset(2, 2));
    if (canvas == nullptr) {
        return 0;
    }
    const std::pair<float, float> lineBand = lineBackgroundBand(runCount, contexts, shift, layoutScale,
                                                                layoutScale / (float) SKFontScaler_DisplayScale(contexts[0]));
    if (transformed) {
        // rotated, sheared or unevenly scaled text (every run shares the transform, SKTextRenderer checks it): laid
        // out in the font's own space, painted under the strike's transform beyond its size from the device origin,
        // as the text path draws such a run; SkParagraph's decorations follow the canvas, and the system decorations
        // are the rule's band at the scale across the baseline, as TextLayout's (drawTransformedDecorations)
        SkAutoCanvasRestore restore(canvas, true);
        canvas->translate(originX, originY);
        canvas->concat(remainder);
        canvas->scale(layoutScale, layoutScale);
        // the line's baseline at y 0 of this space
        drawLineBackgrounds(*paragraph, canvas, 0, 0, chars, length, runCount, runEnds, flags, backgrounds, lineBand, paint, extraAlpha, swizzled);
        paintParagraph(*paragraph, canvas, 0, -baseline, paint, extraAlpha, swizzled, perRun ? &*perRun : nullptr);
        drawTransformedDecorations(*paragraph, canvas, -baseline, 0, layoutScale, chars, length, runCount, runEnds, contexts, colours, flags, shift,
                                   paint, extraAlpha, swizzled);
    } else {
        // the glyphs from CoreText's row on macOS (glyphRow), the backgrounds and the system decorations from the exact
        // baseline (originY, the line's: the runs' shifts apart)
        const float glyphTop = glyphRow(originY, SKFontScaler_DisplayScale(contexts[0])) / layoutScale - baseline;
        if (layoutScale != 1) {
            SkAutoCanvasRestore restore(canvas, true);
            canvas->scale(layoutScale, layoutScale);
            drawLineBackgrounds(*paragraph, canvas, originX / layoutScale, originY / layoutScale, chars, length, runCount, runEnds, flags, backgrounds,
                                lineBand, paint, extraAlpha, swizzled);
            paintParagraph(*paragraph, canvas, originX / layoutScale, glyphTop, paint, extraAlpha, swizzled, perRun ? &*perRun : nullptr);
        } else {
            drawLineBackgrounds(*paragraph, canvas, originX, originY, chars, length, runCount, runEnds, flags, backgrounds, lineBand, paint, extraAlpha,
                                swizzled);
            paintParagraph(*paragraph, canvas, originX, glyphTop, paint, extraAlpha, swizzled, perRun ? &*perRun : nullptr);
        }
        drawSystemDecorations(*paragraph, canvas, originX / layoutScale, paragraphTop, originY / layoutScale, layoutScale, chars, length, runCount,
                              runEnds, contexts, colours, flags, shift, paint, extraAlpha, swizzled);
    }
    gParagraphsBySkia.fetch_add(1, std::memory_order_relaxed);
    gGlyphsShapedBySkia.fetch_add(length, std::memory_order_relaxed);   // the character count stands in for the glyph count
    gGlyphsDrawnBySkia.fetch_add(length, std::memory_order_relaxed);
    return length;
}

void SKBackend_DrawGlyphList(jint numGlyphs, jboolean usePositions, jboolean subPixPos,
                             jboolean rgbOrder, jint lcdContrast,
                             jfloat glyphListOrigX, jfloat glyphListOrigY,
                             const jlong *images, const jfloat *positions) {
    REQUIRE_CANVAS_TEXT();
    SkPaint paint = SKBackend_MakePaint(false);
    // a blend other than SrcOver (XOR is a blender, not a mode), or a gradient or texture paint (the atlas is the batch's
    // shader, the colour the vertices'): the grayscale glyphs as coverage, one by one
    const std::optional<SkBlendMode> blendMode = paint.asBlendMode();
    const bool asCoverage = (blendMode.has_value() && *blendMode != SkBlendMode::kSrcOver) || paint.getShader() != nullptr;
    // The buckets' caches begun for this list, grayscale and LCD. The buckets' batches
    // stay open side by side (glyphs of one paint commute under the blends used), but a
    // switch between grayscale and LCD glyphs draws the pending batches first.
    bool aaBegun[SK_GLYPH_BUCKETS] = {}, lcdBegun[SK_GLYPH_BUCKETS] = {};
    bool anyAA = false, anyLcd = false;
    SkPaint lcdPaint;
    bool lcdPaintReady = false;
    SkCanvas *lcdCanvas = g.canvas;   // the coverage surface when accumulating

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
        // glyph, colour glyph) or a switch between grayscale and LCD glyphs first
        // draws whatever is batched.
        auto flushBatches = [&] {
            flushGlyphBatches();
            for (int b = 0; b < SK_GLYPH_BUCKETS; b++) {
                aaBegun[b] = false;
                lcdBegun[b] = false;
            }
            anyAA = false;
            anyLcd = false;
        };
        if (ginfo->format == sun_font_StrikeCache_PIXEL_FORMAT_GREYSCALE && asCoverage) {
            flushBatches();
            drawGlyphAsCoverage(ginfo, subimage, fx, fy, paint);
        } else if (ginfo->format == sun_font_StrikeCache_PIXEL_FORMAT_GREYSCALE) {
            if (anyLcd) {
                flushBatches();
            }
            SKGlyphCache *aa = glyphCache(false, ginfo);
            if (aa != nullptr) {
                const int b = SKGlyphCache_BucketFor(ginfo);
                if (!aaBegun[b]) {
                    SKGlyphCache_Begin(aa, g.canvas, paint);
                    aaBegun[b] = true;
                    anyAA = true;
                }
                if (SKGlyphCache_Draw(aa, (GlyphInfo *) ginfo, subimage, false, fx, fy)) {
                    continue;
                }
            }
            flushBatches();
            drawGlyphNoCache(ginfo, subimage, fx, fy, paint);
        } else if (ginfo->format == sun_font_StrikeCache_PIXEL_FORMAT_BGRA) {
            flushBatches();
            drawColorGlyph(ginfo, fx, fy);
        } else {
            if (anyAA) {
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
                        lcdCanvas = beginLcdAccumulation(lcdPaint, lcdContrast);
                        if (lcdCanvas == nullptr) {
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
                // the nearest third of a pixel, as the software loops place it (DrawGlyphList.c): the position
                // nudged by a sixth less a half, so a glyph at a whole position takes no shift
                const float pos = gx + 0.1666667f - 0.5f;
                fx = std::floor(pos);
                jint frac = (jint) ((pos - fx) * 3);
                if (frac != 0) {
                    rowBytesOffset = 3 - frac;
                    fx += 1;
                }
            }
            SKGlyphCache *lcd = rowBytesOffset == 0 ? glyphCache(true, ginfo) : nullptr;
            if (lcd != nullptr) {
                const int b = SKGlyphCache_BucketFor(ginfo);
                if (!lcdBegun[b]) {
                    if (gConfig.lcdMode == SKLcdMode::Accumulate) {
                        hookLcdAccumulation(lcd);
                    }
                    SKGlyphCache_Begin(lcd, lcdCanvas, lcdPaint, gConfig.lcdMode == SKLcdMode::TwoPass ? &gLcdTwoPass : nullptr);
                    lcdBegun[b] = true;
                    anyLcd = true;
                }
                if (SKGlyphCache_Draw(lcd, (GlyphInfo *) ginfo, subimage, rgbOrder, fx, fy)) {
                    continue;
                }
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
        SKStats_Count(SK_COUNT_MESH_DRAWS); SKStats_Count(SK_COUNT_MESH_DRAWS_IMMEDIATE);
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
        SKStats_Count(SK_COUNT_MESH_DRAWS); SKStats_Count(SK_COUNT_MESH_DRAWS_IMMEDIATE);
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


// sun.java2d.skia.imageAtlas (x64hw, 2026-11-04; on by default since 2026-11-06): a blit the caller found may be a
// sprite (SKBackend_Blit), of a small Skia surface of this context, unscaled and pixel-aligned, with a plain SrcOver
// composite, becomes a sprite of the context's image atlas (SKImageAtlas.h): one mesh for the small images drawn in
// a row instead of a draw each, each with its own image paint key. False: the caller draws the blit itself.
static bool imageAtlasBlit(SKSDOps *src, const sk_sp<SkImage> &image, const SkPaint &p, jint sx1, jint sy1, jint sx2, jint sy2,
                          jdouble dx1, jdouble dy1, jdouble dx2, jdouble dy2) {
    if (src == nullptr || src->state == nullptr || g.cc == nullptr
            || sx2 - sx1 != dx2 - dx1 || sy2 - sy1 != dy2 - dy1 || dx1 != std::floor(dx1) || dy1 != std::floor(dy1)
            || !SKImageAtlas_Fits(sx2 - sx1, sy2 - sy1) || p.asBlendMode() != SkBlendMode::kSrcOver
            || p.getShader() != nullptr || p.getColorFilter() != nullptr) {
        return false;
    }
    if (g.cc->imageAtlas == nullptr && !g.cc->imageAtlasFailed) {
        g.cc->imageAtlas = SKImageAtlas_Create(g.cc);
        g.cc->imageAtlasFailed = g.cc->imageAtlas == nullptr;
    }
    if (g.cc->imageAtlas == nullptr) {
        return false;
    }
    SkPaint sprite = p;
    sprite.setColor4f(SkColor4f{1, 1, 1, g.extraAlpha});   // the colour modulates the atlas texel: white, the extra alpha
    return SKImageAtlas_Draw(g.cc->imageAtlas, g.canvas, sprite, src->state, image, sx1, sy1, sx2 - sx1, sy2 - sy1,
                            (jint) dx1, (jint) dy1);
}

void SKBackend_Blit(JNIEnv *env, jint packedParams, jint sx1, jint sy1, jint sx2, jint sy2,
                      jdouble dx1, jdouble dy1, jdouble dx2, jdouble dy2, jlong pSrc, jlong pDst) {
    if (g.canvas == nullptr) {
        return;
    }
    markTouched();
    jboolean isoblit = (packedParams >> sun_java2d_skia_SKBlitLoops_OFFSET_ISOBLIT) & 1;
    jint hint        = (packedParams >> sun_java2d_skia_SKBlitLoops_OFFSET_HINT) & 0xff;
    jint srctype     = (packedParams >> sun_java2d_skia_SKBlitLoops_OFFSET_SRCTYPE) & 0xff;
    // A blit that may become an image atlas sprite (of a Skia surface, no transform, no XOR, no MSAA, not from the
    // destination) draws only the other kinds' batches, so consecutive sprites share the atlas batch; any other blit
    // draws everything pending first, and so does one that turns out not to be a sprite (below).
    const bool maySprite = gConfig.imageAtlas && isoblit && !g.xorMode && !SKBackend_Multisampled() && !SKBackend_EdgeAAQuads()
            && !g.hasMatrix && (SKSDOps *) jlong_to_ptr(pSrc) != g.dst && dx1 < dx2 && dy1 < dy2 && sx1 < sx2 && sy1 < sy2;
    {
        SKDrawCauseScope cause(SK_COUNT_MESH_DRAWS_AT_BLIT);
        if (maySprite) {
            drawOtherKinds(3);
        } else {
            flushAllBatches();
        }
    }

    sk_sp<SkImage> image;
    SKSDOps *linkedSrc = nullptr;   // a Skia surface of this context, sampled through its linked image
    SkRect srcRect;
    if (isoblit) {
        SKSDOps *src0 = (SKSDOps *) jlong_to_ptr(pSrc);
        SKStatsScope sourceStats(SK_SPAN_BLIT_SOURCE);
        if (SKStats_IsEnabled() && src0 != nullptr && src0->state != nullptr && src0->state->cc != nullptr) {
            SKStats_Count(SK_COUNT_SURFACE_BLITS);
            if (src0->state->drawnSinceBlit && src0->state->drawnGeneration == src0->state->cc->recordingGeneration) {
                SKStats_Count(SK_COUNT_BLITS_OF_PENDING_SOURCES);
                if (src0->type != sun_java2d_pipe_hw_AccelSurface_TEXTURE) {
                    SKStats_Count(SK_COUNT_PENDING_SOURCES_VOLATILE);
                }
            }
            src0->state->drawnSinceBlit = false;
        }
        SKSDOps *src = (SKSDOps *) jlong_to_ptr(pSrc);
        if (src != nullptr && !sourceUsable(src, "SKBackend_Blit")) {
            return;
        }
        bool linked = false;
        image = sampledImage(src, &linked);
        if (linked) {
            linkedSrc = src;
        }
        sourceStats.end();
        srcRect = SkRect::MakeLTRB(sx1, sy1, sx2, sy2);
    } else {
        SKStatsScope softwareStats(SK_SPAN_BLIT_SOFTWARE);
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
    SKStatsScope drawStats(SK_SPAN_BLIT_DRAW);
    if (maySprite && imageAtlasBlit(linkedSrc, image, p, sx1, sy1, sx2, sy2, dx1, dy1, dx2, dy2)) {
        return;
    }
    if (maySprite) {
        SKDrawCauseScope cause(SK_COUNT_MESH_DRAWS_AT_BLIT);
        flushAllBatches();   // not a sprite: everything pending before it first
    }
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
                SKStatsScope readbackStats(SK_SPAN_READBACK);
                auto *req = new ReadbackRequest();
                req->w = clipped.width();
                req->h = clipped.height();
                req->pixels.resize((size_t) req->w * req->h * 4);
                submitPending(cc, false);   // earlier work of other contexts first (submit=present)
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

// Where Graphite presents (Windows over Vulkan): a window's draw into its next swap chain image,
// recorded into the window's context at the end of a flush, before the flush's submit, so that
// the frame is one recording and one submit instead of two of each. SKBackend_FlushRecording
// inserts the context's recording with the image's semaphores (wait for the acquire, signal the
// present) and its present layout; SKBackend_EndOfFlush presents it after the submit. One window
// per context and flush; any other window of the context presents on its own (presentThroughGraphite).
struct SKFoldedPresent {
    SKSDOps *dst = nullptr;
    SKPlatformPresentTarget target;
    sk_sp<SkSurface> image;   // null: the image could not be wrapped, nothing drawn into it
    bool inserted = false;
};
static std::unordered_map<SKConfigContext *, SKFoldedPresent> gFolded;

static std::atomic<jlong> gRecordings{0};   // recordings snapped (SKBackend_Recordings)

void SKBackend_FlushRecording(SKConfigContext *cc) {
    if (cc == nullptr || !cc->recorder) {
        return;
    }
    { SKDrawCauseScope cause(SK_COUNT_MESH_DRAWS_AT_FLUSH); flushBatchesOf(cc); }
    cc->recordingGeneration++;
    auto folded = gFolded.find(cc);
    SKFoldedPresent *present = folded != gFolded.end() && !folded->second.inserted ? &folded->second : nullptr;
    // Graphite registers a recording's new pipelines while snap() builds its draw passes and
    // compiles them in insertRecording (tint, the D3D shader compiler, the driver's compiler
    // threads): such an insert costs tens of milliseconds where a warm one costs a fraction of one
    const uint64_t pipelinesBefore = gPipelinesBuilt;
    std::unique_ptr<Recording> recording;
    {
        SKStatsScope snapStats(SK_SPAN_SNAP);
        recording = cc->recorder->snap();
    }
    if (recording) {
        gRecordings.fetch_add(1, std::memory_order_relaxed);
        InsertRecordingInfo info;
        info.fRecording = recording.get();
        if (present != nullptr) {
            SKPlatformPresentTarget &t = present->target;
            info.fTargetSurface = present->image.get();
            info.fTargetTextureState = present->image ? &t.presentState : nullptr;
            info.fNumWaitSemaphores = 1;
            info.fWaitSemaphores = &t.waitSemaphore;
            info.fNumSignalSemaphores = 1;
            info.fSignalSemaphores = &t.signalSemaphore;
            info.fFinishedProc = t.finishedProc;     // Graphite calls it whether or not the insert succeeds
            info.fFinishedContext = t.finishedContext;
            present->inserted = true;
        }
        const uint64_t start = SKStats_IsEnabled() ? SKStats_Now() : 0;
        SKStatsScope insertStats(SK_SPAN_INSERT);
        if (!cc->context->insertRecording(info)) {
            J2dRlsTraceLn(J2D_TRACE_WARNING, "SKBackend_FlushRecording: insertRecording failed");
            if (present != nullptr) {
                present->image.reset();   // nothing to present
            }
        }
        if (start != 0 && gPipelinesBuilt != pipelinesBefore) {
            SKStats_AddSpan(SK_SPAN_INSERT_COMPILING, SKStats_Now() - start);
        }
    } else if (present != nullptr && present->target.finishedProc != nullptr) {
        present->target.finishedProc(present->target.finishedContext, skgpu::CallbackResult::kFailed);
        present->inserted = true;
        present->image.reset();
    }
}

// Graphite purges a purgeable resource (one no recording uses any more) only when a budget forces it out, 256 MB the
// recorder's and 256 MB the context's, unless its client asks: the client is to purge what it has not used for a while
// (performDeferredCleanup). The backend never asked, so over RenderPerfTest the recorder kept 194 MB of upload and
// vertex buffers it had finished with (queued frames each take fresh ones; read back by SKEnvironment.gpuMemoryReport)
// and the process ran 260 MB above Metal's. Now, at most once a second, after a submit and on the idle flusher's
// timeout, what was not used for sun.java2d.skia.gpuCachePurgeSeconds goes (0: never, Graphite's budgets alone).
static std::chrono::milliseconds gPurgeAge{5000};
static std::chrono::steady_clock::time_point gLastPurge;

void SKBackend_SetGpuCachePurgeAge(jint seconds) {
    gPurgeAge = std::chrono::milliseconds((jlong) std::max(0, seconds) * 1000);
}

void SKBackend_PurgeIdleResources() {
    if (gPurgeAge.count() <= 0) return;
    const auto now = std::chrono::steady_clock::now();
    if (now - gLastPurge < std::chrono::seconds(1)) return;
    gLastPurge = now;
    for (auto &[config, cc] : gContexts) {
        if (!cc->recorder || !cc->context) continue;
        cc->recorder->performDeferredCleanup(gPurgeAge);
        cc->context->performDeferredCleanup(gPurgeAge);
    }
}

static void submitOne(SKConfigContext *cc, bool syncToCpu) {
    SKBackend_FlushRecording(cc);
    {
        SKStatsScope submitStats(syncToCpu ? SK_SPAN_SUBMIT_WAIT : SK_SPAN_SUBMIT);
        cc->context->submit(syncToCpu ? SyncToCpu::kYes : SyncToCpu::kNo);
    }
    SKBackend_PurgeIdleResources();
}

// Submits the pending contexts in their order, except `except` (which its caller submits next).
static void submitPending(SKConfigContext *except, bool syncToCpu) {
    std::vector<SKConfigContext *> pending;
    pending.swap(gPendingSubmit);
    gDeferredFlushes = 0;
    for (SKConfigContext *cc : pending) {
        if (cc != except && cc->context) {
            submitOne(cc, syncToCpu);
        }
    }
}

static void deferSubmit(SKConfigContext *cc) {
    SKBackend_FlushRecording(cc);   // snap + insert; the GPU work waits for the next submit
    if (std::find(gPendingSubmit.begin(), gPendingSubmit.end(), cc) == gPendingSubmit.end()) {
        gPendingSubmit.push_back(cc);
    }
}

void SKBackend_Submit(SKConfigContext *cc, bool syncToCpu) {
    if (cc == nullptr || !cc->context) {
        return;
    }
    submitPending(cc, false);
    submitOne(cc, syncToCpu);
}

// Diagnostics (SKEnvironment.gpuMemoryReport): what Graphite's resource caches hold, per context — the
// budgets and their use, then every resource the recorder's and the context's caches enumerate, by type and label
// (count, bytes, purgeable bytes; budgeted or not). Called with the render queue locked.
namespace {
struct MemoryTally : SkTraceMemoryDump {
    struct Row { int count = 0; uint64_t bytes = 0, purgeable = 0; };
    struct Entry { std::string type, label; uint64_t bytes = 0, purgeable = 0; bool budgeted = true, wrapped = false; };
    std::map<std::string, Entry> entries;
    void dumpNumericValue(const char *name, const char *valueName, const char *, uint64_t value) override {
        if (!strcmp(valueName, "size")) entries[name].bytes = value;
        else if (!strcmp(valueName, "purgeable_size")) entries[name].purgeable = value;
    }
    void dumpStringValue(const char *name, const char *valueName, const char *value) override {
        if (!strcmp(valueName, "type")) entries[name].type = value;
        else if (!strcmp(valueName, "label")) entries[name].label = value;
    }
    void setMemoryBacking(const char *, const char *, const char *) override {}
    void setDiscardableMemoryBacking(const char *, const SkDiscardableMemory &) override {}
    LevelOfDetail getRequestedDetails() const override { return LevelOfDetail::kObjectsBreakdowns_LevelOfDetail; }
    void dumpWrappedState(const char *name, bool wrapped) override { entries[name].wrapped = wrapped; }
    void dumpBudgetedState(const char *name, bool budgeted) override { entries[name].budgeted = budgeted; }
    void report(std::string &out, const char *owner) {
        std::map<std::string, Row> rows;
        for (auto &[name, e] : entries) {
            std::string key = std::string(owner) + " " + e.type + " [" + e.label + "]" + (e.wrapped ? " wrapped" : "") + (e.budgeted ? "" : " unbudgeted");
            Row &r = rows[key]; r.count++; r.bytes += e.bytes; r.purgeable += e.purgeable;
        }
        char line[512];
        for (auto &[key, r] : rows) {
            snprintf(line, sizeof line, "  %-90s %5d  %9.2f MB  purgeable %9.2f MB\n", key.c_str(), r.count, r.bytes / 1048576.0, r.purgeable / 1048576.0);
            out += line;
        }
    }
};
}

jlong SKBackend_GpuCacheBytes() {
    jlong bytes = 0;
    for (auto &[config, cc] : gContexts) {
        if (cc->recorder && cc->context) bytes += (jlong) (cc->recorder->currentBudgetedBytes() + cc->context->currentBudgetedBytes());
    }
    return bytes;
}

std::string SKBackend_GpuMemoryReport() {
    std::string out;
    char line[512];
    for (auto &[config, cc] : gContexts) {
        if (!cc->recorder || !cc->context) continue;
        submitPending(cc, false);
        submitOne(cc, true);
        snprintf(line, sizeof line, "context %p: recorder %.2f of %.2f MB budgeted (%.2f MB purgeable), context %.2f of %.2f MB (%.2f MB purgeable)\n",
                 (void *) config, cc->recorder->currentBudgetedBytes() / 1048576.0, cc->recorder->maxBudgetedBytes() / 1048576.0,
                 cc->recorder->currentPurgeableBytes() / 1048576.0, cc->context->currentBudgetedBytes() / 1048576.0,
                 cc->context->maxBudgetedBytes() / 1048576.0, cc->context->currentPurgeableBytes() / 1048576.0);
        out += line;
        MemoryTally recorder, context;
        cc->recorder->dumpMemoryStatistics(&recorder);
        cc->context->dumpMemoryStatistics(&context);
        recorder.report(out, "recorder");
        context.report(out, "context ");
    }
    return out;
}

static std::atomic<jlong> gFlushes{0};   // written by the flusher, read by any Java thread

// The floor of Graphite's per-draw cost on this device, without the backend in front of it
// (SKEnvironment.benchGraphite): `draws` draws of one kind into an off-screen 1024x1024 surface
// of the first context, then snap, insert and submit; per frame the nanoseconds of each, and
// the median over `frames` frames (after as many warm-up frames) divided by the draws goes to
// out[0..3] (record, snap, insert, submit, ns per draw). Kinds: 0 drawRect in one colour, 1
// drawRect alternating colours, 2 a one-quad drawVertices (the batches' shape), 3 drawImageRect
// of a 32x32 texture (blits, icons), 4 the same cycling 16 textures, 5 rect, mesh and image in turn, 6
// rects switching between two surfaces every 50 draws; 7-10 Skia's text path, 12-glyph strings of 50 distinct ones:
// 7 a temporary glyph run (drawGlyphs), 8 a retained SkTextBlob, 9 and 10 the same in LCD. Called with the render
// queue locked.
bool SKBackend_BenchGraphite(int kind, int draws, int frames, double out[4]) {
    SKConfigContext *cc = gContexts.empty() ? nullptr : gContexts.begin()->second;
    if (cc == nullptr || !cc->recorder || !cc->context || draws <= 0 || frames <= 0) {
        return false;
    }
    flushAllBatches();
    SKBackend_Submit(cc, true);   // nothing of the queue's in the recordings below
    sk_sp<SkSurface> surface = SkSurfaces::RenderTarget(cc->recorder.get(),
            SkImageInfo::Make(1024, 1024, kBGRA_8888_SkColorType, kPremul_SkAlphaType));
    sk_sp<SkSurface> source = SkSurfaces::RenderTarget(cc->recorder.get(),
            SkImageInfo::Make(32, 32, kBGRA_8888_SkColorType, kPremul_SkAlphaType));
    if (!surface || !source) {
        return false;
    }
    source->getCanvas()->clear(SK_ColorBLUE);
    sk_sp<SkImage> image = SkSurfaces::AsImageCopy(source);
    // kinds 4-6, the IDE's state churn: 16 textures in turn, three pipelines in turn, two surfaces in turn
    std::vector<sk_sp<SkImage>> images;
    for (int i = 0; i < 16; i++) {
        source->getCanvas()->clear(SkColorSetARGB(255, (U8CPU) (i * 16), 80, 160));
        images.push_back(SkSurfaces::AsImageCopy(source));
    }
    sk_sp<SkSurface> second = SkSurfaces::RenderTarget(cc->recorder.get(),
            SkImageInfo::Make(1024, 1024, kBGRA_8888_SkColorType, kPremul_SkAlphaType));
    // kinds 7-10, Skia's text path: 12-glyph strings of 50 distinct ones, drawn as a temporary glyph run
    // (drawGlyphs, as the text route draws) or from a retained SkTextBlob (the text blob cache), grayscale
    // or LCD, at whole-pixel origins, on a surface with the display's pixel geometry (LCD otherwise grayscale)
    const SkSurfaceProps textProps(0, SKBackend_LcdPixelGeometry());
    sk_sp<SkSurface> textSurface = kind >= 7 ? SkSurfaces::RenderTarget(cc->recorder.get(),
            SkImageInfo::Make(1024, 1024, kBGRA_8888_SkColorType, kPremul_SkAlphaType), skgpu::Mipmapped::kNo, &textProps) : nullptr;
    SkFont textFont(SKPlatform_FontManager()->legacyMakeTypeface(nullptr, SkFontStyle()), 14);
    textFont.setEdging(kind == 9 || kind == 10 ? SkFont::Edging::kSubpixelAntiAlias : SkFont::Edging::kAntiAlias);
    textFont.setSubpixel(true);
    std::vector<std::vector<SkGlyphID>> strings;
    std::vector<std::vector<SkPoint>> stringPositions;
    std::vector<sk_sp<SkTextBlob>> blobs;
    for (int s = 0; s < 50; s++) {
        char chars[13];
        for (int c = 0; c < 12; c++) {
            chars[c] = (char) ('a' + (s * 7 + c * 3) % 26);
        }
        chars[12] = 0;
        std::vector<SkGlyphID> glyphs(12);
        textFont.textToGlyphs(chars, 12, SkTextEncoding::kUTF8, glyphs);
        std::vector<SkScalar> advances(12);
        textFont.getWidths(glyphs, advances);
        std::vector<SkPoint> points(12);
        SkScalar penX = 0;
        for (int c = 0; c < 12; c++) {
            points[c] = {penX, 0};
            penX += advances[c];
        }
        SkTextBlobBuilder builder;
        const SkTextBlobBuilder::RunBuffer &run = builder.allocRunPos(textFont, 12);
        memcpy(run.glyphs, glyphs.data(), 12 * sizeof(SkGlyphID));
        memcpy(run.points(), points.data(), 12 * sizeof(SkPoint));
        blobs.push_back(builder.make());
        strings.push_back(std::move(glyphs));
        stringPositions.push_back(std::move(points));
    }
    std::vector<SkPoint> shifted(12);
    const SkPoint pos[4] = {{0, 0}, {16, 0}, {16, 16}, {0, 16}};
    const uint16_t idx[6] = {0, 1, 2, 0, 2, 3};
    std::vector<double> t[4];
    for (int f = 0; f < 2 * frames; f++) {
        SkCanvas *canvas = surface->getCanvas();
        SkPaint paint;
        const uint64_t t0 = SKStats_Now();
        for (int i = 0; i < draws; i++) {
            const float x = (float) ((i * 37) % 1000), y = (float) ((i * 53) % 1000);
            switch (kind) {
                case 0: paint.setColor(SK_ColorRED); canvas->drawRect(SkRect::MakeXYWH(x, y, 16, 12), paint); break;
                case 1: paint.setColor(i % 2 ? SK_ColorRED : SK_ColorGREEN); canvas->drawRect(SkRect::MakeXYWH(x, y, 16, 12), paint); break;
                case 2: {
                    canvas->save();
                    canvas->translate(x, y);
                    paint.setColor(SK_ColorRED);
                    canvas->drawVertices(SkVertices::MakeCopy(SkVertices::kTriangles_VertexMode, 4, pos, nullptr, nullptr, 6, idx),
                                         SkBlendMode::kModulate, paint);
                    canvas->restore();
                    break;
                }
                case 3: canvas->drawImageRect(image, SkRect::MakeXYWH(x, y, 32, 32), SkSamplingOptions(), &paint); break;
                case 4: canvas->drawImageRect(images[(size_t) i % images.size()], SkRect::MakeXYWH(x, y, 32, 32), SkSamplingOptions(), &paint); break;
                case 5:
                    if (i % 3 == 0) {
                        paint.setColor(SK_ColorRED);
                        canvas->drawRect(SkRect::MakeXYWH(x, y, 16, 12), paint);
                    } else if (i % 3 == 1) {
                        canvas->save();
                        canvas->translate(x, y);
                        canvas->drawVertices(SkVertices::MakeCopy(SkVertices::kTriangles_VertexMode, 4, pos, nullptr, nullptr, 6, idx),
                                             SkBlendMode::kModulate, paint);
                        canvas->restore();
                    } else {
                        canvas->drawImageRect(images[(size_t) i % images.size()], SkRect::MakeXYWH(x, y, 32, 32), SkSamplingOptions(), &paint);
                    }
                    break;
                case 7: case 9: {   // a temporary glyph run, as the text route draws
                    const size_t s = (size_t) i % strings.size();
                    for (int c = 0; c < 12; c++) {
                        shifted[c] = {stringPositions[s][c].fX + x, stringPositions[s][c].fY + y + 14};
                    }
                    paint.setColor(i % 2 ? SK_ColorBLACK : SK_ColorBLUE);
                    textSurface->getCanvas()->drawGlyphs(SkSpan<const SkGlyphID>(strings[s]), SkSpan<const SkPoint>(shifted), {0, 0}, textFont, paint);
                    break;
                }
                case 8: case 10:   // a retained blob, as the text blob cache draws
                    paint.setColor(i % 2 ? SK_ColorBLACK : SK_ColorBLUE);
                    textSurface->getCanvas()->drawTextBlob(blobs[(size_t) i % blobs.size()], x, y + 14, paint);
                    break;
                default: {
                    SkCanvas *c = (i / 50) % 2 == 0 || !second ? canvas : second->getCanvas();
                    paint.setColor(SK_ColorRED);
                    c->drawRect(SkRect::MakeXYWH(x, y, 16, 12), paint);
                    break;
                }
            }
        }
        const uint64_t t1 = SKStats_Now();
        std::unique_ptr<Recording> recording = cc->recorder->snap();
        const uint64_t t2 = SKStats_Now();
        InsertRecordingInfo info;
        info.fRecording = recording.get();
        cc->context->insertRecording(info);
        const uint64_t t3 = SKStats_Now();
        cc->context->submit(SyncToCpu::kNo);
        const uint64_t t4 = SKStats_Now();
        cc->context->submit(SyncToCpu::kYes);   // drained between frames, not timed
        if (f >= frames) {
            t[0].push_back((double) (t1 - t0));
            t[1].push_back((double) (t2 - t1));
            t[2].push_back((double) (t3 - t2));
            t[3].push_back((double) (t4 - t3));
        }
    }
    for (int i = 0; i < 4; i++) {
        std::sort(t[i].begin(), t[i].end());
        out[i] = t[i][t[i].size() / 2] / draws;
    }
    return true;
}

jlong SKBackend_Recordings() {
    return gRecordings.load(std::memory_order_relaxed);
}

jlong SKBackend_Flushes() {
    return gFlushes.load(std::memory_order_relaxed);
}

// Tells the Java surface of a window that it was presented or that presenting
// failed, for JBR's awt.window.counters (MTLLayer.countNewFrame on macOS).
static void countFrame(JNIEnv *env, SKSDOps *dst, bool presented) {
    if (env == nullptr || dst->sdOps.sdObject == nullptr) {
        return;
    }
    jobject sd = env->NewLocalRef(dst->sdOps.sdObject);
    if (sd != nullptr) {
        JNU_CallMethodByName(env, nullptr, sd, presented ? "countNewFrame" : "countDroppedFrame", "()V");
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        env->DeleteLocalRef(sd);
    }
}

// Draws the window surface's client area into the acquired swap chain image (wrapped once per
// image of a swap chain generation); null if the image cannot be wrapped.
static sk_sp<SkSurface> drawIntoPresentTarget(SKSurfaceState *st, SKConfigContext *cc, const SKPlatformPresentTarget &target) {
    const uint32_t generation = (uint32_t) (target.imageKey >> 32);
    if (generation != st->presentGeneration) {
        st->presentTargets.clear();   // the images of an earlier swap chain
        st->presentGeneration = generation;
    }
    sk_sp<SkSurface> &image = st->presentTargets[target.imageKey];
    if (!image) {
        image = SkSurfaces::WrapBackendTexture(cc->recorder.get(), target.texture, target.colorType, nullptr, nullptr);
    }
    if (image) {
        const SkIRect &source = target.source;
        SkPaint paint;
        paint.setBlendMode(SkBlendMode::kSrc);
        image->getCanvas()->drawImageRect(SkSurfaces::AsImage(st->surface), SkRect::Make(source),
                                          SkRect::MakeIWH(source.width(), source.height()),
                                          SkSamplingOptions(SkFilterMode::kNearest), &paint,
                                          SkCanvas::kFast_SrcRectConstraint);
    } else {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "drawIntoPresentTarget: cannot wrap the swap chain image (%dx%d)",
                      target.texture.dimensions().width(), target.texture.dimensions().height());
    }
    return image;
}

// Before the flush's submit (SKFoldedPresent): acquires the window's next swap chain image and
// records the window's draw into it. False where the platform presents itself, when the window
// was never drawn into, or when the context has a folded present already.
static bool foldPresent(SKSDOps *dst) {
    SKSurfaceState *st = dst->state;
    SKConfigContext *cc = st != nullptr ? st->cc : nullptr;
    if (cc == nullptr || !st->surface || !cc->context || gFolded.count(cc) != 0) {
        return false;
    }
    SKPlatformPresentTarget target;
    bool acquired;
    {
        SKStatsScope acquireStats(SK_SPAN_PRESENT_ACQUIRE);
        acquired = SKPlatform_AcquirePresentTarget(dst->platform, &target);
    }
    if (!acquired) {
        return false;
    }
    SKFoldedPresent &folded = gFolded[cc];
    folded.dst = dst;
    if (!target.texture.isValid()) {
        folded.inserted = true;   // no image now: nothing to insert, the present fails
        return true;
    }
    SKStatsScope copyStats(SK_SPAN_PRESENT_COPY);   // the draw into the image (its recording is the flush's)
    { SKDrawCauseScope cause(SK_COUNT_MESH_DRAWS_AT_FLUSH); flushBatchesOf(cc); }   // the window's batched quads, before the draw that presents them
    folded.target = target;
    folded.image = drawIntoPresentTarget(st, cc, target);
    return true;
}

// Where Graphite presents (SKPlatform_AcquirePresentTarget, Windows over Vulkan) and the window's
// present was not folded into the flush (a second window of its context): draws the window
// surface's client area into the next swap chain image in a recording of its own, which waits for
// the image and leaves it ready to present, submits it and presents. False where the platform
// presents itself (SKPlatform_RequestRedraw).
static bool presentThroughGraphite(SKSDOps *dst, bool *presented) {
    SKSurfaceState *st = dst->state;
    SKConfigContext *cc = st != nullptr ? st->cc : nullptr;
    if (cc == nullptr || !st->surface || !cc->context) {
        return false;   // never drawn into: nothing Graphite could present
    }
    SKPlatformPresentTarget target;
    bool acquired;
    {
        SKStatsScope acquireStats(SK_SPAN_PRESENT_ACQUIRE);
        acquired = SKPlatform_AcquirePresentTarget(dst->platform, &target);
    }
    if (!acquired) {
        return false;
    }
    *presented = false;
    if (!target.texture.isValid()) {
        return true;
    }
    SKStatsScope copyStats(SK_SPAN_PRESENT_COPY);   // the draw into the image, its recording and submit
    sk_sp<SkSurface> image = drawIntoPresentTarget(st, cc, target);
    // inserted even without a draw: the image's acquire semaphore must be waited for
    std::unique_ptr<Recording> recording = cc->recorder->snap();
    InsertRecordingInfo info;
    info.fRecording = recording.get();
    info.fTargetSurface = image.get();
    info.fTargetTextureState = image ? &target.presentState : nullptr;
    info.fNumWaitSemaphores = 1;
    info.fWaitSemaphores = &target.waitSemaphore;
    info.fNumSignalSemaphores = 1;
    info.fSignalSemaphores = &target.signalSemaphore;
    info.fFinishedProc = target.finishedProc;
    info.fFinishedContext = target.finishedContext;
    if (!recording || !cc->context->insertRecording(info)) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "presentThroughGraphite: insertRecording failed");
        if (!recording && target.finishedProc != nullptr) {
            target.finishedProc(target.finishedContext, skgpu::CallbackResult::kFailed);
        }
        return true;
    }
    cc->context->submit(SyncToCpu::kNo);
    copyStats.end();
    SKStatsScope callStats(SK_SPAN_PRESENT_CALL);
    *presented = image != nullptr && SKPlatform_PresentTarget(dst->platform);
    return true;
}

void SKBackend_RequestPresent(SKSDOps *ops) {
    if (ops != nullptr && ops->type == sun_java2d_pipe_hw_AccelSurface_WINDOW) {
        g.requestedWindows.insert(ops);
    }
}

bool SKBackend_EndOfFlush(JNIEnv *env, bool syncToCpu, bool presentAll) {
    if (presentAll) {
        SKStats_Count(SK_COUNT_TIMEOUT_FLUSHES);
    }
    gFlushes.fetch_add(1, std::memory_order_relaxed);
    // Which windows to present: every one drawn into at the end of every flush
    // (sun.java2d.skia.present=flush), or only those whose paint cycle ended
    // (=cycle, FLUSH_BUFFER); the rest stay dirty until theirs does, or a SYNC
    // or the flusher's timeout flush presents them, as the other pipelines do.
    const bool everything = !gConfig.presentPerCycle || syncToCpu || presentAll;
    bool presenting = false;
    for (SKSDOps *dst : g.touchedWindows) {
        if (everything || g.requestedWindows.count(dst) != 0) {
            presenting = true;
            break;
        }
    }
    // A flush that presents nothing and waits for nothing does not end its recordings: the next
    // flush continues them, so each recording's fixed cost (snap, insert, submit: about 280 µs on
    // Skia/Vulkan in the IDE) is paid per present, not per render-queue buffer. A tenth of the IDE's
    // typing flushes presented nothing; holding them, Skia/Vulkan's flush wait −4 % (2026-11-03). At
    // most MAX_DEFERRED_FLUSHES in a row (upload buffers and scratch textures recycle only after a
    // submit); the flusher's timeout flush (100 ms, presentAll) follows a flush that returns pending,
    // and read-backs, syncs and released surfaces submit as before.
    // J2D_SKIA_RECORDING_PER_FLUSH (diagnostics, A/B): every flush ends its recordings, as before.
    static const bool recordingPerFlush = getenv("J2D_SKIA_RECORDING_PER_FLUSH") != nullptr;
    static int heldFlushes = 0;
    if (!recordingPerFlush && !syncToCpu && !presentAll && !presenting && heldFlushes < MAX_DEFERRED_FLUSHES
            && !g.touchedContexts.empty()) {
        heldFlushes++;
        return true;
    }
    heldFlushes = 0;
    // A flush that presents nothing and waits for nothing only inserts its recordings
    // (sun.java2d.skia.submit=present); the next submit sends them, in order.
    const bool defer = gConfig.submitPerPresent && !syncToCpu && !presentAll && !presenting
                       && gDeferredFlushes < MAX_DEFERRED_FLUSHES;
    if (defer) {
        for (SKConfigContext *cc : g.touchedContexts) {
            deferSubmit(cc);
        }
        if (!g.touchedContexts.empty()) {
            gDeferredFlushes++;
        }
    } else {
        // where Graphite presents, each context's first window to present is drawn into its swap
        // chain image now, inside the recording the submit below sends (SKFoldedPresent)
        for (SKSDOps *dst : g.touchedWindows) {
            if ((everything || g.requestedWindows.count(dst) != 0) && dst->platform != nullptr) {
                SKStatsScope presentStats(SK_SPAN_PRESENT);
                if (foldPresent(dst)) {
                    // its context is submitted below even if this flush drew nothing into it (a
                    // window drawn earlier and presented at its paint cycle's end, present=cycle)
                    g.touchedContexts.insert(dst->state->cc);
                }
            }
        }
        const std::vector<SKConfigContext *> sent = gPendingSubmit;   // submitted by submitPending
        submitPending(nullptr, syncToCpu);
        for (SKConfigContext *cc : g.touchedContexts) {
            if (std::find(sent.begin(), sent.end(), cc) == sent.end()) {
                submitOne(cc, syncToCpu);
            }
        }
    }
    for (SKConfigContext *cc : g.touchedContexts) {
        syncPipelineData(cc, false);
    }
    g.touchedContexts.clear();
    for (auto it = g.touchedWindows.begin(); it != g.touchedWindows.end(); ) {
        SKSDOps *dst = *it;
        if (!everything && g.requestedWindows.count(dst) == 0) {
            ++it;
            continue;
        }
        if (dst->platform != nullptr) {
            bool presented;
            {
                SKStatsScope presentStats(SK_SPAN_PRESENT);
                SKFoldedPresent *folded = nullptr;
                for (auto &entry : gFolded) {
                    if (entry.second.dst == dst) {
                        folded = &entry.second;
                        break;
                    }
                }
                if (folded != nullptr) {
                    if (!folded->inserted && folded->target.finishedProc != nullptr) {
                        // never inserted (its context was not submitted): release what the acquire took
                        folded->target.finishedProc(folded->target.finishedContext, skgpu::CallbackResult::kFailed);
                    }
                    SKStatsScope callStats(SK_SPAN_PRESENT_CALL);
                    presented = folded->inserted && folded->image && SKPlatform_PresentTarget(dst->platform);
                } else if (!presentThroughGraphite(dst, &presented)) {
                    presented = SKPlatform_RequestRedraw(dst->platform);
                }
            }
            if (!presented) {
                SKStats_Count(SK_COUNT_PRESENTS_FAILED);
            }
            if (dst->countFrames) {
                countFrame(env, dst, presented);
            }
        }
        it = g.touchedWindows.erase(it);
    }
    gFolded.clear();
    g.requestedWindows.clear();
    return !g.touchedWindows.empty() || !gPendingSubmit.empty();
}

std::recursive_mutex &SKBackend_EntryMutex() {
    static auto *mutex = new std::recursive_mutex();   // never destroyed: entry points may run during exit
    return *mutex;
}
