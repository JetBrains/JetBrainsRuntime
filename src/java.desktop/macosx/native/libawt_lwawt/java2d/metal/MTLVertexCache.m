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
#include "common.h"

typedef struct _J2DVertex {
    float position[3];
    float txtpos[2];
} J2DVertex;

static J2DVertex *vertexCache = NULL;
static jint vertexCacheIndex = 0;

static id<MTLTexture> maskCacheTex = NULL;
static jint maskCacheIndex = 0;
static id<MTLRenderCommandEncoder> encoder = NULL;

#define MTLVC_ADD_VERTEX(TX, TY, DX, DY, DZ) \
    do { \
        J2DVertex *v = &vertexCache[vertexCacheIndex++]; \
        v->txtpos[0] = TX; \
        v->txtpos[1] = TY; \
        v->position[0]= DX; \
        v->position[1] = DY; \
        v->position[2] = DZ; \
    } while (0)

#define MTLVC_ADD_TRIANGLES(TX1, TY1, TX2, TY2, DX1, DY1, DX2, DY2) \
    do { \
        MTLVC_ADD_VERTEX(TX1, TY1, DX1, DY1, 0); \
        MTLVC_ADD_VERTEX(TX2, TY1, DX2, DY1, 0); \
        MTLVC_ADD_VERTEX(TX2, TY2, DX2, DY2, 0); \
        MTLVC_ADD_VERTEX(TX2, TY2, DX2, DY2, 0); \
        MTLVC_ADD_VERTEX(TX1, TY2, DX1, DY2, 0); \
        MTLVC_ADD_VERTEX(TX1, TY1, DX1, DY1, 0); \
    } while (0)

jboolean
MTLVertexCache_InitVertexCache()
{
    J2dTraceLn(J2D_TRACE_INFO, "MTLVertexCache_InitVertexCache");

    if (vertexCache == NULL) {
        J2dTraceLn(J2D_TRACE_INFO, "MTLVertexCache_InitVertexCache : vertexCache == NULL");
        vertexCache = (J2DVertex *)malloc(MTLVC_MAX_INDEX * sizeof(J2DVertex));
        if (vertexCache == NULL) {
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
        id<MTLBuffer>vertexBuffer = [mtlc.device newBufferWithBytes:vertexCache
                                                 length:vertexCacheIndex * sizeof(J2DVertex)
                                                 options:MTLResourceOptionCPUCacheModeDefault];
        [encoder setVertexBuffer:vertexBuffer offset:0 atIndex:MeshVertexBuffer];
        [encoder setFragmentTexture:maskCacheTex atIndex: 0];
        for (int i = 0; i < maskCacheIndex; i++) {
            J2dTraceLn1(J2D_TRACE_INFO, "MTLVertexCache_FlushVertexCache : draw texture at index %d", i);
            [encoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:i*6 vertexCount:6];
        }
        [encoder endEncoding];
        encoder = nil;
        vertexBuffer = nil;
    }
    vertexCacheIndex = 0;
    maskCacheIndex = 0;
    maskCacheTex = nil;
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
    if (mtlc.paintState == sun_java2d_SunGraphics2D_PAINT_ALPHACOLOR) {
        [mtlc setColor:mtlc.pixel];
    }
}

static jboolean
MTLVertexCache_InitMaskCache(MTLContext *mtlc)
{
    J2dTraceLn(J2D_TRACE_INFO, "MTLVertexCache_InitMaskCache");
    // TODO : We are creating mask cache only of type MTLPixelFormatA8Unorm
    // when we need more than 1 byte to store a pixel(LCD) we need to update
    // below code.
    MTLTextureDescriptor *textureDescriptor =
        [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatA8Unorm
                                                           width:MTLVC_MASK_CACHE_WIDTH_IN_TEXELS
                                                          height:MTLVC_MASK_CACHE_HEIGHT_IN_TEXELS
                                                       mipmapped:NO];
    maskCacheTex = [mtlc.device newTextureWithDescriptor:textureDescriptor];
    return JNI_TRUE;
}

void
MTLVertexCache_EnableMaskCache(MTLContext *mtlc, BMTLSDOps *dstOps)
{
    J2dTraceLn(J2D_TRACE_INFO, "MTLVertexCache_EnableMaskCache");

    if (!MTLVertexCache_InitVertexCache()) {
        return;
    }

    if (maskCacheTex == NULL) {
        if (!MTLVertexCache_InitMaskCache(mtlc)) {
            return;
        }
    }
    MTLVertexCache_CreateSamplingEncoder(mtlc, dstOps);
}

void
MTLVertexCache_DisableMaskCache(MTLContext *mtlc)
{
    // TODO : Once we enable check_previous_op
    // we will start using DisableMaskCache until then
    // we are force flusging vertexcache.
    J2dTraceLn(J2D_TRACE_INFO, "MTLVertexCache_DisableMaskCache");
    MTLVertexCache_FlushVertexCache(mtlc);
    MTLVertexCache_RestoreColorState(mtlc);

    maskCacheTex = nil;
    encoder = nil;
    maskCacheIndex = 0;
    free(vertexCache);
    vertexCache = NULL;
}

void
MTLVertexCache_CreateSamplingEncoder(MTLContext *mtlc, BMTLSDOps *dstOps) {
    J2dTraceLn(J2D_TRACE_INFO, "MTLVertexCache_CreateSamplingEncoder");
    encoder = [mtlc createSamplingEncoderForDest:dstOps->pTexture];
}

void
MTLVertexCache_AddMaskQuad(MTLContext *mtlc,
                           jint srcx, jint srcy,
                           jint dstx, jint dsty,
                           jint width, jint height,
                           jint maskscan, void *mask,
                           BMTLSDOps *dstOps,
                           jint fullwidth)
{
    jfloat tx1, ty1, tx2, ty2;
    jfloat dx1, dy1, dx2, dy2;

    J2dTraceLn1(J2D_TRACE_INFO, "MTLVertexCache_AddMaskQuad: %d",
                maskCacheIndex);

    if (maskCacheIndex >= MTLVC_MASK_CACHE_MAX_INDEX ||
        vertexCacheIndex >= MTLVC_MAX_INDEX)
    {
        J2dTraceLn2(J2D_TRACE_INFO, "maskCacheIndex = %d, vertexCacheIndex = %d", maskCacheIndex, vertexCacheIndex);
        MTLVertexCache_FlushVertexCache(mtlc);
        maskCacheTex = nil;
        // TODO : Since we are not committing command buffer
        // in FlushVertexCache we need to create new maskcache
        // after present cache is full. Check whether we can
        // avoid multiple cache creation.
        MTLVertexCache_EnableMaskCache(mtlc, dstOps);
        maskCacheIndex = 0;
    }

    // TODO : Implement mask == null use case also
    jint texx = MTLVC_MASK_CACHE_TILE_WIDTH *
        (maskCacheIndex % MTLVC_MASK_CACHE_WIDTH_IN_TILES);
    jint texy = MTLVC_MASK_CACHE_TILE_HEIGHT *
        (maskCacheIndex / MTLVC_MASK_CACHE_WIDTH_IN_TILES);
    J2dTraceLn5(J2D_TRACE_INFO, "texx = %d texy = %d width = %d height = %d fullwidth = %d", texx, texy, width, height, fullwidth);
    NSUInteger bytesPerRow = 1 * width;
    NSUInteger slice = bytesPerRow * srcy + srcx;
    MTLRegion region = {
        {texx, texy, 0 },
        {width, height, 1}
    };

    // Whenever we have source stride bigger that destination stride
    // we need to pick appropriate source subtexture. In repalceRegion
    // we can give destination subtexturing properly but we can't
    // subtexture from system memory glyph we have. So in such
    // cases we are creating seperate tile and scan the source
    // stride into destination using memcpy. In case of OpenGL we
    // can update source pointers, in case of D3D we ar doing memcpy.
    // We can use MTLBuffer and then copy source subtexture but that
    // adds extra blitting logic.
    // TODO : Research more and try removing memcpy logic.
    if (fullwidth <= width) {
        int height_offset = bytesPerRow * srcy;
        [maskCacheTex replaceRegion:region
                      mipmapLevel:0
                      withBytes:mask + height_offset
                      bytesPerRow:bytesPerRow];
    } else {
        int dst_offset, src_offset;
        int size = 1 * width * height;
        void* tile = malloc(size);
        dst_offset = 0;
        for (int i = srcy ; i < srcy + height; i++) {
            J2dTraceLn2(J2D_TRACE_INFO, "srcx = %d srcy = %d", srcx, srcy);
            src_offset = fullwidth * i + srcx;
            J2dTraceLn2(J2D_TRACE_INFO, "src_offset = %d dst_offset = %d", src_offset, dst_offset);
            memcpy(tile + dst_offset, mask + src_offset, width);
            dst_offset = dst_offset + width;
        }
        [maskCacheTex replaceRegion:region
                      mipmapLevel:0
                      withBytes:tile
                      bytesPerRow:bytesPerRow];
        free(tile);
    }

    tx1 = ((jfloat)texx) / MTLVC_MASK_CACHE_WIDTH_IN_TEXELS;
    ty1 = ((jfloat)texy) / MTLVC_MASK_CACHE_HEIGHT_IN_TEXELS;

    maskCacheIndex++;

    tx2 = tx1 + (((jfloat)width) / MTLVC_MASK_CACHE_WIDTH_IN_TEXELS);
    ty2 = ty1 + (((jfloat)height) / MTLVC_MASK_CACHE_HEIGHT_IN_TEXELS);

    dx1 = (jfloat)dstx;
    dy1 = (jfloat)dsty;
    dx2 = dx1 + width;
    dy2 = dy1 + height;

    J2dTraceLn8(J2D_TRACE_INFO, "tx1 = %f ty1 = %f tx2 = %f ty2 = %f dx1 = %f dy1 = %f dx2 = %f dy2 = %f", tx1, ty1, tx2, ty2, dx1, dy1, dx2, dy2);
    MTLVC_ADD_TRIANGLES(tx1, ty1, tx2, ty2,
                        dx1, dy1, dx2, dy2);
}

#endif /* !HEADLESS */
