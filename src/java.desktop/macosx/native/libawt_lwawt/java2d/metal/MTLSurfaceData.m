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

#import <stdlib.h>

#import "sun_java2d_metal_MTLSurfaceData.h"

#import "jni_util.h"
#import "MTLRenderQueue.h"
#import "MTLGraphicsConfig.h"
#import "MTLSurfaceData.h"
#import "ThreadUtilities.h"
#include "jlong.h"

/**
 * The following methods are implemented in the windowing system (i.e. GLX
 * and WGL) source files.
 */
extern jlong MTLSD_GetNativeConfigInfo(BMTLSDOps *mtlsdo);
extern jboolean MTLSD_InitMTLWindow(JNIEnv *env, MTLSDOps *mtlsdo);
extern void MTLSD_DestroyMTLSurface(JNIEnv *env, MTLSDOps *mtlsdo);

void MTLSD_SetNativeDimensions(JNIEnv *env, BMTLSDOps *mtlsdo, jint w, jint h);

/**
 * This table contains the "pixel formats" for all system memory surfaces
 * that OpenGL is capable of handling, indexed by the "PF_" constants defined
 * in MTLSurfaceData.java.  These pixel formats contain information that is
 * passed to OpenGL when copying from a system memory ("Sw") surface to
 * an OpenGL "Surface" (via glDrawPixels()) or "Texture" (via glTexImage2D()).
 */
MTLPixelFormat MTPixelFormats[] = {};

/**
 * Given a starting value and a maximum limit, returns the first power-of-two
 * greater than the starting value.  If the resulting value is greater than
 * the maximum limit, zero is returned.
 */
jint
MTLSD_NextPowerOfTwo(jint val, jint max)
{
    jint i;

    if (val > max) {
        return 0;
    }

    for (i = 1; i < val; i *= 2);

    return i;
}

/**
 * Returns true if both given dimensions are a power of two.
 */
static jboolean
MTLSD_IsPowerOfTwo(jint width, jint height)
{
    return (((width & (width-1)) | (height & (height-1))) == 0);
}

/**
 * Initializes an MTL texture, using the given width and height as
 * a guide.
 */
JNIEXPORT jboolean JNICALL
Java_sun_java2d_metal_MTLSurfaceData_initTexture
    (JNIEnv *env, jobject mtlsd,
     jlong pData, jboolean isOpaque,
     jboolean texNonPow2, jboolean texRect,
     jint width, jint height)
{
    BMTLSDOps *bmtlsdo = (BMTLSDOps *)jlong_to_ptr(pData);
    J2dTraceLn3(J2D_TRACE_INFO, "MTLSurfaceData_initTexture: w=%d h=%d p=%p", width, height, bmtlsdo);

    if (bmtlsdo == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLSurfaceData_initTexture: ops are null");
        return JNI_FALSE;
    }

    if (width <= 0 || height <= 0) {
        J2dRlsTraceLn2(J2D_TRACE_ERROR, "MTLSurfaceData_initTexture: texture dimensions is incorrect, w=%d, h=%d", width, height);
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

    MTLTextureDescriptor *textureDescriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat: MTLPixelFormatBGRA8Unorm width: width height: height mipmapped: NO];
    bmtlsdo->pTexture = [[ctx->mtlDevice newTextureWithDescriptor: textureDescriptor] retain];
    bmtlsdo->isOpaque = isOpaque;
    bmtlsdo->xOffset = 0;
    bmtlsdo->yOffset = 0;
    bmtlsdo->width = width;
    bmtlsdo->height = height;
    bmtlsdo->textureWidth = width;
    bmtlsdo->textureHeight = height;
    bmtlsdo->textureTarget = -1;
    bmtlsdo->drawableType = MTLSD_TEXTURE;

    MTLSD_SetNativeDimensions(env, bmtlsdo, width, width);
    J2dTraceLn4(J2D_TRACE_VERBOSE, "\tcreated MTLTexture [texture]: w=%d h=%d bp=%p [tex=%p]", width, height, bmtlsdo, bmtlsdo->pTexture);

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
     jboolean texNonPow2, jboolean texRect,
     jint width, jint height)
{

    BMTLSDOps *bmtlsdo = (BMTLSDOps *)jlong_to_ptr(pData);

    if (bmtlsdo == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR,
            "MTLSurfaceData_initRTexture: BMTLSDOps are null");
        return JNI_FALSE;
    }

    MTLSDOps *mtlsdo = (MTLSDOps *)bmtlsdo->privOps;

    if (mtlsdo == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR,
            "MTLSurfaceData_initRTexture: MTLSDOps are null");
        return JNI_FALSE;
    }

    if (mtlsdo->configInfo == NULL || mtlsdo->configInfo->context == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLSurfaceData_initRTexture: MTLSDOps wasn't initialized (context is null)");
        return JNI_FALSE;
    }

    const MTLContext* ctx = mtlsdo->configInfo->context;
    MTLTextureDescriptor *textureDescriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat: MTLPixelFormatRGBA8Unorm width: width height: height mipmapped: NO];
    bmtlsdo->pTexture = [[ctx->mtlDevice newTextureWithDescriptor: textureDescriptor] retain];;

    bmtlsdo->isOpaque = isOpaque;
    bmtlsdo->xOffset = 0;
    bmtlsdo->yOffset = 0;
    bmtlsdo->width = width;
    bmtlsdo->height = height;
    bmtlsdo->textureWidth = width;
    bmtlsdo->textureHeight = height;
    bmtlsdo->textureTarget = -1;
    bmtlsdo->drawableType = MTLSD_RT_TEXTURE;

    MTLSD_SetNativeDimensions(env, bmtlsdo, width, width);
    J2dTraceLn4(J2D_TRACE_VERBOSE, "\tcreated MTLTexture [FBObject]: w=%d h=%d bp=%p [tex=%p]", width, height, bmtlsdo, bmtlsdo->pTexture);

    return JNI_TRUE;
}

/**
 * Initializes a surface in the backbuffer of a given double-buffered
 * onscreen window for use in a BufferStrategy.Flip situation.  The bounds of
 * the backbuffer surface should always be kept in sync with the bounds of
 * the underlying native window.
 */
JNIEXPORT jboolean JNICALL
Java_sun_java2d_metal_MTLSurfaceData_initFlipBackbuffer
    (JNIEnv *env, jobject mtlsd,
     jlong pData)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLSurfaceData_initFlipBackbuffer");
    MTLSDOps *mtlsdo = (MTLSDOps *)jlong_to_ptr(pData);

    J2dTraceLn(J2D_TRACE_INFO, "MTLSurfaceData_initFlipBackbuffer");
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_sun_java2d_metal_MTLSurfaceData_getTextureTarget
    (JNIEnv *env, jobject mtlsd,
     jlong pData)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLSurfaceData_getTextureTarget");
    MTLSDOps *mtlsdo = (MTLSDOps *)jlong_to_ptr(pData);

    J2dTraceLn(J2D_TRACE_INFO, "MTLSurfaceData_getTextureTarget");

    return 0;
}

JNIEXPORT jint JNICALL
Java_sun_java2d_metal_MTLSurfaceData_getTextureID
    (JNIEnv *env, jobject mtlsd,
     jlong pData)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLSurfaceData_getTextureID");
    return 0;
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
    if (!((*env)->ExceptionOccurred(env))) {
        JNU_SetFieldByName(env, NULL, sdObject, "nativeHeight", "I", height);
    }

    (*env)->DeleteLocalRef(env, sdObject);
}

/**
 * Deletes native OpenGL resources associated with this surface.
 */
void
MTLSD_Delete(JNIEnv *env, BMTLSDOps *mtlsdo)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLSD_Delete");
    J2dTraceLn1(J2D_TRACE_INFO, "MTLSD_Delete: type=%d",
                mtlsdo->drawableType);
}

/**
 * This is the implementation of the general DisposeFunc defined in
 * SurfaceData.h and used by the Disposer mechanism.  It first flushes all
 * native OpenGL resources and then frees any memory allocated within the
 * native MTLSDOps structure.
 */
void
MTLSD_Dispose(JNIEnv *env, SurfaceDataOps *ops)
{
    MTLSDOps *mtlsdo = (MTLSDOps *)ops;
    jlong pConfigInfo = MTLSD_GetNativeConfigInfo(mtlsdo);

    JNU_CallStaticMethodByName(env, NULL, "sun/java2d/metal/MTLSurfaceData",
                               "dispose", "(JJ)V",
                               ptr_to_jlong(ops), pConfigInfo);
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
 * This function disposes of any native windowing system resources associated
 * with this surface.
 */
void
MTLSD_DestroyMTLSurface(JNIEnv *env, MTLSDOps *mtlsdo)
{
    J2dTraceLn(J2D_TRACE_INFO, "OGLSD_DestroyOGLSurface");
}

/**
 * Returns a pointer (as a jlong) to the native CGLGraphicsConfigInfo
 * associated with the given OGLSDOps.  This method can be called from
 * shared code to retrieve the native GraphicsConfig data in a platform-
 * independent manner.
 */
jlong
MTLSD_GetNativeConfigInfo(BMTLSDOps *mtlsdo)
{
    J2dTraceLn(J2D_TRACE_INFO, "OGLSD_GetNativeConfigInfo");

    return 0;
}

/**
 * This function initializes a native window surface and caches the window
 * bounds in the given OGLSDOps.  Returns JNI_TRUE if the operation was
 * successful; JNI_FALSE otherwise.
 */
jboolean
MTLSD_InitMTLWindow(JNIEnv *env, MTLSDOps *oglsdo)
{
    J2dTraceLn(J2D_TRACE_INFO, "MTLSD_InitMTLWindow");

    return JNI_TRUE;
}

void
MTLSD_SwapBuffers(JNIEnv *env, jlong pPeerData)
{
    J2dTraceLn(J2D_TRACE_INFO, "OGLSD_SwapBuffers");
}

#pragma mark -
#pragma mark "--- CGLSurfaceData methods ---"

extern LockFunc        MTLSD_Lock;
extern GetRasInfoFunc  MTLSD_GetRasInfo;
extern UnlockFunc      MTLSD_Unlock;


JNIEXPORT void JNICALL
Java_sun_java2d_metal_MTLSurfaceData_initOps
    (JNIEnv *env, jobject cglsd,
     jlong pConfigInfo, jlong pPeerData, jlong layerPtr,
     jint xoff, jint yoff, jboolean isOpaque)
{
    BMTLSDOps *bmtlsdo = (BMTLSDOps *)SurfaceData_InitOps(env, cglsd, sizeof(BMTLSDOps));
    MTLSDOps *mtlsdo = (MTLSDOps *)malloc(sizeof(MTLSDOps));

    J2dTraceLn1(J2D_TRACE_INFO, "MTLSurfaceData_initOps p=%p", bmtlsdo);
    J2dTraceLn1(J2D_TRACE_INFO, "  pPeerData=%p", jlong_to_ptr(pPeerData));
    J2dTraceLn1(J2D_TRACE_INFO, "  layerPtr=%p", jlong_to_ptr(layerPtr));
    J2dTraceLn2(J2D_TRACE_INFO, "  xoff=%d, yoff=%d", (int)xoff, (int)yoff);

    if (mtlsdo == NULL) {
        JNU_ThrowOutOfMemoryError(env, "creating native cgl ops");
        return;
    }

    bmtlsdo->privOps = mtlsdo;

    bmtlsdo->sdOps.Lock               = MTLSD_Lock;
    bmtlsdo->sdOps.GetRasInfo         = MTLSD_GetRasInfo;
    bmtlsdo->sdOps.Unlock             = MTLSD_Unlock;
    bmtlsdo->sdOps.Dispose            = MTLSD_Dispose;

    bmtlsdo->drawableType = MTLSD_UNDEFINED;
    bmtlsdo->needsInit = JNI_TRUE;
    bmtlsdo->xOffset = xoff;
    bmtlsdo->yOffset = yoff;
    bmtlsdo->isOpaque = isOpaque;

    mtlsdo->peerData = (AWTView *)jlong_to_ptr(pPeerData);
    mtlsdo->layer = (MTLLayer *)jlong_to_ptr(layerPtr);
    mtlsdo->configInfo = (MTLGraphicsConfigInfo *)jlong_to_ptr(pConfigInfo);

    if (mtlsdo->configInfo == NULL) {
        free(mtlsdo);
        JNU_ThrowNullPointerException(env, "Config info is null in initOps");
    }
}

JNIEXPORT void JNICALL
Java_sun_java2d_metal_MTLSurfaceData_clearWindow
(JNIEnv *env, jobject cglsd)
{
    J2dTraceLn(J2D_TRACE_INFO, "CGLSurfaceData_clearWindow");

    BMTLSDOps *mtlsdo = (MTLSDOps*) SurfaceData_GetOps(env, cglsd);
    MTLSDOps *cglsdo = (MTLSDOps*) mtlsdo->privOps;

    cglsdo->peerData = NULL;
    cglsdo->layer = NULL;
}

JNIEXPORT void JNICALL
Java_sun_java2d_metal_MTLSurfaceData_validate
    (JNIEnv *env, jobject jsurfacedata,
     jint xoff, jint yoff, jint width, jint height, jboolean isOpaque)
{
    J2dTraceLn2(J2D_TRACE_INFO, "MTLLSurfaceData_validate: w=%d h=%d", width, height);

    BMTLSDOps *mtlsdo = (BMTLSDOps*)SurfaceData_GetOps(env, jsurfacedata);
    mtlsdo->needsInit = JNI_TRUE;
    mtlsdo->xOffset = xoff;
    mtlsdo->yOffset = yoff;

    mtlsdo->width = width;
    mtlsdo->height = height;
    mtlsdo->isOpaque = isOpaque;

    if (mtlsdo->drawableType == MTLSD_WINDOW) {
        // J2dTraceLn4(J2D_TRACE_INFO, "MTLContext_SetSurfaces: w=%d h=%d src=%p dst=%p", width, height, mtlsdo, mtlsdo);
        MTLContext_SetSurfaces(env, ptr_to_jlong(mtlsdo), ptr_to_jlong(mtlsdo));

        // we have to explicitly tell the NSOpenGLContext that its target
        // drawable has changed size
        MTLSDOps *cglsdo = (MTLSDOps *)mtlsdo->privOps;
        MTLContext *mtlc = cglsdo->configInfo->context;

    }
}
