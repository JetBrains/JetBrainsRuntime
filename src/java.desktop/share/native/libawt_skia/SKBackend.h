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
// per platform graphics config (Graphite context + recorder on the config's
// device and queue, see SKPlatform.h), one SKSDOps per Java SKSurfaceData, and
// a single render state owned by the flusher thread.

#include "SKPlatform.h"

#include "jni.h"
#include "SurfaceData.h"
#include "Trace.h"

#include "include/core/SkCanvas.h"
#include "include/core/SkImage.h"
#include "include/core/SkPaint.h"
#include "include/core/SkPath.h"
#include "include/core/SkShader.h"
#include "include/core/SkSurface.h"
#include "include/gpu/graphite/Context.h"
#include "include/gpu/graphite/Recorder.h"

#include <memory>
#include <unordered_map>
#include <unordered_set>

struct SKGlyphCache;
struct SKMaskCache;

struct SKConfigContext {
    SKPlatformConfig *config = nullptr;
    std::unique_ptr<skgpu::graphite::Context> context;
    std::unique_ptr<skgpu::graphite::Recorder> recorder;
    SKGlyphCache *glyphCacheAA = nullptr;    // created on first use; a failed creation is not retried
    SKGlyphCache *glyphCacheLCD = nullptr;
    SKMaskCache *maskCache = nullptr;
    bool glyphCacheAAFailed = false, glyphCacheLCDFailed = false, maskCacheFailed = false;
    std::unordered_set<struct SKSurfaceState *> surfaces;   // wrapped with this recorder
    std::unordered_map<uint64_t, sk_sp<SkImage>> lcdTables;   // coverage correction per text colour and contrast
    sk_sp<SkSurface> lcdCoverage;            // scratch for sun.java2d.skia.lcd=accumulate, destination-sized
    uint32_t recordingGeneration = 1;        // advanced by every SKBackend_FlushRecording; the glyph caches date cells by it
};

struct SKSurfaceState {
    sk_sp<SkSurface> surface;
    void *wrappedTexture = nullptr;   // identity of the platform texture `surface` wraps (SKPlatform_TextureHandle)
    SKConfigContext *cc = nullptr;
};

// Native ops of a SKSurfaceData. Allocated by SurfaceData_InitOps (plain C
// memory), so only pointers and scalars live here.
typedef struct {
    SurfaceDataOps     sdOps;
    SKPlatformSurface *platform;   // the wrapped platform surface (owns the texture)
    jint               type;       // AccelSurface type
    jboolean           isOpaque;
    SKSurfaceState  *state;
} SKSDOps;

bool SKSurfaceData_IsSkiaOps(SurfaceDataOps *ops);
// The platform surface behind a Skia surface's ops (null if not Skia's or already disposed).
SKPlatformSurface *SKSurfaceData_PlatformOf(SurfaceDataOps *ops);

// Configuration
// Architecture-dependent values (SKEnvironment decides them by whether the GPU
// reads the framebuffer in the shader, and by system property, before the
// first context is created).
// How LCD text is composited (SKEnvironment.LcdMode, same order).
enum class SKLcdMode { Blend = 0, TwoPass = 1, Accumulate = 2 };

void SKBackend_Configure(jint msaaSamples, bool edgeAAQuads, SKLcdMode lcdMode, bool framebufferFetch);
bool SKBackend_Multisampled();
bool SKBackend_EdgeAAQuads();
// Whether a blender reads the live framebuffer (in order, per fragment) rather
// than a copy taken before the draw: overlapping quads may then share a draw.
bool SKBackend_FramebufferFetch();
// Contexts
SKConfigContext *SKBackend_ContextFor(SKPlatformConfig *config);
void SKBackend_DisposeContext(SKPlatformConfig *config);

// Surfaces
sk_sp<SkSurface> SKBackend_GetSurface(SKSDOps *ops);
sk_sp<SkImage>   SKBackend_GetImage(SKSDOps *ops);
void SKBackend_ReleaseSurface(SKSDOps *ops, bool dispose);

// Render state (flusher thread)
void SKBackend_SetDestination(SKSDOps *dst);
void SKBackend_SetScratch(SKPlatformConfig *config);
void SKBackend_InvalidateState();
SKSDOps *SKBackend_CurrentDestination();

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
// Antialiased shapes rasterized by Skia (SKShapePipe). kind: SKShapePipe.KIND_*
// (rect, round rect, oval with params x y w h [arcw arch], or a path with
// types/coords). xform is an AffineTransform matrix (m00 m10 m01 m11 m02 m12)
// applied around the draw; stroke (DRAW only): width, cap, join, miter limit.
// Dashed strokes never arrive: Java's stroker dashes them and they come as fills.
void SKBackend_DrawShape(bool fill, jint kind, const jfloat *xform, const jfloat *stroke,
                         const jfloat *params, jint windingRule, jint numTypes, const jint *types,
                         jint numCoords, const jfloat *coords);
void SKBackend_MaskFill(jint x, jint y, jint w, jint h, jint maskoff, jint maskscan, jint masklen,
                          const unsigned char *mask);
void SKBackend_MaskBlit(jint dstx, jint dsty, jint width, jint height, const void *pixels);
// Skia's own text path: the glyph codes of one strike with Java's positions (or the
// images' advances when usePositions is false) drawn with the strike's SkFont through
// Skia's strike cache, glyph atlas and blending (sun.java2d.skia.text=skia).
// smoothing: the masks come from the platform's font smoothing (Skia's hinting "normal" on the
// CoreText host), as Chrome's do; otherwise Skia's plain coverage.
void SKBackend_DrawGlyphsBySkia(jlong pScalerContext, jint numGlyphs, jboolean usePositions, jboolean smoothing,
                                jfloat originX, jfloat originY, const jint *codes, const jlong *images,
                                const jfloat *positions);
jlong SKBackend_GlyphsDrawnBySkia();
// Skia's shaper (sun.java2d.skia.text=shaper): the characters of a drawString shaped by
// HarfBuzz through SkShaper with Skia's Unicode module (bidi, script and language runs)
// and the platform font manager's fallback, drawn as one text blob at the baseline
// origin (device space) with the strike's SkFont; smoothing as for DrawGlyphsBySkia.
void SKBackend_DrawShapedBySkia(jlong pScalerContext, jboolean smoothing, jfloat originX, jfloat originY,
                                const jchar *chars, jint length);
jlong SKBackend_GlyphsShapedBySkia();
// Skia's paragraph engine (sun.java2d.skia.text=paragraph): an attributed string as runs of
// (strike, colour, decorations) laid out and decorated by SkParagraph on one line, shaped as
// DrawShapedBySkia, painted with its first baseline at the origin. runEnds[r] is the UTF-16 end
// of run r; colours[r] is ARGB when flags[r] has SK_PARAGRAPH_COLOUR.
enum { SK_PARAGRAPH_UNDERLINE = 1, SK_PARAGRAPH_STRIKETHROUGH = 2, SK_PARAGRAPH_COLOUR = 4 };
void SKBackend_DrawParagraphBySkia(jboolean smoothing, jfloat originX, jfloat originY, const jchar *chars, jint length,
                                   jint runCount, const jint *runEnds, const jlong *contexts, const jint *colours,
                                   const jint *flags);
jlong SKBackend_ParagraphsBySkia();
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
