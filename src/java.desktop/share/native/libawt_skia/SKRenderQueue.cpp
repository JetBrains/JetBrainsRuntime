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
// Interpreter of the Java2D render queue for the Skia backend (flusher thread),
// plus the JNI entry points the pipes use when an operation does not fit the
// queue buffer (flushAndInvokeNow).

#include "SKBackend.h"

#include "jlong.h"
#include "jni_util.h"
#include "sun_java2d_pipe_BufferedOpCodes.h"
#include "sun_java2d_pipe_hw_AccelSurface.h"
#include "sun_java2d_pipe_BufferedRenderPipe.h"
#include "sun_java2d_pipe_BufferedTextPipe.h"
#include "sun_java2d_skia_SKMaskFill.h"
#include "sun_java2d_skia_SKRenderQueue.h"
#include "sun_java2d_skia_SKRenderer.h"
#include "sun_java2d_skia_SKShapePipe.h"
#include "sun_java2d_skia_SKTextRenderer.h"

#define BYTES_PER_POLY_POINT sun_java2d_pipe_BufferedRenderPipe_BYTES_PER_POLY_POINT
#define BYTES_PER_SCANLINE   sun_java2d_pipe_BufferedRenderPipe_BYTES_PER_SCANLINE
#define BYTES_PER_SPAN       sun_java2d_pipe_BufferedRenderPipe_BYTES_PER_SPAN
#define BYTES_PER_GLYPH_IMAGE    sun_java2d_pipe_BufferedTextPipe_BYTES_PER_GLYPH_IMAGE
#define BYTES_PER_GLYPH_POSITION sun_java2d_pipe_BufferedTextPipe_BYTES_PER_GLYPH_POSITION
#define OFFSET_POSITIONS sun_java2d_pipe_BufferedTextPipe_OFFSET_POSITIONS
#define OFFSET_SUBPIXPOS sun_java2d_pipe_BufferedTextPipe_OFFSET_SUBPIXPOS
#define OFFSET_RGBORDER  sun_java2d_pipe_BufferedTextPipe_OFFSET_RGBORDER
#define OFFSET_CONTRAST  sun_java2d_pipe_BufferedTextPipe_OFFSET_CONTRAST

#define NEXT_VAL(buf, type) (((type *)((buf) += sizeof(type)))[-1])
#define NEXT_INT(buf)       NEXT_VAL(buf, jint)
#define NEXT_FLOAT(buf)     NEXT_VAL(buf, jfloat)
#define NEXT_BOOLEAN(buf)   (jboolean)NEXT_INT(buf)
#define NEXT_LONG(buf)      NEXT_VAL(buf, jlong)
#define NEXT_DOUBLE(buf)    NEXT_VAL(buf, jdouble)
#define SKIP_BYTES(buf, numbytes) (buf) += (numbytes)
#define EXTRACT_BOOLEAN(packed, offset) (((packed) >> (offset)) & 1)

#define OP(name) case sun_java2d_pipe_BufferedOpCodes_##name

extern "C" {

JNIEXPORT void JNICALL
Java_sun_java2d_skia_SKRenderQueue_flushBuffer(JNIEnv *env, jobject rq, jlong buf, jint limit)
{
    unsigned char *b = (unsigned char *) jlong_to_ptr(buf);
    if (b == nullptr) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "SKRenderQueue_flushBuffer: cannot get direct buffer address");
        return;
    }
    unsigned char *end = b + limit;

    { SKPlatformScope scope;
    while (b < end) {
        jint opcode = NEXT_INT(b);
        switch (opcode) {

        // draw ops
        OP(DRAW_LINE): {
            jint x1 = NEXT_INT(b), y1 = NEXT_INT(b), x2 = NEXT_INT(b), y2 = NEXT_INT(b);
            SKBackend_DrawLine(x1, y1, x2, y2);
            break;
        }
        OP(DRAW_RECT): {
            jint x = NEXT_INT(b), y = NEXT_INT(b), w = NEXT_INT(b), h = NEXT_INT(b);
            SKBackend_DrawRect(x, y, w, h);
            break;
        }
        OP(DRAW_POLY): {
            jint nPoints = NEXT_INT(b);
            jboolean isClosed = NEXT_BOOLEAN(b);
            jint transX = NEXT_INT(b), transY = NEXT_INT(b);
            jint *xPoints = (jint *) b;
            jint *yPoints = xPoints + nPoints;
            SKBackend_DrawPoly(nPoints, isClosed, transX, transY, xPoints, yPoints);
            SKIP_BYTES(b, nPoints * BYTES_PER_POLY_POINT);
            break;
        }
        OP(DRAW_PIXEL): {
            jint x = NEXT_INT(b), y = NEXT_INT(b);
            SKBackend_DrawPixel(x, y);
            break;
        }
        OP(DRAW_SCANLINES): {
            jint count = NEXT_INT(b);
            SKBackend_DrawScanlines(count, (jint *) b);
            SKIP_BYTES(b, count * BYTES_PER_SCANLINE);
            break;
        }
        OP(DRAW_PARALLELOGRAM): {
            jfloat x11 = NEXT_FLOAT(b), y11 = NEXT_FLOAT(b);
            jfloat dx21 = NEXT_FLOAT(b), dy21 = NEXT_FLOAT(b);
            jfloat dx12 = NEXT_FLOAT(b), dy12 = NEXT_FLOAT(b);
            jfloat lwr21 = NEXT_FLOAT(b), lwr12 = NEXT_FLOAT(b);
            SKBackend_DrawParallelogram(false, x11, y11, dx21, dy21, dx12, dy12, lwr21, lwr12);
            break;
        }
        OP(DRAW_AAPARALLELOGRAM): {
            jfloat x11 = NEXT_FLOAT(b), y11 = NEXT_FLOAT(b);
            jfloat dx21 = NEXT_FLOAT(b), dy21 = NEXT_FLOAT(b);
            jfloat dx12 = NEXT_FLOAT(b), dy12 = NEXT_FLOAT(b);
            jfloat lwr21 = NEXT_FLOAT(b), lwr12 = NEXT_FLOAT(b);
            SKBackend_DrawParallelogram(true, x11, y11, dx21, dy21, dx12, dy12, lwr21, lwr12);
            break;
        }

        // fill ops
        OP(FILL_RECT): {
            jint x = NEXT_INT(b), y = NEXT_INT(b), w = NEXT_INT(b), h = NEXT_INT(b);
            SKBackend_FillRect(x, y, w, h);
            break;
        }
        OP(FILL_SPANS): {
            jint count = NEXT_INT(b);
            SKBackend_FillSpans(count, (jint *) b);
            SKIP_BYTES(b, count * BYTES_PER_SPAN);
            break;
        }
        OP(FILL_PARALLELOGRAM): {
            jfloat x11 = NEXT_FLOAT(b), y11 = NEXT_FLOAT(b);
            jfloat dx21 = NEXT_FLOAT(b), dy21 = NEXT_FLOAT(b);
            jfloat dx12 = NEXT_FLOAT(b), dy12 = NEXT_FLOAT(b);
            SKBackend_FillParallelogram(false, x11, y11, dx21, dy21, dx12, dy12);
            break;
        }
        OP(FILL_AAPARALLELOGRAM): {
            jfloat x11 = NEXT_FLOAT(b), y11 = NEXT_FLOAT(b);
            jfloat dx21 = NEXT_FLOAT(b), dy21 = NEXT_FLOAT(b);
            jfloat dx12 = NEXT_FLOAT(b), dy12 = NEXT_FLOAT(b);
            SKBackend_FillParallelogram(true, x11, y11, dx21, dy21, dx12, dy12);
            break;
        }

        // text
        OP(DRAW_GLYPH_LIST): {
            jint numGlyphs = NEXT_INT(b);
            jint packedParams = NEXT_INT(b);
            jfloat origX = NEXT_FLOAT(b), origY = NEXT_FLOAT(b);
            jboolean usePositions = EXTRACT_BOOLEAN(packedParams, OFFSET_POSITIONS);
            jboolean subPixPos = EXTRACT_BOOLEAN(packedParams, OFFSET_SUBPIXPOS);
            jboolean rgbOrder = EXTRACT_BOOLEAN(packedParams, OFFSET_RGBORDER);
            jint lcdContrast = (packedParams >> OFFSET_CONTRAST) & 0xff;
            const jlong *images = (const jlong *) b;
            const jfloat *positions = usePositions ? (const jfloat *) (b + numGlyphs * BYTES_PER_GLYPH_IMAGE) : nullptr;
            SKBackend_DrawGlyphList(numGlyphs, usePositions, subPixPos, rgbOrder, lcdContrast, origX, origY, images, positions);
            SKIP_BYTES(b, numGlyphs * (BYTES_PER_GLYPH_IMAGE + (usePositions ? BYTES_PER_GLYPH_POSITION : 0)));
            break;
        }

        // copy / blit
        OP(COPY_AREA): {
            jint x = NEXT_INT(b), y = NEXT_INT(b), w = NEXT_INT(b), h = NEXT_INT(b);
            jint dx = NEXT_INT(b), dy = NEXT_INT(b);
            SKBackend_CopyArea(x, y, w, h, dx, dy);
            break;
        }
        OP(BLIT): {
            jint packedParams = NEXT_INT(b);
            jint sx1 = NEXT_INT(b), sy1 = NEXT_INT(b), sx2 = NEXT_INT(b), sy2 = NEXT_INT(b);
            jdouble dx1 = NEXT_DOUBLE(b), dy1 = NEXT_DOUBLE(b), dx2 = NEXT_DOUBLE(b), dy2 = NEXT_DOUBLE(b);
            jlong pSrc = NEXT_LONG(b), pDst = NEXT_LONG(b);
            SKBackend_Blit(env, packedParams, sx1, sy1, sx2, sy2, dx1, dy1, dx2, dy2, pSrc, pDst);
            break;
        }
        OP(SURFACE_TO_SW_BLIT): {
            jint sx = NEXT_INT(b), sy = NEXT_INT(b), dx = NEXT_INT(b), dy = NEXT_INT(b);
            jint w = NEXT_INT(b), h = NEXT_INT(b), dsttype = NEXT_INT(b);
            jlong pSrc = NEXT_LONG(b), pDst = NEXT_LONG(b);
            SKBackend_SurfaceToSwBlit(env, sx, sy, dx, dy, w, h, dsttype, pSrc, pDst);
            break;
        }
        OP(MASK_FILL): {
            jint x = NEXT_INT(b), y = NEXT_INT(b), w = NEXT_INT(b), h = NEXT_INT(b);
            jint maskoff = NEXT_INT(b), maskscan = NEXT_INT(b), masklen = NEXT_INT(b);
            SKBackend_MaskFill(x, y, w, h, maskoff, maskscan, masklen, masklen > 0 ? b : nullptr);
            SKIP_BYTES(b, masklen);
            break;
        }
        OP(MASK_BLIT): {
            jint dstx = NEXT_INT(b), dsty = NEXT_INT(b), width = NEXT_INT(b), height = NEXT_INT(b);
            SKBackend_MaskBlit(dstx, dsty, width, height, b);
            SKIP_BYTES(b, width * height * sizeof(jint));
            break;
        }

        // state: clip
        OP(SET_RECT_CLIP): {
            jint x1 = NEXT_INT(b), y1 = NEXT_INT(b), x2 = NEXT_INT(b), y2 = NEXT_INT(b);
            SKBackend_SetRectClip(x1, y1, x2, y2);
            break;
        }
        OP(BEGIN_SHAPE_CLIP): {
            SKBackend_BeginShapeClip();
            break;
        }
        OP(SET_SHAPE_CLIP_SPANS): {
            jint count = NEXT_INT(b);
            SKBackend_AddShapeClipSpans(count, (jint *) b);
            SKIP_BYTES(b, count * BYTES_PER_SPAN);
            break;
        }
        OP(END_SHAPE_CLIP): {
            SKBackend_EndShapeClip();
            break;
        }
        OP(RESET_CLIP): {
            SKBackend_ResetClip();
            break;
        }

        // state: composite
        OP(SET_ALPHA_COMPOSITE): {
            jint rule = NEXT_INT(b);
            jfloat extraAlpha = NEXT_FLOAT(b);
            jint flags = NEXT_INT(b);
            SKBackend_SetAlphaComposite(rule, extraAlpha, flags);
            break;
        }
        OP(SET_XOR_COMPOSITE): {
            jint xorPixel = NEXT_INT(b);
            SKBackend_SetXorComposite(xorPixel);
            break;
        }
        OP(RESET_COMPOSITE): {
            SKBackend_ResetComposite();
            break;
        }

        // state: transform
        OP(SET_TRANSFORM): {
            jdouble m00 = NEXT_DOUBLE(b), m10 = NEXT_DOUBLE(b), m01 = NEXT_DOUBLE(b);
            jdouble m11 = NEXT_DOUBLE(b), m02 = NEXT_DOUBLE(b), m12 = NEXT_DOUBLE(b);
            SKBackend_SetTransform(m00, m10, m01, m11, m02, m12);
            break;
        }
        OP(RESET_TRANSFORM): {
            SKBackend_ResetTransform();
            break;
        }

        // state: surfaces / context
        OP(SET_SURFACES): {
            jlong pSrc = NEXT_LONG(b);
            jlong pDst = NEXT_LONG(b);
            (void) pSrc;
            SKBackend_SetDestination((SKSDOps *) jlong_to_ptr(pDst));
            break;
        }
        OP(SET_SCRATCH_SURFACE): {
            jlong pConfigInfo = NEXT_LONG(b);
            SKBackend_SetScratch((SKPlatformConfig *) jlong_to_ptr(pConfigInfo));
            break;
        }
        OP(FLUSH_SURFACE):
        OP(DISPOSE_SURFACE): {
            jlong pData = NEXT_LONG(b);
            SKSDOps *ops = (SKSDOps *) jlong_to_ptr(pData);
            if (ops == SKBackend_CurrentDestination()) {
                SKBackend_InvalidateState();
            }
            bool dispose = opcode == sun_java2d_pipe_BufferedOpCodes_DISPOSE_SURFACE;
            SKBackend_ReleaseSurface(ops, dispose);
            // A flushed window surface is being replaced (AWT never draws to it
            // again): its platform side goes too, so the window's swap chain is
            // free for the successor without waiting for the Disposer.
            if (ops != nullptr && ops->platform != nullptr
                    && (dispose || ops->type == sun_java2d_pipe_hw_AccelSurface_WINDOW)) {
                SKPlatform_DisposeSurface(ops->platform);
                ops->platform = nullptr;
            }
            break;
        }
        OP(DISPOSE_CONFIG): {
            jlong pConfigInfo = NEXT_LONG(b);
            SKBackend_InvalidateState();
            SKBackend_DisposeContext((SKPlatformConfig *) jlong_to_ptr(pConfigInfo));
            break;
        }
        OP(INVALIDATE_CONTEXT): {
            SKBackend_InvalidateState();
            break;
        }
        case sun_java2d_skia_SKShapePipe_FILL_SHAPE:
        case sun_java2d_skia_SKShapePipe_DRAW_SHAPE: {
            const bool fill = opcode == sun_java2d_skia_SKShapePipe_FILL_SHAPE;
            jint kind = NEXT_INT(b);
            const jfloat *xform = (const jfloat *) b; b += 6 * sizeof(jfloat);
            const jfloat *stroke = nullptr;
            if (!fill) {
                stroke = (const jfloat *) b;
                b += 4 * sizeof(jfloat);   // width, cap, join, miter limit
            }
            if (kind == sun_java2d_skia_SKShapePipe_KIND_PATH) {
                jint rule = NEXT_INT(b), numTypes = NEXT_INT(b), numCoords = NEXT_INT(b);
                const jint *types = (const jint *) b; b += numTypes * sizeof(jint);
                const jfloat *coords = (const jfloat *) b; b += numCoords * sizeof(jfloat);
                SKBackend_DrawShape(fill, kind, xform, stroke, nullptr, rule, numTypes, types, numCoords, coords);
            } else {
                const jfloat *params = (const jfloat *) b; b += 6 * sizeof(jfloat);
                SKBackend_DrawShape(fill, kind, xform, stroke, params, 0, 0, nullptr, 0, nullptr);
            }
            break;
        }
        OP(SYNC): {
            SKBackend_EndOfFlush(true);
            break;
        }
        OP(FLUSH_BUFFER): {
            jlong pLayerPtr = NEXT_LONG(b);
            (void) pLayerPtr;
            break;
        }
        OP(SWAP_BUFFERS): {
            jlong window = NEXT_LONG(b);
            (void) window;
            break;
        }
        OP(NOOP):
            break;

        // state: paint
        OP(RESET_PAINT): {
            SKBackend_ResetPaint();
            break;
        }
        OP(SET_COLOR): {
            jint pixel = NEXT_INT(b);
            SKBackend_SetColor(pixel);
            break;
        }
        OP(SET_GRADIENT_PAINT): {
            jboolean useMask = NEXT_BOOLEAN(b);
            jboolean cyclic = NEXT_BOOLEAN(b);
            jdouble p0 = NEXT_DOUBLE(b), p1 = NEXT_DOUBLE(b), p3 = NEXT_DOUBLE(b);
            jint pixel1 = NEXT_INT(b), pixel2 = NEXT_INT(b);
            (void) useMask;
            SKBackend_SetGradientPaint(cyclic, p0, p1, p3, pixel1, pixel2);
            break;
        }
        OP(SET_LINEAR_GRADIENT_PAINT): {
            jboolean useMask = NEXT_BOOLEAN(b);
            jboolean linear = NEXT_BOOLEAN(b);
            jint cycleMethod = NEXT_INT(b);
            jint numStops = NEXT_INT(b);
            jfloat p0 = NEXT_FLOAT(b), p1 = NEXT_FLOAT(b), p3 = NEXT_FLOAT(b);
            const jfloat *fractions = (const jfloat *) b; SKIP_BYTES(b, numStops * sizeof(jfloat));
            const jint *pixels = (const jint *) b;        SKIP_BYTES(b, numStops * sizeof(jint));
            (void) useMask;
            SKBackend_SetLinearGradientPaint(linear, cycleMethod, numStops, p0, p1, p3, fractions, pixels);
            break;
        }
        OP(SET_RADIAL_GRADIENT_PAINT): {
            jboolean useMask = NEXT_BOOLEAN(b);
            jboolean linear = NEXT_BOOLEAN(b);
            jint numStops = NEXT_INT(b);
            jint cycleMethod = NEXT_INT(b);
            jfloat m00 = NEXT_FLOAT(b), m01 = NEXT_FLOAT(b), m02 = NEXT_FLOAT(b);
            jfloat m10 = NEXT_FLOAT(b), m11 = NEXT_FLOAT(b), m12 = NEXT_FLOAT(b);
            jfloat focusX = NEXT_FLOAT(b);
            const jfloat *fractions = (const jfloat *) b; SKIP_BYTES(b, numStops * sizeof(jfloat));
            const jint *pixels = (const jint *) b;        SKIP_BYTES(b, numStops * sizeof(jint));
            (void) useMask;
            SKBackend_SetRadialGradientPaint(linear, cycleMethod, numStops, m00, m01, m02, m10, m11, m12,
                                               focusX, fractions, pixels);
            break;
        }
        OP(SET_TEXTURE_PAINT): {
            jboolean useMask = NEXT_BOOLEAN(b);
            jboolean filter = NEXT_BOOLEAN(b);
            jlong pSrc = NEXT_LONG(b);
            jdouble xp0 = NEXT_DOUBLE(b), xp1 = NEXT_DOUBLE(b), xp3 = NEXT_DOUBLE(b);
            jdouble yp0 = NEXT_DOUBLE(b), yp1 = NEXT_DOUBLE(b), yp3 = NEXT_DOUBLE(b);
            (void) useMask;
            SKBackend_SetTexturePaint(env, filter, pSrc, xp0, xp1, xp3, yp0, yp1, yp3);
            break;
        }

        // BufferedImageOps are never enqueued by SKDrawImage; skip defensively.
        OP(ENABLE_CONVOLVE_OP): {
            SKIP_BYTES(b, sizeof(jlong) + 3 * sizeof(jint));
            jint kernelWidth = ((jint *) b)[-2], kernelHeight = ((jint *) b)[-1];
            SKIP_BYTES(b, kernelWidth * kernelHeight * sizeof(jfloat));
            break;
        }
        OP(ENABLE_RESCALE_OP): {
            SKIP_BYTES(b, sizeof(jlong) + sizeof(jint));
            jint numFactors = 4;
            SKIP_BYTES(b, numFactors * 2 * sizeof(jfloat));
            break;
        }
        OP(ENABLE_LOOKUP_OP): {
            SKIP_BYTES(b, sizeof(jlong));
            jboolean nonPremult = NEXT_BOOLEAN(b);
            jboolean shortData  = NEXT_BOOLEAN(b);
            jint numBands       = NEXT_INT(b);
            jint bandLength     = NEXT_INT(b);
            jint offset         = NEXT_INT(b);
            (void) nonPremult; (void) offset;
            SKIP_BYTES(b, numBands * bandLength * (shortData ? sizeof(jshort) : sizeof(jbyte)));
            break;
        }
        OP(DISABLE_CONVOLVE_OP):
        OP(DISABLE_RESCALE_OP):
        OP(DISABLE_LOOKUP_OP):
            break;

        default:
            J2dRlsTraceLn(J2D_TRACE_ERROR, "SKRenderQueue_flushBuffer: invalid opcode=%d", opcode);
            SKBackend_EndOfFlush();
            return;
        }
    }
    SKBackend_EndOfFlush();
    } // SKPlatformScope
}

// ---- direct entry points (flushAndInvokeNow) ------------------------------

JNIEXPORT void JNICALL
Java_sun_java2d_skia_SKRenderer_drawPoly(JNIEnv *env, jobject renderer,
                                           jintArray xpointsArray, jintArray ypointsArray,
                                           jint nPoints, jboolean isClosed, jint transX, jint transY)
{
    { SKPlatformScope scope;
    jint *xPoints = (jint *) env->GetPrimitiveArrayCritical(xpointsArray, nullptr);
    if (xPoints != nullptr) {
        jint *yPoints = (jint *) env->GetPrimitiveArrayCritical(ypointsArray, nullptr);
        if (yPoints != nullptr) {
            SKBackend_DrawPoly(nPoints, isClosed, transX, transY, xPoints, yPoints);
            env->ReleasePrimitiveArrayCritical(ypointsArray, yPoints, JNI_ABORT);
        }
        env->ReleasePrimitiveArrayCritical(xpointsArray, xPoints, JNI_ABORT);
    }
    SKBackend_EndOfFlush();
    } // SKPlatformScope
}

JNIEXPORT void JNICALL
Java_sun_java2d_skia_SKShapePipe_drawShape(JNIEnv *env, jclass clazz, jboolean fill, jint kind,
                                           jfloatArray xformArr, jfloatArray strokeArr, jint windingRule,
                                           jintArray typesArr, jint numTypes, jfloatArray coordsArr, jint numCoords)
{
    { SKPlatformScope scope;
    jfloat *xform = (jfloat *) env->GetPrimitiveArrayCritical(xformArr, nullptr);
    jfloat *stroke = strokeArr != nullptr ? (jfloat *) env->GetPrimitiveArrayCritical(strokeArr, nullptr) : nullptr;
    jint *types = typesArr != nullptr ? (jint *) env->GetPrimitiveArrayCritical(typesArr, nullptr) : nullptr;
    jfloat *coords = coordsArr != nullptr ? (jfloat *) env->GetPrimitiveArrayCritical(coordsArr, nullptr) : nullptr;
    // only paths take this entry (the other kinds always fit the queue), so there
    // are no analytic parameters to pass
    if (kind == sun_java2d_skia_SKShapePipe_KIND_PATH && xform != nullptr && (strokeArr == nullptr || stroke != nullptr)
            && (typesArr == nullptr || types != nullptr) && (coordsArr == nullptr || coords != nullptr)) {
        SKBackend_DrawShape(fill, kind, xform, stroke, nullptr, windingRule, numTypes, types, numCoords, coords);
    }
    if (coords != nullptr) env->ReleasePrimitiveArrayCritical(coordsArr, coords, JNI_ABORT);
    if (types != nullptr) env->ReleasePrimitiveArrayCritical(typesArr, types, JNI_ABORT);
    if (stroke != nullptr) env->ReleasePrimitiveArrayCritical(strokeArr, stroke, JNI_ABORT);
    if (xform != nullptr) env->ReleasePrimitiveArrayCritical(xformArr, xform, JNI_ABORT);
    SKBackend_EndOfFlush();
    } // SKPlatformScope
}

JNIEXPORT void JNICALL
Java_sun_java2d_skia_SKMaskFill_maskFill(JNIEnv *env, jobject self,
                                           jint x, jint y, jint w, jint h,
                                           jint maskoff, jint maskscan, jint masklen,
                                           jbyteArray maskArray)
{
    { SKPlatformScope scope;
    unsigned char *mask = nullptr;
    if (maskArray != nullptr) {
        mask = (unsigned char *) env->GetPrimitiveArrayCritical(maskArray, nullptr);
        if (mask == nullptr) {
            return;
        }
    }
    SKBackend_MaskFill(x, y, w, h, maskoff, maskscan, masklen, mask);
    if (mask != nullptr) {
        env->ReleasePrimitiveArrayCritical(maskArray, mask, JNI_ABORT);
    }
    SKBackend_EndOfFlush();
    } // SKPlatformScope
}

JNIEXPORT void JNICALL
Java_sun_java2d_skia_SKTextRenderer_drawGlyphsBySkia(JNIEnv *env, jclass clazz, jlong pScalerContext,
                                                       jint numGlyphs, jboolean usePositions, jboolean smoothing,
                                                       jfloat originX, jfloat originY,
                                                       jintArray codeArray, jlongArray imgArray, jfloatArray posArray)
{
    { SKPlatformScope scope;
    jint *codes = (jint *) env->GetPrimitiveArrayCritical(codeArray, nullptr);
    if (codes == nullptr) {
        return;
    }
    jlong *images = imgArray != nullptr ? (jlong *) env->GetPrimitiveArrayCritical(imgArray, nullptr) : nullptr;
    jfloat *positions = usePositions && posArray != nullptr ? (jfloat *) env->GetPrimitiveArrayCritical(posArray, nullptr) : nullptr;
    if (!usePositions || positions != nullptr) {
        SKBackend_DrawGlyphsBySkia(pScalerContext, numGlyphs, usePositions, smoothing, originX, originY, codes, images, positions);
    }
    if (positions != nullptr) {
        env->ReleasePrimitiveArrayCritical(posArray, positions, JNI_ABORT);
    }
    if (images != nullptr) {
        env->ReleasePrimitiveArrayCritical(imgArray, images, JNI_ABORT);
    }
    env->ReleasePrimitiveArrayCritical(codeArray, codes, JNI_ABORT);
    SKBackend_EndOfFlush();
    } // SKPlatformScope
}

JNIEXPORT void JNICALL
Java_sun_java2d_skia_SKTextRenderer_drawShapedBySkia(JNIEnv *env, jclass clazz, jlong pScalerContext,
                                                       jboolean smoothing, jfloat originX, jfloat originY, jstring text)
{
    { SKPlatformScope scope;
    const jint length = env->GetStringLength(text);
    const jchar *chars = env->GetStringChars(text, nullptr);
    if (chars == nullptr) {
        return;
    }
    SKBackend_DrawShapedBySkia(pScalerContext, smoothing, originX, originY, chars, length);
    env->ReleaseStringChars(text, chars);
    SKBackend_EndOfFlush();
    } // SKPlatformScope
}

JNIEXPORT void JNICALL
Java_sun_java2d_skia_SKTextRenderer_drawGlyphList(JNIEnv *env, jobject self,
                                                    jint numGlyphs, jboolean usePositions,
                                                    jboolean subPixPos, jboolean rgbOrder,
                                                    jint lcdContrast,
                                                    jfloat glyphListOrigX, jfloat glyphListOrigY,
                                                    jlongArray imgArray, jfloatArray posArray)
{
    { SKPlatformScope scope;
    jlong *images = (jlong *) env->GetPrimitiveArrayCritical(imgArray, nullptr);
    if (images == nullptr) {
        return;
    }
    jfloat *positions = nullptr;
    if (usePositions && posArray != nullptr) {
        positions = (jfloat *) env->GetPrimitiveArrayCritical(posArray, nullptr);
    }
    if (!usePositions || positions != nullptr) {
        SKBackend_DrawGlyphList(numGlyphs, usePositions, subPixPos, rgbOrder, lcdContrast,
                                glyphListOrigX, glyphListOrigY, images, positions);
    }
    if (positions != nullptr) {
        env->ReleasePrimitiveArrayCritical(posArray, positions, JNI_ABORT);
    }
    env->ReleasePrimitiveArrayCritical(imgArray, images, JNI_ABORT);
    SKBackend_EndOfFlush();
    } // SKPlatformScope
}

} // extern "C"
