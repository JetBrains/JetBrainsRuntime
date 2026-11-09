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
#ifndef SKContext_h_Included
#define SKContext_h_Included

// The render state of the Skia backend: what sun.java2d.skia.SKContext's validation (BufferedContext)
// sends — the surfaces (the destination and its canvas, the scratch surface of a configuration), the
// clip, the composite and the transform — and the paint SKPaints sets with it. The Java side keeps
// a context per configuration; the native side one render state for all of them (SKContext.java
// re-sends the state when the context changes). One instance, owned by the flusher thread
// (SKContext::current), as every draw runs there; never destroyed (SKNeverDestroyed.h: its paint may hold
// an image of a Graphite context, which is not released at process exit either).

#include "SKNeverDestroyed.h"
#include "SKPendingBatches.h"
#include "SKPlatform.h"
#include "SKSurfaceData.h"

#include "jni.h"

#include "include/core/SkBlendMode.h"
#include "include/core/SkCanvas.h"
#include "include/core/SkColor.h"
#include "include/core/SkImage.h"
#include "include/core/SkMatrix.h"
#include "include/core/SkPath.h"
#include "include/core/SkPathBuilder.h"
#include "include/core/SkPixmap.h"
#include "include/core/SkRect.h"
#include "include/core/SkShader.h"
#include "include/core/SkSurface.h"

class SKGraphicsConfig;

// An image and the context whose recorder made it, as the one function that makes the image returns
// them (SKContext::getImage, uploadPixmap, SKImageSources::blitSourceImage, imageFromSoftwareSurface), so
// that the two cannot drift apart: the image must not outlive that context. Null image: none (and no
// context).
struct SKOwnedImage {
    sk_sp<SkImage> image;
    SKGraphicsConfig *graphicsConfig = nullptr;
};

class SKContext {
public:
    [[nodiscard]] static SKContext &current() { return *sm_instance; }

    SKContext(const SKContext &) = delete;
    SKContext &operator=(const SKContext &) = delete;

    // --- surfaces (SET_SURFACES, SET_SCRATCH_SURFACE)
    void setDestination(SKSDOps *destination);
    void setScratch(SKPlatformConfig *platformConfig);
    void invalidate();   // no destination (its canvas may be gone)
    [[nodiscard]] SKSDOps *destination() const { return m_destination; }
    [[nodiscard]] SkCanvas *canvas() const { return m_canvas; }
    [[nodiscard]] SKGraphicsConfig *graphicsConfig() const { return m_graphicsConfig; }   // the destination's context

    // The surface's Skia wrapper of its platform texture, made with its context's recorder (re-made when the
    // platform replaced the texture); null if the surface has no texture or no context.
    [[nodiscard]] sk_sp<SkSurface> getSurface(SKSDOps *ops);
    // An image view of the surface's texture for drawing on the current destination (see there), made with
    // the recorder of the destination's context, else of the surface's own; with that context.
    [[nodiscard]] SKOwnedImage getImage(SKSDOps *ops);
    // FLUSH_SURFACE and DISPOSE_SURFACE (SKContext.disposeSurface): the wrapper goes, its pending draws submitted.
    void releaseSurface(SKSDOps *ops, bool dispose);

    // Drop references the render state holds to a context / surface about to go away: the destination,
    // and a shader paint of images of the context (made with its recorder) or of the surface.
    void forgetGraphicsConfig(SKGraphicsConfig *graphicsConfig);
    void forgetSurface(SKSDOps *ops);

    // --- clip (device space)
    void setRectClip(jint x1, jint y1, jint x2, jint y2);
    void beginShapeClip();
    void addShapeClipSpans(jint count, const jint *spans);
    void endShapeClip();
    void resetClip();
    [[nodiscard]] bool hasRectClip() const { return m_hasRectClip; }
    [[nodiscard]] const SkIRect &rectClip() const { return m_rectClip; }

    // --- composite
    void setAlphaComposite(jint rule, jfloat extraAlpha, jint flags);
    void setXORComposite(jint xorPixel);
    void resetComposite();
    [[nodiscard]] float extraAlpha() const { return m_extraAlpha; }
    [[nodiscard]] SkBlendMode blendMode() const { return m_blendMode; }
    [[nodiscard]] bool xorMode() const { return m_xorMode; }
    [[nodiscard]] jint xorPixel() const { return m_xorPixel; }

    // --- transform
    void setTransform(jdouble m00, jdouble m10, jdouble m01, jdouble m11, jdouble m02, jdouble m12);
    void resetTransform();
    [[nodiscard]] bool hasMatrix() const { return m_hasMatrix; }

    // --- paint (SKPaints sets it): the color, or a shader
    [[nodiscard]] SkColor color() const { return m_color; }
    [[nodiscard]] const sk_sp<SkShader> &shader() const { return m_shader; }
    // A color paint: no shader.
    void setColorPaint(SkColor color);
    // A shader paint, the color kept for a later color paint; null: the color paint. A shader of images (a
    // texture paint's) names the context whose recorder made them (`imagesGraphicsConfig`, an SKOwnedImage's),
    // and a texture paint its source (`texturePaintSource`, the Skia surface the shader is an image of): the
    // shader goes when that context is disposed (forgetGraphicsConfig) or the source is released
    // (forgetSurface), whether or not Java sends a paint again. A null shader has neither.
    void setShaderPaint(sk_sp<SkShader> shader, SKGraphicsConfig *imagesGraphicsConfig = nullptr,
                        SKSDOps *texturePaintSource = nullptr);

    // --- drawing into the destination
    // The entry of a primitive drawn through the canvas: false without a destination (nothing to draw);
    // otherwise the destination is touched and every pending batch drawn first.
    [[nodiscard]] bool beginDraw() {
        if (m_canvas == nullptr) {
            return false;
        }
        markTouched();
        flushAllBatches();
        return true;
    }
    // The entry of a batched primitive of a kind: as beginDraw(), but only the other kinds' batches are drawn
    // first, so that consecutive primitives of the kind share their batches.
    [[nodiscard]] bool beginDraw(SKBatchKind batchedKind) {
        if (m_canvas == nullptr) {
            return false;
        }
        markTouched();
        SKPendingBatches::drawOtherKinds(m_graphicsConfig, batchedKind);
        return true;
    }
    // The destination is drawn into: its context is submitted and, a window, presented at the end of the flush.
    void markTouched();
    // A texture of the destination's context from pixels the caller owns for the call (see there), with
    // that context.
    [[nodiscard]] SKOwnedImage uploadPixmap(const SkPixmap &pixmap);
    // The pending batches of the destination's context (SKPendingBatches.h): every one, or the glyph caches'.
    void flushAllBatches() { SKPendingBatches::flushOf(m_graphicsConfig); }
    void flushGlyphBatches();

private:
    friend class SKNeverDestroyed<SKContext>;

    SKContext() = default;
    // Never runs (sm_instance); it traces if it does (SkiaExitWhilePaintingTest).
    ~SKContext();

    static SKNeverDestroyed<SKContext> sm_instance;

    void applyCanvasClip();
    void applyCanvasState();

    SKGraphicsConfig *m_graphicsConfig = nullptr;
    SKSDOps *m_destination = nullptr;
    SkCanvas *m_canvas = nullptr;
    int m_baseSaveCount = 1;

    // paint
    SkColor m_color = SK_ColorBLACK;
    sk_sp<SkShader> m_shader;
    SKGraphicsConfig *m_shaderGraphicsConfig = nullptr;   // the context the shader's images are of (setShaderPaint)
    SKSDOps *m_texturePaintSource = nullptr;   // a texture paint's source (setShaderPaint); null: no texture paint
    float m_extraAlpha = 1.0f;
    SkBlendMode m_blendMode = SkBlendMode::kSrcOver;
    bool m_xorMode = false;
    jint m_xorPixel = 0;

    // clip (device space)
    bool m_hasRectClip = false;
    SkIRect m_rectClip = SkIRect::MakeEmpty();
    bool m_hasShapeClip = false;
    bool m_buildingShapeClip = false;
    SkPath m_shapeClip;
    SkPathBuilder m_shapeClipBuilder;

    // transform
    bool m_hasMatrix = false;
    SkMatrix m_matrix = SkMatrix::I();
};

#endif /* SKContext_h_Included */
