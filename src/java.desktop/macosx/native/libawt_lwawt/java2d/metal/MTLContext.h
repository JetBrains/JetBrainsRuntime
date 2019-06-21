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

#include <simd/simd.h>

#include "sun_java2d_pipe_BufferedContext.h"
#include "sun_java2d_metal_MTLContext.h"
#include "sun_java2d_metal_MTLContext_MTLContextCaps.h"

#import <Metal/Metal.h>
#include "j2d_md.h"
#include "MTLSurfaceDataBase.h"
#include "MTLTexturePool.h"
#include "MTLPipelineStatesStorage.h"

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
 * The MTLContext class contains cached state relevant to the native
 * MTL context stored within the native ctxInfo field.  Each Java-level
 * MTLContext object is associated with a native-level MTLContext class.
 * */
@interface MTLContext : NSObject

@property jint          compState;
@property jfloat        extraAlpha;
@property jint          alphaCompositeRule;
@property jint          xorPixel;
@property jint          pixel;

@property jdouble       p0;
@property jdouble       p1;
@property jdouble       p3;
@property jboolean      cyclic;
@property jint          pixel1;
@property jint          pixel2;

@property jubyte        r;
@property jubyte        g;
@property jubyte        b;
@property jubyte        a;
@property jint          paintState;
@property jboolean      useMask;
@property jboolean      useTransform;
@property simd_float4x4 transform4x4;
@property jint          blitTextureID;
@property jint          textureFunction;
@property jboolean      vertexCacheEnabled;

@property (readonly, strong)   id<MTLDevice>   device;
@property (strong) id<MTLLibrary>              library;
@property (strong) id<MTLRenderPipelineState>  pipelineState;
@property (strong) id<MTLCommandQueue>         commandQueue;
@property (readonly,strong) id<MTLCommandBuffer>        commandBuffer;
@property (strong) id<MTLBuffer>               vertexBuffer;
@property jint                        color;
@property MTLScissorRect              clipRect;
@property jboolean                    useClip;
@property (strong)MTLPipelineStatesStorage*   pipelineStateStorage;
@property (strong)MTLTexturePool*             texturePool;

- (void)releaseCommandBuffer;
/**
 * Fetches the MTLContext associated with the given destination surface,
 * makes the context current for those surfaces, updates the destination
 * viewport, and then returns a pointer to the MTLContext.
 */
+ (MTLContext*) setSurfacesEnv:(JNIEnv*)env src:(jlong)pSrc dst:(jlong)pDst;

- (id)initWithDevice:(id<MTLDevice>)d shadersLib:(NSString*)shadersLib;

/**
 * Resets the current clip state (disables both scissor and depth tests).
 */
- (void)resetClip;

/**
 * Sets the Metal scissor bounds to the provided rectangular clip bounds.
 */
- (void)setClipRectX1:(jint)x1 Y1:(jint)y1 X2:(jint)x2 Y2:(jint)y2;

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
- (void)beginShapeClip;

/**
 * Finishes setting up the shape clip by resetting the depth func
 * so that future rendering operations will once again be written into the
 * color buffer (while respecting the clip set up in the depth buffer).
 */
- (void)endShapeClip;

/**
 * Initializes the OpenGL state responsible for applying extra alpha.  This
 * step is only necessary for any operation that uses glDrawPixels() or
 * glCopyPixels() with a non-1.0f extra alpha value.  Since the source is
 * always premultiplied, we apply the extra alpha value to both alpha and
 * color components using GL_*_SCALE.
 */
- (void)setExtraAlpha:(jfloat)ea;

/**
 * Resets all OpenGL compositing state (disables blending and logic
 * operations).
 */
- (void)resetComposite;

/**
 * Initializes the OpenGL blending state.  XOR mode is disabled and the
 * appropriate blend functions are setup based on the AlphaComposite rule
 * constant.
 */
- (void)setAlphaCompositeRule:(jint)rule extraAlpha:(jfloat)extraAlpha
                        flags:(jint)flags;

/**
 * Initializes the OpenGL logic op state to XOR mode.  Blending is disabled
 * before enabling logic op mode.  The XOR pixel value will be applied
 * later in the MTLContext_SetColor() method.
 */
- (void)setXorComposite:(jint)xorPixel;
- (jboolean)isBlendingDisabled;

/**
 * Resets the OpenGL transform state back to the identity matrix.
 */
- (void)resetTransform;

/**
 * Initializes the OpenGL transform state by setting the modelview transform
 * using the given matrix parameters.
 *
 * REMIND: it may be worthwhile to add serial id to AffineTransform, so we
 *         could do a quick check to see if the xform has changed since
 *         last time... a simple object compare won't suffice...
 */
- (void)setTransformM00:(jdouble) m00 M10:(jdouble) m10
                    M01:(jdouble) m01 M11:(jdouble) m11
                    M02:(jdouble) m02 M12:(jdouble) m12;

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
- (jboolean)initBlitTileTexture;


/**
 * Creates a 2D texture of the given format and dimensions and returns the
 * texture object identifier.  This method is typically used to create a
 * temporary texture for intermediate work, such as in the
 * MTLContext_InitBlitTileTexture() method below.
 */
- (jint)createBlitTextureFormat:(jint)internalFormat pixelFormat:(jint)pixelFormat
                          width:(jint)width height:(jint)height;

- (void)destroyContextResources;

- (void)setColorR:(int)r G:(int)g B:(int)b A:(int)a;
- (void)setColorInt:(int)pixel;

- (id<MTLRenderCommandEncoder>)createSamplingEncoderForDest:(id<MTLTexture>)dest clearRed:(int)clearRed;
- (id<MTLRenderCommandEncoder>)createSamplingEncoderForDest:(id<MTLTexture>)dest;
- (id<MTLBlitCommandEncoder>)createBlitEncoder;
// NOTE: debug parameners will be removed soon
- (id<MTLRenderCommandEncoder>)createRenderEncoderForDest:(id<MTLTexture>)dest clearRed:(int) clearRed/*debug param*/;
- (id<MTLRenderCommandEncoder>)createRenderEncoderForDest:(id<MTLTexture>)dest;
- (void)setGradientPaintUseMask:(jboolean)useMask cyclic:(jboolean)cyclic p0:(jdouble) p0 p1:(jdouble) p1 p3:(jdouble)p3
                         pixel1:(jint)pixel1 pixel2:(jint) pixel2;
- (void) setEncoderTransform:(id<MTLRenderCommandEncoder>) encoder dest:(id<MTLTexture>) dest;
- (void)dealloc;
@end

/**
 * See BufferedContext.java for more on these flags...
 */
#define MTLC_NO_CONTEXT_FLAGS \
    sun_java2d_pipe_BufferedContext_NO_CONTEXT_FLAGS
#define MTLC_SRC_IS_OPAQUE    \
    sun_java2d_pipe_BufferedContext_SRC_IS_OPAQUE
#define MTLC_USE_MASK         \
    sun_java2d_pipe_BufferedContext_USE_MASK

#endif /* MTLContext_h_Included */
