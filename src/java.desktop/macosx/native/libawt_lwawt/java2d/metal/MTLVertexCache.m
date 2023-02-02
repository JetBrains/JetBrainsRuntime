/*
 * Copyright (c) 2019, 2025, Oracle and/or its affiliates. All rights reserved.
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
#include <string.h>

#include "sun_java2d_SunGraphics2D.h"

#include "MTLPaints.h"
#include "MTLVertexCache.h"
#include "MTLTextRenderer.h"

typedef struct _J2DVertex {
    float position[2];
    float txtpos[2];
} J2DVertex;

typedef struct MaskFillColor sMaskFillColor;

static J2DVertex *vertexCache = NULL;
static jint vertexCacheIndex = 0;

static sMaskFillColor *maskColorCache = NULL;
static jint maskColorCacheIndex = 0;

static MTLPooledTextureHandle *maskCacheTex = nil;
static jint maskCacheIndex = 0;
static jint maskCacheLastIndex = MTLVC_MASK_CACHE_MAX_INDEX;
static id<MTLRenderCommandEncoder> encoder = nil;

#define MTLVC_ADD_VERTEX(TX, TY, DX, DY)                 \
    do {                                                 \
        J2DVertex *v = &vertexCache[vertexCacheIndex++]; \
        v->txtpos[0]   = TX;                             \
        v->txtpos[1]   = TY;                             \
        v->position[0] = DX;                             \
        v->position[1] = DY;                             \
    } while (0)

/*
 *  1 quad = 2 triangles, 6 vertices
 */
#define MTLVC_ADD_TRIANGLES(TX1, TY1, TX2, TY2, DX1, DY1, DX2, DY2) \
    do {                                                            \
            MTLVC_ADD_VERTEX(TX1, TY1, DX1, DY1);                   \
            MTLVC_ADD_VERTEX(TX2, TY1, DX2, DY1);                   \
            MTLVC_ADD_VERTEX(TX2, TY2, DX2, DY2);                   \
            MTLVC_ADD_VERTEX(TX2, TY2, DX2, DY2);                   \
            MTLVC_ADD_VERTEX(TX1, TY2, DX1, DY2);                   \
            MTLVC_ADD_VERTEX(TX1, TY1, DX1, DY1);                   \
    } while (0)

#define RGBA_COMP   4

#define MTLVC_COPY_COLOR(DST, COL) \
    memcpy(DST, COL, RGBA_COMP);

#define MTLVC_ADD_QUAD_COLOR(COL)                                    \
    do {                                                             \
        sMaskFillColor *uf = &maskColorCache[maskColorCacheIndex++]; \
        MTLVC_COPY_COLOR(uf->color, COL);                            \
    } while (0)

#define RGBA_TO_U4(c)                       \
{                                           \
    (unsigned char) (((c) >> 16) & 0xFF),   \
    (unsigned char) (((c) >> 8) & 0xFF),    \
    (unsigned char) ((c) & 0xFF),           \
    (unsigned char) (((c) >> 24) & 0xFF)    \
}

jboolean
MTLVertexCache_InitVertexCache()
{
    J2dTraceLn(J2D_TRACE_INFO, "MTLVertexCache_InitVertexCache");

    if (vertexCache == NULL) {
        J2dTraceLn(J2D_TRACE_INFO, "MTLVertexCache_InitVertexCache : vertexCache == NULL");

        size_t len = sizeof(J2DVertex);

        if (len != MTLVC_SIZE_J2DVertex) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLVertexCache_InitVertexCache : sizeof(J2DVertex) = %lu != %d bytes !",
                          len, MTLVC_SIZE_J2DVertex);
            return JNI_FALSE;
        }

        len *= MTLVC_MAX_INDEX;

        if (len > MTLVC_MAX_VERTEX_SIZE)  {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLVertexCache_InitVertexCache : J2DVertex buffer size = %lu > %d bytes !",
                          len, MTLVC_MAX_VERTEX_SIZE);
            return JNI_FALSE;
        }
        vertexCache = (J2DVertex *)malloc(len);
        if (vertexCache == NULL) {
            return JNI_FALSE;
        }
    }
    if (maskColorCache == NULL) {
        J2dTraceLn(J2D_TRACE_INFO, "MTLVertexCache_InitVertexCache : maskColorCache == NULL");
        size_t len = MTLVC_MAX_QUAD * sizeof(sMaskFillColor);

        if (len > MTLVC_MAX_VERTEX_SIZE)  {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLVertexCache_InitVertexCache : MaskFillColor buffer size = %lu > %d bytes !",
                          len, MTLVC_MAX_VERTEX_SIZE);
            return JNI_FALSE;
        }
        maskColorCache = (sMaskFillColor *)malloc(len);
        if (maskColorCache == NULL) {
            return JNI_FALSE;
        }
    }
    return JNI_TRUE;
}

void
MTLVertexCache_FlushVertexCache(MTLContext *mtlc)
{
    J2dTraceLn(J2D_TRACE_INFO, "MTLVertexCache_FlushVertexCache");

    if (vertexCacheIndex > 0) {
        [encoder setVertexBytes:vertexCache
                         length:vertexCacheIndex * MTLVC_SIZE_J2DVertex
                        atIndex:MeshVertexBuffer];

        if (maskColorCacheIndex > 0) {
            [encoder setVertexBytes:maskColorCache
                             length:maskColorCacheIndex * sizeof(sMaskFillColor)
                            atIndex:MaskColorBuffer];
        }
        [encoder setFragmentTexture:maskCacheTex.texture atIndex:0];

        J2dTraceLn(J2D_TRACE_INFO,
                   "MTLVertexCache_FlushVertexCache : encode %d tiles", (vertexCacheIndex / VERTS_FOR_A_QUAD));

        [encoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:vertexCacheIndex];

        vertexCacheIndex = 0;
        maskColorCacheIndex = 0;
        maskCacheIndex = 0;
        maskCacheLastIndex = MTLVC_MASK_CACHE_MAX_INDEX;
    }

    // register texture to be released once encoder is completed:
    if (maskCacheTex != nil) {
        [[mtlc getCommandBufferWrapper] registerPooledTexture:maskCacheTex];
        [maskCacheTex release];
        maskCacheTex = nil;
    }
}

void
MTLVertexCache_FlushGlyphVertexCache(MTLContext *mtlc)
{
    J2dTraceLn(J2D_TRACE_INFO, "MTLVertexCache_FlushGlyphVertexCache");

    if (vertexCacheIndex > 0 && mtlc.glyphCacheAA.cacheInfo != NULL) {
        id<MTLRenderCommandEncoder> gcEncoder = mtlc.glyphCacheAA.cacheInfo->encoder;
        [gcEncoder setVertexBytes:vertexCache
                           length:vertexCacheIndex * MTLVC_SIZE_J2DVertex
                          atIndex:MeshVertexBuffer];

        id<MTLTexture> glyphCacheTex = mtlc.glyphCacheAA.cacheInfo->texture;
        [gcEncoder setFragmentTexture:glyphCacheTex atIndex: 0];

        J2dTraceLn(J2D_TRACE_INFO,
                   "MTLVertexCache_FlushGlyphVertexCache : encode %d characters", (vertexCacheIndex / VERTS_FOR_A_QUAD));

        [gcEncoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:vertexCacheIndex];
    }
    vertexCacheIndex = 0;
}

void MTLVertexCache_FreeVertexCache()
{
    if (vertexCache != NULL) {
        free(vertexCache);
        vertexCache = NULL;
    }
    if (maskColorCache != NULL) {
        free(maskColorCache);
        maskColorCache = NULL;
    }
}

static void MTLVertexCache_InitFullTile()
{
    if (maskCacheLastIndex == MTLVC_MASK_CACHE_MAX_INDEX) {
        // init special fully opaque tile in the upper-right corner of
        // the mask cache texture(only 1x1 pixel used)
        static char tile[1];
        static char* pTile = NULL;
        if (!pTile) {
            tile[0] = (char)0xFF;
            pTile = tile;
        }

        jint texx = MTLVC_MASK_CACHE_TILE_WIDTH * (MTLVC_MASK_CACHE_WIDTH_IN_TILES - 1);
        jint texy = MTLVC_MASK_CACHE_TILE_HEIGHT * (MTLVC_MASK_CACHE_HEIGHT_IN_TILES - 1);

        MTLRegion region = {
                {texx,  texy,   0},
                {1, 1, 1}
        };

        [maskCacheTex.texture replaceRegion:region
                                mipmapLevel:0
                                  withBytes:tile
                                bytesPerRow:1];

        maskCacheLastIndex = MTLVC_MASK_CACHE_MAX_INDEX_RESERVED;
    }
}

static jboolean MTLVertexCache_InitMaskCache(MTLContext *mtlc)
{
    J2dTraceLn(J2D_TRACE_INFO, "MTLVertexCache_InitMaskCache");

    if (maskCacheTex == nil) {
        maskCacheTex = [mtlc.texturePool getTexture:MTLVC_MASK_CACHE_WIDTH_IN_TEXELS
                                             height:MTLVC_MASK_CACHE_HEIGHT_IN_TEXELS
                                             format:MTLPixelFormatA8Unorm];
        [maskCacheTex retain];
        if (maskCacheTex == nil) {
            J2dTraceLn(J2D_TRACE_ERROR, "MTLVertexCache_InitMaskCache: can't obtain temporary texture object from pool");
            return JNI_FALSE;
        }
    }
    return JNI_TRUE;
}

void
MTLVertexCache_EnableMaskCache(MTLContext *mtlc, BMTLSDOps *dstOps)
{
    J2dTraceLn(J2D_TRACE_INFO, "MTLVertexCache_EnableMaskCache");

    if ([mtlc useMaskColor]
        && ![mtlc.paint isKindOfClass:[MTLColorPaint class]]) {
        // Reset useMaskColor flag for advanced paints:
        [mtlc setUseMaskColor: JNI_FALSE];
    }
    if (vertexCache == NULL) {
        if (!MTLVertexCache_InitVertexCache()) {
            return;
        }
    }
    if (maskCacheTex == nil) {
        if (!MTLVertexCache_InitMaskCache(mtlc)) {
            return;
        }
    }
    MTLVertexCache_CreateSamplingEncoder(mtlc, dstOps, NO);
}

void
MTLVertexCache_DisableMaskCache(MTLContext *mtlc)
{
    J2dTraceLn(J2D_TRACE_INFO, "MTLVertexCache_DisableMaskCache");
    MTLVertexCache_FlushVertexCache(mtlc);
    MTLVertexCache_FreeVertexCache();
}

void
MTLVertexCache_CreateSamplingEncoder(MTLContext *mtlc, BMTLSDOps *dstOps, bool gmc) {
    J2dTraceLn(J2D_TRACE_INFO, "MTLVertexCache_CreateSamplingEncoder");
    encoder = [mtlc.encoderManager getTextEncoder:dstOps
                                      isSrcOpaque:NO
                                  gammaCorrection:gmc];
}

void
MTLVertexCache_AddMaskQuad(MTLContext *mtlc,
                           jint srcx, jint srcy,
                           jint dstx, jint dsty,
                           jint width, jint height,
                           jint maskscan, void *mask,
                           BMTLSDOps *dstOps)
{
    jfloat tx1, ty1, tx2, ty2;
    jfloat dx1, dy1, dx2, dy2;

    J2dTraceLn(J2D_TRACE_INFO, "MTLVertexCache_AddMaskQuad: %d",
               maskCacheIndex);

    // MTLVC_ADD_TRIANGLES at the end of this function
    // will place VERTS_FOR_A_QUAD vertexes to the vertex cache
    // check free space and flush if needed.
    if ((maskCacheIndex >= maskCacheLastIndex) ||
         ((vertexCacheIndex + VERTS_FOR_A_QUAD) >= MTLVC_MAX_INDEX))
    {
        J2dTraceLn(J2D_TRACE_INFO, "maskCacheIndex = %d, vertexCacheIndex = %d",
                   maskCacheIndex, vertexCacheIndex);
        MTLVertexCache_FlushVertexCache(mtlc);

        // Initialize texture & encoder again
        // as MTLVertexCache_FlushVertexCache cleared texture
        MTLVertexCache_EnableMaskCache(mtlc, dstOps);
    }

    static jint nextFullDx = -1;
    static jint nextFullDy = -1;
    bool mergeTile = NO;

    if (mask != NULL) {
        jint texx = MTLVC_MASK_CACHE_TILE_WIDTH *
                    (maskCacheIndex % MTLVC_MASK_CACHE_WIDTH_IN_TILES);
        jint texy = MTLVC_MASK_CACHE_TILE_HEIGHT *
                    (maskCacheIndex / MTLVC_MASK_CACHE_WIDTH_IN_TILES);
        J2dTraceLn(J2D_TRACE_INFO, "texx = %d texy = %d width = %d height = %d maskscan = %d", texx, texy, width,
                   height, maskscan);
        NSUInteger bytesPerRow = width;
        MTLRegion region = {
                {texx,  texy,   0},
                {width, height, 1}
        };

        // Whenever we have source stride bigger that destination stride
        // we need to pick appropriate source subtexture. In repalceRegion
        // we can give destination subtexturing properly but we can't
        // subtexture from system memory glyph we have. So in such
        // cases we are creating a separate tile and scan the source
        // stride into destination using memcpy. In case of OpenGL we
        // can update source pointers, in case of D3D we are doing memcpy.
        // We can use MTLBuffer and then copy source subtexture but that
        // adds extra blitting logic.
        // TODO : Research more and try removing memcpy logic.
        if (maskscan <= width) {
            NSUInteger height_offset = bytesPerRow * srcy;
            [maskCacheTex.texture replaceRegion:region
                            mipmapLevel:0
                              withBytes:mask + height_offset
                            bytesPerRow:bytesPerRow];
        } else {
            int dst_offset, src_offset;
            int size = width * height;
            char tile[size];
            dst_offset = 0;
            for (int i = srcy; i < srcy + height; i++) {
                J2dTraceLn(J2D_TRACE_INFO, "srcx = %d srcy = %d", srcx, srcy);
                src_offset = maskscan * i + srcx;
                J2dTraceLn(J2D_TRACE_INFO, "src_offset = %d dst_offset = %d", src_offset, dst_offset);
                memcpy(tile + dst_offset, mask + src_offset, width);
                dst_offset = dst_offset + width;
            }
            [maskCacheTex.texture replaceRegion:region
                            mipmapLevel:0
                              withBytes:tile
                            bytesPerRow:bytesPerRow];
        }

        tx1 = ((jfloat) texx) / MTLVC_MASK_CACHE_WIDTH_IN_TEXELS;
        ty1 = ((jfloat) texy) / MTLVC_MASK_CACHE_HEIGHT_IN_TEXELS;

        tx2 = tx1 + (((jfloat)width) / MTLVC_MASK_CACHE_WIDTH_IN_TEXELS);
        ty2 = ty1 + (((jfloat)height) / MTLVC_MASK_CACHE_HEIGHT_IN_TEXELS);

        // count added masks:
        maskCacheIndex++;

        // reset next Full-tile coords:
        nextFullDx = -1;
    } else {
        if (maskCacheLastIndex == MTLVC_MASK_CACHE_MAX_INDEX) {
            MTLVertexCache_InitFullTile();
        }
        // const: texture coords of full tile:
        tx1 = (((jfloat)MTLVC_MASK_CACHE_SPECIAL_TILE_X) /
              MTLVC_MASK_CACHE_WIDTH_IN_TEXELS);
        ty1 = (((jfloat)MTLVC_MASK_CACHE_SPECIAL_TILE_Y) /
              MTLVC_MASK_CACHE_HEIGHT_IN_TEXELS);

        // const: use only 1x1 pixel:
        tx2 = tx1;
        ty2 = ty1;

        if ((dstx == nextFullDx) && (dsty == nextFullDy)
            && (vertexCacheIndex > 0))
        {
            mergeTile = YES;
        }

        // store next Full-tile coords:
        nextFullDx = dstx + width;
        nextFullDy = dsty;
    }

    dx1 = (jfloat)dstx;
    dy1 = (jfloat)dsty;
    dx2 = dx1 + (jfloat)width;
    dy2 = dy1 + (jfloat)height;

    if ([mtlc useMaskColor]) {
        // ColorPaint class is already checked in MTLVertexCache_EnableMaskCache:
        MTLColorPaint* cPaint = (MTLColorPaint *) mtlc.paint;
        jint color = cPaint.color;

        static jint lastColor = 0;
        static unsigned char last_uColor[RGBA_COMP] = {0, 0, 0, 0};

        if (color != lastColor) {
            lastColor = color;
            unsigned char uColor[RGBA_COMP] = RGBA_TO_U4(color);
            MTLVC_COPY_COLOR(last_uColor, uColor);
            // color changed, cannot merge tile:
            mergeTile = NO;
        }
        if (!mergeTile) {
            // add only 1 color per quad:
            MTLVC_ADD_QUAD_COLOR(last_uColor);
        }
    }

    if (mergeTile) {
        // Color, Gradient & Texture paints support tile-merging:
        // Fix dx2 coordinate in previous triangles:
        const jint prevIndex = vertexCacheIndex - VERTS_FOR_A_QUAD; // already tested > 0
        // skip vertex 1 (dx1, dy1)
        // fix  vertex 2 (dx2, dy1)
        J2DVertex *v = &vertexCache[prevIndex + 1];
        v->position[0] = dx2;
        // fix  vertex 3 (dx2, dy2)
        v = &vertexCache[prevIndex + 2];
        v->position[0] = dx2;
        // fix  vertex 4 (dx2, dy2)
        v = &vertexCache[prevIndex + 3];
        v->position[0] = dx2;
        // skip vertex 5 (dx1, dy2)
        // skip vertex 6 (dx1, dy1)
    } else {
        J2dTraceLn(J2D_TRACE_INFO, "tx1 = %f ty1 = %f tx2 = %f ty2 = %f dx1 = %f dy1 = %f dx2 = %f dy2 = %f", tx1, ty1, tx2, ty2, dx1, dy1, dx2, dy2);
        MTLVC_ADD_TRIANGLES(tx1, ty1, tx2, ty2, dx1, dy1, dx2, dy2);
    }
}

void
MTLVertexCache_AddGlyphQuad(MTLContext *mtlc,
                            jfloat tx1, jfloat ty1, jfloat tx2, jfloat ty2,
                            jfloat dx1, jfloat dy1, jfloat dx2, jfloat dy2)
{
    J2dTraceLn(J2D_TRACE_INFO, "MTLVertexCache_AddGlyphQuad");

    // MTLVC_ADD_TRIANGLES adds VERTS_FOR_A_QUAD vertexes into Cache
    // so need to check space for VERTS_FOR_A_QUAD elements
    if ((vertexCacheIndex + VERTS_FOR_A_QUAD) >= MTLVC_MAX_INDEX)
    {
        J2dTraceLn(J2D_TRACE_INFO, "maskCacheIndex = %d, vertexCacheIndex = %d", maskCacheIndex, vertexCacheIndex);
        MTLVertexCache_FlushGlyphVertexCache(mtlc);
    }

    MTLVC_ADD_TRIANGLES(tx1, ty1, tx2, ty2, dx1, dy1, dx2, dy2);
}
