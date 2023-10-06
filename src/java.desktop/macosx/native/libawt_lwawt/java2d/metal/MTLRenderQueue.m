/*
 * Copyright (c) 2019, 2022, Oracle and/or its affiliates. All rights reserved.
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

#include <stdlib.h>
#import <sys/time.h>

#include "sun_java2d_pipe_BufferedOpCodes.h"

#include "jlong.h"
#include <jni.h>
#include "MTLBlitLoops.h"
#include "MTLBufImgOps.h"
#include "MTLMaskBlit.h"
#include "MTLMaskFill.h"
#include "MTLPaints.h"
#include "MTLRenderQueue.h"
#include "MTLRenderer.h"
#include "MTLTextRenderer.h"
#import "PropertiesUtilities.h"
#import "ThreadUtilities.h"

/* PlatformLogger wrapper */
enum LOG_LEVEL {
    LL_TRACE,
    LL_DEBUG,
    LL_INFO,
    LL_WARNING,
    LL_ERROR
};
void rq_plog(JNIEnv* env, int logLevel, const char *formatMsg, ...);

#define DO_STATS        true
#define STATS_INTERVAL  1000

/* Debugging */
#define DO_TRACE_OP     false

const static uint32 STATS_LEN = sun_java2d_pipe_BufferedOpCodes_DISABLE_LOOKUP_OP + 1;

static const char* toStr(uint opcode);
static const char* mtlOpToStr(uint op);
static long rqCurrentTimeMillis();

BOOL isStatsEnabled() {
    static int statsEnabled = -1;
    if (statsEnabled == -1) {
        JNIEnv *env = [ThreadUtilities getJNIEnvUncached];
        if (env == NULL) return NO;
        NSString *statsEnabledProp = [PropertiesUtilities javaSystemPropertyForKey:@"sun.java2d.rq.stats"
                                                                          withEnv:env];
        statsEnabled = [@"true" isCaseInsensitiveLike:statsEnabledProp] ? YES : NO;
        J2dRlsTraceLn1(J2D_TRACE_INFO, "MTLRenderQueue_isStatsEnabled: %d", statsEnabled);
    }
    return (BOOL)statsEnabled;
}

/**
 * References to the "current" context and destination surface.
 */
static MTLContext *mtlc = NULL;
static BMTLSDOps *dstOps = NULL;
jint mtlPreviousOp = MTL_OP_INIT;

extern BOOL isDisplaySyncEnabled();
extern void MTLGC_DestroyMTLGraphicsConfig(jlong pConfigInfo);

void MTLRenderQueue_CheckPreviousOp(jint op) {

    if (mtlPreviousOp == op) {
        // The op is the same as last time, so we can return immediately.
        return;
    }

    if (op == MTL_OP_SET_COLOR) {
        // MTL_OP_MASK_OP: switching from advanced paints to SET_COLOR must cause endEncoder:
        if ((mtlPreviousOp != MTL_OP_MASK_OP) || (mtlc == NULL) || [mtlc useMaskColor]) {
            return;
        }
    } else if (op == MTL_OP_MASK_OP) {
        MTLVertexCache_EnableMaskCache(mtlc, dstOps);
        mtlPreviousOp = op;
        return;
    }

    if (DO_TRACE_OP) {
        J2dRlsTraceLn2(J2D_TRACE_INFO, "MTLRenderQueue_CheckPreviousOp: op=%s\tnew op=%s",
                    mtlOpToStr(mtlPreviousOp), mtlOpToStr(op));
    } else {
        J2dTraceLn1(J2D_TRACE_VERBOSE, "MTLRenderQueue_CheckPreviousOp: new op=%d", op);
    }

    switch (mtlPreviousOp) {
        case MTL_OP_INIT :
            mtlPreviousOp = op;
            return;
        case MTL_OP_MASK_OP :
            MTLVertexCache_DisableMaskCache(mtlc);
            break;
        default:
            break;
    }

    if (mtlc != NULL) {
        [mtlc.encoderManager endEncoder];

        if (op == MTL_OP_RESET_PAINT || op == MTL_OP_SYNC || op == MTL_OP_SHAPE_CLIP_SPANS ||
            mtlPreviousOp == MTL_OP_MASK_OP)
        {
            [mtlc commitCommandBuffer:(op == MTL_OP_SYNC || op == MTL_OP_SHAPE_CLIP_SPANS)
                              display:NO];
        }
    }
    mtlPreviousOp = op;
}

JNIEXPORT void JNICALL
Java_sun_java2d_metal_MTLRenderQueue_flushBuffer
    (JNIEnv *env, jobject mtlrq,
     jlong buf, jint limit)
{
    unsigned char *b, *end;
    BOOL sync = NO;
    J2dTraceLn1(J2D_TRACE_INFO,
                "MTLRenderQueue_flushBuffer: limit=%d", limit);

    b = (unsigned char *)jlong_to_ptr(buf);
    if (b == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR,
            "MTLRenderQueue_flushBuffer: cannot get direct buffer address");
        return;
    }

    end = b + limit;
    @autoreleasepool {
        const BOOL isStats = isStatsEnabled();
        int statsLevel = -1;
        static jint* statsOpsPtr = NULL;

        if (DO_STATS && isStats) {
            statsLevel = LL_INFO;

            if (statsOpsPtr == NULL) {
                statsOpsPtr = malloc(sizeof(jint) * STATS_LEN); // leak, no-free
                for (uint32 i = 0; i < STATS_LEN; i++) {
                    statsOpsPtr[i] = 0;
                }
            }
            rq_plog(env, LL_DEBUG, "Java_sun_java2d_metal_MTLRenderQueue_flushBuffer(%d bytes): start", limit);
        }

        while (b < end) {
            jint opcode = NEXT_INT(b);

            J2dTraceLn2(J2D_TRACE_VERBOSE,
                    "MTLRenderQueue_flushBuffer: opcode=%d, rem=%d",
                    opcode, (end-b));

            switch (opcode) {

                // draw ops
                case sun_java2d_pipe_BufferedOpCodes_DRAW_LINE:
                {
                    CHECK_RENDER_OP(MTL_OP_OTHER, dstOps, sync);

                    if ([mtlc useXORComposite]) {
                        [mtlc commitCommandBuffer:YES display:NO];
                        J2dTraceLn(J2D_TRACE_VERBOSE,
                                   "DRAW_LINE in XOR mode - Force commit earlier draw calls before DRAW_LINE.");
                    }
                    jint x1 = NEXT_INT(b);
                    jint y1 = NEXT_INT(b);
                    jint x2 = NEXT_INT(b);
                    jint y2 = NEXT_INT(b);
                    MTLRenderer_DrawLine(mtlc, dstOps, x1, y1, x2, y2);
                    break;
                }
                case sun_java2d_pipe_BufferedOpCodes_DRAW_RECT:
                {
                    CHECK_RENDER_OP(MTL_OP_OTHER, dstOps, sync);

                    if ([mtlc useXORComposite]) {

                        [mtlc commitCommandBuffer:YES display:NO];
                        J2dTraceLn(J2D_TRACE_VERBOSE,
                                   "DRAW_RECT in XOR mode - Force commit earlier draw calls before DRAW_RECT.");
                    }
                    jint x = NEXT_INT(b);
                    jint y = NEXT_INT(b);
                    jint w = NEXT_INT(b);
                    jint h = NEXT_INT(b);
                    MTLRenderer_DrawRect(mtlc, dstOps, x, y, w, h);
                    break;
                }
                case sun_java2d_pipe_BufferedOpCodes_DRAW_POLY:
                {
                    CHECK_RENDER_OP(MTL_OP_OTHER, dstOps, sync);

                    jint nPoints      = NEXT_INT(b);
                    jboolean isClosed = NEXT_BOOLEAN(b);
                    jint transX       = NEXT_INT(b);
                    jint transY       = NEXT_INT(b);
                    jint *xPoints = (jint *)b;
                    jint *yPoints = ((jint *)b) + nPoints;

                    if ([mtlc useXORComposite]) {
                        [mtlc commitCommandBuffer:YES display:NO];
                        J2dTraceLn(J2D_TRACE_VERBOSE,
                                   "DRAW_POLY in XOR mode - Force commit earlier draw calls before DRAW_POLY.");

                        // draw separate (N-1) lines using N points
                        for(int point = 0; point < nPoints-1; point++) {
                            jint x1 = xPoints[point] + transX;
                            jint y1 = yPoints[point] + transY;
                            jint x2 = xPoints[point + 1] + transX;
                            jint y2 = yPoints[point + 1] + transY;
                            MTLRenderer_DrawLine(mtlc, dstOps, x1, y1, x2, y2);
                        }

                        if (isClosed) {
                            MTLRenderer_DrawLine(mtlc, dstOps, xPoints[0] + transX, yPoints[0] + transY,
                                                 xPoints[nPoints-1] + transX, yPoints[nPoints-1] + transY);
                        }
                    } else {
                        MTLRenderer_DrawPoly(mtlc, dstOps, nPoints, isClosed, transX, transY, xPoints, yPoints);
                    }

                    SKIP_BYTES(b, nPoints * BYTES_PER_POLY_POINT);
                    break;
                }
                case sun_java2d_pipe_BufferedOpCodes_DRAW_PIXEL:
                {
                    CHECK_RENDER_OP(MTL_OP_OTHER, dstOps, sync);

                    if ([mtlc useXORComposite]) {
                        [mtlc commitCommandBuffer:YES display:NO];
                        J2dTraceLn(J2D_TRACE_VERBOSE,
                                   "DRAW_PIXEL in XOR mode - Force commit earlier draw calls before DRAW_PIXEL.");
                    }

                    jint x = NEXT_INT(b);
                    jint y = NEXT_INT(b);
                    CONTINUE_IF_NULL(mtlc);
                    MTLRenderer_DrawPixel(mtlc, dstOps, x, y);
                    break;
                }
                case sun_java2d_pipe_BufferedOpCodes_DRAW_SCANLINES:
                {
                    CHECK_RENDER_OP(MTL_OP_OTHER, dstOps, sync);

                    if ([mtlc useXORComposite]) {
                        [mtlc commitCommandBuffer:YES display:NO];
                        J2dTraceLn(J2D_TRACE_VERBOSE,
                                   "DRAW_SCANLINES in XOR mode - Force commit earlier draw calls before "
                                   "DRAW_SCANLINES.");
                    }

                    jint count = NEXT_INT(b);
                    MTLRenderer_DrawScanlines(mtlc, dstOps, count, (jint *)b);

                    SKIP_BYTES(b, count * BYTES_PER_SCANLINE);
                    break;
                }
                case sun_java2d_pipe_BufferedOpCodes_DRAW_PARALLELOGRAM:
                {
                    CHECK_RENDER_OP(MTL_OP_OTHER, dstOps, sync);

                    if ([mtlc useXORComposite]) {
                        [mtlc commitCommandBuffer:YES display:NO];
                        J2dTraceLn(J2D_TRACE_VERBOSE,
                                   "DRAW_PARALLELOGRAM in XOR mode - Force commit earlier draw calls before "
                                   "DRAW_PARALLELOGRAM.");
                    }

                    jfloat x11 = NEXT_FLOAT(b);
                    jfloat y11 = NEXT_FLOAT(b);
                    jfloat dx21 = NEXT_FLOAT(b);
                    jfloat dy21 = NEXT_FLOAT(b);
                    jfloat dx12 = NEXT_FLOAT(b);
                    jfloat dy12 = NEXT_FLOAT(b);
                    jfloat lwr21 = NEXT_FLOAT(b);
                    jfloat lwr12 = NEXT_FLOAT(b);

                    MTLRenderer_DrawParallelogram(mtlc, dstOps,
                                                  x11, y11,
                                                  dx21, dy21,
                                                  dx12, dy12,
                                                  lwr21, lwr12);
                    break;
                }
                case sun_java2d_pipe_BufferedOpCodes_DRAW_AAPARALLELOGRAM:
                {
                    CHECK_RENDER_OP(MTL_OP_OTHER, dstOps, sync);

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
                    break;
                }

                // fill ops
                case sun_java2d_pipe_BufferedOpCodes_FILL_RECT:
                {
                    CHECK_RENDER_OP(MTL_OP_OTHER, dstOps, sync);

                    if ([mtlc useXORComposite]) {
                        [mtlc commitCommandBuffer:YES display:NO];
                        J2dTraceLn(J2D_TRACE_VERBOSE,
                                   "FILL_RECT in XOR mode - Force commit earlier draw calls before FILL_RECT.");
                    }

                    jint x = NEXT_INT(b);
                    jint y = NEXT_INT(b);
                    jint w = NEXT_INT(b);
                    jint h = NEXT_INT(b);
                    MTLRenderer_FillRect(mtlc, dstOps, x, y, w, h);
                    break;
                }
                case sun_java2d_pipe_BufferedOpCodes_FILL_SPANS:
                {
                    CHECK_RENDER_OP(MTL_OP_OTHER, dstOps, sync);

                    if ([mtlc useXORComposite]) {
                        [mtlc commitCommandBuffer:YES display:NO];
                        J2dTraceLn(J2D_TRACE_VERBOSE,
                                   "FILL_SPANS in XOR mode - Force commit earlier draw calls before FILL_SPANS.");
                    }

                    jint count = NEXT_INT(b);
                    MTLRenderer_FillSpans(mtlc, dstOps, count, (jint *)b);
                    SKIP_BYTES(b, count * BYTES_PER_SPAN);
                    break;
                }
                case sun_java2d_pipe_BufferedOpCodes_FILL_PARALLELOGRAM:
                {
                    CHECK_RENDER_OP(MTL_OP_OTHER, dstOps, sync);

                    if ([mtlc useXORComposite]) {
                        [mtlc commitCommandBuffer:YES display:NO];
                        J2dTraceLn(J2D_TRACE_VERBOSE,
                                   "FILL_PARALLELOGRAM in XOR mode - Force commit earlier draw calls before "
                                   "FILL_PARALLELOGRAM.");
                    }

                    jfloat x11 = NEXT_FLOAT(b);
                    jfloat y11 = NEXT_FLOAT(b);
                    jfloat dx21 = NEXT_FLOAT(b);
                    jfloat dy21 = NEXT_FLOAT(b);
                    jfloat dx12 = NEXT_FLOAT(b);
                    jfloat dy12 = NEXT_FLOAT(b);
                    MTLRenderer_FillParallelogram(mtlc, dstOps,
                                                  x11, y11,
                                                  dx21, dy21,
                                                  dx12, dy12);
                    break;
                }
                case sun_java2d_pipe_BufferedOpCodes_FILL_AAPARALLELOGRAM:
                {
                    CHECK_RENDER_OP(MTL_OP_OTHER, dstOps, sync);
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
                    break;
                }

                // text-related ops
                case sun_java2d_pipe_BufferedOpCodes_DRAW_GLYPH_LIST:
                {
                    CHECK_RENDER_OP(MTL_OP_OTHER, dstOps, sync);

                    if ([mtlc useXORComposite]) {
                        [mtlc commitCommandBuffer:YES display:NO];
                        J2dTraceLn(J2D_TRACE_VERBOSE,
                                   "DRAW_GLYPH_LIST in XOR mode - Force commit earlier draw calls before "
                                   "DRAW_GLYPH_LIST.");
                    }

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
                    break;
                }

                // copy-related ops
                case sun_java2d_pipe_BufferedOpCodes_COPY_AREA:
                {
                    CHECK_RENDER_OP(MTL_OP_OTHER, dstOps, sync);
                    jint x  = NEXT_INT(b);
                    jint y  = NEXT_INT(b);
                    jint w  = NEXT_INT(b);
                    jint h  = NEXT_INT(b);
                    jint dx = NEXT_INT(b);
                    jint dy = NEXT_INT(b);
                    MTLBlitLoops_CopyArea(env, mtlc, dstOps,
                                          x, y, w, h, dx, dy);
                    break;
                }
                case sun_java2d_pipe_BufferedOpCodes_BLIT:
                {
                    CHECK_PREVIOUS_OP(MTL_OP_OTHER);
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
                    jboolean xform    = EXTRACT_BOOLEAN(packedParams,
                                                        OFFSET_XFORM);
                    jboolean isoblit  = EXTRACT_BOOLEAN(packedParams,
                                                        OFFSET_ISOBLIT);

                    BMTLSDOps *dst = (BMTLSDOps *)jlong_to_ptr(pDst);
                    CHECK_RENDER_DEST(dst, sync);

                    if (isoblit) {
                        MTLBlitLoops_IsoBlit(env, mtlc, pSrc, pDst,
                                             xform, hint, texture,
                                             sx1, sy1, sx2, sy2,
                                             dx1, dy1, dx2, dy2);
                    } else {
                        jint srctype = EXTRACT_BYTE(packedParams, OFFSET_SRCTYPE);
                        MTLBlitLoops_Blit(env, mtlc, pSrc, pDst,
                                          xform, hint, srctype, texture,
                                          sx1, sy1, sx2, sy2,
                                          dx1, dy1, dx2, dy2);
                    }
                    break;
                }
                case sun_java2d_pipe_BufferedOpCodes_SURFACE_TO_SW_BLIT:
                {
                    CHECK_PREVIOUS_OP(MTL_OP_OTHER);
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
                    break;
                }
                case sun_java2d_pipe_BufferedOpCodes_MASK_FILL:
                {
                    jint x        = NEXT_INT(b);
                    jint y        = NEXT_INT(b);
                    jint w        = NEXT_INT(b);
                    jint h        = NEXT_INT(b);
                    jint maskoff  = NEXT_INT(b);
                    jint maskscan = NEXT_INT(b);
                    jint masklen  = NEXT_INT(b);
                    unsigned char *pMask = (masklen > 0) ? b : NULL;
                    if (mtlc == nil)
                        return;
                    CHECK_RENDER_OP(MTL_OP_MASK_OP, dstOps, sync);
                    MTLMaskFill_MaskFill(mtlc, dstOps, x, y, w, h,
                                         maskoff, maskscan, masklen, pMask);
                    SKIP_BYTES(b, masklen);
                    break;
                }
                case sun_java2d_pipe_BufferedOpCodes_MASK_BLIT:
                {
                    CHECK_RENDER_OP(MTL_OP_OTHER, dstOps, sync);
                    jint dstx     = NEXT_INT(b);
                    jint dsty     = NEXT_INT(b);
                    jint width    = NEXT_INT(b);
                    jint height   = NEXT_INT(b);
                    jint masklen  = width * height * sizeof(jint);
                    MTLMaskBlit_MaskBlit(env, mtlc, dstOps,
                                         dstx, dsty, width, height, b);
                    SKIP_BYTES(b, masklen);
                    break;
                }

                // state-related ops
                case sun_java2d_pipe_BufferedOpCodes_SET_RECT_CLIP:
                {
                    CHECK_PREVIOUS_OP(MTL_OP_OTHER);
                    jint x1 = NEXT_INT(b);
                    jint y1 = NEXT_INT(b);
                    jint x2 = NEXT_INT(b);
                    jint y2 = NEXT_INT(b);
                    [mtlc setClipRectX1:x1 Y1:y1 X2:x2 Y2:y2];
                    break;
                }
                case sun_java2d_pipe_BufferedOpCodes_BEGIN_SHAPE_CLIP:
                {
                    CHECK_PREVIOUS_OP(MTL_OP_OTHER);
                    [mtlc beginShapeClip:dstOps];
                    break;
                }
                case sun_java2d_pipe_BufferedOpCodes_SET_SHAPE_CLIP_SPANS:
                {
                    CHECK_PREVIOUS_OP(MTL_OP_SHAPE_CLIP_SPANS);
                    // This results in creation of new render encoder with
                    // stencil buffer set as render target
                    jint count = NEXT_INT(b);
                    MTLRenderer_FillSpans(mtlc, dstOps, count, (jint *)b);
                    SKIP_BYTES(b, count * BYTES_PER_SPAN);
                    break;
                }
                case sun_java2d_pipe_BufferedOpCodes_END_SHAPE_CLIP:
                {
                    CHECK_PREVIOUS_OP(MTL_OP_OTHER);
                    [mtlc endShapeClip:dstOps];
                    break;
                }
                case sun_java2d_pipe_BufferedOpCodes_RESET_CLIP:
                {
                    CHECK_PREVIOUS_OP(MTL_OP_OTHER);
                    [mtlc resetClip];
                    break;
                }
                case sun_java2d_pipe_BufferedOpCodes_SET_ALPHA_COMPOSITE:
                {
                    CHECK_PREVIOUS_OP(MTL_OP_OTHER);
                    jint rule         = NEXT_INT(b);
                    jfloat extraAlpha = NEXT_FLOAT(b);
                    jint flags        = NEXT_INT(b);
                    [mtlc setAlphaCompositeRule:rule extraAlpha:extraAlpha flags:flags];
                    break;
                }
                case sun_java2d_pipe_BufferedOpCodes_SET_XOR_COMPOSITE:
                {
                    CHECK_PREVIOUS_OP(MTL_OP_OTHER);
                    jint xorPixel = NEXT_INT(b);
                    [mtlc setXorComposite:xorPixel];
                    break;
                }
                case sun_java2d_pipe_BufferedOpCodes_RESET_COMPOSITE:
                {
                    /* TODO: check whether something needs to be done here if we are moving out of XOR composite
                    commitEncodedCommands();
                    MTLCommandBufferWrapper * cbwrapper = [mtlc pullCommandBufferWrapper];
                    [cbwrapper onComplete];

                    J2dTraceLn(J2D_TRACE_VERBOSE,
                     "RESET_COMPOSITE - Force commit earlier draw calls before RESET_COMPOSITE.");*/

                    CHECK_PREVIOUS_OP(MTL_OP_OTHER);
                    [mtlc resetComposite];
                    break;
                }
                case sun_java2d_pipe_BufferedOpCodes_SET_TRANSFORM:
                {
                    CHECK_PREVIOUS_OP(MTL_OP_OTHER);
                    jdouble m00 = NEXT_DOUBLE(b);
                    jdouble m10 = NEXT_DOUBLE(b);
                    jdouble m01 = NEXT_DOUBLE(b);
                    jdouble m11 = NEXT_DOUBLE(b);
                    jdouble m02 = NEXT_DOUBLE(b);
                    jdouble m12 = NEXT_DOUBLE(b);
                    [mtlc setTransformM00:m00 M10:m10 M01:m01 M11:m11 M02:m02 M12:m12];
                    break;
                }
                case sun_java2d_pipe_BufferedOpCodes_RESET_TRANSFORM:
                {
                    CHECK_PREVIOUS_OP(MTL_OP_OTHER);
                    [mtlc resetTransform];
                    break;
                }

                // context-related ops
                case sun_java2d_pipe_BufferedOpCodes_SET_SURFACES:
                {
                    CHECK_PREVIOUS_OP(MTL_OP_OTHER);
                    jlong pSrc = NEXT_LONG(b);
                    jlong pDst = NEXT_LONG(b);

                    if (mtlc != NULL) {
                        [mtlc.glyphCacheAA free];
                        [mtlc.glyphCacheLCD free];
                        if (isDisplaySyncEnabled() && IS_OUTPUT_DEST(dstOps)) {
                            [mtlc commitCommandBuffer:YES display:YES];
                            sync = NO;
                        } else {
                            [mtlc commitCommandBuffer:NO display:NO];
                        }
                    }
                    mtlc = [MTLContext setSurfacesEnv:env src:pSrc dst:pDst];
                    dstOps = (BMTLSDOps *)jlong_to_ptr(pDst);
                    break;
                }
                case sun_java2d_pipe_BufferedOpCodes_SET_SCRATCH_SURFACE:
                {
                    CHECK_PREVIOUS_OP(MTL_OP_OTHER);
                    jlong pConfigInfo = NEXT_LONG(b);
                    MTLGraphicsConfigInfo *mtlInfo =
                            (MTLGraphicsConfigInfo *)jlong_to_ptr(pConfigInfo);

                    if (mtlc != NULL) {
                        [mtlc.glyphCacheAA free];
                        [mtlc.glyphCacheLCD free];
                        [mtlc commitCommandBuffer:NO display:NO];
                    }

                    if (mtlInfo != NULL) {
                        mtlc = mtlInfo->context;
                    } else {
                        mtlc = NULL;
                    }
                    dstOps = NULL;
                    break;
                }
                case sun_java2d_pipe_BufferedOpCodes_FLUSH_SURFACE:
                {
                    CHECK_PREVIOUS_OP(MTL_OP_OTHER);
                    jlong pData = NEXT_LONG(b);
                    BMTLSDOps *mtlsdo = (BMTLSDOps *)jlong_to_ptr(pData);
                    if (mtlsdo != NULL) {
                        CONTINUE_IF_NULL(mtlc);
                        [mtlc.glyphCacheAA free];
                        [mtlc.glyphCacheLCD free];
                        MTLSD_Delete(env, mtlsdo);
                    }
                    break;
                }
                case sun_java2d_pipe_BufferedOpCodes_DISPOSE_SURFACE:
                {
                    CHECK_PREVIOUS_OP(MTL_OP_OTHER);
                    jlong pData = NEXT_LONG(b);
                    BMTLSDOps *mtlsdo = (BMTLSDOps *)jlong_to_ptr(pData);
                    if (mtlsdo != NULL) {
                        CONTINUE_IF_NULL(mtlc);
                        MTLSD_Delete(env, mtlsdo);
                        if (mtlsdo->privOps != NULL) {
                            free(mtlsdo->privOps);
                        }
                    }
                    break;
                }
                case sun_java2d_pipe_BufferedOpCodes_DISPOSE_CONFIG:
                {
                    CHECK_PREVIOUS_OP(MTL_OP_OTHER);
                    jlong pConfigInfo = NEXT_LONG(b);
                    CONTINUE_IF_NULL(mtlc);
                    [mtlc.glyphCacheAA free];
                    [mtlc.glyphCacheLCD free];
                    [mtlc.encoderManager endEncoder];
                    MTLGC_DestroyMTLGraphicsConfig(pConfigInfo);
                    mtlc = NULL;
                    break;
                }

                case sun_java2d_pipe_BufferedOpCodes_SYNC:
                {
                    CHECK_PREVIOUS_OP(MTL_OP_SYNC);
                    sync = YES;
                    break;
                }

                // special no-op (mainly used for achieving 8-byte alignment)
                case sun_java2d_pipe_BufferedOpCodes_NOOP:
                    break;

                // paint-related ops
                case sun_java2d_pipe_BufferedOpCodes_RESET_PAINT:
                {
                  CHECK_PREVIOUS_OP(MTL_OP_RESET_PAINT);
                  [mtlc resetPaint];
                    break;
                }
                case sun_java2d_pipe_BufferedOpCodes_SET_COLOR:
                {
                    CHECK_PREVIOUS_OP(MTL_OP_SET_COLOR);
                    jint pixel = NEXT_INT(b);
                    [mtlc setColorPaint:pixel];
                    break;
                }
                case sun_java2d_pipe_BufferedOpCodes_SET_GRADIENT_PAINT:
                {
                    CHECK_PREVIOUS_OP(MTL_OP_OTHER);
                    jboolean useMask= NEXT_BOOLEAN(b);
                    jboolean cyclic = NEXT_BOOLEAN(b);
                    jdouble p0      = NEXT_DOUBLE(b);
                    jdouble p1      = NEXT_DOUBLE(b);
                    jdouble p3      = NEXT_DOUBLE(b);
                    jint pixel1     = NEXT_INT(b);
                    jint pixel2     = NEXT_INT(b);
                    [mtlc setGradientPaintUseMask:useMask
                                        cyclic:cyclic
                                            p0:p0
                                            p1:p1
                                            p3:p3
                                        pixel1:pixel1
                                        pixel2:pixel2];
                    break;
                }
                case sun_java2d_pipe_BufferedOpCodes_SET_LINEAR_GRADIENT_PAINT:
                {
                    CHECK_PREVIOUS_OP(MTL_OP_OTHER);
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
                    [mtlc setLinearGradientPaint:useMask
                                          linear:linear
                                     cycleMethod:cycleMethod
                                        numStops:numStops
                                              p0:p0
                                              p1:p1
                                              p3:p3
                                       fractions:fractions
                                          pixels:pixels];
                    break;
                }
                case sun_java2d_pipe_BufferedOpCodes_SET_RADIAL_GRADIENT_PAINT:
                {
                    CHECK_PREVIOUS_OP(MTL_OP_OTHER);
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
                    [mtlc setRadialGradientPaint:useMask
                                          linear:linear
                                     cycleMethod:cycleMethod
                                        numStops:numStops
                                             m00:m00
                                             m01:m01
                                             m02:m02
                                             m10:m10
                                             m11:m11
                                             m12:m12
                                          focusX:focusX
                                       fractions:fractions
                                          pixels:pixels];
                    break;
                }
                case sun_java2d_pipe_BufferedOpCodes_SET_TEXTURE_PAINT:
                {
                    CHECK_PREVIOUS_OP(MTL_OP_OTHER);
                    jboolean useMask= NEXT_BOOLEAN(b);
                    jboolean filter = NEXT_BOOLEAN(b);
                    jlong pSrc      = NEXT_LONG(b);
                    jdouble xp0     = NEXT_DOUBLE(b);
                    jdouble xp1     = NEXT_DOUBLE(b);
                    jdouble xp3     = NEXT_DOUBLE(b);
                    jdouble yp0     = NEXT_DOUBLE(b);
                    jdouble yp1     = NEXT_DOUBLE(b);
                    jdouble yp3     = NEXT_DOUBLE(b);
                    [mtlc setTexturePaint:useMask
                                  pSrcOps:pSrc
                                   filter:filter
                                      xp0:xp0
                                      xp1:xp1
                                      xp3:xp3
                                      yp0:yp0
                                      yp1:yp1
                                      yp3:yp3];
                    break;
                }

                // BufferedImageOp-related ops
                case sun_java2d_pipe_BufferedOpCodes_ENABLE_CONVOLVE_OP:
                {
                    CHECK_PREVIOUS_OP(MTL_OP_OTHER);
                    jlong pSrc        = NEXT_LONG(b);
                    jboolean edgeZero = NEXT_BOOLEAN(b);
                    jint kernelWidth  = NEXT_INT(b);
                    jint kernelHeight = NEXT_INT(b);

                    BMTLSDOps * bmtlsdOps = (BMTLSDOps *)pSrc;
                    MTLConvolveOp * convolveOp = [[MTLConvolveOp alloc] init:edgeZero
                            kernelWidth:kernelWidth
                           kernelHeight:kernelHeight
                               srcWidth:bmtlsdOps->width
                              srcHeight:bmtlsdOps->height
                                 kernel:b
                                 device:mtlc.device
                                                  ];
                    [mtlc setBufImgOp:convolveOp];
                    SKIP_BYTES(b, kernelWidth * kernelHeight * sizeof(jfloat));
                    break;
                }
                case sun_java2d_pipe_BufferedOpCodes_DISABLE_CONVOLVE_OP:
                {
                    CHECK_PREVIOUS_OP(MTL_OP_OTHER);
                    [mtlc setBufImgOp:NULL];
                    break;
                }
                case sun_java2d_pipe_BufferedOpCodes_ENABLE_RESCALE_OP:
                {
                    CHECK_PREVIOUS_OP(MTL_OP_OTHER);
                    jlong pSrc          = NEXT_LONG(b);
                    jboolean nonPremult = NEXT_BOOLEAN(b);
                    jint numFactors     = 4;
                    unsigned char *scaleFactors = b;
                    unsigned char *offsets = (b + numFactors * sizeof(jfloat));
                    MTLRescaleOp * rescaleOp =
                            [[MTLRescaleOp alloc] init:nonPremult factors:scaleFactors offsets:offsets];
                    [mtlc setBufImgOp:rescaleOp];
                    SKIP_BYTES(b, numFactors * sizeof(jfloat) * 2);
                    break;
                }
                case sun_java2d_pipe_BufferedOpCodes_DISABLE_RESCALE_OP:
                {
                    CHECK_PREVIOUS_OP(MTL_OP_OTHER);
                    [mtlc setBufImgOp:NULL];
                    break;
                }
                case sun_java2d_pipe_BufferedOpCodes_ENABLE_LOOKUP_OP:
                {
                    CHECK_PREVIOUS_OP(MTL_OP_OTHER);
                    jlong pSrc          = NEXT_LONG(b);
                    jboolean nonPremult = NEXT_BOOLEAN(b);
                    jboolean shortData  = NEXT_BOOLEAN(b);
                    jint numBands       = NEXT_INT(b);
                    jint bandLength     = NEXT_INT(b);
                    jint offset         = NEXT_INT(b);
                    jint bytesPerElem = shortData ? sizeof(jshort):sizeof(jbyte);
                    void *tableValues = b;

                    MTLLookupOp * lookupOp = [[MTLLookupOp alloc] init:nonPremult
                                                             shortData:shortData
                                                              numBands:numBands
                                                            bandLength:bandLength
                                                                offset:offset
                                                           tableValues:tableValues
                                                                device:mtlc.device];
                    [mtlc setBufImgOp:lookupOp];
                    SKIP_BYTES(b, numBands * bandLength * bytesPerElem);
                    break;
                }
                case sun_java2d_pipe_BufferedOpCodes_DISABLE_LOOKUP_OP:
                {
                    CHECK_PREVIOUS_OP(MTL_OP_OTHER);
                    [mtlc setBufImgOp:NULL];
                    break;
                }

                default:
                    J2dRlsTraceLn1(J2D_TRACE_ERROR,
                        "MTLRenderQueue_flushBuffer: invalid opcode=%d", opcode);
                    return;
            }
            if (DO_STATS && isStats) {
                statsOpsPtr[opcode]++;
            }
        } // buffer loop

        if (mtlc != NULL) {
            if (mtlPreviousOp == MTL_OP_MASK_OP) {
                MTLVertexCache_DisableMaskCache(mtlc);
            }

            if (isDisplaySyncEnabled()) {
                [mtlc commitCommandBuffer:NO display:YES];
            } else {
                [mtlc commitCommandBuffer:sync display:YES];
            }
        }
        RESET_PREVIOUS_OP();

        if (DO_STATS && isStats) {
            static long lastTimeMs = -1;
            const long timeMs = rqCurrentTimeMillis();

            if (lastTimeMs == -1) {
                lastTimeMs = timeMs;
            } else if ((timeMs - lastTimeMs) > STATS_INTERVAL) {
                rq_plog(env, statsLevel, "Java_sun_java2d_metal_MTLRenderQueue_flushBuffer stats since (%ld ms) {",
                        (timeMs - lastTimeMs));
                lastTimeMs = timeMs;

                for (uint32 i = 0; i < STATS_LEN; i++) {
                    if (statsOpsPtr[i] != 0) {
                        rq_plog(env, statsLevel, "%30s: %d", toStr(i), statsOpsPtr[i]);
                        // reset:
                        statsOpsPtr[i] = 0;
                    }
                }
                rq_plog(env, statsLevel, "}");
            }
        }
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

#define CASE_MTL_OP(X) \
    case MTL_OP_##X:   \
    return #X;

static const char* mtlOpToStr(uint op) {
    switch (op) {
        CASE_MTL_OP(INIT)
        CASE_MTL_OP(AA)
        CASE_MTL_OP(SET_COLOR)
        CASE_MTL_OP(RESET_PAINT)
        CASE_MTL_OP(SYNC)
        CASE_MTL_OP(SHAPE_CLIP_SPANS)
        CASE_MTL_OP(MASK_OP)
        CASE_MTL_OP(OTHER)
        default:
            return "";
    }
}


#define CASE_BUF_OP(X) \
    case sun_java2d_pipe_BufferedOpCodes_##X:   \
    return #X;

static const char* toStr(uint opcode) {
    switch (opcode) {
        // draw ops
        CASE_BUF_OP(DRAW_LINE)
        CASE_BUF_OP(DRAW_RECT)
        CASE_BUF_OP(DRAW_POLY)
        CASE_BUF_OP(DRAW_PIXEL)
        CASE_BUF_OP(DRAW_SCANLINES)
        CASE_BUF_OP(DRAW_PARALLELOGRAM)
        CASE_BUF_OP(DRAW_AAPARALLELOGRAM)

        // fill ops
        CASE_BUF_OP(FILL_RECT)
        CASE_BUF_OP(FILL_SPANS)
        CASE_BUF_OP(FILL_PARALLELOGRAM)
        CASE_BUF_OP(FILL_AAPARALLELOGRAM)

        // copy-related ops
        CASE_BUF_OP(COPY_AREA)
        CASE_BUF_OP(BLIT)
        CASE_BUF_OP(MASK_FILL)
        CASE_BUF_OP(MASK_BLIT)
        CASE_BUF_OP(SURFACE_TO_SW_BLIT)

        // text-related ops
        CASE_BUF_OP(DRAW_GLYPH_LIST)

        // state-related ops
        CASE_BUF_OP(SET_RECT_CLIP)
        CASE_BUF_OP(BEGIN_SHAPE_CLIP)
        CASE_BUF_OP(SET_SHAPE_CLIP_SPANS)
        CASE_BUF_OP(END_SHAPE_CLIP)
        CASE_BUF_OP(RESET_CLIP)
        CASE_BUF_OP(SET_ALPHA_COMPOSITE)
        CASE_BUF_OP(SET_XOR_COMPOSITE)
        CASE_BUF_OP(RESET_COMPOSITE)
        CASE_BUF_OP(SET_TRANSFORM)
        CASE_BUF_OP(RESET_TRANSFORM)

        // context-related ops
        CASE_BUF_OP(SET_SURFACES)
        CASE_BUF_OP(SET_SCRATCH_SURFACE)
        CASE_BUF_OP(FLUSH_SURFACE)
        CASE_BUF_OP(DISPOSE_SURFACE)
        CASE_BUF_OP(DISPOSE_CONFIG)
        CASE_BUF_OP(INVALIDATE_CONTEXT)
        CASE_BUF_OP(SYNC)
        CASE_BUF_OP(RESTORE_DEVICES)

        CASE_BUF_OP(SWAP_BUFFERS)

        // special no-op (mainly used for achieving 8-byte alignment)
        CASE_BUF_OP(NOOP)

        // paint-related ops
        CASE_BUF_OP(RESET_PAINT)
        CASE_BUF_OP(SET_COLOR)
        CASE_BUF_OP(SET_GRADIENT_PAINT)
        CASE_BUF_OP(SET_LINEAR_GRADIENT_PAINT)
        CASE_BUF_OP(SET_RADIAL_GRADIENT_PAINT)
        CASE_BUF_OP(SET_TEXTURE_PAINT)

        // BufferedImageOp-related ops
        CASE_BUF_OP(ENABLE_CONVOLVE_OP)
        CASE_BUF_OP(DISABLE_CONVOLVE_OP)
        CASE_BUF_OP(ENABLE_RESCALE_OP)
        CASE_BUF_OP(DISABLE_RESCALE_OP)
        CASE_BUF_OP(ENABLE_LOOKUP_OP)
        CASE_BUF_OP(DISABLE_LOOKUP_OP)
        default:
            return "";
    }
}

void rq_plog(JNIEnv* env, int logLevel, const char *formatMsg, ...) {
    if (logLevel < LL_TRACE || logLevel > LL_ERROR || formatMsg == NULL)
        return;

    // TODO: check whether current logLevel is enabled in PlatformLogger
    static jobject loggerObject = NULL;
    static jclass clazz = NULL;
    static jmethodID midTrace = NULL, midDebug = NULL, midInfo = NULL, midWarn = NULL, midError = NULL;

    if (loggerObject == NULL) {
        jclass shkClass = (*env)->FindClass(env, "sun/java2d/pipe/RenderQueue");
        if (shkClass == NULL)
            return;
        jfieldID fieldId = (*env)->GetStaticFieldID(env, shkClass, "rqLog", "Lsun/util/logging/PlatformLogger;");
        if (fieldId == NULL)
            return;
        loggerObject = (*env)->GetStaticObjectField(env, shkClass, fieldId);
        loggerObject = (*env)->NewGlobalRef(env, loggerObject);

        clazz = (*env)->GetObjectClass(env, loggerObject);
        if (clazz == NULL) {
            NSLog(@"plogImpl: can't find PlatformLogger class");
            return;
        }

        midTrace = (*env)->GetMethodID(env, clazz, "finest", "(Ljava/lang/String;)V");
        midDebug = (*env)->GetMethodID(env, clazz, "fine", "(Ljava/lang/String;)V");
        midInfo = (*env)->GetMethodID(env, clazz, "info", "(Ljava/lang/String;)V");
        midWarn = (*env)->GetMethodID(env, clazz, "warning", "(Ljava/lang/String;)V");
        midError = (*env)->GetMethodID(env, clazz, "severe", "(Ljava/lang/String;)V");
    }

    jmethodID mid;
    switch (logLevel) {
        case LL_DEBUG: mid = midDebug; break;
        case LL_INFO: mid = midInfo; break;
        case LL_WARNING: mid = midWarn; break;
        case LL_ERROR: mid = midError; break;
        default:
            mid = midTrace;
    }
    if (mid == NULL) {
        NSLog(@"plogImpl: undefined log-method for level %d", logLevel);
        return;
    }

    va_list args;
    va_start(args, formatMsg);
    const int bufSize = 512;
    char buf[bufSize];
    vsnprintf(buf, bufSize, formatMsg, args);
    va_end(args);

    jstring jstr = (*env)->NewStringUTF(env, buf);

    (*env)->CallVoidMethod(env, loggerObject, mid, jstr);
    (*env)->DeleteLocalRef(env, jstr);
}

static const char * toCString(id obj) {
    return obj == nil ? "nil" : [NSString stringWithFormat:@"%@", obj].UTF8String;
}

static long rqCurrentTimeMillis() {
    struct timeval t;
    gettimeofday(&t, 0);
    return ((long)t.tv_sec) * 1000 + (long)(t.tv_usec/1000);
}
