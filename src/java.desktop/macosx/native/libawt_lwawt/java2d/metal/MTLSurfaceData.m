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

#import <stdlib.h>

#import "sun_java2d_metal_MTLSurfaceData.h"

#import "jni_util.h"
#import "MTLGraphicsConfig.h"
#import "MTLSurfaceData.h"
#include "jlong.h"

extern BOOL isDisplaySyncEnabled();
jboolean MTLSD_InitMTLWindow(JNIEnv *env, BMTLSDOps *bmtlsdo);
void MTLSD_SetNativeDimensions(JNIEnv *env, BMTLSDOps *bmtlsdo, jint w, jint h);

static jboolean MTLSurfaceData_initTexture(BMTLSDOps *bmtlsdo, jboolean isOpaque, jint sfType, jint width, jint height) {
    @autoreleasepool {
        if (bmtlsdo == NULL) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLSurfaceData_initTexture: ops are null");
            return JNI_FALSE;
        }
        if (width <= 0 || height <= 0) {
            J2dRlsTraceLn(J2D_TRACE_ERROR,
                          "MTLSurfaceData_initTexture: texture dimensions is incorrect, w=%d, h=%d",
                          width, height);
            return JNI_FALSE;
        }

        MTLSDOps *mtlsdo = (MTLSDOps *)bmtlsdo->privOps;

        if (mtlsdo == NULL) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLSurfaceData_initTexture: MTLSDOps are null");
            return JNI_FALSE;
        }
        if (mtlsdo->configInfo == NULL || mtlsdo->configInfo->context == NULL) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLSurfaceData_initTexture: MTLSDOps wasn't initialized (context is null)");
            return JNI_FALSE;
        }

        MTLContext* ctx = mtlsdo->configInfo->context;

        width = (width <= MTL_GPU_FAMILY_MAC_TXT_SIZE) ? width : 0;
        height = (height <= MTL_GPU_FAMILY_MAC_TXT_SIZE) ? height : 0;

        J2dTraceLn(J2D_TRACE_VERBOSE,
                   "  desired texture dimensions: w=%d h=%d max=%d",
                   width, height, MTL_GPU_FAMILY_MAC_TXT_SIZE);

        // if either dimension is 0, we cannot allocate a texture with the
        // requested dimensions
        if ((width == 0 || height == 0)) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLSurfaceData_initTexture: texture dimensions too large");
            return JNI_FALSE;
        }

        MTLTextureDescriptor *textureDescriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat: MTLPixelFormatBGRA8Unorm width: width height: height mipmapped: NO];
        textureDescriptor.usage = MTLTextureUsageUnknown;
        textureDescriptor.storageMode = MTLStorageModePrivate;
        bmtlsdo->pTexture = [ctx.device newTextureWithDescriptor: textureDescriptor];
        if (sfType == MTLSD_FLIP_BACKBUFFER && !isDisplaySyncEnabled()) {
            bmtlsdo->pOutTexture = [ctx.device newTextureWithDescriptor: textureDescriptor];
        } else {
            bmtlsdo->pOutTexture = NULL;
        }
        MTLTextureDescriptor *stencilDataDescriptor =
            [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatR8Uint width:width height:height mipmapped:NO];
        stencilDataDescriptor.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
        stencilDataDescriptor.storageMode = MTLStorageModePrivate;
        bmtlsdo->pStencilData = [ctx.device newTextureWithDescriptor:stencilDataDescriptor];

        MTLTextureDescriptor *stencilTextureDescriptor =
            [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatStencil8 width:width height:height mipmapped:NO];
        stencilTextureDescriptor.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite;
        stencilTextureDescriptor.storageMode = MTLStorageModePrivate;
        bmtlsdo->pStencilTexture = [ctx.device newTextureWithDescriptor:stencilTextureDescriptor];
        bmtlsdo->isOpaque = isOpaque;
        bmtlsdo->width = width;
        bmtlsdo->height = height;
        bmtlsdo->drawableType = sfType;

        J2dTraceLn(J2D_TRACE_VERBOSE, "MTLSurfaceData_initTexture: w=%d h=%d bp=%p [tex=%p] opaque=%d sfType=%d",
                   width, height, bmtlsdo, bmtlsdo->pTexture, isOpaque, sfType);
        return JNI_TRUE;
    }
}

static jboolean MTLSurfaceData_initWithTexture(BMTLSDOps *bmtlsdo, jboolean isOpaque, void* pTexture) {
    @autoreleasepool {
        if (bmtlsdo == NULL) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLSurfaceData_initWithTexture: ops are null");
            return JNI_FALSE;
        }

        if (pTexture == NULL) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLSurfaceData_initWithTexture: texture is null");
            return JNI_FALSE;
        }

        id <MTLTexture> texture = (__bridge id <MTLTexture>) pTexture;
        if (texture == NULL) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLSurfaceData_initWithTexture: failed to cast texture to MTLTexture");
            return JNI_FALSE;
        }

        if (texture.width >= MTL_GPU_FAMILY_MAC_TXT_SIZE || texture.height >= MTL_GPU_FAMILY_MAC_TXT_SIZE ||
            texture.width == 0 || texture.height == 0) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLSurfaceData_initWithTexture: wrong texture size %d x %d",
                          texture.width, texture.height);
            return JNI_FALSE;
        }

        if (texture.pixelFormat != MTLPixelFormatBGRA8Unorm) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLSurfaceData_initWithTexture: unsupported pixel format: %d",
                          texture.pixelFormat);
            return JNI_FALSE;
        }

        bmtlsdo->pTexture = texture;
        bmtlsdo->pOutTexture = NULL;

        MTLSDOps *mtlsdo = (MTLSDOps *)bmtlsdo->privOps;
        if (mtlsdo == NULL) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLSurfaceData_initWithTexture: MTLSDOps are null");
            return JNI_FALSE;
        }
        if (mtlsdo->configInfo == NULL || mtlsdo->configInfo->context == NULL) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLSurfaceData_initWithTexture: MTLSDOps wasn't initialized (context is null)");
            return JNI_FALSE;
        }
        MTLContext* ctx = mtlsdo->configInfo->context;
        MTLTextureDescriptor *stencilDataDescriptor =
                [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatR8Uint
                                                                   width:texture.width
                                                                  height:texture.height
                                                               mipmapped:NO];
        stencilDataDescriptor.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
        stencilDataDescriptor.storageMode = MTLStorageModePrivate;
        bmtlsdo->pStencilData = [ctx.device newTextureWithDescriptor:stencilDataDescriptor];

        MTLTextureDescriptor *stencilTextureDescriptor =
                [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatStencil8
                                                                   width:texture.width
                                                                  height:texture.height
                                                               mipmapped:NO];
        stencilTextureDescriptor.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite;
        stencilTextureDescriptor.storageMode = MTLStorageModePrivate;
        bmtlsdo->pStencilTexture = [ctx.device newTextureWithDescriptor:stencilTextureDescriptor];

        bmtlsdo->isOpaque = isOpaque;
        bmtlsdo->width = texture.width;
        bmtlsdo->height = texture.height;
        bmtlsdo->drawableType = MTLSD_RT_TEXTURE;

        [texture retain];

        J2dTraceLn(J2D_TRACE_VERBOSE, "MTLSurfaceData_initTexture: w=%d h=%d bp=%p [tex=%p] opaque=%d sfType=%d",
                   bmtlsdo->width, bmtlsdo->height, bmtlsdo, bmtlsdo->pTexture, isOpaque, bmtlsdo->drawableType);
        return JNI_TRUE;
    }
}

/**
 * Initializes an MTL texture, using the given width and height as
 * a guide.
 */
JNIEXPORT jboolean JNICALL
Java_sun_java2d_metal_MTLSurfaceData_initTexture(
    JNIEnv *env, jobject mtlsd,
    jlong pData, jboolean isOpaque,
    jint width, jint height
) {
    if (!MTLSurfaceData_initTexture((BMTLSDOps *)pData, isOpaque, MTLSD_TEXTURE, width, height))
        return JNI_FALSE;
    MTLSD_SetNativeDimensions(env, (BMTLSDOps *)pData, width, height);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_sun_java2d_metal_MTLSurfaceData_initWithTexture(
        JNIEnv *env, jobject mtlds,
        jlong pData, jboolean isOpaque,
        jlong pTexture) {
    BMTLSDOps *bmtlsdops = (BMTLSDOps *) pData;
    if (!MTLSurfaceData_initWithTexture(bmtlsdops, isOpaque, jlong_to_ptr(pTexture))) {
        return JNI_FALSE;
    }
    MTLSD_SetNativeDimensions(env, (BMTLSDOps *) pData, bmtlsdops->width, bmtlsdops->height);
    return JNI_TRUE;
}

/**
 * Initializes a framebuffer object, using the given width and height as
 * a guide.  See MTLSD_InitTextureObject() and MTLSD_initRTexture()
 * for more information.
 */
JNIEXPORT jboolean JNICALL
Java_sun_java2d_metal_MTLSurfaceData_initRTexture
    (JNIEnv *env, jobject mtlsd,
     jlong pData, jboolean isOpaque,
     jint width, jint height)
{
    if (!MTLSurfaceData_initTexture((BMTLSDOps *)pData, isOpaque, MTLSD_RT_TEXTURE, width, height))
        return JNI_FALSE;
    MTLSD_SetNativeDimensions(env, (BMTLSDOps *)pData, width, height);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_sun_java2d_metal_MTLSurfaceData_initFlipBackbuffer
    (JNIEnv *env, jobject mtlsd, jlong pData, jboolean isOpaque, jint width, jint height) {
    if (!MTLSurfaceData_initTexture((BMTLSDOps *)pData, isOpaque, MTLSD_FLIP_BACKBUFFER, width, height))
        return JNI_FALSE;
    MTLSD_SetNativeDimensions(env, (BMTLSDOps *)pData, width, height);
    return JNI_TRUE;
}

JNIEXPORT jlong JNICALL
Java_sun_java2d_metal_MTLSurfaceData_getMTLTexturePointer(JNIEnv *env, jobject mtlsd, jlong pData) {
    if (pData == 0)
        return 0;
    return ptr_to_jlong(((BMTLSDOps *)pData)->pTexture);
}

/**
 * Initializes nativeWidth/Height fields of the surfaceData object with
 * passed arguments.
 */
void
MTLSD_SetNativeDimensions(JNIEnv *env, BMTLSDOps *mtlsdo,
                          jint width, jint height)
{
    jobject sdObject;

    sdObject = (*env)->NewLocalRef(env, mtlsdo->sdOps.sdObject);
    if (sdObject == NULL) {
        return;
    }

    JNU_SetFieldByName(env, NULL, sdObject, "nativeWidth", "I", width);
    if (!((*env)->ExceptionCheck(env))) {
        JNU_SetFieldByName(env, NULL, sdObject, "nativeHeight", "I", height);
    }

    (*env)->DeleteLocalRef(env, sdObject);
}

/**
 * Deletes native Metal resources associated with this surface.
 */
void
MTLSD_Delete(JNIEnv *env, BMTLSDOps *bmtlsdo)
{
    J2dTraceLn(J2D_TRACE_VERBOSE, "MTLSD_Delete: type=%d %p [tex=%p]",
               bmtlsdo->drawableType, bmtlsdo, bmtlsdo->pTexture);
    if (bmtlsdo->drawableType == MTLSD_WINDOW) {
        bmtlsdo->drawableType = MTLSD_UNDEFINED;
    } else if (
            bmtlsdo->drawableType == MTLSD_RT_TEXTURE
            || bmtlsdo->drawableType == MTLSD_TEXTURE
            || bmtlsdo->drawableType == MTLSD_FLIP_BACKBUFFER
    ) {
        [(NSObject *)bmtlsdo->pTexture release];
        if (bmtlsdo->pOutTexture != NULL) {
            [(NSObject *)bmtlsdo->pOutTexture release];
            bmtlsdo->pOutTexture = NULL;
        }
        [(NSObject *)bmtlsdo->pStencilTexture release];
        [(NSObject *)bmtlsdo->pStencilData release];
        bmtlsdo->pTexture = NULL;
        bmtlsdo->drawableType = MTLSD_UNDEFINED;
    }
}

/**
 * This is the implementation of the general DisposeFunc defined in
 * SurfaceData.h and used by the Disposer mechanism.  It first flushes all
 * native Metal resources and then frees any memory allocated within the
 * native MTLSDOps structure.
 */
void
MTLSD_Dispose(JNIEnv *env, SurfaceDataOps *ops)
{
    JNU_CallStaticMethodByName(env, NULL, "sun/java2d/metal/MTLSurfaceData",
                               "dispose", "(J)V", ptr_to_jlong(ops));
}

/**
 * This is the implementation of the general surface LockFunc defined in
 * SurfaceData.h.
 */
jint
MTLSD_Lock(JNIEnv *env,
           SurfaceDataOps *ops,
           SurfaceDataRasInfo *pRasInfo,
           jint lockflags)
{
    JNU_ThrowInternalError(env, "MTLSD_Lock not implemented!");
    return SD_FAILURE;
}

/**
 * This is the implementation of the general GetRasInfoFunc defined in
 * SurfaceData.h.
 */
void
MTLSD_GetRasInfo(JNIEnv *env,
                 SurfaceDataOps *ops,
                 SurfaceDataRasInfo *pRasInfo)
{
    JNU_ThrowInternalError(env, "MTLSD_GetRasInfo not implemented!");
}

/**
 * This is the implementation of the general surface UnlockFunc defined in
 * SurfaceData.h.
 */
void
MTLSD_Unlock(JNIEnv *env,
             SurfaceDataOps *ops,
             SurfaceDataRasInfo *pRasInfo)
{
    JNU_ThrowInternalError(env, "MTLSD_Unlock not implemented!");
}


/**
 * This function initializes a native window surface and caches the window
 * bounds in the given BMTLSDOps.  Returns JNI_TRUE if the operation was
 * successful; JNI_FALSE otherwise.
 */
jboolean
MTLSD_InitMTLWindow(JNIEnv *env, BMTLSDOps *bmtlsdo)
{
    if (bmtlsdo == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLSD_InitMTLWindow: ops are null");
        return JNI_FALSE;
    }

    MTLSDOps *mtlsdo = (MTLSDOps *)bmtlsdo->privOps;
    if (mtlsdo == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLSD_InitMTLWindow: priv ops are null");
        return JNI_FALSE;
    }

    AWTView *v = mtlsdo->peerData;
    if (v == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLSD_InitMTLWindow: view is invalid");
        return JNI_FALSE;
    }

    JNI_COCOA_ENTER(env);
            NSRect surfaceBounds = [v bounds];
            bmtlsdo->drawableType = MTLSD_WINDOW;
            bmtlsdo->isOpaque = JNI_TRUE;
            bmtlsdo->width = surfaceBounds.size.width;
            bmtlsdo->height = surfaceBounds.size.height;
    JNI_COCOA_EXIT(env);

    J2dTraceLn(J2D_TRACE_VERBOSE, "  created window: w=%d h=%d",
               bmtlsdo->width, bmtlsdo->height);
    return JNI_TRUE;
}

#pragma mark -
#pragma mark "--- MTLSurfaceData methods ---"

extern LockFunc        MTLSD_Lock;
extern GetRasInfoFunc  MTLSD_GetRasInfo;
extern UnlockFunc      MTLSD_Unlock;


JNIEXPORT void JNICALL
Java_sun_java2d_metal_MTLSurfaceData_initOps
    (JNIEnv *env, jobject mtlsd, jobject gc,
     jlong pConfigInfo, jlong pPeerData, jlong layerPtr,
     jint xoff, jint yoff, jboolean isOpaque)
{
    BMTLSDOps *bmtlsdo = (BMTLSDOps *)SurfaceData_InitOps(env, mtlsd, sizeof(BMTLSDOps));
    MTLSDOps *mtlsdo = (MTLSDOps *)malloc(sizeof(MTLSDOps));

    if (mtlsdo == NULL) {
        JNU_ThrowOutOfMemoryError(env, "Initialization of SurfaceData failed.");
        return;
    }

    J2dTraceLn(J2D_TRACE_INFO, "MTLSurfaceData_initOps p=%p", bmtlsdo);
    J2dTraceLn(J2D_TRACE_INFO, "  pPeerData=%p", jlong_to_ptr(pPeerData));
    J2dTraceLn(J2D_TRACE_INFO, "  layerPtr=%p", jlong_to_ptr(layerPtr));
    J2dTraceLn(J2D_TRACE_INFO, "  xoff=%d, yoff=%d", (int)xoff, (int)yoff);

    bmtlsdo->privOps = mtlsdo;

    bmtlsdo->sdOps.Lock               = MTLSD_Lock;
    bmtlsdo->sdOps.GetRasInfo         = MTLSD_GetRasInfo;
    bmtlsdo->sdOps.Unlock             = MTLSD_Unlock;
    bmtlsdo->sdOps.Dispose            = MTLSD_Dispose;
    bmtlsdo->drawableType = MTLSD_UNDEFINED;

    bmtlsdo->isOpaque = isOpaque;

    mtlsdo->peerData = (AWTView *)jlong_to_ptr(pPeerData);
    mtlsdo->layer = (MTLLayer *)jlong_to_ptr(layerPtr);
    mtlsdo->configInfo = (MTLGraphicsConfigInfo *)jlong_to_ptr(pConfigInfo);

    if (mtlsdo->configInfo == NULL) {
        free(mtlsdo);
        JNU_ThrowNullPointerException(env, "Config info is null in initOps");
    }
}

extern void replaceTexture(MTLContext *mtlc, id<MTLTexture> dest, void* pRaster, int width, int height, int dx1, int dy1, int dx2, int dy2);

JNIEXPORT void JNICALL
Java_sun_java2d_metal_MTLSurfaceData_clearWindow
(JNIEnv *env, jobject mtlsd)
{
    J2dTraceLn(J2D_TRACE_INFO, "MTLSurfaceData_clearWindow");

    BMTLSDOps *bmtlsdo = (MTLSDOps*) SurfaceData_GetOps(env, mtlsd);
    MTLSDOps *mtlsdo = (MTLSDOps*) bmtlsdo->privOps;

    mtlsdo->peerData = NULL;
    mtlsdo->layer = NULL;
}

JNIEXPORT jboolean JNICALL
Java_sun_java2d_metal_MTLSurfaceData_loadNativeRasterWithRects
        (JNIEnv *env, jclass clazz,
         jlong sdops, jlong pRaster, jint width, jint height, jlong pRects, jint rectsCount)
{
    BMTLSDOps *dstOps = (BMTLSDOps *)jlong_to_ptr(sdops);
    if (dstOps == NULL || pRaster == 0) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLSurfaceData_loadNativeRasterWithRects: params are null");
        return JNI_FALSE;
    }

    id<MTLTexture> dest = dstOps->pTexture;
    if (dest == NULL) {
        J2dTraceLn(J2D_TRACE_ERROR, "MTLSurfaceData_loadNativeRasterWithRects: dest is null");
        return JNI_FALSE;
    }

    MTLSDOps *dstMTLOps = (MTLSDOps *)dstOps->privOps;
    MTLContext *ctx = dstMTLOps->configInfo->context;
    if (pRects == 0 || rectsCount < 1) {
        J2dTraceLn(J2D_TRACE_VERBOSE, "MTLSurfaceData_loadNativeRasterWithRects: do full copy of raster:");
        replaceTexture(ctx, dest, (void*)pRaster, (int)width, (int)height, 0, 0, (int)width, (int)height);
    } else {
        int32_t *pr = (int32_t *) pRects;
        for (int c = 0; c < rectsCount; ++c) {
            int32_t x = *(pr++);
            int32_t y = *(pr++);
            int32_t w = *(pr++);
            int32_t h = *(pr++);
            //fprintf(stderr, "MTLSurfaceData_loadNativeRasterWithRects: process rect %d %d %d %d\n", x, y, w, h);
            replaceTexture(ctx, dest, (void*)pRaster, (int)width, (int)height, x, y, x + w, y + h);
        }
    }

    return JNI_TRUE;
}

NSString * getSurfaceDescription(const BMTLSDOps * bmtlsdOps) {
    if (bmtlsdOps == NULL)
        return @"NULL";
    return [NSString stringWithFormat:@"%p [tex=%p, %dx%d, O=%d]", bmtlsdOps, bmtlsdOps->pTexture, bmtlsdOps->width, bmtlsdOps->height, bmtlsdOps->isOpaque];
}
