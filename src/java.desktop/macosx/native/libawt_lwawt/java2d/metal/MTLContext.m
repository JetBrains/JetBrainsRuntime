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

#include "jlong.h"
#include "jni_util.h"
#include "MTLContext.h"
#include "MTLRenderQueue.h"
#include "MTLSurfaceDataBase.h"
#include "GraphicsPrimitiveMgr.h"
#include "Region.h"

#include "jvm.h"

extern jboolean MTLSD_InitMTLWindow(JNIEnv *env, MTLSDOps *mtlsdo);
extern MTLContext *MTLSD_MakeMTLContextCurrent(JNIEnv *env,
                                               MTLSDOps *srcOps,
                                               MTLSDOps *dstOps);

/**
 * This table contains the standard blending rules (or Porter-Duff compositing
 * factors) used in glBlendFunc(), indexed by the rule constants from the
 * AlphaComposite class.
 */
MTLBlendRule MTStdBlendRules[] = {
};

/** Evaluates to "front" or "back", depending on the value of buf. */
//#define MTLC_ACTIVE_BUFFER_NAME(buf) \
//    (buf == GL_FRONT || buf == GL_COLOR_ATTACHMENT0_EXT) ? "front" : "back"

/**
 * Initializes the viewport and projection matrix, effectively positioning
 * the origin at the top-left corner of the surface.  This allows Java 2D
 * coordinates to be passed directly to OpenGL, which is typically based on
 * a bottom-right coordinate system.  This method also sets the appropriate
 * read and draw buffers.
 */
static void
MTLContext_SetViewport(BMTLSDOps *srcOps,BMTLSDOps *dstOps)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLContext_SetViewport");
    jint width = dstOps->width;
    jint height = dstOps->height;
}

/**
 * Initializes the alpha channel of the current surface so that it contains
 * fully opaque alpha values.
 */
static void
MTLContext_InitAlphaChannel()
{
    //TODO
    J2dTraceNotImplPrimitive("MTLContext_InitAlphaChannel");
    J2dTraceLn(J2D_TRACE_INFO, "MTLContext_InitAlphaChannel");
}

/**
 * Fetches the MTLContext associated with the given destination surface,
 * makes the context current for those surfaces, updates the destination
 * viewport, and then returns a pointer to the MTLContext.
 */
MTLContext *
MTLContext_SetSurfaces(JNIEnv *env, jlong pSrc, jlong pDst)
{
    BMTLSDOps *srcOps = (BMTLSDOps *)jlong_to_ptr(pSrc);
    BMTLSDOps *dstOps = (BMTLSDOps *)jlong_to_ptr(pDst);
    MTLContext *mtlc = NULL;

    J2dTraceLn(J2D_TRACE_INFO, "MTLContext_SetSurfaces");

    if (srcOps == NULL || dstOps == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR,
                      "MTLContext_SetSurfaces: ops are null");
        return NULL;
    }

    J2dTraceLn2(J2D_TRACE_VERBOSE, "  srctype=%d dsttype=%d",
                srcOps->drawableType, dstOps->drawableType);

    if (dstOps->drawableType == MTLSD_TEXTURE) {
        J2dRlsTraceLn(J2D_TRACE_ERROR,
                      "MTLContext_SetSurfaces: texture cannot be used as destination");
        return NULL;
    }

    if (dstOps->drawableType == MTLSD_UNDEFINED) {
        // initialize the surface as an OGLSD_WINDOW
        if (!MTLSD_InitMTLWindow(env, dstOps)) {
            J2dRlsTraceLn(J2D_TRACE_ERROR,
                          "MTLContext_SetSurfaces: could not init OGL window");
            return NULL;
        }
    }

    // make the context current
    mtlc = MTLSD_MakeMTLContextCurrent(env, srcOps, dstOps);
    if (mtlc == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR,
                      "MTLContext_SetSurfaces: could not make context current");
        return NULL;
    }

    // update the viewport
    MTLContext_SetViewport(srcOps, dstOps);

    // perform additional one-time initialization, if necessary
    if (dstOps->needsInit) {
        if (dstOps->isOpaque) {
            // in this case we are treating the destination as opaque, but
            // to do so, first we need to ensure that the alpha channel
            // is filled with fully opaque values (see 6319663)
            MTLContext_InitAlphaChannel();
        }
        dstOps->needsInit = JNI_FALSE;
    }

    return mtlc;
}

/**
 * Resets the current clip state (disables both scissor and depth tests).
 */
void
MTLContext_ResetClip(MTLContext *mtlc)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLContext_ResetClip");
    J2dTraceLn(J2D_TRACE_INFO, "MTLContext_ResetClip");
}

/**
 * Sets the Metal scissor bounds to the provided rectangular clip bounds.
 */
void
MTLContext_SetRectClip(MTLContext *mtlc, BMTLSDOps *dstOps,
                       jint x1, jint y1, jint x2, jint y2)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLContext_SetRectClip");
    jint width = x2 - x1;
    jint height = y2 - y1;

    J2dTraceLn4(J2D_TRACE_INFO,
                "MTLContext_SetRectClip: x=%d y=%d w=%d h=%d",
                x1, y1, width, height);
}

/**
 * Sets up a complex (shape) clip using the OpenGL depth buffer.  This
 * method prepares the depth buffer so that the clip Region spans can
 * be "rendered" into it.  The depth buffer is first cleared, then the
 * depth func is setup so that when we render the clip spans,
 * nothing is rendered into the color buffer, but for each pixel that would
 * be rendered, a non-zero value is placed into that location in the depth
 * buffer.  With depth test enabled, pixels will only be rendered into the
 * color buffer if the corresponding value at that (x,y) location in the
 * depth buffer differs from the incoming depth value.
 */
void
MTLContext_BeginShapeClip(MTLContext *mtlc)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLContext_BeginShapeClip");
    J2dTraceLn(J2D_TRACE_INFO, "MTLContext_BeginShapeClip");
}

/**
 * Finishes setting up the shape clip by resetting the depth func
 * so that future rendering operations will once again be written into the
 * color buffer (while respecting the clip set up in the depth buffer).
 */
void
MTLContext_EndShapeClip(MTLContext *mtlc, BMTLSDOps *dstOps)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLContext_EndShapeClip");
    J2dTraceLn(J2D_TRACE_INFO, "MTLContext_EndShapeClip");
}

/**
 * Initializes the OpenGL state responsible for applying extra alpha.  This
 * step is only necessary for any operation that uses glDrawPixels() or
 * glCopyPixels() with a non-1.0f extra alpha value.  Since the source is
 * always premultiplied, we apply the extra alpha value to both alpha and
 * color components using GL_*_SCALE.
 */
void
MTLContext_SetExtraAlpha(jfloat ea)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLContext_SetExtraAlpha");
    J2dTraceLn1(J2D_TRACE_INFO, "MTLContext_SetExtraAlpha: ea=%f", ea);
}

/**
 * Resets all OpenGL compositing state (disables blending and logic
 * operations).
 */
void
MTLContext_ResetComposite(MTLContext *mtlc)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLContext_ResetComposite");
    J2dTraceLn(J2D_TRACE_INFO, "MTLContext_ResetComposite");
}

/**
 * Initializes the OpenGL blending state.  XOR mode is disabled and the
 * appropriate blend functions are setup based on the AlphaComposite rule
 * constant.
 */
void
MTLContext_SetAlphaComposite(MTLContext *mtlc,
                             jint rule, jfloat extraAlpha, jint flags)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLContext_SetAlphaComposite");
    J2dTraceLn1(J2D_TRACE_INFO,
                "MTLContext_SetAlphaComposite: flags=%d", flags);
}

/**
 * Initializes the OpenGL logic op state to XOR mode.  Blending is disabled
 * before enabling logic op mode.  The XOR pixel value will be applied
 * later in the MTLContext_SetColor() method.
 */
void
MTLContext_SetXorComposite(MTLContext *mtlc, jint xorPixel)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLContext_SetXorComposite");
    J2dTraceLn1(J2D_TRACE_INFO,
                "MTLContext_SetXorComposite: xorPixel=%08x", xorPixel);

}

/**
 * Resets the OpenGL transform state back to the identity matrix.
 */
void
MTLContext_ResetTransform(MTLContext *mtlc)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLContext_ResetTransform");
    J2dTraceLn(J2D_TRACE_INFO, "MTLContext_ResetTransform");
}

/**
 * Initializes the OpenGL transform state by setting the modelview transform
 * using the given matrix parameters.
 *
 * REMIND: it may be worthwhile to add serial id to AffineTransform, so we
 *         could do a quick check to see if the xform has changed since
 *         last time... a simple object compare won't suffice...
 */
void
MTLContext_SetTransform(MTLContext *mtlc,
                        jdouble m00, jdouble m10,
                        jdouble m01, jdouble m11,
                        jdouble m02, jdouble m12)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLContext_SetTransform");
    J2dTraceLn(J2D_TRACE_INFO, "MTLContext_SetTransform");
}

/**
 * Creates a 2D texture of the given format and dimensions and returns the
 * texture object identifier.  This method is typically used to create a
 * temporary texture for intermediate work, such as in the
 * MTLContext_InitBlitTileTexture() method below.
 */
jint
MTLContext_CreateBlitTexture(jint internalFormat, jint pixelFormat,
                             jint width, jint height)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLContext_CreateBlitTexture");
    return 0;
}

/**
 * Initializes a small texture tile for use with tiled blit operations (see
 * MTLBlitLoops.c and MTLMaskBlit.c for usage examples).  The texture ID for
 * the tile is stored in the given MTLContext.  The tile is initially filled
 * with garbage values, but the tile is updated as needed (via
 * glTexSubImage2D()) with real RGBA values used in tiled blit situations.
 * The internal format for the texture is GL_RGBA8, which should be sufficient
 * for storing system memory surfaces of any known format (see PixelFormats
 * for a list of compatible surface formats).
 */
jboolean
MTLContext_InitBlitTileTexture(MTLContext *mtlc)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLContext_InitBlitTileTexture");
    J2dTraceLn(J2D_TRACE_INFO, "MTLContext_InitBlitTileTexture");

    return JNI_TRUE;
}

/**
 * Destroys the OpenGL resources associated with the given MTLContext.
 * It is required that the native context associated with the MTLContext
 * be made current prior to calling this method.
 */
void
MTLContext_DestroyContextResources(MTLContext *mtlc)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLContext_DestroyContextResources");
    J2dTraceLn(J2D_TRACE_INFO, "MTLContext_DestroyContextResources");

    if (mtlc->xformMatrix != NULL) {
        free(mtlc->xformMatrix);
    }

    NSAutoreleasePool * pool = [[NSAutoreleasePool alloc] init];
    [mtlc->mtlDevice release];
    mtlc->mtlDevice = nil;

    [pool drain];
    //if (mtlc->blitTextureID != 0) {
    //}
}

/*
 * Class:     sun_java2d_metal_MTLContext
 * Method:    getMTLIdString
 * Signature: ()Ljava/lang/String;
 */
JNIEXPORT jstring JNICALL Java_sun_java2d_metal_MTLContext_getMTLIdString
  (JNIEnv *env, jclass mtlcc)
{
    char *vendor, *renderer, *version;
    char *pAdapterId;
    jobject ret = NULL;
    int len;


    return NULL;
}

#endif /* !HEADLESS */
