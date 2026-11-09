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
// The render state (SKContext.h): the destination and the surfaces as it sees them, clip, composite
// and transform, and the paint SKPaints sets.
// All functions run on the Skia queue flusher thread.

#include "SKContext.h"
#include "SKGraphicsConfig.h"
#include "SKPendingBatches.h"
#include "SKPresentation.h"
#include "SKRenderQueueLayout.h"

#include "Trace.h"
#include "java_awt_AlphaComposite.h"
#include "sun_java2d_pipe_hw_AccelSurface.h"

#include "include/core/SkSurfaceProps.h"
#include "include/gpu/graphite/BackendTexture.h"
#include "include/gpu/graphite/Image.h"
#include "include/gpu/graphite/Surface.h"

using namespace skgpu::graphite;

namespace {

SKSurfaceState *stateOf(SKSDOps *ops) {
    if (ops->state == nullptr) {
        ops->state = new SKSurfaceState();
    }
    return ops->state;
}

// The pixel geometry for a surface's SkSurfaceProps: horizontal stripes in the
// display's sub-pixel order (SKPlatform::lcdOrderBGR).
SkPixelGeometry lcdPixelGeometry() {
    return SKPlatform::lcdOrderBGR() ? kBGR_H_SkPixelGeometry : kRGB_H_SkPixelGeometry;
}

SkBlendMode blendModeForRule(jint rule) {
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

} // namespace

// Never destroyed (SKNeverDestroyed.h): at process exit its destructor would release the paint's shader,
// an image of a Graphite context that is not released then, during DLL detach.
SKNeverDestroyed<SKContext> SKContext::sm_instance;

SKContext::~SKContext() {
    J2dRlsTraceLn(J2D_TRACE_INFO, "SKContext: releasing the render state");
}

void SKContext::forgetGraphicsConfig(SKGraphicsConfig *graphicsConfig) {
    SKPresentation::forgetGraphicsConfig(graphicsConfig);
    if (m_graphicsConfig == graphicsConfig) {
        m_graphicsConfig = nullptr;
        m_destination = nullptr;
        m_canvas = nullptr;
    }
    if (m_shaderGraphicsConfig == graphicsConfig) {
        // the shader's images are of the context's recorder: they go before it (Java sends the paint
        // again for the next draw, the context changed)
        m_shader.reset();
        m_shaderGraphicsConfig = nullptr;
        m_texturePaintSource = nullptr;
    }
}

void SKContext::forgetSurface(SKSDOps *ops) {
    if (m_destination == ops) {
        m_destination = nullptr;
        m_canvas = nullptr;
    }
    if (m_texturePaintSource == ops) {
        // the shader is an image of the released texture
        m_texturePaintSource = nullptr;
        m_shaderGraphicsConfig = nullptr;
        m_shader.reset();
    }
    SKPresentation::forgetWindow(ops);   // a released window surface is not presented at the end of the flush
}

// The canvas clip and transform follow the render state as it changes: a change draws the pending
// batches first (their quads were added under the previous state), then applies the new one.
void SKContext::applyCanvasClip() {
    m_canvas->restoreToCount(m_baseSaveCount);
    m_canvas->save();
    // Java2D clips are in device space: apply before the transform.
    if (m_hasRectClip) {
        m_canvas->clipIRect(m_rectClip);
    }
    if (m_hasShapeClip) {
        m_canvas->clipPath(m_shapeClip, SkClipOp::kIntersect, false);
    }
    m_canvas->setMatrix(m_hasMatrix ? SkM44(m_matrix) : SkM44());
}

void SKContext::applyCanvasState() {
    if (m_canvas == nullptr) {
        return;
    }
    flushAllBatches();
    applyCanvasClip();
}

void SKContext::markTouched() {
    if (m_graphicsConfig != nullptr) {
        SKPresentation::markTouched(m_graphicsConfig);
    }
    SKSDOps *destination = m_destination;
    if (destination != nullptr && destination->state != nullptr && m_graphicsConfig != nullptr) {
        destination->state->touch();
    }
    if (destination != nullptr && destination->type == sun_java2d_pipe_hw_AccelSurface_WINDOW) {
        SKPresentation::markTouchedWindow(destination);
    }
}

void SKContext::setColorPaint(SkColor color) {
    m_color = color;
    m_shader.reset();
    m_shaderGraphicsConfig = nullptr;
    m_texturePaintSource = nullptr;
}

void SKContext::setShaderPaint(sk_sp<SkShader> shader, SKGraphicsConfig *imagesGraphicsConfig,
                               SKSDOps *texturePaintSource) {
    m_shader = std::move(shader);
    m_shaderGraphicsConfig = m_shader ? imagesGraphicsConfig : nullptr;
    m_texturePaintSource = m_shader ? texturePaintSource : nullptr;
}

void SKContext::flushGlyphBatches() {
    if (m_graphicsConfig != nullptr) {
        m_graphicsConfig->flushGlyphCaches();
    }
}

// Graphite does not draw raster images directly; images drawn repeatedly
// arrive as textures already (SKSurfaceDataProxy), everything else is uploaded
// per draw. Texture from pixels the caller owns only for the duration of the call (a
// locked Java raster, an opcode buffer, a glyph image): Graphite copies them
// into its upload buffer while recording (UploadInstance::Make copies the
// pixels), so the pixmap is wrapped, not copied first.
SKOwnedImage SKContext::uploadPixmap(const SkPixmap &pixmap) {
    if (m_graphicsConfig == nullptr) {
        return {};   // every caller has a destination, and with it a context
    }
    sk_sp<SkImage> raster = SkImages::RasterFromPixmap(pixmap, nullptr, nullptr);
    sk_sp<SkImage> image = raster ? SkImages::TextureFromImage(m_graphicsConfig->recorder(), raster.get()) : nullptr;
    return image ? SKOwnedImage{std::move(image), m_graphicsConfig} : SKOwnedImage{};
}

// ---------------------------------------------------------------------------
// Surfaces
// ---------------------------------------------------------------------------

sk_sp<SkSurface> SKContext::getSurface(SKSDOps *ops) {
    void *texture = ops != nullptr && ops->platform != nullptr ? SKPlatform::textureHandle(ops->platform) : nullptr;
    if (texture == nullptr) {
        return nullptr;
    }
    SKSurfaceState *surfaceState = stateOf(ops);
    if (surfaceState->wraps(texture)) {
        return surfaceState->surface();
    }
    SKGraphicsConfig *graphicsConfig = SKGraphicsConfig::ofSurface(ops);
    if (graphicsConfig == nullptr) {
        return nullptr;
    }
    if (surfaceState->surface()) {
        // the texture was replaced: pending batches may still target the old
        // canvas, and so may the render state if this is the destination
        J2dTraceLn(J2D_TRACE_VERBOSE, "SKContext::getSurface: re-wrapping ops=%p (texture %p -> %p)",
                   ops, surfaceState->wrappedTexture(), texture);
        flushAllBatches();
        forgetSurface(ops);
        surfaceState->discardSurface();
    }
    BackendTexture backendTexture;
    SkColorType colorType;
    if (!SKPlatform::backendTexture(ops->platform, &backendTexture, &colorType)) {
        return nullptr;
    }
    // The surface's pixel geometry: without one Skia's glyph painter downgrades every LCD request
    // to grayscale before Graphite's LCD text step (A565 masks) is reached. Horizontal stripes in
    // the display's sub-pixel order (the desktop hint's LCD_HRGB or LCD_HBGR, or the property's);
    // a draw whose own LCD hint names the other order gets the surface's — a geometry is a
    // surface's, not a draw's.
    const SkSurfaceProps props(/* flags */ 0, lcdPixelGeometry());
    sk_sp<SkSurface> surface = SkSurfaces::WrapBackendTexture(graphicsConfig->recorder(), backendTexture, colorType,
                                                              nullptr, &props);
    if (!surface) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "SKContext::getSurface: cannot wrap texture %p (%dx%d)",
                      texture, backendTexture.dimensions().width(), backendTexture.dimensions().height());
        return nullptr;
    }
    graphicsConfig->wrapSurface(surfaceState, std::move(surface), texture);
    return surfaceState->surface();
}

// An image view of the surface's texture (no copy) for drawing on the current
// destination. Valid until the texture is replaced. Graphite images belong to
// the recorder that wraps them, so the view is made with the drawing context's
// recorder, not the source's (without a destination, the source's own).
SKOwnedImage SKContext::getImage(SKSDOps *ops) {
    if (ops == nullptr || ops->platform == nullptr || SKPlatform::textureHandle(ops->platform) == nullptr) {
        return {};
    }
    SKGraphicsConfig *graphicsConfig = m_graphicsConfig != nullptr ? m_graphicsConfig : SKGraphicsConfig::ofSurface(ops);
    if (graphicsConfig == nullptr) {
        return {};
    }
    BackendTexture backendTexture;
    SkColorType colorType;
    if (!SKPlatform::backendTexture(ops->platform, &backendTexture, &colorType)) {
        return {};
    }
    sk_sp<SkImage> image = SkImages::WrapTexture(graphicsConfig->recorder(), backendTexture,
                                                 ops->isOpaque ? kOpaque_SkAlphaType : kPremul_SkAlphaType,
                                                 nullptr, skgpu::Origin::kTopLeft);
    return image ? SKOwnedImage{std::move(image), graphicsConfig} : SKOwnedImage{};
}

void SKContext::releaseSurface(SKSDOps *ops, bool dispose) {
    if (ops == nullptr || ops->state == nullptr) {
        return;
    }
    SKSurfaceState *surfaceState = ops->state;
    J2dTraceLn(J2D_TRACE_VERBOSE, "SKContext::releaseSurface: ops=%p dispose=%d surface=%p", ops, dispose,
               (void *) surfaceState->surface().get());
    flushAllBatches();   // batches may refer to this surface's canvas
    SKGraphicsConfig *wrappingGraphicsConfig = surfaceState->graphicsConfig();
    if (wrappingGraphicsConfig != nullptr) {
        // make sure pending draws into this surface reach the GPU first; no CPU
        // wait is needed before the platform frees the texture, since Graphite
        // retains the wrapped texture object for as long as a command buffer
        // that uses it is tracked
        SKPresentation::submit(wrappingGraphicsConfig, false);
        wrappingGraphicsConfig->unwrapSurface(surfaceState);   // its wrappers, the swap chain images' too
    }
    forgetSurface(ops);
    if (wrappingGraphicsConfig != nullptr) {
        // the submit ran while the surface and the paint of it held its linked image: a texture paint's image
        // of it goes now rather than at the context's next endRecording
        wrappingGraphicsConfig->releaseUnreferencedTexturePaintImages();
    }
    if (dispose) {
        delete surfaceState;
        ops->state = nullptr;
    }
}

// ---------------------------------------------------------------------------
// Destination
// ---------------------------------------------------------------------------

void SKContext::setDestination(SKSDOps *destination) {
    // The same destination again, its texture unchanged: a redundant state change. BufferedContext.validate sends
    // SET_SURFACES for a new source as well (before a blit of another image, and after it), and sends the clip and
    // the rest of the render state again only for a new destination, so the canvas, its clip and the pending
    // batches stay as they are.
    if (destination != nullptr && destination == m_destination && m_canvas != nullptr
            && destination->state != nullptr && destination->state->graphicsConfig() == m_graphicsConfig
            && destination->platform != nullptr && destination->state->wraps(SKPlatform::textureHandle(destination->platform))) {
        return;
    }
    // Another destination: the pending batches belong to the canvas being left; they are drawn, and the render
    // state is applied to the new canvas.
    flushAllBatches();
    m_destination = nullptr;
    m_canvas = nullptr;
    if (destination == nullptr) {
        return;
    }
    m_graphicsConfig = SKGraphicsConfig::ofSurface(destination);
    // getSurface may re-wrap a replaced texture and forget the previous
    // destination on the way, so the destination is taken after it
    sk_sp<SkSurface> surface = getSurface(destination);
    if (!surface) {
        return;
    }
    m_destination = destination;
    m_canvas = surface->getCanvas();
    // The canvas keeps the save()/clip applyCanvasState() left behind the last
    // time this surface was the destination; drop it before taking the base
    // count, or the stale clip would stick to this surface forever.
    m_canvas->restoreToCount(1);
    m_baseSaveCount = m_canvas->getSaveCount();
    applyCanvasState();
    if (destination->type == sun_java2d_pipe_hw_AccelSurface_WINDOW) {
        J2dTraceLn(J2D_TRACE_VERBOSE, "SKContext::setDestination: window ops=%p tex=%p",
                   destination, SKPlatform::textureHandle(destination->platform));
    }
}

void SKContext::setScratch(SKPlatformConfig *platformConfig) {
    flushAllBatches();
    // only an existing context: the scratch surface precedes disposals, which
    // must not create a context for a configuration that never rendered
    m_graphicsConfig = SKGraphicsConfig::existing(platformConfig);
    m_destination = nullptr;
    m_canvas = nullptr;
}

void SKContext::invalidate() {
    m_destination = nullptr;
    m_canvas = nullptr;
}

// --- clip

void SKContext::setRectClip(jint x1, jint y1, jint x2, jint y2) {
    m_hasRectClip = true;
    m_rectClip = SkIRect::MakeLTRB(x1, y1, x2, y2);
    m_hasShapeClip = false;
    applyCanvasState();
}

void SKContext::beginShapeClip() {
    m_hasRectClip = false;   // replaces, not intersects, the rect clip (as MTLContext.beginShapeClip)
    m_buildingShapeClip = true;
    m_shapeClipBuilder.reset();
    m_shapeClipBuilder.setFillType(SkPathFillType::kWinding);
}

void SKContext::addShapeClipSpans(jint count, const jint *spans) {
    for (jint index = 0; index < count; index++) {
        const jint *span = spans + SK_INTS_PER_SPAN * index;
        m_shapeClipBuilder.addRect(SkRect::MakeLTRB(span[0], span[1], span[2], span[3]));
    }
}

void SKContext::endShapeClip() {
    m_buildingShapeClip = false;
    m_shapeClip = m_shapeClipBuilder.detach();
    m_hasShapeClip = true;
    applyCanvasState();
}

void SKContext::resetClip() {
    m_hasRectClip = false;
    m_hasShapeClip = false;
    m_buildingShapeClip = false;
    applyCanvasState();
}

// --- composite

void SKContext::setAlphaComposite(jint rule, jfloat extraAlpha, jint flags) {
    m_blendMode = blendModeForRule(rule);
    m_extraAlpha = extraAlpha;
    m_xorMode = false;
}

void SKContext::setXORComposite(jint xorPixel) {
    m_xorMode = true;
    m_xorPixel = xorPixel;
}

void SKContext::resetComposite() {
    m_blendMode = SkBlendMode::kSrcOver;
    m_extraAlpha = 1.0f;
    m_xorMode = false;
}

// --- transform

void SKContext::setTransform(jdouble m00, jdouble m10, jdouble m01, jdouble m11, jdouble m02, jdouble m12) {
    m_hasMatrix = true;
    m_matrix = SkMatrix::MakeAll((SkScalar) m00, (SkScalar) m01, (SkScalar) m02,
                                (SkScalar) m10, (SkScalar) m11, (SkScalar) m12,
                                0, 0, 1);
    applyCanvasState();
}

void SKContext::resetTransform() {
    m_hasMatrix = false;
    m_matrix = SkMatrix::I();
    applyCanvasState();
}
