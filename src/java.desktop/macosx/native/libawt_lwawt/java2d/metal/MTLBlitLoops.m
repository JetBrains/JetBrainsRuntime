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

#include <jni.h>
#include <jlong.h>

#include "SurfaceData.h"
#include "MTLBlitLoops.h"
#include "MTLRenderQueue.h"
#include "MTLSurfaceData.h"
#include "MTLUtils.h"
#include "GraphicsPrimitiveMgr.h"

#include <stdlib.h> // malloc
#include <string.h> // memcpy
#include "IntArgbPre.h"

extern MTLPixelFormat PixelFormats[];

void _fillTxQuad(
        struct TxtVertex * txQuadVerts,
        jint sx1, jint sy1, jint sx2, jint sy2, jint sw, jint sh,
        jdouble dx1, jdouble dy1, jdouble dx2, jdouble dy2
) {
    const float nsx1 = sx1/(float)sw;
    const float nsy1 = sy1/(float)sh;
    const float nsx2 = sx2/(float)sw;
    const float nsy2 = sy2/(float)sh;

    txQuadVerts[0].position[0] = dx1;
    txQuadVerts[0].position[1] = dy1;
    txQuadVerts[0].position[2] = 0;
    txQuadVerts[0].txtpos[0]   = nsx1;
    txQuadVerts[0].txtpos[1]   = nsy1;

    txQuadVerts[1].position[0] = dx2;
    txQuadVerts[1].position[1] = dy1;
    txQuadVerts[1].position[2] = 0;
    txQuadVerts[1].txtpos[0]   = nsx2;
    txQuadVerts[1].txtpos[1]   = nsy1;

    txQuadVerts[2].position[0] = dx2;
    txQuadVerts[2].position[1] = dy2;
    txQuadVerts[2].position[2] = 0;
    txQuadVerts[2].txtpos[0]   = nsx2;
    txQuadVerts[2].txtpos[1]   = nsy2;

    txQuadVerts[3].position[0] = dx2;
    txQuadVerts[3].position[1] = dy2;
    txQuadVerts[3].position[2] = 0;
    txQuadVerts[3].txtpos[0]   = nsx2;
    txQuadVerts[3].txtpos[1]   = nsy2;

    txQuadVerts[4].position[0] = dx1;
    txQuadVerts[4].position[1] = dy2;
    txQuadVerts[4].position[2] = 0;
    txQuadVerts[4].txtpos[0]   = nsx1;
    txQuadVerts[4].txtpos[1]   = nsy2;

    txQuadVerts[5].position[0] = dx1;
    txQuadVerts[5].position[1] = dy1;
    txQuadVerts[5].position[2] = 0;
    txQuadVerts[5].txtpos[0]   = nsx1;
    txQuadVerts[5].txtpos[1]   = nsy1;
}

//
// DEBUG funcs, will be removed later
//

void _drawDebugMarkers(MTLContext *ctx, BMTLSDOps *dstOps) {
    J2dTraceLn2(J2D_TRACE_VERBOSE, "draw debug markers onto bdst %p [tex=%p]", dstOps, dstOps->pTexture);
    MTLContext_SetColor(ctx, 0, 0, 255, 255);
    id <MTLRenderCommandEncoder> encoder = MTLContext_CreateRenderEncoder(ctx, dstOps->pTexture);
    struct Vertex vvv[2] = {
            {{MTLUtils_normalizeX(dstOps->pTexture, 2), MTLUtils_normalizeY(dstOps->pTexture, 2), 0.0}},
            {{MTLUtils_normalizeX(dstOps->pTexture, 30), MTLUtils_normalizeY(dstOps->pTexture, 100), 0.0}},
    };
    [encoder setVertexBytes:vvv length:sizeof(vvv) atIndex:MeshVertexBuffer];
    [encoder drawPrimitives:MTLPrimitiveTypeLine vertexStart:0 vertexCount:2];
    [encoder endEncoding];
}

void _traceRaster(SurfaceDataRasInfo *srcInfo, int width, int height) {
    char * p = srcInfo->rasBase;
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            char pix0 = p[y*srcInfo->scanStride + x*4];
            char pix1 = p[y*srcInfo->scanStride + x*4 + 1];
            char pix2 = p[y*srcInfo->scanStride + x*4 + 2];
            char pix3 = p[y*srcInfo->scanStride + x*4 + 3];
            J2dTrace4(J2D_TRACE_INFO, "[%d,%d,%d,%d], ", pix0, pix1, pix2, pix3);
        }
        J2dTraceLn(J2D_TRACE_INFO, "");
    }
}

/**
 * Inner loop used for copying a source MTL "Surface" (window, pbuffer,
 * etc.) to a destination OpenGL "Surface".  Note that the same surface can
 * be used as both the source and destination, as is the case in a copyArea()
 * operation.  This method is invoked from MTLBlitLoops_IsoBlit() as well as
 * MTLBlitLoops_CopyArea().
 *
 * The standard glCopyPixels() mechanism is used to copy the source region
 * into the destination region.  If the regions have different dimensions,
 * the source will be scaled into the destination as appropriate (only
 * nearest neighbor filtering will be applied for simple scale operations).
 */
static void
MTLBlitSurfaceToSurface(MTLContext *mtlc, BMTLSDOps *srcOps, BMTLSDOps *dstOps,
                        jint sx1, jint sy1, jint sx2, jint sy2,
                        jdouble dx1, jdouble dy1, jdouble dx2, jdouble dy2)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLBlitSurfaceToSurface");
}

static void _drawTex2Tex(MTLContext *mtlc,
                        id<MTLTexture> src, id<MTLTexture> dst,
                        jboolean rtt, jint hint,
                        jint sx1, jint sy1, jint sx2, jint sy2,
                        jdouble dx1, jdouble dy1, jdouble dx2, jdouble dy2)
{
    if (mtlc == NULL || src == nil || dst == nil)
        return;

//    J2dTraceLn2(J2D_TRACE_VERBOSE, "_drawTex2Tex: src tex=%p, dst tex=%p", src, dst);
//    J2dTraceLn4(J2D_TRACE_VERBOSE, "  sw=%d sh=%d dw=%d dh=%d", src.width, src.height, dst.width, dst.height);
//    J2dTraceLn4(J2D_TRACE_VERBOSE, "  sx1=%d sy1=%d sx2=%d sy2=%d", sx1, sy1, sx2, sy2);
//    J2dTraceLn4(J2D_TRACE_VERBOSE, "  dx1=%f dy1=%f dx2=%f dy2=%f", dx1, dy1, dx2, dy2);

    id<MTLRenderCommandEncoder> encoder = MTLContext_CreateBlitEncoder(mtlc, dst);
    mtlc->mtlEmptyCommandBuffer = NO;

    struct TxtVertex quadTxVerticesBuffer[6];
    _fillTxQuad(quadTxVerticesBuffer, sx1, sy1, sx2, sy2, src.width, src.height, dx1, dy1, dx2, dy2);

    [encoder setVertexBytes:quadTxVerticesBuffer length:sizeof(quadTxVerticesBuffer) atIndex:MeshVertexBuffer];
    [encoder setFragmentTexture:src atIndex: 0];
    [encoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:6];
    [encoder endEncoding];

    // _drawDebugMarkers(ctx, dst);
}

void MTLBlitTex2Tex(MTLContext *mtlc, id<MTLTexture> src, id<MTLTexture> dest) {
    if (mtlc == NULL || src == nil || dest == nil)
        return;

//    J2dTraceLn2(J2D_TRACE_VERBOSE, "MTLBlitTex2Tex: src tex=%p, dst tex=%p", src, dest);
//    J2dTraceLn4(J2D_TRACE_VERBOSE, "  sw=%d sh=%d dw=%d dh=%d", src.width, src.height, dest.width, dest.height);

    id<MTLRenderCommandEncoder> encoder = MTLContext_CreateBlitEncoder(mtlc, dest);
    mtlc->mtlEmptyCommandBuffer = NO;

    // TODO: use blit encoder and add dimension check
    [encoder setFragmentTexture: src atIndex: 0];
    struct TxtVertex verts[6] = {
            {{-1.0f, 1.0f, 0.0}, {0.0, 0.0}},
            {{1.0f, 1.0f, 0.0}, {1.0, 0.0}},
            {{1.0f, -1.0f, 0.0}, {1.0, 1.0}},
            {{1.0f, -1.0f, 0.0}, {1.0, 1.0}},
            {{-1.0f, -1.0f, 0.0}, {0.0, 1.0}},
            {{-1.0f, 1.0f, 0.0}, {0.0, 0.0}}
    };
    [encoder setVertexBytes:verts length:sizeof(verts) atIndex:MeshVertexBuffer];
    [encoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:6];
    [encoder endEncoding];
}

/**
 * Inner loop used for copying a source MTL "Texture" to a destination
 * MTL "Surface".  This method is invoked from MTLBlitLoops_IsoBlit().
 *
 * This method will copy, scale, or transform the source texture into the
 * destination depending on the transform state, as established in
 * and MTLContext_SetTransform().  If the source texture is
 * transformed in any way when rendered into the destination, the filtering
 * method applied is determined by the hint parameter (can be GL_NEAREST or
 * GL_LINEAR).
 */
static void
MTLBlitTextureToSurface(MTLContext *mtlc,
                        BMTLSDOps *srcOps, BMTLSDOps *dstOps,
                        jboolean rtt, jint hint,
                        jint sx1, jint sy1, jint sx2, jint sy2,
                        jdouble dx1, jdouble dy1, jdouble dx2, jdouble dy2)
{
    id<MTLTexture> srcTex = srcOps->pTexture;

    J2dTraceLn4(J2D_TRACE_VERBOSE, "MTLBlitLoops_IsoBlit [texture->surface]: bsrc=%p [tex=%p], bdst=%p [tex=%p]", srcOps, srcOps->pTexture, dstOps, dstOps->pTexture);
    J2dTraceLn4(J2D_TRACE_VERBOSE, "  sw=%d sh=%d dw=%d dh=%d", srcTex.width, srcTex.height, dstOps->width, dstOps->height);
    J2dTraceLn4(J2D_TRACE_VERBOSE, "  sx1=%d sy1=%d sx2=%d sy2=%d", sx1, sy1, sx2, sy2);
    J2dTraceLn4(J2D_TRACE_VERBOSE, "  dx1=%f dy1=%f dx2=%f dy2=%f", dx1, dy1, dx2, dy2);

    _drawTex2Tex(mtlc, srcOps->pTexture, dstOps->pTexture, rtt, hint, sx1, sy1, sx2, sy2, dx1, dy1, dx2, dy2);
}

/**
 * Inner loop used for copying a source system memory ("Sw") surface to a
 * destination MTL "Surface".  This method is invoked from
 * MTLBlitLoops_Blit().
 *
 * The standard glDrawPixels() mechanism is used to copy the source region
 * into the destination region.  If the regions have different
 * dimensions, the source will be scaled into the destination
 * as appropriate (only nearest neighbor filtering will be applied for simple
 * scale operations).
 */

static void
MTLBlitSwToSurface(MTLContext *ctx, SurfaceDataRasInfo *srcInfo, BMTLSDOps * bmtlsdOps,
                   MTPixelFormat *pf,
                   jint sx1, jint sy1, jint sx2, jint sy2,
                   jdouble dx1, jdouble dy1, jdouble dx2, jdouble dy2)
{
    if (bmtlsdOps == NULL || bmtlsdOps->pTexture == NULL) {
        J2dTraceLn(J2D_TRACE_ERROR, "MTLBlitSwToSurface: dest is null");
        return;
    }

    const int width = sx2 - sx1;
    const int height = sy2 - sy1;

    id<MTLTexture> dest = bmtlsdOps->pTexture;
    J2dTraceLn7(J2D_TRACE_VERBOSE, "MTLBlitSwToSurface [replaceRegion]: bdst=%p [tex=%p], w=%d, h=%d, Sstride=%d, Pstride=%d, offset=%d", bmtlsdOps, dest, width, height, srcInfo->scanStride, srcInfo->pixelStride, srcInfo->pixelBitOffset);

    MTLRegion region = MTLRegionMake2D(0, 0, width, height);
    [dest replaceRegion:region mipmapLevel:0 withBytes:srcInfo->rasBase bytesPerRow:srcInfo->scanStride];
}

/**
 * Inner loop used for copying a source system memory ("Sw") surface or
 * MTL "Surface" to a destination OpenGL "Surface", using an MTL texture
 * tile as an intermediate surface.  This method is invoked from
 * MTLBlitLoops_Blit() for "Sw" surfaces and MTLBlitLoops_IsoBlit() for
 * "Surface" surfaces.
 *
 * This method is used to transform the source surface into the destination.
 * Pixel rectangles cannot be arbitrarily transformed (without the
 * GL_EXT_pixel_transform extension, which is not supported on most modern
 * hardware).  However, texture mapped quads do respect the GL_MODELVIEW
 * transform matrix, so we use textures here to perform the transform
 * operation.  This method uses a tile-based approach in which a small
 * subregion of the source surface is copied into a cached texture tile.  The
 * texture tile is then mapped into the appropriate location in the
 * destination surface.
 *
 * REMIND: this only works well using GL_NEAREST for the filtering mode
 *         (GL_LINEAR causes visible stitching problems between tiles,
 *         but this can be fixed by making use of texture borders)
 */
static void
MTLBlitToSurfaceViaTexture(MTLContext *mtlc, SurfaceDataRasInfo *srcInfo,
                           MTPixelFormat *pf, MTLSDOps *srcOps,
                           jboolean swsurface, jint hint,
                           jint sx1, jint sy1, jint sx2, jint sy2,
                           jdouble dx1, jdouble dy1, jdouble dx2, jdouble dy2)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLBlitToSurfaceViaTexture");
}

/**
 * Inner loop used for copying a source system memory ("Sw") surface to a
 * destination OpenGL "Texture".  This method is invoked from
 * MTLBlitLoops_Blit().
 *
 * The source surface is effectively loaded into the MTL texture object,
 * which must have already been initialized by MTLSD_initTexture().  Note
 * that this method is only capable of copying the source surface into the
 * destination surface (i.e. no scaling or general transform is allowed).
 * This restriction should not be an issue as this method is only used
 * currently to cache a static system memory image into an MTL texture in
 * a hidden-acceleration situation.
 */
static void
MTLBlitSwToTexture(SurfaceDataRasInfo *srcInfo, MTPixelFormat *pf,
                   MTLSDOps *dstOps,
                   jint dx1, jint dy1, jint dx2, jint dy2)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLBlitSwToTexture");
}

/**
 * General blit method for copying a native MTL surface (of type "Surface"
 * or "Texture") to another MTL "Surface".  If texture is JNI_TRUE, this
 * method will invoke the Texture->Surface inner loop; otherwise, one of the
 * Surface->Surface inner loops will be invoked, depending on the transform
 * state.
 *
 * REMIND: we can trick these blit methods into doing XOR simply by passing
 *         in the (pixel ^ xorpixel) as the pixel value and preceding the
 *         blit with a fillrect...
 */
void
MTLBlitLoops_IsoBlit(JNIEnv *env,
                     MTLContext *mtlc, jlong pSrcOps, jlong pDstOps,
                     jboolean xform, jint hint,
                     jboolean texture, jboolean rtt,
                     jint sx1, jint sy1, jint sx2, jint sy2,
                     jdouble dx1, jdouble dy1, jdouble dx2, jdouble dy2)
{
    BMTLSDOps *srcOps = (BMTLSDOps *)jlong_to_ptr(pSrcOps);
    BMTLSDOps *dstOps = (BMTLSDOps *)jlong_to_ptr(pDstOps);

    RETURN_IF_NULL(srcOps);
    RETURN_IF_NULL(dstOps);

    const jint sw    = sx2 - sx1;
    const jint sh    = sy2 - sy1;
    const jdouble dw = dx2 - dx1;
    const jdouble dh = dy2 - dy1;

    if (sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0) {
        J2dTraceLn4(J2D_TRACE_WARNING, "MTLBlitLoops_IsoBlit: invalid dimensions: sw=%d, sh%d, dw=%d, dh=%d", sw, sh, dw, dh);
        return;
    }

    SurfaceDataRasInfo srcInfo;
    srcInfo.bounds.x1 = sx1;
    srcInfo.bounds.y1 = sy1;
    srcInfo.bounds.x2 = sx2;
    srcInfo.bounds.y2 = sy2;
    SurfaceData_IntersectBoundsXYXY(&srcInfo.bounds, 0, 0, srcOps->width, srcOps->height);

    if (srcInfo.bounds.x2 <= srcInfo.bounds.x1 || srcInfo.bounds.y2 <= srcInfo.bounds.y1) {
        J2dTraceLn(J2D_TRACE_VERBOSE, "MTLBlitLoops_IsoBlit: source rectangle doesn't intersect with source surface bounds");
        J2dTraceLn6(J2D_TRACE_VERBOSE, "  sx1=%d sy1=%d sx2=%d sy2=%d sw=%d sh=%d", sx1, sy1, sx2, sy2, srcOps->width, srcOps->height);
        J2dTraceLn4(J2D_TRACE_VERBOSE, "  dx1=%f dy1=%f dx2=%f dy2=%f", dx1, dy1, dx2, dy2);
        return;
    }

    if (srcInfo.bounds.x1 != sx1) {
        dx1 += (srcInfo.bounds.x1 - sx1) * (dw / sw);
        sx1 = srcInfo.bounds.x1;
    }
    if (srcInfo.bounds.y1 != sy1) {
        dy1 += (srcInfo.bounds.y1 - sy1) * (dh / sh);
        sy1 = srcInfo.bounds.y1;
    }
    if (srcInfo.bounds.x2 != sx2) {
        dx2 += (srcInfo.bounds.x2 - sx2) * (dw / sw);
        sx2 = srcInfo.bounds.x2;
    }
    if (srcInfo.bounds.y2 != sy2) {
        dy2 += (srcInfo.bounds.y2 - sy2) * (dh / sh);
        sy2 = srcInfo.bounds.y2;
    }

    // TODO: support other flags
    [JNFRunLoop performOnMainThreadWaiting:NO withBlock:^() {
        MTLBlitTextureToSurface(mtlc, srcOps, dstOps, rtt, hint,
                                sx1, sy1, sx2, sy2,
                                dx1, dy1, dx2, dy2);

    }];
}

/**
 * General blit method for copying a system memory ("Sw") surface to a native
 * MTL surface (of type "Surface" or "Texture").  If texture is JNI_TRUE,
 * this method will invoke the Sw->Texture inner loop; otherwise, one of the
 * Sw->Surface inner loops will be invoked, depending on the transform state.
 */
void
MTLBlitLoops_Blit(JNIEnv *env,
                  MTLContext *mtlc, jlong pSrcOps, jlong pDstOps,
                  jboolean xform, jint hint,
                  jint srctype, jboolean texture,
                  jint sx1, jint sy1, jint sx2, jint sy2,
                  jdouble dx1, jdouble dy1, jdouble dx2, jdouble dy2)
{
    RETURN_IF_NULL(jlong_to_ptr(pSrcOps));
    RETURN_IF_NULL(jlong_to_ptr(pDstOps));

    SurfaceDataOps *srcOps = (SurfaceDataOps *)jlong_to_ptr(pSrcOps);
    BMTLSDOps *dstOps = (BMTLSDOps *)jlong_to_ptr(pDstOps);
    SurfaceDataRasInfo srcInfo;
    MTLPixelFormat pf = PixelFormats[srctype];
    jint sw    = sx2 - sx1;
    jint sh    = sy2 - sy1;
    jdouble dw = dx2 - dx1;
    jdouble dh = dy2 - dy1;

    if (sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0 || srctype < 0) {
        J2dTraceLn(J2D_TRACE_WARNING, "MTLBlitLoops_Blit: invalid dimensions or srctype");
        return;
    }

    srcInfo.bounds.x1 = sx1;
    srcInfo.bounds.y1 = sy1;
    srcInfo.bounds.x2 = sx2;
    srcInfo.bounds.y2 = sy2;

    if (srcOps->Lock(env, srcOps, &srcInfo, SD_LOCK_READ) != SD_SUCCESS) {
        J2dTraceLn(J2D_TRACE_WARNING, "MTLBlitLoops_Blit: could not acquire lock");
        return;
    }

    J2dTraceLn2(J2D_TRACE_VERBOSE, "MTLBlitLoops_Blit: src=%p bdst=%p", jlong_to_ptr(pSrcOps), jlong_to_ptr(pDstOps));

    if (srcInfo.bounds.x2 > srcInfo.bounds.x1 && srcInfo.bounds.y2 > srcInfo.bounds.y1) {
        srcOps->GetRasInfo(env, srcOps, &srcInfo);
        if (srcInfo.rasBase) {
            if (srcInfo.bounds.x1 != sx1) {
                dx1 += (srcInfo.bounds.x1 - sx1) * (dw / sw);
                sx1 = srcInfo.bounds.x1;
            }
            if (srcInfo.bounds.y1 != sy1) {
                dy1 += (srcInfo.bounds.y1 - sy1) * (dh / sh);
                sy1 = srcInfo.bounds.y1;
            }
            if (srcInfo.bounds.x2 != sx2) {
                dx2 += (srcInfo.bounds.x2 - sx2) * (dw / sw);
                sx2 = srcInfo.bounds.x2;
            }
            if (srcInfo.bounds.y2 != sy2) {
                dy2 += (srcInfo.bounds.y2 - sy2) * (dh / sh);
                sy2 = srcInfo.bounds.y2;
            }

            J2dTraceLn3(J2D_TRACE_VERBOSE, "  texture=%d srctype=%d hint=%d", texture, srctype, hint);
            J2dTraceLn4(J2D_TRACE_VERBOSE, "  sx1=%d sy1=%d sx2=%d sy2=%d", sx1, sy1, sx2, sy2);
            J2dTraceLn4(J2D_TRACE_VERBOSE, "  dx1=%f dy1=%f dx2=%f dy2=%f", dx1, dy1, dx2, dy2);

            [JNFRunLoop performOnMainThreadWaiting:NO withBlock:^() {
                MTLBlitSwToSurface(mtlc, &srcInfo, dstOps, &pf, sx1, sy1, sx2, sy2, dx1, dy1, dx2, dy2);
            }];
        }
        SurfaceData_InvokeRelease(env, srcOps, &srcInfo);
    }
    SurfaceData_InvokeUnlock(env, srcOps, &srcInfo);
}

/**
 * Specialized blit method for copying a native MTL "Surface" (pbuffer,
 * window, etc.) to a system memory ("Sw") surface.
 */
void
MTLBlitLoops_SurfaceToSwBlit(JNIEnv *env, MTLContext *mtlc,
                             jlong pSrcOps, jlong pDstOps, jint dsttype,
                             jint srcx, jint srcy, jint dstx, jint dsty,
                             jint width, jint height)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLBlitLoops_SurfaceToSwBlit");
}

void
MTLBlitLoops_CopyArea(JNIEnv *env,
                      MTLContext *mtlc, BMTLSDOps *dstOps,
                      jint x, jint y, jint width, jint height,
                      jint dx, jint dy)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLBlitLoops_CopyArea");
}

#endif /* !HEADLESS */
