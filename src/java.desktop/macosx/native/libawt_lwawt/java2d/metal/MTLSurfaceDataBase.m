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

#include "sun_java2d_metal_MTLSurfaceData.h"

#include "jlong.h"
#include "jni_util.h"
#include "MTLSurfaceData.h"
#import "ThreadUtilities.h"

/**
 * The following methods are implemented in the windowing system (i.e. GLX
 * and WGL) source files.
 */
extern jlong MTLSD_GetNativeConfigInfo(MTLSDOps *mtlsdo);
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
 * Initializes an OpenGL texture object.
 *
 * If the isOpaque parameter is JNI_FALSE, then the texture will have a
 * full alpha channel; otherwise, the texture will be opaque (this can
 * help save VRAM when translucency is not needed).
 *
 * If the GL_ARB_texture_non_power_of_two extension is present (texNonPow2
 * is JNI_TRUE), the actual texture is allowed to have non-power-of-two
 * dimensions, and therefore width==textureWidth and height==textureHeight.
 *
 * Failing that, if the GL_ARB_texture_rectangle extension is present
 * (texRect is JNI_TRUE), the actual texture is allowed to have
 * non-power-of-two dimensions, except that instead of using the usual
 * GL_TEXTURE_2D target, we need to use the GL_TEXTURE_RECTANGLE_ARB target.
 * Note that the GL_REPEAT wrapping mode is not allowed with this target,
 * so if that mode is needed (e.g. as is the case in the TexturePaint code)
 * one should pass JNI_FALSE to avoid using this extension.  Also note that
 * when the texture target is GL_TEXTURE_RECTANGLE_ARB, texture coordinates
 * must be specified in the range [0,width] and [0,height] rather than
 * [0,1] as is the case with the usual GL_TEXTURE_2D target (so take care)!
 *
 * Otherwise, the actual texture must have power-of-two dimensions, and
 * therefore the textureWidth and textureHeight will be the next
 * power-of-two greater than (or equal to) the requested width and height.
 */
static jboolean
MTLSD_InitTextureObject(MTLSDOps *mtlsdo,
                        jboolean isOpaque,
                        jboolean texNonPow2, jboolean texRect,
                        jint width, jint height)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLSD_InitTextureObject");
    return JNI_TRUE;
}

/**
 * Initializes an MTL texture, using the given width and height as
 * a guide.  See MTLSD_InitTextureObject() for more information.
 */
JNIEXPORT jboolean JNICALL
Java_sun_java2d_metal_MTLSurfaceDataBase_initTexture
    (JNIEnv *env, jobject mtlsd,
     jlong pData, jboolean isOpaque,
     jboolean texNonPow2, jboolean texRect,
     jint width, jint height)
{
    BMTLSDOps *mtlsdo = (BMTLSDOps *)jlong_to_ptr(pData);
    J2dTraceLn2(J2D_TRACE_INFO, "MTLSurfaceData_initTexture: w=%d h=%d",
                width, height);

    if (mtlsdo == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR,
            "MTLSurfaceData_initTexture: ops are null");
        return JNI_FALSE;
    }

    /*
     * We only use the GL_ARB_texture_rectangle extension if it is available
     * and the requested bounds are not pow2 (it is probably faster to use
     * GL_TEXTURE_2D for pow2 textures, and besides, our TexturePaint
     * code relies on GL_REPEAT, which is not allowed for
     * GL_TEXTURE_RECTANGLE_ARB targets).
     */
    texRect = texRect && !MTLSD_IsPowerOfTwo(width, height);

    if (!MTLSD_InitTextureObject(mtlsdo, isOpaque, texNonPow2, texRect,
                                 width, height))
    {
        J2dRlsTraceLn(J2D_TRACE_ERROR,
            "MTLSurfaceData_initTexture: could not init texture object");
        return JNI_FALSE;
    }

    MTLSD_SetNativeDimensions(env, mtlsdo,
                              mtlsdo->textureWidth, mtlsdo->textureHeight);

    mtlsdo->drawableType = MTLSD_TEXTURE;
    // other fields (e.g. width, height) are set in MTLSD_InitTextureObject()

    return JNI_TRUE;
}

/**
 * Initializes a framebuffer object, using the given width and height as
 * a guide.  See MTLSD_InitTextureObject() and MTLSD_InitFBObject()
 * for more information.
 */
JNIEXPORT jboolean JNICALL
Java_sun_java2d_metal_MTLSurfaceDataBase_initFBObject
    (JNIEnv *env, jobject mtlsd,
     jlong pData, jboolean isOpaque,
     jboolean texNonPow2, jboolean texRect,
     jint width, jint height)
{

    BMTLSDOps *bmtlsdo = (BMTLSDOps *)jlong_to_ptr(pData);

    if (bmtlsdo == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR,
            "MTLSurfaceData_initFBObject: BMTLSDOps are null");
        return JNI_FALSE;
    }

    MTLSDOps *mtlsdo = (MTLSDOps *)bmtlsdo->privOps;

    if (mtlsdo == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR,
            "MTLSurfaceData_initFBObject: MTLSDOps are null");
        return JNI_FALSE;
    }

    J2dTraceLn2(J2D_TRACE_INFO,
                "MTLSurfaceData_initFBObject: w=%d h=%d",
                width, height);



    if (mtlsdo->configInfo) {
        if (mtlsdo->configInfo->context != NULL) {
            MTLContext* ctx = mtlsdo->configInfo->context;
            if (ctx == NULL) {
              NSLog(@"ctx is NULL");
            } else {
                [ThreadUtilities performOnMainThreadWaiting:NO block:^(){
                        MTLTextureDescriptor *textureDescriptor =
                                    [MTLTextureDescriptor texture2DDescriptorWithPixelFormat: MTLPixelFormatRGBA8Unorm
                                                                                       width: width
                                                                                      height: height
                                                                                   mipmapped: NO];
                        ctx->mtlFrameBuffer = [[ctx->mtlDevice newTextureWithDescriptor: textureDescriptor] retain];
                }];
            }
        }
     }


    bmtlsdo->drawableType = MTLSD_FBOBJECT;

    return JNI_TRUE;
}

/**
 * Initializes a surface in the backbuffer of a given double-buffered
 * onscreen window for use in a BufferStrategy.Flip situation.  The bounds of
 * the backbuffer surface should always be kept in sync with the bounds of
 * the underlying native window.
 */
JNIEXPORT jboolean JNICALL
Java_sun_java2d_metal_MTLSurfaceDataBase_initFlipBackbuffer
    (JNIEnv *env, jobject mtlsd,
     jlong pData)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLSurfaceDataBase_initFlipBackbuffer");
    MTLSDOps *mtlsdo = (MTLSDOps *)jlong_to_ptr(pData);

    J2dTraceLn(J2D_TRACE_INFO, "MTLSurfaceData_initFlipBackbuffer");
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_sun_java2d_metal_MTLSurfaceDataBase_getTextureTarget
    (JNIEnv *env, jobject mtlsd,
     jlong pData)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLSurfaceDataBase_getTextureTarget");
    MTLSDOps *mtlsdo = (MTLSDOps *)jlong_to_ptr(pData);

    J2dTraceLn(J2D_TRACE_INFO, "MTLSurfaceData_getTextureTarget");

    return 0;
}

JNIEXPORT jint JNICALL
Java_sun_java2d_metal_MTLSurfaceDataBase_getTextureID
    (JNIEnv *env, jobject mtlsd,
     jlong pData)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLSurfaceDataBase_getTextureID");
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

#endif /* !HEADLESS */
