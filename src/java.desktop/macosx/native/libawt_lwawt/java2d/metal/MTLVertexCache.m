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
#include <string.h>

#include "sun_java2d_SunGraphics2D.h"

#include "MTLPaints.h"
#include "MTLVertexCache.h"

typedef struct _J2DVertex {
    jfloat tx, ty;
    jubyte r, g, b, a;
    jfloat dx, dy;
} J2DVertex;

static J2DVertex *vertexCache = NULL;
static jint vertexCacheIndex = 0;

static jint maskCacheTexID = 0;
static jint maskCacheIndex = 0;

#define MTLVC_ADD_VERTEX(TX, TY, R, G, B, A, DX, DY) \
    do { \
        J2DVertex *v = &vertexCache[vertexCacheIndex++]; \
        v->tx = TX; \
        v->ty = TY; \
        v->r  = R;  \
        v->g  = G;  \
        v->b  = B;  \
        v->a  = A;  \
        v->dx = DX; \
        v->dy = DY; \
    } while (0)

#define MTLVC_ADD_QUAD(TX1, TY1, TX2, TY2, DX1, DY1, DX2, DY2, R, G, B, A) \
    do { \
        MTLVC_ADD_VERTEX(TX1, TY1, R, G, B, A, DX1, DY1); \
        MTLVC_ADD_VERTEX(TX2, TY1, R, G, B, A, DX2, DY1); \
        MTLVC_ADD_VERTEX(TX2, TY2, R, G, B, A, DX2, DY2); \
        MTLVC_ADD_VERTEX(TX1, TY2, R, G, B, A, DX1, DY2); \
    } while (0)

jboolean
MTLVertexCache_InitVertexCache(MTLContext *mtlc)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLVertexCache_InitVertexCache");
    J2dTraceLn(J2D_TRACE_INFO, "MTLVertexCache_InitVertexCache");
    return JNI_TRUE;
}

void
MTLVertexCache_FlushVertexCache()
{
    // TODO
    J2dTraceNotImplPrimitive("MTLVertexCache_FlushVertexCache");
    J2dTraceLn(J2D_TRACE_INFO, "MTLVertexCache_FlushVertexCache");
    vertexCacheIndex = 0;
}

/**
 * This method is somewhat hacky, but necessary for the foreseeable future.
 * The problem is the way OpenGL handles color values in vertex arrays.  When
 * a vertex in a vertex array contains a color, and then the vertex array
 * is rendered via glDrawArrays(), the global OpenGL color state is actually
 * modified each time a vertex is rendered.  This means that after all
 * vertices have been flushed, the global OpenGL color state will be set to
 * the color of the most recently rendered element in the vertex array.
 *
 * The reason this is a problem for us is that we do not want to flush the
 * vertex array (in the case of mask/glyph operations) or issue a glEnd()
 * (in the case of non-antialiased primitives) everytime the current color
 * changes, which would defeat any benefit from batching in the first place.
 * We handle this in practice by not calling CHECK/RESET_PREVIOUS_OP() when
 * the simple color state is changing in MTLPaints_SetColor().  This is
 * problematic for vertex caching because we may end up with the following
 * situation, for example:
 *   SET_COLOR (orange)
 *   MASK_FILL
 *   MASK_FILL
 *   SET_COLOR (blue; remember, this won't cause a flush)
 *   FILL_RECT (this will cause the vertex array to be flushed)
 *
 * In this case, we would actually end up rendering an orange FILL_RECT,
 * not a blue one as intended, because flushing the vertex cache flush would
 * override the color state from the most recent SET_COLOR call.
 *
 * Long story short, the easiest way to resolve this problem is to call
 * this method just after disabling the mask/glyph cache, which will ensure
 * that the appropriate color state is restored.
 */
void
MTLVertexCache_RestoreColorState(MTLContext *mtlc)
{
    // TODO
    J2dTraceNotImplPrimitive("MTLVertexCache_RestoreColorState");
    if (mtlc->paintState == sun_java2d_SunGraphics2D_PAINT_ALPHACOLOR) {
        MTLPaints_SetColor(mtlc, mtlc->pixel);
    }
}

static jboolean
MTLVertexCache_InitMaskCache()
{
    // TODO
    J2dTraceNotImplPrimitive("MTLVertexCache_InitMaskCache");
    J2dTraceLn(J2D_TRACE_INFO, "MTLVertexCache_InitMaskCache");
    return JNI_TRUE;
}

void
MTLVertexCache_EnableMaskCache(MTLContext *mtlc)
{
    // TODO
    J2dTraceNotImplPrimitive("MTLVertexCache_EnableMaskCache");
    J2dTraceLn(J2D_TRACE_INFO, "MTLVertexCache_EnableMaskCache");
}

void
MTLVertexCache_DisableMaskCache(MTLContext *mtlc)
{
    // TODO
    J2dTraceNotImplPrimitive("MTLVertexCache_DisableMaskCache");
    J2dTraceLn(J2D_TRACE_INFO, "MTLVertexCache_DisableMaskCache");
    maskCacheIndex = 0;
}

void
MTLVertexCache_AddMaskQuad(MTLContext *mtlc,
                           jint srcx, jint srcy,
                           jint dstx, jint dsty,
                           jint width, jint height,
                           jint maskscan, void *mask)
{
    // TODO
    J2dTraceNotImplPrimitive("MTLVertexCache_AddMaskQuad");
}

void
MTLVertexCache_AddGlyphQuad(MTLContext *mtlc,
                            jfloat tx1, jfloat ty1, jfloat tx2, jfloat ty2,
                            jfloat dx1, jfloat dy1, jfloat dx2, jfloat dy2)
{
    // TODO
    J2dTraceNotImplPrimitive("MTLVertexCache_AddGlyphQuad");
    J2dTraceLn(J2D_TRACE_INFO, "MTLVertexCache_AddGlyphQuad");
}

#endif /* !HEADLESS */
