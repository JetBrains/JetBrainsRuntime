/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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

#ifndef HEADLESS

#include <stdlib.h>

#include "sun_java2d_pipe_BufferedOpCodes.h"

#include "jlong.h"
#include "MTLBlitLoops.h"
#include "MTLBufImgOps.h"
#include "MTLContext.h"
#include "MTLMaskBlit.h"
#include "MTLMaskFill.h"
#include "MTLPaints.h"
#include "MTLRenderQueue.h"
#include "MTLRenderer.h"
#include "MTLSurfaceData.h"
#include "MTLTextRenderer.h"
#include "MTLVertexCache.h"

/**
 * Used to track whether we are in a series of a simple primitive operations
 * or texturing operations.  This variable should be controlled only via
 * the INIT/CHECK/RESET_PREVIOUS_OP() macros.  See the
 * MTLRenderQueue_CheckPreviousOp() method below for more information.
 */
jint previousOp;

/**
 * References to the "current" context and destination surface.
 */
static MTLContext *mtlc = NULL;
static BMTLSDOps *dstOps = NULL;

/**
 * The following methods are implemented in the windowing system (i.e. GLX
 * and WGL) source files.
 */
extern MTLContext *MTLSD_SetScratchSurface(JNIEnv *env, jlong pConfigInfo);
extern void MTLGC_DestroyMTLGraphicsConfig(jlong pConfigInfo);
extern void MTLSD_SwapBuffers(JNIEnv *env, jlong window);
extern void MTLSD_Flush(JNIEnv *env);

JNIEXPORT void JNICALL
Java_sun_java2d_metal_MTLRenderQueue_flushBuffer
    (JNIEnv *env, jobject mtlrq,
     jlong buf, jint limit)
{
    jboolean sync = JNI_FALSE;
    unsigned char *b, *end;

    J2dTraceLn1(J2D_TRACE_INFO,
                "MTLRenderQueue_flushBuffer: limit=%d", limit);

    b = (unsigned char *)jlong_to_ptr(buf);
    if (b == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR,
            "MTLRenderQueue_flushBuffer: cannot get direct buffer address");
        return;
    }

    INIT_PREVIOUS_OP();
    end = b + limit;

    while (b < end) {
        jint opcode = NEXT_INT(b);

        J2dTraceLn2(J2D_TRACE_VERBOSE,
                    "MTLRenderQueue_flushBuffer: opcode=%d, rem=%d",
                    opcode, (end-b));

        if (opcode != sun_java2d_pipe_BufferedOpCodes_DRAW_GLYPH_LIST &&
            opcode != sun_java2d_pipe_BufferedOpCodes_NOOP)
        {
            //MTLTR_DisableGlyphModeState();
        }

        switch (opcode) {

        // draw ops
        case sun_java2d_pipe_BufferedOpCodes_DRAW_LINE:
            {
                J2dTraceLn(J2D_TRACE_VERBOSE, "sun_java2d_pipe_BufferedOpCodes_DRAW_LINE");
                jint x1 = NEXT_INT(b);
                jint y1 = NEXT_INT(b);
                jint x2 = NEXT_INT(b);
                jint y2 = NEXT_INT(b);
                MTLRenderer_DrawLine(mtlc, x1, y1, x2, y2);
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_DRAW_RECT:
            {
                jint x = NEXT_INT(b);
                jint y = NEXT_INT(b);
                jint w = NEXT_INT(b);
                jint h = NEXT_INT(b);
                MTLRenderer_DrawRect(mtlc, x, y, w, h);
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_DRAW_POLY:
            {
                jint nPoints      = NEXT_INT(b);
                jboolean isClosed = NEXT_BOOLEAN(b);
                jint transX       = NEXT_INT(b);
                jint transY       = NEXT_INT(b);
                jint *xPoints = (jint *)b;
                jint *yPoints = ((jint *)b) + nPoints;
                MTLRenderer_DrawPoly(mtlc, nPoints, isClosed, transX, transY, xPoints, yPoints);
                SKIP_BYTES(b, nPoints * BYTES_PER_POLY_POINT);
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_DRAW_PIXEL:
            {
                jint x = NEXT_INT(b);
                jint y = NEXT_INT(b);
                CONTINUE_IF_NULL(mtlc);
                //TODO
                J2dTracePrimitive("MTLRenderQueue_DRAW_PIXEL");
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_DRAW_SCANLINES:
            {
                jint count = NEXT_INT(b);
                MTLRenderer_DrawScanlines(mtlc, count, (jint *)b);

                SKIP_BYTES(b, count * BYTES_PER_SCANLINE);
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_DRAW_PARALLELOGRAM:
            {
                jfloat x11 = NEXT_FLOAT(b);
                jfloat y11 = NEXT_FLOAT(b);
                jfloat dx21 = NEXT_FLOAT(b);
                jfloat dy21 = NEXT_FLOAT(b);
                jfloat dx12 = NEXT_FLOAT(b);
                jfloat dy12 = NEXT_FLOAT(b);
                jfloat lwr21 = NEXT_FLOAT(b);
                jfloat lwr12 = NEXT_FLOAT(b);

                MTLRenderer_DrawParallelogram(mtlc,
                                              x11, y11,
                                              dx21, dy21,
                                              dx12, dy12,
                                              lwr21, lwr12);
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_DRAW_AAPARALLELOGRAM:
            {
                jfloat x11 = NEXT_FLOAT(b);
                jfloat y11 = NEXT_FLOAT(b);
                jfloat dx21 = NEXT_FLOAT(b);
                jfloat dy21 = NEXT_FLOAT(b);
                jfloat dx12 = NEXT_FLOAT(b);
                jfloat dy12 = NEXT_FLOAT(b);
                jfloat lwr21 = NEXT_FLOAT(b);
                jfloat lwr12 = NEXT_FLOAT(b);

                MTLRenderer_DrawAAParallelogram(mtlc, dstOps,
                                                x11, y11,
                                                dx21, dy21,
                                                dx12, dy12,
                                                lwr21, lwr12);
            }
            break;

        // fill ops
        case sun_java2d_pipe_BufferedOpCodes_FILL_RECT:
            {
                jint x = NEXT_INT(b);
                jint y = NEXT_INT(b);
                jint w = NEXT_INT(b);
                jint h = NEXT_INT(b);
                MTLRenderer_FillRect(mtlc, x, y, w, h);
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_FILL_SPANS:
            {
                jint count = NEXT_INT(b);
                MTLRenderer_FillSpans(mtlc, count, (jint *)b);
                SKIP_BYTES(b, count * BYTES_PER_SPAN);
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_FILL_PARALLELOGRAM:
            {
                jfloat x11 = NEXT_FLOAT(b);
                jfloat y11 = NEXT_FLOAT(b);
                jfloat dx21 = NEXT_FLOAT(b);
                jfloat dy21 = NEXT_FLOAT(b);
                jfloat dx12 = NEXT_FLOAT(b);
                jfloat dy12 = NEXT_FLOAT(b);
                MTLRenderer_FillParallelogram(mtlc,
                                              x11, y11,
                                              dx21, dy21,
                                              dx12, dy12);
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_FILL_AAPARALLELOGRAM:
            {
                jfloat x11 = NEXT_FLOAT(b);
                jfloat y11 = NEXT_FLOAT(b);
                jfloat dx21 = NEXT_FLOAT(b);
                jfloat dy21 = NEXT_FLOAT(b);
                jfloat dx12 = NEXT_FLOAT(b);
                jfloat dy12 = NEXT_FLOAT(b);
                MTLRenderer_FillAAParallelogram(mtlc, dstOps,
                                                x11, y11,
                                                dx21, dy21,
                                                dx12, dy12);
            }
            break;

        // text-related ops
        case sun_java2d_pipe_BufferedOpCodes_DRAW_GLYPH_LIST:
            {
                jint numGlyphs        = NEXT_INT(b);
                jint packedParams     = NEXT_INT(b);
                jfloat glyphListOrigX = NEXT_FLOAT(b);
                jfloat glyphListOrigY = NEXT_FLOAT(b);
                jboolean usePositions = EXTRACT_BOOLEAN(packedParams,
                                                        OFFSET_POSITIONS);
                jboolean subPixPos    = EXTRACT_BOOLEAN(packedParams,
                                                        OFFSET_SUBPIXPOS);
                jboolean rgbOrder     = EXTRACT_BOOLEAN(packedParams,
                                                        OFFSET_RGBORDER);
                jint lcdContrast      = EXTRACT_BYTE(packedParams,
                                                     OFFSET_CONTRAST);
                unsigned char *images = b;
                unsigned char *positions;
                jint bytesPerGlyph;
                if (usePositions) {
                    positions = (b + numGlyphs * BYTES_PER_GLYPH_IMAGE);
                    bytesPerGlyph = BYTES_PER_POSITIONED_GLYPH;
                } else {
                    positions = NULL;
                    bytesPerGlyph = BYTES_PER_GLYPH_IMAGE;
                }
                MTLTR_DrawGlyphList(env, mtlc, dstOps,
                                    numGlyphs, usePositions,
                                    subPixPos, rgbOrder, lcdContrast,
                                    glyphListOrigX, glyphListOrigY,
                                    images, positions);
                SKIP_BYTES(b, numGlyphs * bytesPerGlyph);
            }
            break;

        // copy-related ops
        case sun_java2d_pipe_BufferedOpCodes_COPY_AREA:
            {
                jint x  = NEXT_INT(b);
                jint y  = NEXT_INT(b);
                jint w  = NEXT_INT(b);
                jint h  = NEXT_INT(b);
                jint dx = NEXT_INT(b);
                jint dy = NEXT_INT(b);
                MTLBlitLoops_CopyArea(env, mtlc, dstOps,
                                      x, y, w, h, dx, dy);
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_BLIT:
            {
                J2dTracePrimitive("sun_java2d_pipe_BufferedOpCodes_BLIT");

                jint packedParams = NEXT_INT(b);
                jint sx1          = NEXT_INT(b);
                jint sy1          = NEXT_INT(b);
                jint sx2          = NEXT_INT(b);
                jint sy2          = NEXT_INT(b);
                jdouble dx1       = NEXT_DOUBLE(b);
                jdouble dy1       = NEXT_DOUBLE(b);
                jdouble dx2       = NEXT_DOUBLE(b);
                jdouble dy2       = NEXT_DOUBLE(b);
                jlong pSrc        = NEXT_LONG(b);
                jlong pDst        = NEXT_LONG(b);
                jint hint         = EXTRACT_BYTE(packedParams, OFFSET_HINT);
                jboolean texture  = EXTRACT_BOOLEAN(packedParams,
                                                    OFFSET_TEXTURE);
                jboolean rtt      = EXTRACT_BOOLEAN(packedParams,
                                                    OFFSET_RTT);
                jboolean xform    = EXTRACT_BOOLEAN(packedParams,
                                                    OFFSET_XFORM);
                jboolean isoblit  = EXTRACT_BOOLEAN(packedParams,
                                                    OFFSET_ISOBLIT);
                if (isoblit) {
                    MTLBlitLoops_IsoBlit(env, mtlc, pSrc, pDst,
                                         xform, hint, texture, rtt,
                                         sx1, sy1, sx2, sy2,
                                         dx1, dy1, dx2, dy2);
                } else {
                    jint srctype = EXTRACT_BYTE(packedParams, OFFSET_SRCTYPE);
                    MTLBlitLoops_Blit(env, mtlc, pSrc, pDst,
                                      xform, hint, srctype, texture,
                                      sx1, sy1, sx2, sy2,
                                      dx1, dy1, dx2, dy2);
                }
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_SURFACE_TO_SW_BLIT:
            {
                J2dTracePrimitive("sun_java2d_pipe_BufferedOpCodes_SURFACE_TO_SW_BLIT");

                jint sx      = NEXT_INT(b);
                jint sy      = NEXT_INT(b);
                jint dx      = NEXT_INT(b);
                jint dy      = NEXT_INT(b);
                jint w       = NEXT_INT(b);
                jint h       = NEXT_INT(b);
                jint dsttype = NEXT_INT(b);
                jlong pSrc   = NEXT_LONG(b);
                jlong pDst   = NEXT_LONG(b);
                MTLBlitLoops_SurfaceToSwBlit(env, mtlc,
                                             pSrc, pDst, dsttype,
                                             sx, sy, dx, dy, w, h);
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_MASK_FILL:
            {
                J2dTracePrimitive("sun_java2d_pipe_BufferedOpCodes_MASK_FILL");

                jint x        = NEXT_INT(b);
                jint y        = NEXT_INT(b);
                jint w        = NEXT_INT(b);
                jint h        = NEXT_INT(b);
                jint maskoff  = NEXT_INT(b);
                jint maskscan = NEXT_INT(b);
                jint masklen  = NEXT_INT(b);
                unsigned char *pMask = (masklen > 0) ? b : NULL;
                MTLMaskFill_MaskFill(mtlc, x, y, w, h,
                                     maskoff, maskscan, masklen, pMask);
                SKIP_BYTES(b, masklen);
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_MASK_BLIT:
            {
                J2dTracePrimitive("sun_java2d_pipe_BufferedOpCodes_MASK_BLIT");

                jint dstx     = NEXT_INT(b);
                jint dsty     = NEXT_INT(b);
                jint width    = NEXT_INT(b);
                jint height   = NEXT_INT(b);
                jint masklen  = width * height * sizeof(jint);
                MTLMaskBlit_MaskBlit(env, mtlc,
                                     dstx, dsty, width, height, b);
                SKIP_BYTES(b, masklen);
            }
            break;

        // state-related ops
        case sun_java2d_pipe_BufferedOpCodes_SET_RECT_CLIP:
            {
                jint x1 = NEXT_INT(b);
                jint y1 = NEXT_INT(b);
                jint x2 = NEXT_INT(b);
                jint y2 = NEXT_INT(b);
                MTLContext_SetRectClip(mtlc, dstOps, x1, y1, x2, y2);
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_BEGIN_SHAPE_CLIP:
            {
                MTLContext_BeginShapeClip(mtlc);
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_SET_SHAPE_CLIP_SPANS:
            {
                jint count = NEXT_INT(b);
                MTLRenderer_FillSpans(mtlc, count, (jint *)b);
                SKIP_BYTES(b, count * BYTES_PER_SPAN);
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_END_SHAPE_CLIP:
            {
                MTLContext_EndShapeClip(mtlc, dstOps);
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_RESET_CLIP:
            {
                MTLContext_ResetClip(mtlc);
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_SET_ALPHA_COMPOSITE:
            {
                jint rule         = NEXT_INT(b);
                jfloat extraAlpha = NEXT_FLOAT(b);
                jint flags        = NEXT_INT(b);
                MTLContext_SetAlphaComposite(mtlc, rule, extraAlpha, flags);
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_SET_XOR_COMPOSITE:
            {
                jint xorPixel = NEXT_INT(b);
                MTLContext_SetXorComposite(mtlc, xorPixel);
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_RESET_COMPOSITE:
            {
                MTLContext_ResetComposite(mtlc);
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_SET_TRANSFORM:
            {
                jdouble m00 = NEXT_DOUBLE(b);
                jdouble m10 = NEXT_DOUBLE(b);
                jdouble m01 = NEXT_DOUBLE(b);
                jdouble m11 = NEXT_DOUBLE(b);
                jdouble m02 = NEXT_DOUBLE(b);
                jdouble m12 = NEXT_DOUBLE(b);
                MTLContext_SetTransform(mtlc, m00, m10, m01, m11, m02, m12);
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_RESET_TRANSFORM:
            {
                MTLContext_ResetTransform(mtlc);
            }
            break;

        // context-related ops
        case sun_java2d_pipe_BufferedOpCodes_SET_SURFACES:
            {
                J2dTracePrimitive("sun_java2d_pipe_BufferedOpCodes_SET_SURFACES");

                jlong pSrc = NEXT_LONG(b);
                jlong pDst = NEXT_LONG(b);
                if (mtlc != NULL) {
                    RESET_PREVIOUS_OP();
                }
                mtlc = MTLContext_SetSurfaces(env, pSrc, pDst);

                dstOps = (BMTLSDOps *)jlong_to_ptr(pDst);

                if (dstOps != NULL) {
                    MTLSDOps *dstCGLOps = (MTLSDOps *)dstOps->privOps;
                    MTLLayer *layer = (MTLLayer*)dstCGLOps->layer;
                    [JNFRunLoop performOnMainThreadWaiting:NO withBlock:^(){
                        MTLRenderer_BeginFrame(dstCGLOps->configInfo->context, layer);
                    }];
                }
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_SET_SCRATCH_SURFACE:
            {
                J2dTracePrimitive("sun_java2d_pipe_BufferedOpCodes_SET_SCRATCH_SURFACE");
                jlong pConfigInfo = NEXT_LONG(b);
                if (mtlc != NULL) {
                    RESET_PREVIOUS_OP();
                }
                [JNFRunLoop performOnMainThreadWaiting:NO withBlock:^(){
                    mtlc = MTLSD_SetScratchSurface(env, pConfigInfo);
                    dstOps = NULL;
                }];
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_FLUSH_SURFACE:
            {
                J2dTracePrimitive("sun_java2d_pipe_BufferedOpCodes_FLUSH_SURFACE");
                jlong pData = NEXT_LONG(b);
                BMTLSDOps *mtlsdo = (BMTLSDOps *)jlong_to_ptr(pData);
                if (mtlsdo != NULL) {
                    CONTINUE_IF_NULL(mtlc);
                    RESET_PREVIOUS_OP();
                    MTLSD_Delete(env, mtlsdo);
                }
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_DISPOSE_SURFACE:
            {
                J2dTracePrimitive("MTLRenderQueue_DISPOSE_SURFACE");
                jlong pData = NEXT_LONG(b);
                BMTLSDOps *mtlsdo = (BMTLSDOps *)jlong_to_ptr(pData);
                if (mtlsdo != NULL) {
                    CONTINUE_IF_NULL(mtlc);
                    RESET_PREVIOUS_OP();
                    MTLSD_Delete(env, mtlsdo);
                    if (mtlsdo->privOps != NULL) {
                        free(mtlsdo->privOps);
                    }
                }
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_DISPOSE_CONFIG:
            {
                J2dTracePrimitive("MTLRenderQueue_DISPOSE_CONFIG");
                jlong pConfigInfo = NEXT_LONG(b);
                CONTINUE_IF_NULL(mtlc);
                RESET_PREVIOUS_OP();
                [JNFRunLoop performOnMainThreadWaiting:NO withBlock:^(){
                    MTLGC_DestroyMTLGraphicsConfig(pConfigInfo);
                }];


                // the previous method will call glX/wglMakeCurrent(None),
                // so we should nullify the current mtlc and dstOps to avoid
                // calling glFlush() (or similar) while no context is current
                mtlc = NULL;
             //   dstOps = NULL;
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_INVALIDATE_CONTEXT:
            {
                //TODO
                J2dTracePrimitive("MTLRenderQueue_INVALIDATE_CONTEXT");
                // flush just in case there are any pending operations in
                // the hardware pipe
                if (mtlc != NULL) {
                    RESET_PREVIOUS_OP();
                }
                // invalidate the references to the current context and
                // destination surface that are maintained at the native level
                mtlc = NULL;
            //    dstOps = NULL;
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_SAVE_STATE:
            {
                //TODO
                J2dTracePrimitive("MTLRenderQueue_INVALIDATE_CONTEXT");
            }
            break;

        case sun_java2d_pipe_BufferedOpCodes_RESTORE_STATE:
            {
                //TODO
                J2dTracePrimitive("MTLRenderQueue_RESTORE_STATE");

            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_SYNC:
            {
                sync = JNI_TRUE;
            }
            break;

        // multibuffering ops
        case sun_java2d_pipe_BufferedOpCodes_SWAP_BUFFERS:
            {
                jlong window = NEXT_LONG(b);
                if (mtlc != NULL) {
                    RESET_PREVIOUS_OP();
                }
                MTLSD_SwapBuffers(env, window);
            }
            break;

        // special no-op (mainly used for achieving 8-byte alignment)
        case sun_java2d_pipe_BufferedOpCodes_NOOP:
            break;

        // paint-related ops
        case sun_java2d_pipe_BufferedOpCodes_RESET_PAINT:
            {
                MTLPaints_ResetPaint(mtlc);
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_SET_COLOR:
            {
                jint pixel = NEXT_INT(b);

                if (dstOps != NULL) {
                    MTLSDOps *dstCGLOps = (MTLSDOps *)dstOps->privOps;
                    [JNFRunLoop performOnMainThreadWaiting:NO withBlock:^(){
                        dstCGLOps->configInfo->context->mtlColor = pixel;
                    }];
                }
                MTLPaints_SetColor(mtlc, pixel);
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_SET_GRADIENT_PAINT:
            {
                jboolean useMask= NEXT_BOOLEAN(b);
                jboolean cyclic = NEXT_BOOLEAN(b);
                jdouble p0      = NEXT_DOUBLE(b);
                jdouble p1      = NEXT_DOUBLE(b);
                jdouble p3      = NEXT_DOUBLE(b);
                jint pixel1     = NEXT_INT(b);
                jint pixel2     = NEXT_INT(b);
                MTLPaints_SetGradientPaint(mtlc, useMask, cyclic,
                                           p0, p1, p3,
                                           pixel1, pixel2);
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_SET_LINEAR_GRADIENT_PAINT:
            {
                jboolean useMask = NEXT_BOOLEAN(b);
                jboolean linear  = NEXT_BOOLEAN(b);
                jint cycleMethod = NEXT_INT(b);
                jint numStops    = NEXT_INT(b);
                jfloat p0        = NEXT_FLOAT(b);
                jfloat p1        = NEXT_FLOAT(b);
                jfloat p3        = NEXT_FLOAT(b);
                void *fractions, *pixels;
                fractions = b; SKIP_BYTES(b, numStops * sizeof(jfloat));
                pixels    = b; SKIP_BYTES(b, numStops * sizeof(jint));
                MTLPaints_SetLinearGradientPaint(mtlc, dstOps,
                                                 useMask, linear,
                                                 cycleMethod, numStops,
                                                 p0, p1, p3,
                                                 fractions, pixels);
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_SET_RADIAL_GRADIENT_PAINT:
            {
                jboolean useMask = NEXT_BOOLEAN(b);
                jboolean linear  = NEXT_BOOLEAN(b);
                jint numStops    = NEXT_INT(b);
                jint cycleMethod = NEXT_INT(b);
                jfloat m00       = NEXT_FLOAT(b);
                jfloat m01       = NEXT_FLOAT(b);
                jfloat m02       = NEXT_FLOAT(b);
                jfloat m10       = NEXT_FLOAT(b);
                jfloat m11       = NEXT_FLOAT(b);
                jfloat m12       = NEXT_FLOAT(b);
                jfloat focusX    = NEXT_FLOAT(b);
                void *fractions, *pixels;
                fractions = b; SKIP_BYTES(b, numStops * sizeof(jfloat));
                pixels    = b; SKIP_BYTES(b, numStops * sizeof(jint));
                MTLPaints_SetRadialGradientPaint(mtlc, dstOps,
                                                 useMask, linear,
                                                 cycleMethod, numStops,
                                                 m00, m01, m02,
                                                 m10, m11, m12,
                                                 focusX,
                                                 fractions, pixels);
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_SET_TEXTURE_PAINT:
            {
                jboolean useMask= NEXT_BOOLEAN(b);
                jboolean filter = NEXT_BOOLEAN(b);
                jlong pSrc      = NEXT_LONG(b);
                jdouble xp0     = NEXT_DOUBLE(b);
                jdouble xp1     = NEXT_DOUBLE(b);
                jdouble xp3     = NEXT_DOUBLE(b);
                jdouble yp0     = NEXT_DOUBLE(b);
                jdouble yp1     = NEXT_DOUBLE(b);
                jdouble yp3     = NEXT_DOUBLE(b);
                MTLPaints_SetTexturePaint(mtlc, useMask, pSrc, filter,
                                          xp0, xp1, xp3,
                                          yp0, yp1, yp3);
            }
            break;

        // BufferedImageOp-related ops
        case sun_java2d_pipe_BufferedOpCodes_ENABLE_CONVOLVE_OP:
            {
                jlong pSrc        = NEXT_LONG(b);
                jboolean edgeZero = NEXT_BOOLEAN(b);
                jint kernelWidth  = NEXT_INT(b);
                jint kernelHeight = NEXT_INT(b);
                MTLBufImgOps_EnableConvolveOp(mtlc, pSrc, edgeZero,
                                              kernelWidth, kernelHeight, b);
                SKIP_BYTES(b, kernelWidth * kernelHeight * sizeof(jfloat));
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_DISABLE_CONVOLVE_OP:
            {
                MTLBufImgOps_DisableConvolveOp(mtlc);
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_ENABLE_RESCALE_OP:
            {
                jlong pSrc          = NEXT_LONG(b);
                jboolean nonPremult = NEXT_BOOLEAN(b);
                jint numFactors     = 4;
                unsigned char *scaleFactors = b;
                unsigned char *offsets = (b + numFactors * sizeof(jfloat));
                MTLBufImgOps_EnableRescaleOp(mtlc, pSrc, nonPremult,
                                             scaleFactors, offsets);
                SKIP_BYTES(b, numFactors * sizeof(jfloat) * 2);
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_DISABLE_RESCALE_OP:
            {
                MTLBufImgOps_DisableRescaleOp(mtlc);
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_ENABLE_LOOKUP_OP:
            {
                jlong pSrc          = NEXT_LONG(b);
                jboolean nonPremult = NEXT_BOOLEAN(b);
                jboolean shortData  = NEXT_BOOLEAN(b);
                jint numBands       = NEXT_INT(b);
                jint bandLength     = NEXT_INT(b);
                jint offset         = NEXT_INT(b);
                jint bytesPerElem = shortData ? sizeof(jshort):sizeof(jbyte);
                void *tableValues = b;
                MTLBufImgOps_EnableLookupOp(mtlc, pSrc, nonPremult, shortData,
                                            numBands, bandLength, offset,
                                            tableValues);
                SKIP_BYTES(b, numBands * bandLength * bytesPerElem);
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_DISABLE_LOOKUP_OP:
            {
                MTLBufImgOps_DisableLookupOp(mtlc);
            }
            break;

        default:
            J2dRlsTraceLn1(J2D_TRACE_ERROR,
                "MTLRenderQueue_flushBuffer: invalid opcode=%d", opcode);
            if (mtlc != NULL) {
                RESET_PREVIOUS_OP();
            }
            return;
        }
    }

    MTLTR_DisableGlyphModeState();
    if (mtlc != NULL) {
        RESET_PREVIOUS_OP();
        //if (sync) {
        //} else {
        //}
        MTLSD_Flush(env);
    }
}

/**
 * Returns a pointer to the "current" context, as set by the last SET_SURFACES
 * or SET_SCRATCH_SURFACE operation.
 */
MTLContext *
MTLRenderQueue_GetCurrentContext()
{
    return mtlc;
}

/**
 * Returns a pointer to the "current" destination surface, as set by the last
 * SET_SURFACES operation.
 */
BMTLSDOps *
MTLRenderQueue_GetCurrentDestination()
{
    return dstOps;
}

/**
 * Used to track whether we are within a series of simple primitive operations
 * or texturing operations.  The op parameter determines the nature of the
 * operation that is to follow.  Valid values for this op parameter are:
 */
void
MTLRenderQueue_CheckPreviousOp(jint op)
{
    //TODO
    J2dTracePrimitive("MTLRenderQueue_CheckPreviousOp");
    previousOp = op;
}

#endif /* !HEADLESS */
