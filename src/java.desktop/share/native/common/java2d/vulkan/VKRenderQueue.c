/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2024, JetBrains s.r.o.. All rights reserved.
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

#include "sun_font_StrikeCache.h"
#include "sun_java2d_pipe_BufferedOpCodes.h"
#include "sun_java2d_pipe_BufferedRenderPipe.h"
#include "sun_java2d_pipe_BufferedTextPipe.h"
#include "fontscalerdefs.h"
#include "Trace.h"
#include "jlong.h"
#include "VKBase.h"
#include "VKBlitLoops.h"
#include "VKSurfaceData.h"
#include "VKRenderer.h"
#include "VKUtil.h"

/*
 * The following macros are used to pick values (of the specified type) off
 * the queue.
 */
#define NEXT_VAL(buf, type) (((type *)((buf) = ((unsigned char*)(buf)) + sizeof(type)))[-1])
#define NEXT_BYTE(buf)      NEXT_VAL(buf, unsigned char)
#define NEXT_INT(buf)       NEXT_VAL(buf, jint)
#define NEXT_FLOAT(buf)     NEXT_VAL(buf, jfloat)
#define NEXT_BOOLEAN(buf)   (jboolean)NEXT_INT(buf)
#define NEXT_LONG(buf)      NEXT_VAL(buf, jlong)
#define NEXT_DOUBLE(buf)    NEXT_VAL(buf, jdouble)
#define NEXT_SURFACE(buf) ((VKSDOps*) (SurfaceDataOps*) jlong_to_ptr(NEXT_LONG(buf)))

/*
 * Increments a pointer (buf) by the given number of bytes.
 */
#define SKIP_BYTES(buf, numbytes) (buf) = ((unsigned char*)(buf)) + (numbytes)

/*
 * Extracts a value at the given offset from the provided packed value.
 */
#define EXTRACT_VAL(packedval, offset, mask) \
    (((packedval) >> (offset)) & (mask))
#define EXTRACT_BYTE(packedval, offset) \
    (unsigned char)EXTRACT_VAL(packedval, offset, 0xff)
#define EXTRACT_BOOLEAN(packedval, offset) \
    (jboolean)EXTRACT_VAL(packedval, offset, 0x1)

#define BYTES_PER_POLY_POINT \
    sun_java2d_pipe_BufferedRenderPipe_BYTES_PER_POLY_POINT
#define BYTES_PER_SCANLINE \
    sun_java2d_pipe_BufferedRenderPipe_BYTES_PER_SCANLINE
#define BYTES_PER_SPAN \
    sun_java2d_pipe_BufferedRenderPipe_BYTES_PER_SPAN

#define BYTES_PER_GLYPH_IMAGE \
    sun_java2d_pipe_BufferedTextPipe_BYTES_PER_GLYPH_IMAGE
#define BYTES_PER_GLYPH_POSITION \
    sun_java2d_pipe_BufferedTextPipe_BYTES_PER_GLYPH_POSITION
#define BYTES_PER_POSITIONED_GLYPH \
    (BYTES_PER_GLYPH_IMAGE + BYTES_PER_GLYPH_POSITION)

#define OFFSET_CONTRAST  sun_java2d_pipe_BufferedTextPipe_OFFSET_CONTRAST
#define OFFSET_RGBORDER  sun_java2d_pipe_BufferedTextPipe_OFFSET_RGBORDER
#define OFFSET_SUBPIXPOS sun_java2d_pipe_BufferedTextPipe_OFFSET_SUBPIXPOS
#define OFFSET_POSITIONS sun_java2d_pipe_BufferedTextPipe_OFFSET_POSITIONS

#define OFFSET_SRCTYPE sun_java2d_vulkan_VKBlitLoops_OFFSET_SRCTYPE
#define OFFSET_HINT    sun_java2d_vulkan_VKBlitLoops_OFFSET_HINT
#define OFFSET_TEXTURE sun_java2d_vulkan_VKBlitLoops_OFFSET_TEXTURE
#define OFFSET_RTT     sun_java2d_vulkan_VKBlitLoops_OFFSET_RTT
#define OFFSET_XFORM   sun_java2d_vulkan_VKBlitLoops_OFFSET_XFORM
#define OFFSET_ISOBLIT sun_java2d_vulkan_VKBlitLoops_OFFSET_ISOBLIT

// Rendering context is only accessed from VKRenderQueue_flushBuffer,
// which is only called from queue flusher thread, no need for synchronization.
static VKRenderingContext context = {
        .surface = NULL,
        .transform = {1.0, 0.0, 0.0,0.0, 1.0, 0.0},
        .clipRect = {{0, 0},{INT_MAX, INT_MAX}},
        .color = {},
        .composite = ALPHA_COMPOSITE_SRC_OVER,
        .extraAlpha = 1.0f
};
// We keep this color separately from context.color,
// because we need consistent state when switching between XOR and alpha composite modes.
// This variable holds last value set by SET_COLOR, while context.color holds color,
// currently used for drawing, which may have also been provided by SET_XOR_COMPOSITE.
Color color;

JNIEXPORT void JNICALL Java_sun_java2d_vulkan_VKRenderQueue_flushBuffer
    (JNIEnv *env, jobject oglrq, jlong buf, jint limit)
{
    unsigned char *b, *end;

    J2dTraceLn1(J2D_TRACE_VERBOSE,
                "VKRenderQueue_flushBuffer: limit=%d", limit);

    b = (unsigned char *)jlong_to_ptr(buf);
    if (b == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR,
            "VKRenderQueue_flushBuffer: cannot get direct buffer address");
        return;
    }

    if (limit == 0) return;

    end = b + limit;

    while (b < end) {
        jint opcode = NEXT_INT(b);

        J2dRlsTraceLn2(J2D_TRACE_VERBOSE2,
                    "VKRenderQueue_flushBuffer: opcode=%d, rem=%d",
                    opcode, (end-b));

        switch (opcode) {

        // draw ops
        case sun_java2d_pipe_BufferedOpCodes_DRAW_LINE:
            {
                jint x1 = NEXT_INT(b);
                jint y1 = NEXT_INT(b);
                jint x2 = NEXT_INT(b);
                jint y2 = NEXT_INT(b);
                J2dRlsTraceLn4(J2D_TRACE_VERBOSE,
                            "VKRenderQueue_flushBuffer: DRAW_LINE(%d, %d, %d, %d)",
                            x1, y1, x2, y2);
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_DRAW_RECT:
            {
                jint x = NEXT_INT(b);
                jint y = NEXT_INT(b);
                jint w = NEXT_INT(b);
                jint h = NEXT_INT(b);
                J2dRlsTraceLn4(J2D_TRACE_VERBOSE,
                            "VKRenderQueue_flushBuffer: DRAW_RECT(%d, %d, %d, %d)",
                            x, y, w, h);
                VKRenderer_RenderRect(&context, PIPELINE_DRAW_COLOR, x, y, w, h);
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
                J2dRlsTraceLn(J2D_TRACE_VERBOSE, "VKRenderQueue_flushBuffer: DRAW_POLY");
                SKIP_BYTES(b, nPoints * BYTES_PER_POLY_POINT);
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_DRAW_PIXEL:
            {
                jint x = NEXT_INT(b);
                jint y = NEXT_INT(b);
                J2dRlsTraceLn(J2D_TRACE_VERBOSE, "VKRenderQueue_flushBuffer: DRAW_PIXEL");
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_DRAW_SCANLINES:
            {
                jint count = NEXT_INT(b);
                J2dRlsTraceLn(J2D_TRACE_VERBOSE, "VKRenderQueue_flushBuffer: DRAW_SCANLINES");
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
                J2dRlsTraceLn8(J2D_TRACE_VERBOSE,
                    "VKRenderQueue_flushBuffer: DRAW_PARALLELOGRAM(%f, %f, %f, %f, %f, %f, %f, %f)",
                    x11, y11, dx21, dy21, dx12, dy12, lwr21, lwr12);
                VKRenderer_RenderParallelogram(&context, PIPELINE_DRAW_COLOR, x11, y11, dx21, dy21, dx12, dy12);
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
                J2dRlsTraceLn8(J2D_TRACE_VERBOSE,
                    "VKRenderQueue_flushBuffer: DRAW_AAPARALLELOGRAM(%f, %f, %f, %f, %f, %f, %f, %f)",
                    x11, y11, dx21, dy21, dx12, dy12, lwr21, lwr12);
            }
            break;

        // fill ops
        case sun_java2d_pipe_BufferedOpCodes_FILL_RECT:
            {
                jint x = NEXT_INT(b);
                jint y = NEXT_INT(b);
                jint w = NEXT_INT(b);
                jint h = NEXT_INT(b);
                J2dRlsTraceLn4(J2D_TRACE_VERBOSE,
                    "VKRenderQueue_flushBuffer: FILL_RECT(%d, %d, %d, %d)", x, y, w, h);
                VKRenderer_RenderRect(&context, PIPELINE_FILL_COLOR, x, y, w, h);
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_FILL_SPANS:
            {
                jint count = NEXT_INT(b);
                J2dRlsTraceLn(J2D_TRACE_VERBOSE,
                    "VKRenderQueue_flushBuffer: FILL_SPANS");
                VKRenderer_FillSpans(&context, count, (jint *)b);
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
                J2dRlsTraceLn6(J2D_TRACE_VERBOSE,
                    "VKRenderQueue_flushBuffer: FILL_PARALLELOGRAM(%f, %f, %f, %f, %f, %f)",
                    x11, y11, dx21, dy21, dx12, dy12);
                VKRenderer_RenderParallelogram(&context, PIPELINE_FILL_COLOR, x11, y11, dx21, dy21, dx12, dy12);
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
                J2dRlsTraceLn6(J2D_TRACE_VERBOSE,
                    "VKRenderQueue_flushBuffer: FILL_AAPARALLELOGRAM(%f, %f, %f, %f, %f, %f)",
                    x11, y11, dx21, dy21, dx12, dy12);
                // TODO this is not AA!
                VKRenderer_RenderParallelogram(&context, PIPELINE_FILL_COLOR, x11, y11, dx21, dy21, dx12, dy12);
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
                J2dRlsTraceLn(J2D_TRACE_VERBOSE, "VKRenderQueue_flushBuffer: DRAW_GLYPH_LIST");
                // TODO this is a quick and dirty implementation of greyscale-AA text rendering over MASK_FILL. Need to do better.
                jfloat glyphx, glyphy;
                for (int i = 0; i < numGlyphs; i++) {
                    GlyphInfo *ginfo = (GlyphInfo *)jlong_to_ptr(NEXT_LONG(images));
                    if (usePositions) {
                        jfloat posx = NEXT_FLOAT(positions);
                        jfloat posy = NEXT_FLOAT(positions);
                        glyphx = glyphListOrigX + posx + ginfo->topLeftX;
                        glyphy = glyphListOrigY + posy + ginfo->topLeftY;
                    } else {
                        glyphx = glyphListOrigX + ginfo->topLeftX;
                        glyphy = glyphListOrigY + ginfo->topLeftY;
                        glyphListOrigX += ginfo->advanceX;
                        glyphListOrigY += ginfo->advanceY;
                    }
                    if (ginfo->format != sun_font_StrikeCache_PIXEL_FORMAT_GREYSCALE) continue;
                    if (ginfo->height*ginfo->rowBytes == 0) continue;
                    VKRenderer_MaskFill(&context, (int) glyphx, (int) glyphy, ginfo->width, ginfo->height, 0, ginfo->rowBytes, ginfo->height*ginfo->rowBytes, ginfo->image);
                }
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
                J2dRlsTraceLn6(J2D_TRACE_VERBOSE,
                    "VKRenderQueue_flushBuffer: COPY_AREA(%d, %d, %d, %d, %d, %d)",
                    x, y, w, h, dx, dy);
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_BLIT:
            {
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
                VKSDOps *dstOps = (VKSDOps *)jlong_to_ptr(pDst);
                VKSDOps *oldSurface = context.surface;
                context.surface = dstOps;
                if (isoblit) {
                    VKBlitLoops_IsoBlit(env, &context, pSrc,
                                         xform, hint, texture,
                                         sx1, sy1, sx2, sy2,
                                         dx1, dy1, dx2, dy2);
                } else {
                    jint srctype = EXTRACT_BYTE(packedParams, OFFSET_SRCTYPE);
                    VKBlitLoops_Blit(env, &context, pSrc,
                                      xform, hint, srctype, texture,
                                      sx1, sy1, sx2, sy2,
                                      dx1, dy1, dx2, dy2);
                }
                context.surface = oldSurface;
                break;
                J2dRlsTraceLn8(J2D_TRACE_VERBOSE, "VKRenderQueue_flushBuffer: BLIT (%d %d %d %d) -> (%f %f %f %f) ",
                               sx1, sy1, sx2, sy2, dx1, dy1, dx2, dy2)
                J2dRlsTraceLn4(J2D_TRACE_VERBOSE, "VKRenderQueue_flushBuffer: BLIT texture=%d rtt=%d xform=%d isoblit=%d",
                               texture, rtt, xform, isoblit)

            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_SURFACE_TO_SW_BLIT:
            {
                jint sx      = NEXT_INT(b);
                jint sy      = NEXT_INT(b);
                jint dx      = NEXT_INT(b);
                jint dy      = NEXT_INT(b);
                jint w       = NEXT_INT(b);
                jint h       = NEXT_INT(b);
                jint dsttype = NEXT_INT(b);
                jlong pSrc   = NEXT_LONG(b);
                jlong pDst   = NEXT_LONG(b);
                J2dRlsTraceLn(J2D_TRACE_VERBOSE, "VKRenderQueue_flushBuffer: SURFACE_TO_SW_BLIT");
            }
            break;
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
                J2dRlsTraceLn7(J2D_TRACE_VERBOSE,
                               "VKRenderQueue_flushBuffer: MASK_FILL(%d, %d, %dx%d, maskoff=%d, maskscan=%d, masklen=%d)",
                               x, y, w, h, maskoff, maskscan, masklen);
                VKRenderer_MaskFill(&context, x, y, w, h, maskoff, maskscan, masklen, pMask);
                SKIP_BYTES(b, masklen);
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_MASK_BLIT:
            {
                jint dstx     = NEXT_INT(b);
                jint dsty     = NEXT_INT(b);
                jint width    = NEXT_INT(b);
                jint height   = NEXT_INT(b);
                jint masklen  = width * height * sizeof(jint);
                J2dRlsTraceLn(J2D_TRACE_VERBOSE, "VKRenderQueue_flushBuffer: MASK_BLIT");
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
                context.clipRect = (VkRect2D){
                    {x1, y1},
                    {x2-x1, y2 - y1}};
                J2dRlsTraceLn4(J2D_TRACE_VERBOSE,
                    "VKRenderQueue_flushBuffer: SET_RECT_CLIP(%d, %d, %d, %d)",
                    x1, y1, x2, y2);
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_BEGIN_SHAPE_CLIP:
            {
                J2dRlsTraceLn(J2D_TRACE_VERBOSE,
                    "VKRenderQueue_flushBuffer: BEGIN_SHAPE_CLIP");
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_SET_SHAPE_CLIP_SPANS:
            {
                jint count = NEXT_INT(b);
                J2dRlsTraceLn(J2D_TRACE_VERBOSE,
                    "VKRenderQueue_flushBuffer: SET_SHAPE_CLIP_SPANS");
                SKIP_BYTES(b, count * BYTES_PER_SPAN);
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_END_SHAPE_CLIP:
            {
                J2dRlsTraceLn(J2D_TRACE_VERBOSE,
                    "VKRenderQueue_flushBuffer: END_SHAPE_CLIP");
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_RESET_CLIP:
            {
                J2dRlsTraceLn(J2D_TRACE_VERBOSE,
                    "VKRenderQueue_flushBuffer: RESET_CLIP");
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_SET_ALPHA_COMPOSITE:
            {
                jint   rule       = NEXT_INT(b);
                jfloat extraAlpha = NEXT_FLOAT(b);
                jint   flags      = NEXT_INT(b);
                J2dRlsTraceLn3(J2D_TRACE_VERBOSE,
                    "VKRenderQueue_flushBuffer: SET_ALPHA_COMPOSITE(%d, %f, %d)", rule, extraAlpha, flags);
                context.color      = color;
                context.composite  = (VKCompositeMode) rule;
                context.extraAlpha = extraAlpha;
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_SET_XOR_COMPOSITE:
            {
                jint xorPixel = NEXT_INT(b);
                J2dRlsTraceLn(J2D_TRACE_VERBOSE,
                    "VKRenderQueue_flushBuffer: SET_XOR_COMPOSITE");
                context.color = VKUtil_DecodeJavaColor(xorPixel);
                context.color.a = 0.0f; // Alpha is left unchanged in XOR mode.
                context.composite  = LOGIC_COMPOSITE_XOR;
                context.extraAlpha = 1.0f;
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_RESET_COMPOSITE:
            {
                J2dRlsTraceLn(J2D_TRACE_VERBOSE,
                    "VKRenderQueue_flushBuffer: RESET_COMPOSITE");
                context.color      = color;
                context.composite  = ALPHA_COMPOSITE_SRC;
                context.extraAlpha = 1.0f;
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
                J2dRlsTraceLn3(J2D_TRACE_VERBOSE,
                    "VKRenderQueue_flushBuffer: SET_TRANSFORM | %.2f %.2f %.2f |", m00, m01, m02);
                J2dRlsTraceLn3(J2D_TRACE_VERBOSE,
                    "                                         | %.2f %.2f %.2f |", m10, m11, m12);
                J2dRlsTraceLn(J2D_TRACE_VERBOSE,
                    "                                         | 0.00 0.00 1.00 |");
                context.transform.m00 = m00;
                context.transform.m10 = m10;
                context.transform.m01 = m01;
                context.transform.m11 = m11;
                context.transform.m02 = m02;
                context.transform.m12 = m12;
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_RESET_TRANSFORM:
            {
                J2dRlsTraceLn(J2D_TRACE_VERBOSE,
                    "VKRenderQueue_flushBuffer: RESET_TRANSFORM");
            }
            break;

        // context-related ops
        case sun_java2d_pipe_BufferedOpCodes_SET_SURFACES:
            {
                VKSDOps* src = NEXT_SURFACE(b);
                VKSDOps* dst = NEXT_SURFACE(b);

                J2dRlsTraceLn(J2D_TRACE_VERBOSE,
                    "VKRenderQueue_flushBuffer: SET_SURFACES");
                context.surface = dst;
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_SET_SCRATCH_SURFACE:
            {
                jlong pConfigInfo = NEXT_LONG(b);
                J2dRlsTraceLn(J2D_TRACE_VERBOSE,
                           "VKRenderQueue_flushBuffer: SET_SCRATCH_SURFACE");
                context.surface = NULL;
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_FLUSH_SURFACE:
            {
                VKSDOps* surface = NEXT_SURFACE(b);
                J2dRlsTraceLn(J2D_TRACE_VERBOSE,
                    "VKRenderQueue_flushBuffer: FLUSH_SURFACE");
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_DISPOSE_SURFACE:
            {
                jlong pData = NEXT_LONG(b);
                J2dRlsTraceLn(J2D_TRACE_VERBOSE,
                    "VKRenderQueue_flushBuffer: DISPOSE_SURFACE");
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_DISPOSE_CONFIG:
            {
                jlong pConfigInfo = NEXT_LONG(b);
                J2dRlsTraceLn(J2D_TRACE_VERBOSE,
                    "VKRenderQueue_flushBuffer: DISPOSE_CONFIG")
                context.surface = NULL;
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_INVALIDATE_CONTEXT:
            {
                J2dRlsTraceLn(J2D_TRACE_VERBOSE,
                           "VKRenderQueue_flushBuffer: INVALIDATE_CONTEXT");
                context.surface = NULL;
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_SYNC:
            {
                J2dRlsTraceLn(J2D_TRACE_VERBOSE,
                              "VKRenderQueue_flushBuffer: SYNC");
            }
            break;

        case sun_java2d_pipe_BufferedOpCodes_CONFIGURE_SURFACE:
            {
                VKSDOps* surface = NEXT_SURFACE(b);
                jint width = NEXT_INT(b);
                jint height = NEXT_INT(b);
                J2dRlsTraceLn2(J2D_TRACE_VERBOSE,
                              "VKRenderQueue_flushBuffer: CONFIGURE_SURFACE %dx%d", width, height);
                VKRenderer_ConfigureSurface(surface, (VkExtent2D) {width, height});
            }
            break;

        // multibuffering ops
        case sun_java2d_pipe_BufferedOpCodes_SWAP_BUFFERS:
            {
                jlong window = NEXT_LONG(b);
                J2dRlsTraceLn(J2D_TRACE_VERBOSE,
                    "VKRenderQueue_flushBuffer: SWAP_BUFFERS");
            }
            break;

        case sun_java2d_pipe_BufferedOpCodes_FLUSH_BUFFER:
            {
                VKSDOps* surface = NEXT_SURFACE(b);
                J2dRlsTraceLn(J2D_TRACE_VERBOSE,
                    "VKRenderQueue_flushBuffer: FLUSH_BUFFER");

                VKRenderer_FlushSurface(surface);
            }
            break;

        // special no-op (mainly used for achieving 8-byte alignment)
        case sun_java2d_pipe_BufferedOpCodes_NOOP:
            {
                J2dRlsTraceLn(J2D_TRACE_VERBOSE,
                              "VKRenderQueue_flushBuffer: NOOP");
            }
            break;

        // paint-related ops
        case sun_java2d_pipe_BufferedOpCodes_RESET_PAINT:
            {
                J2dRlsTraceLn(J2D_TRACE_VERBOSE,
                    "VKRenderQueue_flushBuffer: RESET_PAINT");
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_SET_COLOR:
            {
                jint javaColor = NEXT_INT(b);
                color = VKUtil_DecodeJavaColor(javaColor);
                if (COMPOSITE_GROUP(context.composite) == ALPHA_COMPOSITE_GROUP) context.color = color;
                J2dRlsTraceLn1(J2D_TRACE_VERBOSE, "VKRenderQueue_flushBuffer: SET_COLOR(0x%08x)", javaColor);
                J2dTraceLn4(J2D_TRACE_VERBOSE, // Print color values with straight alpha for convenience.
                    "    srgb={%.3f, %.3f, %.3f, %.3f}", color.r/color.a, color.g/color.a, color.b/color.a, color.a);
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
                J2dRlsTraceLn(J2D_TRACE_VERBOSE,
                    "VKRenderQueue_flushBuffer: SET_GRADIENT_PAINT");
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
                J2dRlsTraceLn(J2D_TRACE_VERBOSE,
                    "VKRenderQueue_flushBuffer: SET_LINEAR_GRADIENT_PAINT");
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
                J2dRlsTraceLn(J2D_TRACE_VERBOSE,
                           "VKRenderQueue_flushBuffer: SET_RADIAL_GRADIENT_PAINT");
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
                J2dRlsTraceLn(J2D_TRACE_VERBOSE,
                    "VKRenderQueue_flushBuffer: SET_TEXTURE_PAINT");
            }
            break;

        // BufferedImageOp-related ops
        case sun_java2d_pipe_BufferedOpCodes_ENABLE_CONVOLVE_OP:
            {
                jlong pSrc        = NEXT_LONG(b);
                jboolean edgeZero = NEXT_BOOLEAN(b);
                jint kernelWidth  = NEXT_INT(b);
                jint kernelHeight = NEXT_INT(b);
                J2dRlsTraceLn(J2D_TRACE_VERBOSE,
                    "VKRenderQueue_flushBuffer: ENABLE_CONVOLVE_OP");
                SKIP_BYTES(b, kernelWidth * kernelHeight * sizeof(jfloat));
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_DISABLE_CONVOLVE_OP:
            {
                J2dRlsTraceLn(J2D_TRACE_VERBOSE,
                    "VKRenderQueue_flushBuffer: DISABLE_CONVOLVE_OP");
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_ENABLE_RESCALE_OP:
            {
                jlong pSrc          = NEXT_LONG(b);
                jboolean nonPremult = NEXT_BOOLEAN(b);
                jint numFactors     = 4;
                unsigned char *scaleFactors = b;
                unsigned char *offsets = (b + numFactors * sizeof(jfloat));
                J2dRlsTraceLn(J2D_TRACE_VERBOSE,
                    "VKRenderQueue_flushBuffer: ENABLE_RESCALE_OP");
                SKIP_BYTES(b, numFactors * sizeof(jfloat) * 2);
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_DISABLE_RESCALE_OP:
            {
                J2dRlsTraceLn(J2D_TRACE_VERBOSE,
                    "VKRenderQueue_flushBuffer: DISABLE_RESCALE_OP");
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
                jint bytesPerElem = shortData ? sizeof(jshort) : sizeof(jbyte);
                void *tableValues = b;
                J2dRlsTraceLn(J2D_TRACE_VERBOSE,
                    "VKRenderQueue_flushBuffer: ENABLE_LOOKUP_OP");
                SKIP_BYTES(b, numBands * bandLength * bytesPerElem);
            }
            break;
        case sun_java2d_pipe_BufferedOpCodes_DISABLE_LOOKUP_OP:
            {
                J2dRlsTraceLn(J2D_TRACE_VERBOSE,
                    "VKRenderQueue_flushBuffer: DISABLE_LOOKUP_OP");
            }
            break;

        default:
            J2dRlsTraceLn1(J2D_TRACE_ERROR,
                "VKRenderQueue_flushBuffer: invalid opcode=%d", opcode);
            return;
        }
    }

    // Flush all pending GPU work
    VKGraphicsEnvironment* ge = VKGE_graphics_environment();
    for (uint32_t i = 0; i < ARRAY_SIZE(ge->devices); i++) {
        VKRenderer_Flush(ge->devices[i].renderer);
    }
}

#endif /* !HEADLESS */
