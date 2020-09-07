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

#ifndef MTLSurfaceDataBase_h_Included
#define MTLSurfaceDataBase_h_Included

#include "java_awt_image_AffineTransformOp.h"
#include "sun_java2d_metal_MTLSurfaceData.h"
#include "sun_java2d_pipe_hw_AccelSurface.h"

#include "SurfaceData.h"
#include "Trace.h"
#include "MTLFuncs.h"


/**
 * The MTLPixelFormat structure contains all the information OpenGL needs to
 * know when copying from or into a particular system memory image buffer (via
 * glDrawPixels(), glReadPixels, glTexSubImage2D(), etc).
 *
 *     GLenum format;
 * The pixel format parameter used in glDrawPixels() and other similar calls.
 * Indicates the component ordering for each pixel (e.g. GL_BGRA).
 *
 *     GLenum type;
 * The pixel data type parameter used in glDrawPixels() and other similar
 * calls.  Indicates the data type for an entire pixel or for each component
 * in a pixel (e.g. GL_UNSIGNED_BYTE with GL_BGR means a pixel consists of
 * 3 unsigned byte components, blue first, then green, then red;
 * GL_UNSIGNED_INT_8_8_8_8_REV with GL_BGRA means a pixel consists of 1
 * unsigned integer comprised of four byte components, alpha first, then red,
 * then green, then blue).
 *
 *     jint alignment;
 * The byte alignment parameter used in glPixelStorei(GL_UNPACK_ALIGNMENT).  A
 * value of 4 indicates that each pixel starts on a 4-byte aligned region in
 * memory, and so on.  This alignment parameter helps OpenGL speed up pixel
 * transfer operations by transferring memory in aligned blocks.
 *
 *     jboolean hasAlpha;
 * If true, indicates that this pixel format contains an alpha component.
 *
 *     jboolean isPremult;
 * If true, indicates that this pixel format contains color components that
 * have been pre-multiplied by their corresponding alpha component.
 */
typedef struct {
    //GLenum   format;
    //GLenum   type;
    jint format;
    jint type;
    jint     alignment;
    jboolean hasAlpha;
    jboolean isPremult;
} MTPixelFormat;

/**
 * The MTLSDOps structure describes a native OpenGL surface and contains all
 * information pertaining to the native surface.  Some information about
 * the more important/different fields:
 *
 *     void *privOps;
 * Pointer to native-specific (GLX, WGL, etc.) SurfaceData info, such as the
 * native Drawable handle and GraphicsConfig data.
 *
 *     jint drawableType;
 * The surface type; can be any one of the surface type constants defined
 * below (MTLSD_WINDOW, MTLSD_TEXTURE, etc).
 *
 *     GLenum activeBuffer;
 * Can be either GL_FRONT if this is the front buffer surface of an onscreen
 * window or a pbuffer surface, or GL_BACK if this is the backbuffer surface
 * of an onscreen window.
 *
 *     jboolean isOpaque;
 * If true, the surface should be treated as being fully opaque.  If
 * the underlying surface (e.g. pbuffer) has an alpha channel and isOpaque
 * is true, then we should take appropriate action (i.e. call glColorMask()
 * to disable writes into the alpha channel) to ensure that the surface
 * remains fully opaque.
 *
 *     jboolean needsInit;
 * If true, the surface requires some one-time initialization, which should
 * be performed after a context has been made current to the surface for
 * the first time.
 *
 *     jint x/yOffset
 * The offset in pixels of the OpenGL viewport origin from the lower-left
 * corner of the heavyweight drawable.  For example, a top-level frame on
 * Windows XP has lower-left insets of (4,4).  The OpenGL viewport origin
 * would typically begin at the lower-left corner of the client region (inside
 * the frame decorations), but AWT/Swing will take the insets into account
 * when rendering into that window.  So in order to account for this, we
 * need to adjust the OpenGL viewport origin by an x/yOffset of (-4,-4).  On
 * X11, top-level frames typically don't have this insets issue, so their
 * x/yOffset would be (0,0) (the same applies to pbuffers).
 *
 *     jint width/height;
 * The cached surface bounds.  For offscreen surface types (MTLSD_FBOBJECT,
 * MTLSD_TEXTURE, etc.) these values must remain constant.  Onscreen window
 * surfaces (MTLSD_WINDOW, MTLSD_FLIP_BACKBUFFER, etc.) may have their
 * bounds changed in response to a programmatic or user-initiated event, so
 * these values represent the last known dimensions.  To determine the true
 * current bounds of this surface, query the native Drawable through the
 * privOps field.
 *
 *     GLuint textureID;
 * The texture object handle, as generated by glGenTextures().  If this value
 * is zero, the texture has not yet been initialized.
 *
 *     jint textureWidth/Height;
 * The actual bounds of the texture object for this surface.  If the
 * GL_ARB_texture_non_power_of_two extension is not present, the dimensions
 * of an OpenGL texture object must be a power-of-two (e.g. 64x32 or 128x512).
 * The texture image that we care about has dimensions specified by the width
 * and height fields in this MTLSDOps structure.  For example, if the image
 * to be stored in the texture has dimensions 115x47, the actual OpenGL
 * texture we allocate will have dimensions 128x64 to meet the pow2
 * restriction.  The image bounds within the texture can be accessed using
 * floating point texture coordinates in the range [0.0,1.0].
 *
 *     GLenum textureTarget;
 * The texture target of the texture object for this surface.  If this
 * surface is not backed by a texture, this value is set to zero.  Otherwise,
 * this value is GL_TEXTURE_RECTANGLE_ARB when the GL_ARB_texture_rectangle
 * extension is in use; if not, it is set to GL_TEXTURE_2D.
 *
 *     GLint textureFilter;
 * The current filter state for this texture object (can be either GL_NEAREST
 * or GL_LINEAR).  We cache this value here and check it before updating
 * the filter state to avoid redundant calls to glTexParameteri() when the
 * filter state remains constant (see the MTLSD_UPDATE_TEXTURE_FILTER()
 * macro below).
 *
 *     GLuint fbobjectID, depthID;
 * The object handles for the framebuffer object and depth renderbuffer
 * associated with this surface.  These fields are only used when
 * drawableType is MTLSD_FBOBJECT, otherwise they are zero.
 */
typedef struct {
    SurfaceDataOps               sdOps;
    void                         *privOps;
    jobject                      graphicsConfig;
    jint                         drawableType;
    jint                       activeBuffer;
    jboolean                     isOpaque;
    jboolean                     needsInit;
    jint                         xOffset;
    jint                         yOffset;
    jint                         width;
    jint                         height;
    void*                        pTexture;
    void*                        pStencilData;      // stencil data to be rendered to this buffer
    void*                        pStencilDataBuf;   // MTLBuffer with stencil data
    void*                        pStencilTexture;   // stencil texture byte buffer stencil mask used in main rendering
    void*                        pAAStencilData;    // stencil data for AA rendering
    void*                        pAAStencilDataBuf; // MTLBuffer with AA stencil data
    jint                         textureWidth;
    jint                         textureHeight;
   /* GLenum */ jint                      textureTarget;
   /* GLint  */ jint                      textureFilter;
   /* GLuint */ jint                      fbobjectID;
   /* GLuint  */ jint                     depthID;
} BMTLSDOps;

#define MTLSD_UNDEFINED       sun_java2d_pipe_hw_AccelSurface_UNDEFINED
#define MTLSD_WINDOW          sun_java2d_pipe_hw_AccelSurface_WINDOW
#define MTLSD_TEXTURE         sun_java2d_pipe_hw_AccelSurface_TEXTURE
#define MTLSD_FLIP_BACKBUFFER sun_java2d_pipe_hw_AccelSurface_FLIP_BACKBUFFER
#define MTLSD_RT_TEXTURE        sun_java2d_pipe_hw_AccelSurface_RT_TEXTURE

/**
 * These are shorthand names for the filtering method constants used by
 * image transform methods.
 */
#define MTLSD_XFORM_DEFAULT 0
#define MTLSD_XFORM_NEAREST_NEIGHBOR \
    java_awt_image_AffineTransformOp_TYPE_NEAREST_NEIGHBOR
#define MTLSD_XFORM_BILINEAR \
    java_awt_image_AffineTransformOp_TYPE_BILINEAR

/**
 * The SurfaceRasterFlags structure contains information about raster (of some MTLTexture):
 *
 *     jboolean isOpaque;
 * If true, indicates that this pixel format hasn't alpha component (and values of this component can contain garbage).
 *
 *     jboolean isPremultiplied;
 * If true, indicates that this pixel format contains color components that have been pre-multiplied by their
 * corresponding alpha component.
*/
typedef struct {
    jboolean isOpaque;
    jboolean isPremultiplied;
} SurfaceRasterFlags;

/**
 * Exported methods.
 */
jint MTLSD_Lock(JNIEnv *env,
                SurfaceDataOps *ops, SurfaceDataRasInfo *pRasInfo,
                jint lockflags);
void MTLSD_GetRasInfo(JNIEnv *env,
                      SurfaceDataOps *ops, SurfaceDataRasInfo *pRasInfo);
void MTLSD_Unlock(JNIEnv *env,
                  SurfaceDataOps *ops, SurfaceDataRasInfo *pRasInfo);
void MTLSD_Dispose(JNIEnv *env, SurfaceDataOps *ops);
void MTLSD_Delete(JNIEnv *env, BMTLSDOps *mtlsdo);
jint MTLSD_NextPowerOfTwo(jint val, jint max);

#endif /* MTLSurfaceDataBase_h_Included */
