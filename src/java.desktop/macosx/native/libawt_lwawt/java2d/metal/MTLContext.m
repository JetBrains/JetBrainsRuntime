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

    if (srcOps == NULL || dstOps == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLContext_SetSurfaces: ops are null");
        return NULL;
    }

    J2dTraceLn6(J2D_TRACE_VERBOSE, "MTLContext_SetSurfaces: bsrc=%p (tex=%p type=%d), bdst=%p (tex=%p type=%d)", srcOps, srcOps->pTexture, srcOps->drawableType, dstOps, dstOps->pTexture, dstOps->drawableType);

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
    MTLSDOps *dstCGLOps = (MTLSDOps *)dstOps->privOps;
    mtlc = dstCGLOps->configInfo->context;

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
    mtlc->useClip = JNI_FALSE;
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

    J2dTraceLn4(J2D_TRACE_INFO, "MTLContext_SetRectClip: x=%d y=%d w=%d h=%d", x1, y1, width, height);

    mtlc->mtlClipRect.x = x1;
    mtlc->mtlClipRect.y = y1;
    mtlc->mtlClipRect.width = width;
    mtlc->mtlClipRect.height = height;
    mtlc->useClip = JNI_TRUE;
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
void MTLContext_SetAlphaComposite(MTLContext *mtlc, jint rule, jfloat extraAlpha, jint flags) {
    J2dTracePrimitive("MTLContext_SetAlphaComposite");
    J2dTraceLn3(J2D_TRACE_INFO, "MTLContext_SetAlphaComposite: rule=%d, extraAlpha=%1.2f, flags=%d", rule, extraAlpha, flags);

    mtlc->extraAlpha = extraAlpha;
    mtlc->alphaCompositeRule = rule;
}

jboolean MTLContext_IsBlendingDisabled(MTLContext *mtlc) {
    // TODO: hold case mtlc->alphaCompositeRule == RULE_SrcOver && sun_java2d_pipe_BufferedContext_SRC_IS_OPAQUE
    return mtlc->alphaCompositeRule == RULE_Src && (mtlc->extraAlpha - 1.0f < 0.001f);
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
    J2dTracePrimitive("MTLContext_ResetTransform");
    J2dTraceLn(J2D_TRACE_INFO, "MTLContext_ResetTransform");
    mtlc->useTransform = JNI_FALSE;
}

static void _traceMatrix(simd_float4x4 * mtx) {
    for (int row = 0; row < 4; ++row) {
        J2dTraceLn4(J2D_TRACE_VERBOSE, "  [%lf %lf %lf %lf]",
                    mtx->columns[0][row], mtx->columns[1][row], mtx->columns[2][row], mtx->columns[3][row]);
    }
}

static void _setTransform(MTLContext *mtlc, id<MTLTexture> dest,
                        jdouble m00, jdouble m10,
                        jdouble m01, jdouble m11,
                        jdouble m02, jdouble m12
) {
    if (dest == nil) {
        J2dTraceLn(J2D_TRACE_ERROR, "MTLContext_SetTransformMetal: current dest is null");
        return;
    }

    const double dw = dest.width;
    const double dh = dest.height;

    simd_float4x4 transform;
    memset(&transform, 0, sizeof(transform));
    transform.columns[0][0] = m00;
    transform.columns[0][1] = m10;
    transform.columns[1][0] = m01;
    transform.columns[1][1] = m11;
    transform.columns[3][0] = m02;
    transform.columns[3][1] = m12;
    transform.columns[3][3] = 1.0;
    transform.columns[4][4] = 1.0;


    simd_float4x4 normalize;
    memset(&normalize, 0, sizeof(normalize));
    normalize.columns[0][0] = 2/dw;
    normalize.columns[1][1] = -2/dh;
    normalize.columns[3][0] = -1.f;
    normalize.columns[3][1] = 1.f;
    normalize.columns[3][3] = 1.0;
    normalize.columns[4][4] = 1.0;

    mtlc->transform4x4 = simd_mul(normalize, transform);
    mtlc->useTransform = JNI_TRUE;

    J2dTraceLn(J2D_TRACE_INFO, "MTLContext_SetTransform");
//    _traceMatrix(&(transform));
//    _traceMatrix(&(normalize));
//    _traceMatrix(&(mtlc->transform4x4));

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
    J2dTracePrimitive("MTLContext_SetTransform");

    const BMTLSDOps * bmtlsdOps = MTLRenderQueue_GetCurrentDestination();
    if (bmtlsdOps == NULL) {
        J2dTraceLn(J2D_TRACE_ERROR, "MTLContext_SetTransform: current dest is null");
        return;
    }

    _setTransform(mtlc, bmtlsdOps->pTexture, m00, m10, m01, m11, m02, m12);
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

MTLRenderPassDescriptor * _createRenderPassDesc(id<MTLTexture> dest) {
    MTLRenderPassDescriptor * result = [MTLRenderPassDescriptor renderPassDescriptor];
    if (result == nil)
        return nil;

    if (dest == nil) {
        J2dTraceLn(J2D_TRACE_ERROR, "_createRenderPassDesc: destination texture is null");
        return nil;
    }

    MTLRenderPassColorAttachmentDescriptor * ca = result.colorAttachments[0];
    ca.texture = dest;
    ca.loadAction = MTLLoadActionLoad;
    ca.clearColor = MTLClearColorMake(0.0f, 0.9f, 0.0f, 1.0f);
    ca.storeAction = MTLStoreActionStore;
    return result;
}

void MTLContext_SetColor(MTLContext *mtlc, int r, int g, int b, int a) {
    mtlc->mtlColor = 0;
    mtlc->mtlColor |= (r & (0xFF)) << 16;
    mtlc->mtlColor |= (g & (0xFF)) << 8;
    mtlc->mtlColor |= b & (0xFF);
    mtlc->mtlColor |= (a & (0xFF)) << 24;
    J2dTraceLn4(J2D_TRACE_INFO, "MTLContext_SetColor (%d, %d, %d) %d", r,g,b,a);
}

void MTLContext_SetColorInt(MTLContext *mtlc, int pixel) {
    mtlc->mtlColor = pixel;
    J2dTraceLn5(J2D_TRACE_INFO, "MTLContext_SetColorInt: pixel=%08x [r=%d g=%d b=%d a=%d]", pixel, (pixel >> 16) & (0xFF), (pixel >> 8) & 0xFF, (pixel) & 0xFF, (pixel >> 24) & 0xFF);
}

static id<MTLCommandBuffer> _getCommandBuffer(MTLContext *mtlc) {
    if (mtlc == NULL)
        return nil;
    if (mtlc->mtlCommandBuffer == nil) {
        // NOTE: Command queues are thread-safe and allow multiple outstanding command buffers to be encoded simultaneously.
        mtlc->mtlCommandBuffer = [[mtlc->mtlCommandQueue commandBuffer] retain];// released in [layer blitTexture]
    }
    return mtlc->mtlCommandBuffer;
}

// NOTE: debug parameners will be removed soon
id<MTLRenderCommandEncoder> _createRenderEncoder(MTLContext *mtlc, id<MTLTexture> dest, int clearRed/*debug param*/) {
    id<MTLCommandBuffer> cb = _getCommandBuffer(mtlc);
    if (cb == nil)
        return nil;

    MTLRenderPassDescriptor * rpd = _createRenderPassDesc(dest);
    if (rpd == nil)
        return nil;

    if (clearRed > 0) {//dbg
        MTLRenderPassColorAttachmentDescriptor * ca = rpd.colorAttachments[0];
        ca.loadAction = MTLLoadActionClear;
        ca.clearColor = MTLClearColorMake(clearRed/255.0f, 0.0f, 0.0f, 1.0f);
    }

    // J2dTraceLn1(J2D_TRACE_VERBOSE, "MTLContext: created render encoder to draw on tex=%p", dest);

    id <MTLRenderCommandEncoder> mtlEncoder = [cb renderCommandEncoderWithDescriptor:rpd];

    // set viewport and pipeline state
    dest = rpd.colorAttachments[0].texture;
    MTLViewport vp = {0, 0, dest.width, dest.height, 0, 1};
    [mtlEncoder setViewport:vp];
    [mtlEncoder setRenderPipelineState:mtlc->mtlPipelineState];

    if (mtlc->useClip)
        [mtlEncoder setScissorRect:mtlc->mtlClipRect];

    // set color from ctx
    int r = (mtlc->mtlColor >> 16) & (0xFF);
    int g = (mtlc->mtlColor >> 8) & 0xFF;
    int b = (mtlc->mtlColor) & 0xFF;
    int a = (mtlc->mtlColor >> 24) & 0xFF;

    vector_float4 color = {r / 255.0f, g / 255.0f, b / 255.0f, a / 255.0f};
    struct FrameUniforms uf = {color};
    [mtlEncoder setVertexBytes:&uf length:sizeof(uf) atIndex:FrameUniformBuffer];

    return mtlEncoder;
}

id<MTLRenderCommandEncoder> MTLContext_CreateRenderEncoder(MTLContext *mtlc, id<MTLTexture> dest) {
    return _createRenderEncoder(mtlc, dest, -1);
}

// NOTE: debug parameners will be removed soon
id<MTLRenderCommandEncoder> _createBlitEncoder(MTLContext * mtlc, id<MTLTexture> dest, int clearRed/*debug param*/) {
    id<MTLCommandBuffer> cb = _getCommandBuffer(mtlc);
    if (cb == nil)
        return nil;

    MTLRenderPassDescriptor * rpd = _createRenderPassDesc(dest);
    if (rpd == nil)
        return nil;

    if (clearRed > 0) {//dbg
        MTLRenderPassColorAttachmentDescriptor *ca = rpd.colorAttachments[0];
        ca.loadAction = MTLLoadActionClear;
        ca.clearColor = MTLClearColorMake(clearRed/255.f, 0.0f, 0.0f, 1.0f);
    }

    id <MTLRenderCommandEncoder> mtlEncoder = [cb renderCommandEncoderWithDescriptor:rpd];
    //J2dTraceLn1(J2D_TRACE_VERBOSE, "MTLContext: created sampling encoder to draw on tex=%p", dest);

    // set viewport and pipeline state
    MTLViewport vp = {0, 0, dest.width, dest.height, 0, 1};
    [mtlEncoder setViewport:vp];
    if (mtlc->alphaCompositeRule == RULE_Src)
        [mtlEncoder setRenderPipelineState:mtlc->mtlBlitPipelineState];
    else if (mtlc->alphaCompositeRule == RULE_SrcOver)
        [mtlEncoder setRenderPipelineState:mtlc->mtlBlitSrcOverPipelineState];
    else {
        J2dTraceLn2(J2D_TRACE_ERROR, "MTLContext: alpha-composite rule %d isn't implemented (draw on %p)", mtlc->alphaCompositeRule, dest);
        [mtlEncoder setRenderPipelineState:mtlc->mtlBlitPipelineState];
    }

    return mtlEncoder;
}

// NOTE: debug parameners will be removed soon
id<MTLRenderCommandEncoder> _createBlitTransformEncoder(MTLContext * mtlc, id<MTLTexture> dest, int clearRed/*debug param*/) {
    id<MTLCommandBuffer> cb = _getCommandBuffer(mtlc);
    if (cb == nil)
        return nil;

    MTLRenderPassDescriptor * rpd = _createRenderPassDesc(dest);
    if (rpd == nil)
        return nil;

    if (clearRed > 0) {//dbg
        MTLRenderPassColorAttachmentDescriptor *ca = rpd.colorAttachments[0];
        ca.loadAction = MTLLoadActionClear;
        ca.clearColor = MTLClearColorMake(clearRed/255.f, 0.0f, 0.0f, 1.0f);
    }

    id <MTLRenderCommandEncoder> mtlEncoder = [cb renderCommandEncoderWithDescriptor:rpd];
    //J2dTraceLn1(J2D_TRACE_VERBOSE, "MTLContext: created sampling-transform encoder to draw on tex=%p", dest);

    // set viewport and pipeline state
    MTLViewport vp = {0, 0, dest.width, dest.height, 0, 1};
    [mtlEncoder setViewport:vp];
    if (mtlc->alphaCompositeRule == RULE_Src)
        [mtlEncoder setRenderPipelineState:mtlc->mtlBlitMatrixPipelineState];
    else if (mtlc->alphaCompositeRule == RULE_SrcOver)
        [mtlEncoder setRenderPipelineState:mtlc->mtlBlitMatrixSrcOverPipelineState];
    else {
        J2dTraceLn2(J2D_TRACE_ERROR, "MTLContext: alpha-composite rule %d isn't implemented (draw on %p with transform)", mtlc->alphaCompositeRule, dest);
        [mtlEncoder setRenderPipelineState:mtlc->mtlBlitMatrixPipelineState];
    }

    [mtlEncoder setVertexBytes:&(mtlc->transform4x4) length:sizeof(mtlc->transform4x4) atIndex:FrameUniformBuffer];

    return mtlEncoder;
}

id<MTLRenderCommandEncoder> MTLContext_CreateBlitEncoder(MTLContext * mtlc, id<MTLTexture> dest) {
    if (mtlc->useTransform)
        return _createBlitTransformEncoder(mtlc, dest, -1);
    return _createBlitEncoder(mtlc, dest, -1);
}

#endif /* !HEADLESS */
