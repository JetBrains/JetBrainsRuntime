/*
 * Copyright (c) 2007, 2012, Oracle and/or its affiliates. All rights reserved.
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
#include <float.h>

#include "sun_java2d_SunGraphics2D.h"

#include "jni_util.h"
#include "OGLPaints.h"
#include "OGLVertexCache.h"

typedef struct _J2DVertex {
    jfloat tx, ty;
    jubyte r, g, b, a;
    jfloat dx, dy;
} J2DVertex;

// Multitexture vertex
typedef struct _J2DMTVertex {
    jfloat dx, dy;
    jfloat tx0, ty0;
    jfloat tx1, ty1;
} J2DMTVertex;

static J2DVertex *vertexCache = NULL;
static jint vertexCacheIndex = 0;

static J2DMTVertex *mtVertexCache = NULL;
static jboolean mtVertexCacheEnabled = JNI_FALSE;
static jboolean mtUseTxtBarrier = JNI_FALSE;
static jint evenLCDGlyphInd = 0;
static jint oddLCDGlyphInd = ODD_LCD_GLYPHS_OFFSET;
static jint lcdGlyphInd = 0;
static jfloat evenOx2 = FLT_MIN;
static jfloat oddOx2 = FLT_MIN;

static GLuint maskCacheTexID = 0;
static jint maskCacheIndex = 0;
static void OGLMTVertexCache_flush(jint mask);

#define OGLVC_ADD_VERTEX(TX, TY, R, G, B, A, DX, DY) \
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

#define OGLVC_ADD_QUAD(TX1, TY1, TX2, TY2, DX1, DY1, DX2, DY2, R, G, B, A) \
    do { \
        OGLVC_ADD_VERTEX(TX1, TY1, R, G, B, A, DX1, DY1); \
        OGLVC_ADD_VERTEX(TX2, TY1, R, G, B, A, DX2, DY1); \
        OGLVC_ADD_VERTEX(TX2, TY2, R, G, B, A, DX2, DY2); \
        OGLVC_ADD_VERTEX(TX1, TY2, R, G, B, A, DX1, DY2); \
    } while (0)

#define OGLMTVC_ADD_VERTEX(IND, DX, DY, TX0, TY0, TX1, TY1) \
    do { \
        J2DMTVertex *v = &mtVertexCache[IND++]; \
        v->dx = DX; \
        v->dy = DY; \
        v->tx0 = TX0; \
        v->ty0 = TY0; \
        v->tx1 = TX1; \
        v->ty1 = TY1; \
    } while (0)

#define OGLMTVC_ADD_QUAD(IND, DX1, DY1, DX2, DY2, TX1, TY1, TX2, TY2, DTX1, DTY1, DTX2, DTY2) \
    do { \
        OGLMTVC_ADD_VERTEX((IND), DX1, DY1, TX1, TY1, DTX1, DTY1); \
        OGLMTVC_ADD_VERTEX((IND), DX2, DY1, TX2, TY1, DTX2, DTY1); \
        OGLMTVC_ADD_VERTEX((IND), DX2, DY2, TX2, TY2, DTX2, DTY2); \
        OGLMTVC_ADD_VERTEX((IND), DX1, DY2, TX1, TY2, DTX1, DTY2); \
    } while (0)

jboolean
OGLVertexCache_InitVertexCache(OGLContext *oglc)
{
    J2dTraceLn(J2D_TRACE_INFO, "OGLVertexCache_InitVertexCache");

    if (vertexCache == NULL) {
        vertexCache = (J2DVertex *)malloc(OGLVC_MAX_INDEX * sizeof(J2DVertex));
        if (vertexCache == NULL) {
            return JNI_FALSE;
        }
    }

    if (!oglc->vertexCacheEnabled) {
        j2d_glTexCoordPointer(2, GL_FLOAT,
                              sizeof(J2DVertex), vertexCache);
        j2d_glColorPointer(4, GL_UNSIGNED_BYTE,
                           sizeof(J2DVertex), ((jfloat *)vertexCache) + 2);
        j2d_glVertexPointer(2, GL_FLOAT,
                            sizeof(J2DVertex), ((jfloat *)vertexCache) + 3);

        j2d_glEnableClientState(GL_TEXTURE_COORD_ARRAY);
        j2d_glEnableClientState(GL_COLOR_ARRAY);
        j2d_glEnableClientState(GL_VERTEX_ARRAY);

        oglc->vertexCacheEnabled = JNI_TRUE;
    }

    return JNI_TRUE;
}

void
OGLVertexCache_FlushVertexCache()
{
    J2dTraceLn(J2D_TRACE_INFO, "OGLVertexCache_FlushVertexCache");

    if (vertexCacheIndex > 0) {
        j2d_glDrawArrays(GL_QUADS, 0, vertexCacheIndex);
    }
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
 * the simple color state is changing in OGLPaints_SetColor().  This is
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
OGLVertexCache_RestoreColorState(OGLContext *oglc)
{
    if (oglc->paintState == sun_java2d_SunGraphics2D_PAINT_ALPHACOLOR) {
        OGLPaints_SetColor(oglc, oglc->pixel);
    }
}

static jboolean
OGLVertexCache_InitMaskCache()
{
    J2dTraceLn(J2D_TRACE_INFO, "OGLVertexCache_InitMaskCache");

    maskCacheTexID =
        OGLContext_CreateBlitTexture(GL_INTENSITY8, GL_LUMINANCE,
                                     OGLVC_MASK_CACHE_WIDTH_IN_TEXELS,
                                     OGLVC_MASK_CACHE_HEIGHT_IN_TEXELS);

    // init special fully opaque tile in the upper-right corner of
    // the mask cache texture
    {
        GLubyte allOnes[OGLVC_MASK_CACHE_TILE_SIZE];
        memset(allOnes, 0xff, OGLVC_MASK_CACHE_TILE_SIZE);
        j2d_glTexSubImage2D(GL_TEXTURE_2D, 0,
                            OGLVC_MASK_CACHE_SPECIAL_TILE_X,
                            OGLVC_MASK_CACHE_SPECIAL_TILE_Y,
                            OGLVC_MASK_CACHE_TILE_WIDTH,
                            OGLVC_MASK_CACHE_TILE_HEIGHT,
                            GL_LUMINANCE, GL_UNSIGNED_BYTE, allOnes);
    }

    return JNI_TRUE;
}

void
OGLVertexCache_EnableMaskCache(OGLContext *oglc)
{
    J2dTraceLn(J2D_TRACE_INFO, "OGLVertexCache_EnableMaskCache");

    if (!OGLVertexCache_InitVertexCache(oglc)) {
        return;
    }

    if (maskCacheTexID == 0) {
        if (!OGLVertexCache_InitMaskCache()) {
            return;
        }
    }

    j2d_glEnable(GL_TEXTURE_2D);
    j2d_glBindTexture(GL_TEXTURE_2D, maskCacheTexID);
    OGLC_UPDATE_TEXTURE_FUNCTION(oglc, GL_MODULATE);
    j2d_glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
}

void
OGLVertexCache_DisableMaskCache(OGLContext *oglc)
{
    J2dTraceLn(J2D_TRACE_INFO, "OGLVertexCache_DisableMaskCache");

    OGLVertexCache_FlushVertexCache();
    OGLVertexCache_RestoreColorState(oglc);

    j2d_glDisable(GL_TEXTURE_2D);
    j2d_glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    j2d_glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
    j2d_glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
    j2d_glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);

    maskCacheIndex = 0;
}

void
OGLVertexCache_AddMaskQuad(OGLContext *oglc,
                           jint srcx, jint srcy,
                           jint dstx, jint dsty,
                           jint width, jint height,
                           jint maskscan, void *mask)
{
    jfloat tx1, ty1, tx2, ty2;
    jfloat dx1, dy1, dx2, dy2;

    J2dTraceLn1(J2D_TRACE_INFO, "OGLVertexCache_AddMaskQuad: %d",
                maskCacheIndex);

    if (maskCacheIndex >= OGLVC_MASK_CACHE_MAX_INDEX ||
        vertexCacheIndex >= OGLVC_MAX_INDEX)
    {
        OGLVertexCache_FlushVertexCache();
        maskCacheIndex = 0;
    }

    if (mask != NULL) {
        jint texx = OGLVC_MASK_CACHE_TILE_WIDTH *
            (maskCacheIndex % OGLVC_MASK_CACHE_WIDTH_IN_TILES);
        jint texy = OGLVC_MASK_CACHE_TILE_HEIGHT *
            (maskCacheIndex / OGLVC_MASK_CACHE_WIDTH_IN_TILES);

        // update the source pointer offsets
        j2d_glPixelStorei(GL_UNPACK_SKIP_PIXELS, srcx);
        j2d_glPixelStorei(GL_UNPACK_SKIP_ROWS, srcy);
        j2d_glPixelStorei(GL_UNPACK_ROW_LENGTH, maskscan);

        // copy alpha mask into texture tile
        j2d_glTexSubImage2D(GL_TEXTURE_2D, 0,
                            texx, texy, width, height,
                            GL_LUMINANCE, GL_UNSIGNED_BYTE, mask);

        tx1 = ((jfloat)texx) / OGLVC_MASK_CACHE_WIDTH_IN_TEXELS;
        ty1 = ((jfloat)texy) / OGLVC_MASK_CACHE_HEIGHT_IN_TEXELS;

        maskCacheIndex++;
    } else {
        // use special fully opaque tile
        tx1 = ((jfloat)OGLVC_MASK_CACHE_SPECIAL_TILE_X) /
            OGLVC_MASK_CACHE_WIDTH_IN_TEXELS;
        ty1 = ((jfloat)OGLVC_MASK_CACHE_SPECIAL_TILE_Y) /
            OGLVC_MASK_CACHE_HEIGHT_IN_TEXELS;
    }

    tx2 = tx1 + (((jfloat)width) / OGLVC_MASK_CACHE_WIDTH_IN_TEXELS);
    ty2 = ty1 + (((jfloat)height) / OGLVC_MASK_CACHE_HEIGHT_IN_TEXELS);

    dx1 = (jfloat)dstx;
    dy1 = (jfloat)dsty;
    dx2 = dx1 + width;
    dy2 = dy1 + height;

    OGLVC_ADD_QUAD(tx1, ty1, tx2, ty2,
                   dx1, dy1, dx2, dy2,
                   oglc->r, oglc->g, oglc->b, oglc->a);
}

void
OGLVertexCache_AddGlyphQuad(OGLContext *oglc,
                            jfloat tx1, jfloat ty1, jfloat tx2, jfloat ty2,
                            jfloat dx1, jfloat dy1, jfloat dx2, jfloat dy2)
{
    J2dTraceLn(J2D_TRACE_INFO, "OGLVertexCache_AddGlyphQuad");

    if (vertexCacheIndex >= OGLVC_MAX_INDEX) {
        OGLVertexCache_FlushVertexCache();
    }

    OGLVC_ADD_QUAD(tx1, ty1, tx2, ty2,
                   dx1, dy1, dx2, dy2,
                   oglc->r, oglc->g, oglc->b, oglc->a);
}

jboolean OGLMTVertexCache_enable(OGLContext *oglc, jboolean useTxtBarrier) {
     mtUseTxtBarrier = useTxtBarrier;
    if (mtVertexCache == NULL) {
        mtVertexCache = (J2DMTVertex *)malloc(OGLMTVC_MAX_INDEX * sizeof(J2DMTVertex));
        if (mtVertexCache == NULL) {
            return JNI_FALSE;
        }
    }

    if (!mtVertexCacheEnabled) {
        oglc->vertexCacheEnabled = JNI_FALSE;

        j2d_glVertexPointer(2, GL_FLOAT, sizeof(J2DMTVertex), &mtVertexCache[0].dx);
        j2d_glEnableClientState(GL_VERTEX_ARRAY);
        j2d_glClientActiveTexture(GL_TEXTURE1_ARB);
        j2d_glTexCoordPointer(2, GL_FLOAT, sizeof(J2DMTVertex), &mtVertexCache[0].tx1);
        j2d_glEnableClientState(GL_TEXTURE_COORD_ARRAY);
        j2d_glClientActiveTexture(GL_TEXTURE0_ARB);
        j2d_glTexCoordPointer(2, GL_FLOAT, sizeof(J2DMTVertex), &mtVertexCache[0].tx0);
        j2d_glEnableClientState(GL_TEXTURE_COORD_ARRAY);
        mtVertexCacheEnabled = JNI_TRUE;
        evenLCDGlyphInd = 0;
        oddLCDGlyphInd = ODD_LCD_GLYPHS_OFFSET;
        lcdGlyphInd = 0;
    }

    return JNI_TRUE;
}
void OGLMTVertexCache_disable() {
    if (mtVertexCacheEnabled) {
        OGLMTVertexCache_flush(OGLMTVC_FLUSH_ALL);
        mtVertexCacheEnabled = JNI_FALSE;
    }
}

void OGLMTVertexCache_flush(jint mask) {
    if (mtVertexCacheEnabled) {
        if ((mask & OGLMTVC_FLUSH_EVEN) && evenLCDGlyphInd > 0) {
            if (mtUseTxtBarrier) {
                // TextureBarrierNV() will guarantee that writes have completed
                // and caches have been invalidated before subsequent Draws are
                // executed
                j2d_glTextureBarrierNV();
                evenOx2 = FLT_MIN;
            }
            j2d_glDrawArrays(GL_QUADS, 0, evenLCDGlyphInd);
            evenLCDGlyphInd = 0;
        }

        if ((mask & OGLMTVC_FLUSH_ODD) && oddLCDGlyphInd > ODD_LCD_GLYPHS_OFFSET) {
            if (mtUseTxtBarrier) {
                // See the comment above
                j2d_glTextureBarrierNV();
                oddOx2 = FLT_MIN;
            }
            j2d_glDrawArrays(GL_QUADS, ODD_LCD_GLYPHS_OFFSET,
                             oddLCDGlyphInd - ODD_LCD_GLYPHS_OFFSET);
            oddLCDGlyphInd = ODD_LCD_GLYPHS_OFFSET;
        }
    }
}

void OGLMTVertexCache_addGlyphQuad(jfloat dx1, jfloat dy1,
                                   jfloat dx2, jfloat dy2,
                                   jfloat tx1, jfloat ty1,
                                   jfloat tx2, jfloat ty2,
                                   jfloat dtx1, jfloat dty1,
                                   jfloat dtx2, jfloat dty2)
{
    jint* ind;
    if (lcdGlyphInd & 0x1) {
        if (oddLCDGlyphInd >= OGLMTVC_MAX_INDEX ||
            (mtUseTxtBarrier && oddOx2 >= dx1))
        {
            OGLMTVertexCache_flush(OGLMTVC_FLUSH_ODD);
        } else if (mtUseTxtBarrier) {
            oddOx2 = dx2;
        }
        ind = &oddLCDGlyphInd;
    } else {
        if (evenLCDGlyphInd >= ODD_LCD_GLYPHS_OFFSET ||
            (mtUseTxtBarrier && evenOx2 >= dx1))
        {
            OGLMTVertexCache_flush(OGLMTVC_FLUSH_EVEN);
        } else if (mtUseTxtBarrier) {
            evenOx2 = dx2;
        }
        ind = &evenLCDGlyphInd;
    }
    lcdGlyphInd++;
    OGLMTVC_ADD_QUAD(*ind, dx1, dy1, dx2, dy2, tx1, ty1, tx2, ty2, dtx1, dty1, dtx2, dty2);
}

#endif /* !HEADLESS */
