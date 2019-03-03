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

#endif /* MTLContext_h_Included */
