/*
 * Copyright 2018 JetBrains s.r.o.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

#ifndef MTLContext_h_Included
#define MTLContext_h_Included

#include "sun_java2d_pipe_BufferedContext.h"
#include "sun_java2d_metal_MTLContext.h"
#include "sun_java2d_metal_MTLContext_MTLContextCaps.h"

#import <Metal/Metal.h>
#include "j2d_md.h"
#include "MTLSurfaceDataBase.h"

/**
 * The MTLBlendRule structure encapsulates the two enumerated values that
 * comprise a given Porter-Duff blending (compositing) rule.  For example,
 * the "SrcOver" rule can be represented by:
 *     rule.src = GL_ONE;
 *     rule.dst = GL_ONE_MINUS_SRC_ALPHA;
 *
 *     GLenum src;
 * The constant representing the source factor in this Porter-Duff rule.
 *
 *     GLenum dst;
 * The constant representing the destination factor in this Porter-Duff rule.
 */
typedef struct {
    jint src;
    jint dst;
} MTLBlendRule;

/**
 * The MTLContext structure contains cached state relevant to the native
 * OpenGL context stored within the native ctxInfo field.  Each Java-level
 * MTLContext object is associated with a native-level MTLContext structure.
 * The caps field is a bitfield that expresses the capabilities of the
 * GraphicsConfig associated with this context (see MTLContext.java for
 * the definitions of each capability bit).  The other fields are
 * simply cached values of various elements of the context state, typically
 * used in the MTLContext.set*() methods.
 *
 * Note that the textureFunction field is implicitly set to zero when the
 * MTLContext is created.  The acceptable values (e.g. GL_MODULATE,
 * GL_REPLACE) for this field are never zero, which means we will always
 * validate the texture function state upon the first call to the
 * MTLC_UPDATE_TEXTURE_FUNCTION() macro.
 */
typedef struct {
    jint       caps;
    jint       compState;
    jfloat     extraAlpha;
    jint       xorPixel;
    jint       pixel;
    jubyte     r;
    jubyte     g;
    jubyte     b;
    jubyte     a;
    jint       paintState;
    jboolean   useMask;
    jdouble   *xformMatrix;
    jint     blitTextureID;
    jint      textureFunction;
    jboolean   vertexCacheEnabled;

    id<MTLDevice>               mtlDevice;
    id<MTLLibrary>              mtlLibrary;
    id<MTLRenderPipelineState>  mtlPipelineState;
    id<MTLRenderPipelineState>  mtlBlitPipelineState;
    id<MTLCommandQueue>         mtlCommandQueue;
    id<MTLCommandBuffer>        mtlCommandBuffer;
    id<MTLTexture>              mtlFrameBuffer;
    BOOL                        mtlEmptyCommandBuffer;
    id<MTLBuffer>               mtlVertexBuffer;
    jint                        mtlColor;
    MTLRenderPassDescriptor*    mtlRenderPassDesc;
} MTLContext;

/**
 * See BufferedContext.java for more on these flags...
 */
#define MTLC_NO_CONTEXT_FLAGS \
    sun_java2d_pipe_BufferedContext_NO_CONTEXT_FLAGS
#define MTLC_SRC_IS_OPAQUE    \
    sun_java2d_pipe_BufferedContext_SRC_IS_OPAQUE
#define MTLC_USE_MASK         \
    sun_java2d_pipe_BufferedContext_USE_MASK

/**
 * See MTLContext.java for more on these flags.
 */
#define CAPS_EMPTY           \
    sun_java2d_metal_MTLContext_MTLContextCaps_CAPS_EMPTY
#define CAPS_RT_PLAIN_ALPHA  \
    sun_java2d_metal_MTLContext_MTLContextCaps_CAPS_RT_PLAIN_ALPHA
#define CAPS_RT_TEXTURE_ALPHA       \
    sun_java2d_metal_MTLContext_MTLContextCaps_CAPS_RT_TEXTURE_ALPHA
#define CAPS_RT_TEXTURE_OPAQUE      \
    sun_java2d_metal_MTLContext_MTLContextCaps_CAPS_RT_TEXTURE_OPAQUE
#define CAPS_MULTITEXTURE    \
    sun_java2d_metal_MTLContext_MTLContextCaps_CAPS_MULTITEXTURE
#define CAPS_TEXNONPOW2      \
    sun_java2d_metal_MTLContext_MTLContextCaps_CAPS_TEXNONPOW2
#define CAPS_TEXNONSQUARE    \
    sun_java2d_metal_MTLContext_MTLContextCaps_CAPS_TEXNONSQUARE
#define CAPS_PS20            \
    sun_java2d_metal_MTLContext_MTLContextCaps_CAPS_PS20
#define CAPS_PS30            \
    sun_java2d_metal_MTLContext_MTLContextCaps_CAPS_PS30
#define LAST_SHARED_CAP      \
    sun_java2d_metal_MTLContext_MTLContextCaps_LAST_SHARED_CAP
#define CAPS_EXT_FBOBJECT    \
    sun_java2d_metal_MTLContext_MTLContextCaps_CAPS_EXT_FBOBJECT
#define CAPS_DOUBLEBUFFERED  \
    sun_java2d_metal_MTLContext_MTLContextCaps_CAPS_DOUBLEBUFFERED
#define CAPS_EXT_LCD_SHADER  \
    sun_java2d_metal_MTLContext_MTLContextCaps_CAPS_EXT_LCD_SHADER
#define CAPS_EXT_BIOP_SHADER \
    sun_java2d_metal_MTLContext_MTLContextCaps_CAPS_EXT_BIOP_SHADER
#define CAPS_EXT_GRAD_SHADER \
    sun_java2d_metal_MTLContext_MTLContextCaps_CAPS_EXT_GRAD_SHADER
#define CAPS_EXT_TEXRECT     \
    sun_java2d_metal_MTLContext_MTLContextCaps_CAPS_EXT_TEXRECT
#define CAPS_EXT_TEXBARRIER  \
    sun_java2d_metal_MTLContext_MTLContextCaps_CAPS_EXT_TEXBARRIER

/**
 * Evaluates to true if the given capability bitmask is present for the
 * given MTLContext.  Note that only the constant name needs to be passed as
 * a parameter, as this macro will automatically prepend the full package
 * and class name to the constant name.
 */
#define MTLC_IS_CAP_PRESENT(mtlc, cap) (((mtlc)->caps & (cap)) != 0)

/**
 * At startup we will embed one of the following values in the caps field
 * of MTLContext.  Later we can use this information to select
 * the codepath that offers the best performance for that vendor's
 * hardware and/or drivers.
 */
#define MTLC_VENDOR_OTHER  0
#define MTLC_VENDOR_ATI    1
#define MTLC_VENDOR_NVIDIA 2
#define MTLC_VENDOR_INTEL  3

#define MTLC_VCAP_MASK     0x3
#define MTLC_VCAP_OFFSET   24

#define MTLC_GET_VENDOR(mtlc) \
    (((mtlc)->caps >> MTLC_VCAP_OFFSET) & MTLC_VCAP_MASK)

/**
 * This constant determines the size of the shared tile texture used
 * by a number of image rendering methods.  For example, the blit tile texture
 * will have dimensions with width MTLC_BLIT_TILE_SIZE and height
 * MTLC_BLIT_TILE_SIZE (the tile will always be square).
 */
#define MTLC_BLIT_TILE_SIZE 128

/**
 * Helper macros that update the current texture function state only when
 * it needs to be changed, which helps reduce overhead for small texturing
 * operations.  The filter state is set on a per-context (not per-texture)
 * basis; for example, if we apply one texture using GL_MODULATE followed by
 * another texture using GL_MODULATE (under the same context), there is no
 * need to set the texture function the second time, as that would be
 * redundant.
 */
#define MTLC_INIT_TEXTURE_FUNCTION(mtlc, func)                      \
    do {                                                            \
        j2d_glTexEnvi(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, (func)); \
        (mtlc)->textureFunction = (func);                           \
    } while (0)

#define MTLC_UPDATE_TEXTURE_FUNCTION(mtlc, func)    \
    do {                                            \
        if ((mtlc)->textureFunction != (func)) {    \
            MTLC_INIT_TEXTURE_FUNCTION(mtlc, func); \
        }                                           \
    } while (0)

/**
 * Exported methods.
 */
MTLContext *MTLContext_SetSurfaces(JNIEnv *env, jlong pSrc, jlong pDst);
void MTLContext_ResetClip(MTLContext *mtlc);
void MTLContext_SetRectClip(MTLContext *mtlc, BMTLSDOps *dstOps,
                            jint x1, jint y1, jint x2, jint y2);
void MTLContext_BeginShapeClip(MTLContext *mtlc);
void MTLContext_EndShapeClip(MTLContext *mtlc, BMTLSDOps *dstOps);
void MTLContext_SetExtraAlpha(jfloat ea);
void MTLContext_ResetComposite(MTLContext *mtlc);
void MTLContext_SetAlphaComposite(MTLContext *mtlc,
                                  jint rule, jfloat extraAlpha, jint flags);
void MTLContext_SetXorComposite(MTLContext *mtlc, jint xorPixel);
void MTLContext_ResetTransform(MTLContext *mtlc);
void MTLContext_SetTransform(MTLContext *mtlc,
                             jdouble m00, jdouble m10,
                             jdouble m01, jdouble m11,
                             jdouble m02, jdouble m12);

jboolean MTLContext_InitBlitTileTexture(MTLContext *mtlc);
jint MTLContext_CreateBlitTexture(jint internalFormat, jint pixelFormat,
                                    jint width, jint height);

void MTLContext_DestroyContextResources(MTLContext *mtlc);

jboolean MTLContext_IsExtensionAvailable(const char *extString, char *extName);
void MTLContext_GetExtensionInfo(JNIEnv *env, jint *caps);
jboolean MTLContext_IsVersionSupported(const unsigned char *versionstr);

void* MTLContext_CreateFragmentProgram(const char *fragmentShaderSource);

#endif /* MTLContext_h_Included */
