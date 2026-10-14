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
#ifndef SKBackend_h_Included
#define SKBackend_h_Included

// Native state of the Skia rendering backend (JBR-10654). One SKConfigContext
// per Metal graphics config (Graphite context + recorder on the config's device
// and command queue), one SKSDOps per Java SKSurfaceData, and a single
// render state owned by the flusher thread.

#include "SKMetalInterop.h"

#include "include/core/SkCanvas.h"
#include "include/core/SkImage.h"
#include "include/core/SkPaint.h"
#include "include/core/SkPath.h"
#include "include/core/SkShader.h"
#include "include/core/SkSurface.h"
#include "include/gpu/graphite/Context.h"
#include "include/gpu/graphite/Recorder.h"

#include <memory>
#include <unordered_set>

struct SKGlyphCache;
struct SKMaskCache;

struct SKConfigContext {
    SKMTLGraphicsConfigInfo *info = nullptr;
    std::unique_ptr<skgpu::graphite::Context> context;
    std::unique_ptr<skgpu::graphite::Recorder> recorder;
    bool dirty = false;   // recorded but not yet inserted/submitted work
    SKGlyphCache *glyphCacheAA = nullptr;    // created on first use
    SKGlyphCache *glyphCacheLCD = nullptr;
    SKMaskCache *maskCache = nullptr;        // created on first use
    std::unordered_set<struct SKSurfaceState *> surfaces;   // wrapped with this recorder
};

struct SKSurfaceState {
    sk_sp<SkSurface> surface;
    void *wrappedTexture = nullptr;   // the MTLTexture `surface` wraps
    SKConfigContext *cc = nullptr;
};

// Native ops of a SKSurfaceData. Allocated by SurfaceData_InitOps (plain C
// memory), so only pointers and scalars live here.
typedef struct {
    SurfaceDataOps     sdOps;
    BMTLSDOps         *mtl;        // the wrapped Metal surface (owns the texture)
    jint               type;       // AccelSurface type
    jboolean           isOpaque;
    SKSurfaceState  *state;
} SKSDOps;

bool SKSurfaceData_IsSkiaOps(SurfaceDataOps *ops);

// Contexts
SKConfigContext *SKBackend_ContextFor(SKMTLGraphicsConfigInfo *info);
void SKBackend_DisposeContext(SKMTLGraphicsConfigInfo *info);

// Surfaces
sk_sp<SkSurface> SKBackend_GetSurface(SKSDOps *ops);
sk_sp<SkImage>   SKBackend_GetImage(SKSDOps *ops);
void SKBackend_ReleaseSurface(SKSDOps *ops, bool dispose);

// Render state (flusher thread)
void SKBackend_SetDestination(SKSDOps *dst);
void SKBackend_SetScratch(SKMTLGraphicsConfigInfo *info);
void SKBackend_InvalidateState();
SKSDOps *SKBackend_CurrentDestination();
SkCanvas *SKBackend_CurrentCanvas();

void SKBackend_SetRectClip(jint x1, jint y1, jint x2, jint y2);
void SKBackend_BeginShapeClip();
void SKBackend_AddShapeClipSpans(jint count, const jint *spans);
void SKBackend_EndShapeClip();
void SKBackend_ResetClip();

void SKBackend_SetAlphaComposite(jint rule, jfloat extraAlpha, jint flags);
void SKBackend_SetXorComposite(jint xorPixel);
void SKBackend_ResetComposite();

void SKBackend_SetTransform(jdouble m00, jdouble m10, jdouble m01, jdouble m11, jdouble m02, jdouble m12);
void SKBackend_ResetTransform();

void SKBackend_SetColor(jint pixel);
void SKBackend_ResetPaint();
void SKBackend_SetGradientPaint(jboolean cyclic, jdouble p0, jdouble p1, jdouble p3, jint pixel1, jint pixel2);
void SKBackend_SetLinearGradientPaint(jboolean linear, jint cycleMethod, jint numStops,
                                        jfloat p0, jfloat p1, jfloat p3,
                                        const jfloat *fractions, const jint *pixels);
void SKBackend_SetRadialGradientPaint(jboolean linear, jint cycleMethod, jint numStops,
                                        jfloat m00, jfloat m01, jfloat m02, jfloat m10, jfloat m11, jfloat m12,
                                        jfloat focusX, const jfloat *fractions, const jint *pixels);
void SKBackend_SetTexturePaint(JNIEnv *env, jboolean filter, jlong pSrc,
                                 jdouble xp0, jdouble xp1, jdouble xp3, jdouble yp0, jdouble yp1, jdouble yp3);

// Paint for the current state; `aa` selects antialiasing.
SkPaint SKBackend_MakePaint(bool aa);

// Primitives (device coordinates)
void SKBackend_FillRect(jint x, jint y, jint w, jint h);
void SKBackend_DrawRect(jint x, jint y, jint w, jint h);
void SKBackend_DrawLine(jint x1, jint y1, jint x2, jint y2);
void SKBackend_DrawPixel(jint x, jint y);
void SKBackend_DrawScanlines(jint count, const jint *scanlines);
void SKBackend_FillSpans(jint count, const jint *spans);
void SKBackend_DrawPoly(jint nPoints, jboolean isClosed, jint transX, jint transY,
                          const jint *xPoints, const jint *yPoints);
void SKBackend_FillParallelogram(bool aa, jfloat x11, jfloat y11, jfloat dx21, jfloat dy21, jfloat dx12, jfloat dy12);
void SKBackend_DrawParallelogram(bool aa, jfloat x11, jfloat y11, jfloat dx21, jfloat dy21, jfloat dx12, jfloat dy12,
                                   jfloat lwr21, jfloat lwr12);
// Antialiased shapes rasterized by Skia (SKShapePipe). kind: 0 rect, 1 round
// rect, 2 oval (params x y w h [arcw arch]) or 3 path (types/coords). xform is
// an AffineTransform matrix (m00 m10 m01 m11 m02 m12) applied around the draw;
// stroke (DRAW only): width, cap, join, miter limit, dash phase, dash count,
// dashes.
void SKBackend_DrawShape(bool fill, jint kind, const jfloat *xform, const jfloat *stroke,
                         const jfloat *params, jint windingRule, jint numTypes, const jint *types,
                         jint numCoords, const jfloat *coords);
void SKBackend_MaskFill(jint x, jint y, jint w, jint h, jint maskoff, jint maskscan, jint masklen,
                          const unsigned char *mask);
void SKBackend_MaskBlit(jint dstx, jint dsty, jint width, jint height, const void *pixels);
void SKBackend_DrawGlyphList(jint numGlyphs, jboolean usePositions, jboolean subPixPos,
                             jboolean rgbOrder, jint lcdContrast,
                               jfloat glyphListOrigX, jfloat glyphListOrigY,
                               const jlong *images, const jfloat *positions);
void SKBackend_Blit(JNIEnv *env, jint packedParams, jint sx1, jint sy1, jint sx2, jint sy2,
                      jdouble dx1, jdouble dy1, jdouble dx2, jdouble dy2, jlong pSrc, jlong pDst);
void SKBackend_SurfaceToSwBlit(JNIEnv *env, jint sx, jint sy, jint dx, jint dy, jint w, jint h,
                                 jint dsttype, jlong pSrc, jlong pDst);
void SKBackend_CopyArea(jint x, jint y, jint w, jint h, jint dx, jint dy);

// Submission / presentation
void SKBackend_FlushRecording(SKConfigContext *cc);          // snap + insert, no submit
void SKBackend_Submit(SKConfigContext *cc, bool syncToCpu);  // flush + submit
void SKBackend_EndOfFlush(bool syncToCpu = false);             // submit all + request redraws; SYNC waits for the GPU

#endif /* SKBackend_h_Included */
