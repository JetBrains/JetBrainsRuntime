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

#include <malloc.h>
#include <string.h>
#include <setjmp.h>
#include "jlong.h"
#include "SurfaceData.h"
#include "VKUtil.h"
#include "VKBlitLoops.h"
#include "VKSurfaceData.h"
#include "VKRenderer.h"


#include "Trace.h"
#include "VKImage.h"
#include "VKBuffer.h"
#include "CArrayUtil.h"

static void VKBlitSwToTextureViaPooledTexture(VKRenderingContext* context, VKImage* dest, const SurfaceDataRasInfo *srcInfo,
                     int dx1, int dy1, int dx2, int dy2) {
    VKSDOps* surface = context->surface;
    VKDevice* device = surface->device;

    const int sw = srcInfo->bounds.x2 - srcInfo->bounds.x1;
    const int sh = srcInfo->bounds.y2 - srcInfo->bounds.y1;
    const int dw = dx2 - dx1;
    const int dh = dy2 - dy1;

    if (dw < sw || dh < sh) {
        J2dTraceLn4(J2D_TRACE_ERROR, "replaceTextureRegion: dest size: (%d, %d) less than source size: (%d, %d)", dw, dh, sw, sh);
        return;
    }

    double width = dest->extent.width/2.0;
    double height = dest->extent.height/2.0;
    J2dRlsTraceLn2(J2D_TRACE_VERBOSE,
                   "VKRenderQueue_flushBuffer: FILL_PARALLELOGRAM(W=%f, H=%f)",
                   width, height);

    double p1ux = dx1;
    double p1uy = dy1;
    double p2ux = dx2;
    double p2uy = dy2;

    VKTransform *t = &context->transform;


    VKTxVertex* vertices = ARRAY_ALLOC(VKTxVertex, 4);
    /*
     *    (p1)---------(p2)
     *     |             |
     *     |             |
     *     |             |
     *    (p4)---------(p3)
     */
    float p1x = (float)(-1.0 + p1ux / width);
    float p1y = (float)(-1.0 + p1uy / height);
    float p2x = (float)(-1.0 + p2ux / width);
    float p2y = p1y;
    float p3x = p2x;
    float p3y = (float)(-1.0 + p2uy / height);
    float p4x = p1x;
    float p4y = p3y;

    //  Direct allocation for debug purpose
//  VKImage *image = VKImage_Create(device, sw, sh, surface->image->format, VK_IMAGE_TILING_LINEAR,
//                                  VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
//                                  VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    VKTexturePoolHandle* hnd = VKTexturePool_GetTexture(device->texturePool, sw, sh, surface->image->format);
    double u = (double)sw / VKTexturePoolHandle_GetActualWidth(hnd);
    double v = (double)sh / VKTexturePoolHandle_GetActualHeight(hnd);

    ARRAY_PUSH_BACK(vertices, ((VKTxVertex) {p1x, p1y, 0.0f, 0.0f}));
    ARRAY_PUSH_BACK(vertices, ((VKTxVertex) {p2x, p2y, u, 0.0f}));
    ARRAY_PUSH_BACK(vertices, ((VKTxVertex) {p4x, p4y, 0.0f, v}));
    ARRAY_PUSH_BACK(vertices, ((VKTxVertex) {p3x, p3y, u, v}));

    VKBuffer* renderVertexBuffer = ARRAY_TO_VERTEX_BUF(device, vertices);
    ARRAY_FREE(vertices);

    const char *raster = srcInfo->rasBase;
    raster += (uint32_t)srcInfo->bounds.y1 * (uint32_t)srcInfo->scanStride + (uint32_t)srcInfo->bounds.x1 * (uint32_t)srcInfo->pixelStride;
    J2dTraceLn4(J2D_TRACE_VERBOSE, "replaceTextureRegion src (dw, dh) : [%d, %d] dest (dx1, dy1) =[%d, %d]",
                dw, dh, dx1, dy1);
    uint32_t dataSize = sw * sh * srcInfo->pixelStride;
    char* data = malloc(dataSize);
    // copy src pixels inside src bounds to buff
    for (int row = 0; row < sh; row++) {
        memcpy(data + (row * sw * srcInfo->pixelStride), raster, sw * srcInfo->pixelStride);
        raster += (uint32_t)srcInfo->scanStride;
    }
    VKBuffer *buffer = VKBuffer_CreateFromData(device, data, dataSize);
    free(data);
//  VKImage_LoadBuffer(context->surface->device, image, buffer, 0, 0, sw, sh);
    VKImage_LoadBuffer(context->surface->device,
                       VKTexturePoolHandle_GetTexture(hnd), buffer, 0, 0, sw, sh);
//  VKRenderer_TextureRender(context, dest, image, renderVertexBuffer->handle, 4);
    VKRenderer_TextureRender(context, dest, VKTexturePoolHandle_GetTexture(hnd),
                             renderVertexBuffer->handle, 4);
    VKTexturePoolHandle_ReleaseTexture(hnd);
}


void VKBlitLoops_IsoBlit(JNIEnv *env,
                         VKRenderingContext* context, jlong pSrcOps, jlong pDstOps,
                         jboolean xform, jint hint,
                         jboolean texture,
                         jint sx1, jint sy1,
                         jint sx2, jint sy2,
                         jdouble dx1, jdouble dy1,
                         jdouble dx2, jdouble dy2)
{
    J2dRlsTraceLn8(J2D_TRACE_VERBOSE, "VKRenderQueue_flushBuffer: BLIT_IsoBlit (%d %d %d %d) -> (%f %f %f %f) ",
                                   sx1, sy1, sx2, sy2, dx1, dy1, dx2, dy2);
    J2dRlsTraceLn2(J2D_TRACE_VERBOSE, "VKRenderQueue_flushBuffer: BLIT_IsoBlit texture=%d xform=%d",
                                   texture, xform)
}

void VKBlitLoops_Blit(JNIEnv *env,
                      VKRenderingContext* context, jlong pSrcOps, jlong pDstOps,
                      jboolean xform, jint hint,
                      jint srctype, jboolean texture,
                      jint sx1, jint sy1,
                      jint sx2, jint sy2,
                      jdouble dx1, jdouble dy1,
                      jdouble dx2, jdouble dy2)
{
    jmp_buf cleanup_jmpbuf;
    J2dRlsTraceLn8(J2D_TRACE_VERBOSE, "VKRenderQueue_flushBuffer: BLIT_Blit (%d %d %d %d) -> (%f %f %f %f) ",
                                   sx1, sy1, sx2, sy2, dx1, dy1, dx2, dy2)
    J2dRlsTraceLn3(J2D_TRACE_VERBOSE, "VKRenderQueue_flushBuffer: BLIT_Blit texture=%d xform=%d srctype=%d",
                                   texture, xform, srctype)

    SurfaceDataOps *srcOps = (SurfaceDataOps *)jlong_to_ptr(pSrcOps);
    VKSDOps *dstOps = (VKSDOps *)jlong_to_ptr(pDstOps);


    if (context == NULL || srcOps == NULL || dstOps == NULL) {
        J2dRlsTraceLn3(J2D_TRACE_ERROR, "VKBlitLoops_Blit: context(%p) or srcOps(%p) or dstOps(%p) is null",
                       context, srcOps, dstOps)
        return;
    }

    VKSDOps *oldSurface = context->surface;
    context->surface = dstOps;

    if (setjmp(cleanup_jmpbuf)) {
        context->surface = oldSurface;
        return;
    }

    if (!VKRenderer_Validate(context, PIPELINE_BLIT)) {
        J2dRlsTrace(J2D_TRACE_ERROR, "replaceTextureRegion: cannot validate renderer");
        longjmp(cleanup_jmpbuf, 1);
    }
    VKImage *dest = context->surface->image;
//    if (srctype < 0 || srctype >= sizeof(RasterFormatInfos)/ sizeof(MTLRasterFormatInfo)) {
//        J2dTraceLn1(J2D_TRACE_ERROR, "MTLBlitLoops_Blit: source pixel format %d isn't supported", srctype);
//        return;
//    }
    const jint sw    = sx2 - sx1;
    const jint sh    = sy2 - sy1;
    const jint dw = dx2 - dx1;
    const jint dh = dy2 - dy1;

    if (sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0) {
        J2dTraceLn(J2D_TRACE_ERROR, "MTLBlitLoops_Blit: invalid dimensions");
        longjmp(cleanup_jmpbuf, 1);
    }

//    if (!xform) {
//        clipDestCoords(
//                &dx1, &dy1, &dx2, &dy2,
//                &sx1, &sy1, &sx2, &sy2,
//                dest.width, dest.height, texture ? NULL : [mtlc.clip getRect]
//        );
//    }

    SurfaceDataRasInfo srcInfo;
    srcInfo.bounds.x1 = sx1;
    srcInfo.bounds.y1 = sy1;
    srcInfo.bounds.x2 = sx2;
    srcInfo.bounds.y2 = sy2;

    // NOTE: This function will modify the contents of the bounds field to represent the maximum available raster data.
    if (srcOps->Lock(env, srcOps, &srcInfo, SD_LOCK_READ) != SD_SUCCESS) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "VKBlitLoops_Blit: could not acquire lock");
        longjmp(cleanup_jmpbuf, 1);
    }

    if (srcInfo.bounds.x2 > srcInfo.bounds.x1 && srcInfo.bounds.y2 > srcInfo.bounds.y1) {
        jdouble dstX1 = dx1;
        jdouble dstY1 = dy1;
        jdouble dstX2 = dx2;
        jdouble dstY2 = dy2;

        srcOps->GetRasInfo(env, srcOps, &srcInfo);
        if (srcInfo.rasBase) {
            if (srcInfo.bounds.x1 != sx1) {
                const int dx = srcInfo.bounds.x1 - sx1;
                dstX1 += dx * (dw / sw);
            }
            if (srcInfo.bounds.y1 != sy1) {
                const int dy = srcInfo.bounds.y1 - sy1;
                dstY1 += dy * (dh / sh);
            }
            if (srcInfo.bounds.x2 != sx2) {
                const int dx = srcInfo.bounds.x2 - sx2;
                dstX2 += dx * (dw / sw);
            }
            if (srcInfo.bounds.y2 != sy2) {
                const int dy = srcInfo.bounds.y2 - sy2;
                dstY2 += dy * (dh / sh);
            }

//            MTLRasterFormatInfo rfi = RasterFormatInfos[srctype];
//
//            if (texture) {
//                replaceTextureRegion(mtlc, dest, &srcInfo, &rfi, (int) dx1, (int) dy1, (int) dx2, (int) dy2);
//            } else {
                VKBlitSwToTextureViaPooledTexture(context, dest, &srcInfo,
                                                  (int)dstX1, (int)dstY1, (int)dstX2, (int)dstY2);
//            }
        }
        SurfaceData_InvokeRelease(env, srcOps, &srcInfo);
    }
    SurfaceData_InvokeUnlock(env, srcOps, &srcInfo);
    longjmp(cleanup_jmpbuf, 1);
}