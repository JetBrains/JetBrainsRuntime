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
    J2dTraceLn(J2D_TRACE_INFO, "MTLVertexCache_InitVertexCache");
    return JNI_TRUE;
}

void
MTLVertexCache_FlushVertexCache()
{
    // TODO
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
    if (mtlc->paintState == sun_java2d_SunGraphics2D_PAINT_ALPHACOLOR) {
        MTLPaints_SetColor(mtlc, mtlc->pixel);
    }
}

static jboolean
MTLVertexCache_InitMaskCache()
{
    J2dTraceLn(J2D_TRACE_INFO, "MTLVertexCache_InitMaskCache");
/*
    maskCacheTexID =
        MTLContext_CreateBlitTexture(GL_INTENSITY8, GL_LUMINANCE,
                                     MTLVC_MASK_CACHE_WIDTH_IN_TEXELS,
                                     MTLVC_MASK_CACHE_HEIGHT_IN_TEXELS);

    // init special fully opaque tile in the upper-right corner of
    // the mask cache texture
    {
        GLubyte allOnes[MTLVC_MASK_CACHE_TILE_SIZE];
        memset(allOnes, 0xff, MTLVC_MASK_CACHE_TILE_SIZE);
        j2d_glTexSubImage2D(GL_TEXTURE_2D, 0,
                            MTLVC_MASK_CACHE_SPECIAL_TILE_X,
                            MTLVC_MASK_CACHE_SPECIAL_TILE_Y,
                            MTLVC_MASK_CACHE_TILE_WIDTH,
                            MTLVC_MASK_CACHE_TILE_HEIGHT,
                            GL_LUMINANCE, GL_UNSIGNED_BYTE, allOnes);
    }
*/
    return JNI_TRUE;
}

void
MTLVertexCache_EnableMaskCache(MTLContext *mtlc)
{
    J2dTraceLn(J2D_TRACE_INFO, "MTLVertexCache_EnableMaskCache");
/*
    if (!MTLVertexCache_InitVertexCache(mtlc)) {
        return;
    }

    if (maskCacheTexID == 0) {
        if (!MTLVertexCache_InitMaskCache()) {
            return;
        }
    }

    j2d_glEnable(GL_TEXTURE_2D);
    j2d_glBindTexture(GL_TEXTURE_2D, maskCacheTexID);
    MTLC_UPDATE_TEXTURE_FUNCTION(mtlc, GL_MODULATE);
    j2d_glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    */
}

void
MTLVertexCache_DisableMaskCache(MTLContext *mtlc)
{
    J2dTraceLn(J2D_TRACE_INFO, "MTLVertexCache_DisableMaskCache");
/*
    MTLVertexCache_FlushVertexCache();
    MTLVertexCache_RestoreColorState(mtlc);

    j2d_glDisable(GL_TEXTURE_2D);
    j2d_glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    j2d_glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
    j2d_glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
    j2d_glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
*/
    maskCacheIndex = 0;
}

void
MTLVertexCache_AddMaskQuad(MTLContext *mtlc,
                           jint srcx, jint srcy,
                           jint dstx, jint dsty,
                           jint width, jint height,
                           jint maskscan, void *mask)
{
/*
    jfloat tx1, ty1, tx2, ty2;
    jfloat dx1, dy1, dx2, dy2;

    J2dTraceLn1(J2D_TRACE_INFO, "MTLVertexCache_AddMaskQuad: %d",
                maskCacheIndex);

    if (maskCacheIndex >= MTLVC_MASK_CACHE_MAX_INDEX ||
        vertexCacheIndex >= MTLVC_MAX_INDEX)
    {
        MTLVertexCache_FlushVertexCache();
        maskCacheIndex = 0;
    }

    if (mask != NULL) {
        jint texx = MTLVC_MASK_CACHE_TILE_WIDTH *
            (maskCacheIndex % MTLVC_MASK_CACHE_WIDTH_IN_TILES);
        jint texy = MTLVC_MASK_CACHE_TILE_HEIGHT *
            (maskCacheIndex / MTLVC_MASK_CACHE_WIDTH_IN_TILES);

        // update the source pointer offsets
        j2d_glPixelStorei(GL_UNPACK_SKIP_PIXELS, srcx);
        j2d_glPixelStorei(GL_UNPACK_SKIP_ROWS, srcy);
        j2d_glPixelStorei(GL_UNPACK_ROW_LENGTH, maskscan);

        // copy alpha mask into texture tile
        j2d_glTexSubImage2D(GL_TEXTURE_2D, 0,
                            texx, texy, width, height,
                            GL_LUMINANCE, GL_UNSIGNED_BYTE, mask);

        tx1 = ((jfloat)texx) / MTLVC_MASK_CACHE_WIDTH_IN_TEXELS;
        ty1 = ((jfloat)texy) / MTLVC_MASK_CACHE_HEIGHT_IN_TEXELS;

        maskCacheIndex++;
    } else {
        // use special fully opaque tile
        tx1 = ((jfloat)MTLVC_MASK_CACHE_SPECIAL_TILE_X) /
            MTLVC_MASK_CACHE_WIDTH_IN_TEXELS;
        ty1 = ((jfloat)MTLVC_MASK_CACHE_SPECIAL_TILE_Y) /
            MTLVC_MASK_CACHE_HEIGHT_IN_TEXELS;
    }

    tx2 = tx1 + (((jfloat)width) / MTLVC_MASK_CACHE_WIDTH_IN_TEXELS);
    ty2 = ty1 + (((jfloat)height) / MTLVC_MASK_CACHE_HEIGHT_IN_TEXELS);

    dx1 = (jfloat)dstx;
    dy1 = (jfloat)dsty;
    dx2 = dx1 + width;
    dy2 = dy1 + height;

    MTLVC_ADD_QUAD(tx1, ty1, tx2, ty2,
                   dx1, dy1, dx2, dy2,
                   mtlc->r, mtlc->g, mtlc->b, mtlc->a);
                   */
}

void
MTLVertexCache_AddGlyphQuad(MTLContext *mtlc,
                            jfloat tx1, jfloat ty1, jfloat tx2, jfloat ty2,
                            jfloat dx1, jfloat dy1, jfloat dx2, jfloat dy2)
{
    J2dTraceLn(J2D_TRACE_INFO, "MTLVertexCache_AddGlyphQuad");
/*
    if (vertexCacheIndex >= MTLVC_MAX_INDEX) {
        MTLVertexCache_FlushVertexCache();
    }

    MTLVC_ADD_QUAD(tx1, ty1, tx2, ty2,
                   dx1, dy1, dx2, dy2,
                   mtlc->r, mtlc->g, mtlc->b, mtlc->a);
                   */
}

#endif /* !HEADLESS */
