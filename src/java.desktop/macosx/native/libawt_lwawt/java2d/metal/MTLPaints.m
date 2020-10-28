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

#include "MTLPaints.h"

#include "MTLClip.h"

#include "common.h"

#include "sun_java2d_SunGraphics2D.h"
#include "sun_java2d_pipe_BufferedPaints.h"
#import "MTLComposite.h"
#import "MTLBufImgOps.h"
#include "MTLRenderQueue.h"

#define RGBA_TO_V4(c)              \
{                                  \
    (((c) >> 16) & (0xFF))/255.0f, \
    (((c) >> 8) & 0xFF)/255.0f,    \
    ((c) & 0xFF)/255.0f,           \
    (((c) >> 24) & 0xFF)/255.0f    \
}

#define FLOAT_ARR_TO_V4(p) \
{                      \
    p[0], \
    p[1], \
    p[2], \
    p[3]  \
}

static MTLRenderPipelineDescriptor * templateRenderPipelineDesc = nil;
static MTLRenderPipelineDescriptor * templateTexturePipelineDesc = nil;
static MTLRenderPipelineDescriptor * templateAATexturePipelineDesc = nil;
static void setTxtUniforms(
        id<MTLRenderCommandEncoder> encoder, int color, int mode, int interpolation, bool repeat, jfloat extraAlpha,
        const SurfaceRasterFlags * srcFlags, const SurfaceRasterFlags * dstFlags
);

static void initTemplatePipelineDescriptors() {
    if (templateRenderPipelineDesc != nil && templateTexturePipelineDesc != nil)
        return;

    MTLVertexDescriptor *vertDesc = [[MTLVertexDescriptor new] autorelease];
    vertDesc.attributes[VertexAttributePosition].format = MTLVertexFormatFloat2;
    vertDesc.attributes[VertexAttributePosition].offset = 0;
    vertDesc.attributes[VertexAttributePosition].bufferIndex = MeshVertexBuffer;
    vertDesc.layouts[MeshVertexBuffer].stride = sizeof(struct Vertex);
    vertDesc.layouts[MeshVertexBuffer].stepRate = 1;
    vertDesc.layouts[MeshVertexBuffer].stepFunction = MTLVertexStepFunctionPerVertex;

    templateRenderPipelineDesc = [MTLRenderPipelineDescriptor new];
    templateRenderPipelineDesc.sampleCount = 1;
    templateRenderPipelineDesc.vertexDescriptor = vertDesc;
    templateRenderPipelineDesc.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
    templateRenderPipelineDesc.colorAttachments[0].rgbBlendOperation =   MTLBlendOperationAdd;
    templateRenderPipelineDesc.colorAttachments[0].alphaBlendOperation = MTLBlendOperationAdd;
    templateRenderPipelineDesc.colorAttachments[0].sourceRGBBlendFactor = MTLBlendFactorOne;
    templateRenderPipelineDesc.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorOne;
    templateRenderPipelineDesc.colorAttachments[0].destinationRGBBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
    templateRenderPipelineDesc.colorAttachments[0].destinationAlphaBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
    templateRenderPipelineDesc.label = @"template_render";

    templateTexturePipelineDesc = [templateRenderPipelineDesc copy];
    templateTexturePipelineDesc.vertexDescriptor.attributes[VertexAttributeTexPos].format = MTLVertexFormatFloat2;
    templateTexturePipelineDesc.vertexDescriptor.attributes[VertexAttributeTexPos].offset = 2*sizeof(float);
    templateTexturePipelineDesc.vertexDescriptor.attributes[VertexAttributeTexPos].bufferIndex = MeshVertexBuffer;
    templateTexturePipelineDesc.vertexDescriptor.layouts[MeshVertexBuffer].stride = sizeof(struct TxtVertex);
    templateTexturePipelineDesc.vertexDescriptor.layouts[MeshVertexBuffer].stepRate = 1;
    templateTexturePipelineDesc.vertexDescriptor.layouts[MeshVertexBuffer].stepFunction = MTLVertexStepFunctionPerVertex;
    templateTexturePipelineDesc.label = @"template_texture";

    templateAATexturePipelineDesc = [templateTexturePipelineDesc copy];
    templateAATexturePipelineDesc.label = @"template_aa_texture";

}


@implementation MTLColorPaint {
// color-mode
jint _color;
}
- (id)initWithColor:(jint)color {
    self = [super initWithState:sun_java2d_SunGraphics2D_PAINT_ALPHACOLOR];

    if (self) {
        _color = color;
    }
    return self;
}

- (jint)color {
    return _color;
}

- (BOOL)isEqual:(MTLColorPaint *)other {
    if (other == self)
        return YES;
    if (!other || ![[other class] isEqual:[self class]])
        return NO;

    return _color == other->_color;
}

- (NSUInteger)hash {
    NSUInteger h = [super hash];
    h = h*31 + _color;
    return h;
}

- (NSString *)description {
    return [NSString stringWithFormat:
            @"[r=%d g=%d b=%d a=%d]",
            (_color >> 16) & (0xFF),
            (_color >> 8) & 0xFF,
            (_color) & 0xFF,
            (_color >> 24) & 0xFF];
}

- (void)setPipelineState:(id<MTLRenderCommandEncoder>)encoder
                 context:(MTLContext *)mtlc
           renderOptions:(const RenderOptions *)renderOptions
    pipelineStateStorage:(MTLPipelineStatesStorage *)pipelineStateStorage
{
    initTemplatePipelineDescriptors();

    MTLRenderPipelineDescriptor *rpDesc = nil;

    NSString *vertShader = @"vert_col";
    NSString *fragShader = @"frag_col";

    if (renderOptions->isTexture) {
        vertShader = @"vert_txt";
        fragShader = @"frag_txt";
        rpDesc = [[templateTexturePipelineDesc copy] autorelease];
        if (renderOptions->isAA) {
            fragShader = @"aa_frag_txt";
            rpDesc = [[templateAATexturePipelineDesc copy] autorelease];
        }
        if (renderOptions->isText) {
            fragShader = @"frag_text";
        }
        setTxtUniforms(encoder, _color, 1,
                       renderOptions->interpolation, NO, [mtlc.composite getExtraAlpha], &renderOptions->srcFlags,
                       &renderOptions->dstFlags);
    } else {
        rpDesc = [[templateRenderPipelineDesc copy] autorelease];
    }

    struct FrameUniforms uf = {RGBA_TO_V4(_color)};
    [encoder setVertexBytes:&uf length:sizeof(uf) atIndex:FrameUniformBuffer];

    id <MTLRenderPipelineState> pipelineState = [pipelineStateStorage getPipelineState:rpDesc
                                                                        vertexShaderId:vertShader
                                                                      fragmentShaderId:fragShader
                                                                             composite:mtlc.composite
                                                                         renderOptions:renderOptions
                                                                         stencilNeeded:[mtlc.clip isShape]];
    [encoder setRenderPipelineState:pipelineState];
}

- (void)setXorModePipelineState:(id<MTLRenderCommandEncoder>)encoder
                        context:(MTLContext *)mtlc
                  renderOptions:(const RenderOptions *)renderOptions
           pipelineStateStorage:(MTLPipelineStatesStorage *)pipelineStateStorage
{
    initTemplatePipelineDescriptors();
    NSString * vertShader = @"vert_col_xorMode";
    NSString * fragShader = @"frag_col_xorMode";
    MTLRenderPipelineDescriptor * rpDesc = nil;
    jint xorColor = (jint) [mtlc.composite getXorColor];
    // Calculate _color ^ xorColor for RGB components
    // This color gets XORed with destination framebuffer pixel color
    const int col = _color ^ xorColor;
    BMTLSDOps *dstOps = MTLRenderQueue_GetCurrentDestination();

    if (renderOptions->isTexture) {
        vertShader = @"vert_txt_xorMode";
        fragShader = @"frag_txt_xorMode";
        rpDesc = [[templateTexturePipelineDesc copy] autorelease];

        setTxtUniforms(encoder, col, 1,
                       renderOptions->interpolation, NO, [mtlc.composite getExtraAlpha],
                       &renderOptions->srcFlags, &renderOptions->dstFlags);
        [encoder setFragmentBytes:&xorColor length:sizeof(xorColor) atIndex:0];

        [encoder setFragmentTexture:dstOps->pTexture atIndex:1];
    } else {
        struct FrameUniforms uf = {RGBA_TO_V4(col)};
        rpDesc = [[templateRenderPipelineDesc copy] autorelease];

        [encoder setVertexBytes:&uf length:sizeof(uf) atIndex:FrameUniformBuffer];
        [encoder setFragmentTexture:dstOps->pTexture atIndex:0];
    }

    id <MTLRenderPipelineState> pipelineState = [pipelineStateStorage getPipelineState:rpDesc
                                                                        vertexShaderId:vertShader
                                                                      fragmentShaderId:fragShader
                                                                             composite:mtlc.composite
                                                                         renderOptions:renderOptions
                                                                         stencilNeeded:[mtlc.clip isShape]];
    [encoder setRenderPipelineState:pipelineState];
}
@end

@implementation MTLBaseGradPaint {
    jboolean      _useMask;
@protected
    jint          _cyclic;
}

- (id)initWithState:(jint)state mask:(jboolean)useMask cyclic:(jboolean)cyclic {
    self = [super initWithState:state];

    if (self) {
        _useMask = useMask;
        _cyclic = cyclic;
    }
    return self;
}

- (BOOL)isEqual:(MTLBaseGradPaint *)other {
    if (other == self)
        return YES;
    if (!other || ![[other class] isEqual:[self class]])
        return NO;

    return [super isEqual:self] && _cyclic == other->_cyclic && _useMask == other->_useMask;
}

- (NSUInteger)hash {
    NSUInteger h = [super hash];
    h = h*31 + _cyclic;
    h = h*31 + _useMask;
    return h;
}

@end

@implementation MTLGradPaint {
    jdouble _p0;
    jdouble _p1;
    jdouble _p3;
    jint _pixel1;
    jint _pixel2;
}
- (id)initWithUseMask:(jboolean)useMask
               cyclic:(jboolean)cyclic
                   p0:(jdouble)p0
                   p1:(jdouble)p1
                   p3:(jdouble)p3
               pixel1:(jint)pixel1
               pixel2:(jint)pixel2
{
    self = [super initWithState:sun_java2d_SunGraphics2D_PAINT_GRADIENT
                           mask:useMask
                         cyclic:cyclic];

    if (self) {
        _p0 = p0;
        _p1 = p1;
        _p3 = p3;
        _pixel1 = pixel1;
        _pixel2 = pixel2;
    }
    return self;
}

- (BOOL)isEqual:(MTLGradPaint *)other {
    if (other == self)
        return YES;
    if (!other || ![[other class] isEqual:[self class]])
        return NO;

    return [super isEqual:self] && _p0 == other->_p0 &&
           _p1 == other->_p1 && _p3 == other->_p3 &&
           _pixel1 == other->_pixel1 && _pixel2 == other->_pixel2;
}

- (NSUInteger)hash {
    NSUInteger h = [super hash];
    h = h*31 + [@(_p0) hash];
    h = h*31 + [@(_p1) hash];;
    h = h*31 + [@(_p3) hash];;
    h = h*31 + _pixel1;
    h = h*31 + _pixel2;
    return h;
}
- (NSString *)description {
    return [NSString stringWithFormat:@"gradient"];
}

- (void)setPipelineState:(id)encoder
                 context:(MTLContext *)mtlc
           renderOptions:(const RenderOptions *)renderOptions
    pipelineStateStorage:(MTLPipelineStatesStorage *)pipelineStateStorage
{
    initTemplatePipelineDescriptors();
    MTLRenderPipelineDescriptor *rpDesc = nil;

    NSString *vertShader = @"vert_grad";
    NSString *fragShader = @"frag_grad";

    struct GradFrameUniforms uf = {
            {_p0, _p1, _p3},
            RGBA_TO_V4(_pixel1),
            RGBA_TO_V4(_pixel2),
            _cyclic,
            [mtlc.composite getExtraAlpha]
    };
    [encoder setFragmentBytes:&uf length:sizeof(uf) atIndex:0];

    if (renderOptions->isTexture) {
        vertShader = @"vert_txt_grad";
        fragShader = @"frag_txt_grad";
        rpDesc = [[templateTexturePipelineDesc copy] autorelease];
    } else {
        rpDesc = [[templateRenderPipelineDesc copy] autorelease];
    }

    id <MTLRenderPipelineState> pipelineState = [pipelineStateStorage getPipelineState:rpDesc
                                                                        vertexShaderId:vertShader
                                                                      fragmentShaderId:fragShader
                                                                             composite:mtlc.composite
                                                                         renderOptions:renderOptions
                                                                         stencilNeeded:[mtlc.clip isShape]];
    [encoder setRenderPipelineState:pipelineState];
}

- (void)setXorModePipelineState:(id)encoder
                        context:(MTLContext *)mtlc
                  renderOptions:(const RenderOptions *)renderOptions
           pipelineStateStorage:(MTLPipelineStatesStorage *)pipelineStateStorage
{
    // This block is not reached in current implementation.
    // Gradient paint XOR mode rendering uses a tile based rendering using a SW pipe (similar to OGL)
    NSString* vertShader = @"vert_grad_xorMode";
    NSString* fragShader = @"frag_grad_xorMode";
    MTLRenderPipelineDescriptor *rpDesc = [[templateRenderPipelineDesc copy] autorelease];
    jint xorColor = (jint) [mtlc.composite getXorColor];

    struct GradFrameUniforms uf = {
            {_p0, _p1, _p3},
            RGBA_TO_V4(_pixel1 ^ xorColor),
            RGBA_TO_V4(_pixel2 ^ xorColor),
            _cyclic,
            [mtlc.composite getExtraAlpha]
    };

    [encoder setFragmentBytes: &uf length:sizeof(uf) atIndex:0];
    BMTLSDOps *dstOps = MTLRenderQueue_GetCurrentDestination();
    [encoder setFragmentTexture:dstOps->pTexture atIndex:0];

    J2dTraceLn(J2D_TRACE_INFO, "MTLPaints - setXorModePipelineState -- PAINT_GRADIENT");

    id <MTLRenderPipelineState> pipelineState = [pipelineStateStorage getPipelineState:rpDesc
                                                                        vertexShaderId:vertShader
                                                                      fragmentShaderId:fragShader
                                                                             composite:mtlc.composite
                                                                         renderOptions:renderOptions
                                                                         stencilNeeded:[mtlc.clip isShape]];
    [encoder setRenderPipelineState:pipelineState];
}

@end

@implementation MTLBaseMultiGradPaint {
    @protected
    jboolean _linear;
    jint _numFracts;
    jfloat _fract[GRAD_MAX_FRACTIONS];
    jint _pixel[GRAD_MAX_FRACTIONS];
}

- (id)initWithState:(jint)state
               mask:(jboolean)useMask
             linear:(jboolean)linear
        cycleMethod:(jboolean)cycleMethod
           numStops:(jint)numStops
          fractions:(jfloat *)fractions
             pixels:(jint *)pixels
{
    self = [super initWithState:state
                           mask:useMask
                         cyclic:cycleMethod];

    if (self) {
        _linear = linear;
        memcpy(_fract, fractions,numStops*sizeof(jfloat));
        memcpy(_pixel, pixels, numStops*sizeof(jint));
        _numFracts = numStops;
    }
    return self;
}

- (BOOL)isEqual:(MTLBaseMultiGradPaint *)other {
    if (other == self)
        return YES;
    if (!other || ![[other class] isEqual:[self class]])
        return NO;

    if (_numFracts != other->_numFracts || ![super isEqual:self])
        return NO;
    for (int i = 0; i < _numFracts; i++) {
        if (_fract[i] != other->_fract[i]) return NO;
        if (_pixel[i] != other->_pixel[i]) return NO;
    }
    return YES;
}

- (NSUInteger)hash {
    NSUInteger h = [super hash];
    h = h*31 + _numFracts;
    for (int i = 0; i < _numFracts; i++) {
        h = h*31 + [@(_fract[i]) hash];
        h = h*31 + _pixel[i];
    }
    return h;
}

@end

@implementation MTLLinearGradPaint {
    jdouble _p0;
    jdouble _p1;
    jdouble _p3;
}
- (void)setPipelineState:(id)encoder
                 context:(MTLContext *)mtlc
           renderOptions:(const RenderOptions *)renderOptions
    pipelineStateStorage:(MTLPipelineStatesStorage *)pipelineStateStorage

{
    MTLRenderPipelineDescriptor *rpDesc = nil;

    NSString *vertShader = @"vert_grad";
    NSString *fragShader = @"frag_lin_grad";

    if (renderOptions->isTexture) {
        vertShader = @"vert_txt_grad";
        fragShader = @"frag_txt_lin_grad";
        rpDesc = [[templateTexturePipelineDesc copy] autorelease];
    } else {
        rpDesc = [[templateRenderPipelineDesc copy] autorelease];
    }

    struct LinGradFrameUniforms uf = {
            {_p0, _p1, _p3},
            {},
            {},
            _numFracts,
            _linear,
            _cyclic,
            [mtlc.composite getExtraAlpha]
    };

    memcpy(uf.fract, _fract, _numFracts*sizeof(jfloat));
    for (int i = 0; i < _numFracts; i++) {
        vector_float4 v = RGBA_TO_V4(_pixel[i]);
        uf.color[i] = v;
    }
    [encoder setFragmentBytes:&uf length:sizeof(uf) atIndex:0];

    id <MTLRenderPipelineState> pipelineState = [pipelineStateStorage getPipelineState:rpDesc
                                                                        vertexShaderId:vertShader
                                                                      fragmentShaderId:fragShader
                                                                             composite:mtlc.composite
                                                                         renderOptions:renderOptions
                                                                         stencilNeeded:[mtlc.clip isShape]];
    [encoder setRenderPipelineState:pipelineState];
}

- (id)initWithUseMask:(jboolean)useMask
               linear:(jboolean)linear
          cycleMethod:(jboolean)cycleMethod
             numStops:(jint)numStops
                   p0:(jfloat)p0
                   p1:(jfloat)p1
                   p3:(jfloat)p3
            fractions:(jfloat *)fractions
               pixels:(jint *)pixels
{
    self = [super initWithState:sun_java2d_SunGraphics2D_PAINT_LIN_GRADIENT
                           mask:useMask
                         linear:linear
                    cycleMethod:cycleMethod
                       numStops:numStops
                      fractions:fractions
                         pixels:pixels];

    if (self) {
        _p0 = p0;
        _p1 = p1;
        _p3 = p3;
    }
    return self;
}

- (BOOL)isEqual:(MTLLinearGradPaint *)other {
    if (other == self)
        return YES;
    if (!other || ![[other class] isEqual:[self class]] || ![super isEqual:other])
        return NO;

    return _p0 == other->_p0 && _p1 == other->_p1 && _p3 == other->_p3;
}

- (NSUInteger)hash {
    NSUInteger h = [super hash];
    h = h*31 + [@(_p0) hash];
    h = h*31 + [@(_p1) hash];
    h = h*31 + [@(_p3) hash];
    return h;
}

- (NSString *)description {
    return [NSString stringWithFormat:@"linear_gradient"];
}
@end

@implementation MTLRadialGradPaint {
    jfloat _m00;
    jfloat _m01;
    jfloat _m02;
    jfloat _m10;
    jfloat _m11;
    jfloat _m12;
    jfloat _focusX;
}

- (id)initWithUseMask:(jboolean)useMask
               linear:(jboolean)linear
          cycleMethod:(jint)cycleMethod
             numStops:(jint)numStops
                  m00:(jfloat)m00
                  m01:(jfloat)m01
                  m02:(jfloat)m02
                  m10:(jfloat)m10
                  m11:(jfloat)m11
                  m12:(jfloat)m12
               focusX:(jfloat)focusX
            fractions:(void *)fractions
               pixels:(void *)pixels
{
    self = [super initWithState:sun_java2d_SunGraphics2D_PAINT_RAD_GRADIENT
                           mask:useMask
                         linear:linear
                    cycleMethod:cycleMethod
                       numStops:numStops
                      fractions:fractions
                         pixels:pixels];

    if (self) {
        _m00 = m00;
        _m01 = m01;
        _m02 = m02;
        _m10 = m10;
        _m11 = m11;
        _m12 = m12;
        _focusX = focusX;
    }
    return self;
}

- (BOOL)isEqual:(MTLRadialGradPaint *)other {
    if (other == self)
        return YES;
    if (!other || ![[other class] isEqual:[self class]]
            || ![super isEqual:self])
        return NO;

    return _m00 == other->_m00 && _m01 == other->_m01 && _m02 == other->_m02 &&
           _m10 == other->_m10 && _m11 == other->_m11 && _m12 == other->_m12 &&
           _focusX == other->_focusX;
}

- (NSUInteger)hash {
    NSUInteger h = [super hash];
    h = h*31 + [@(_m00) hash];
    h = h*31 + [@(_m01) hash];
    h = h*31 + [@(_m02) hash];
    h = h*31 + [@(_m10) hash];
    h = h*31 + [@(_m11) hash];
    h = h*31 + [@(_m12) hash];
    return h;
}

- (NSString *)description {
    return [NSString stringWithFormat:@"radial_gradient"];
}

- (void)setPipelineState:(id)encoder
                 context:(MTLContext *)mtlc
           renderOptions:(const RenderOptions *)renderOptions
    pipelineStateStorage:(MTLPipelineStatesStorage *)pipelineStateStorage
{
    MTLRenderPipelineDescriptor *rpDesc = nil;

    NSString *vertShader = @"vert_grad";
    NSString *fragShader = @"frag_rad_grad";

    if (renderOptions->isTexture) {
        vertShader = @"vert_txt_grad";
        fragShader = @"frag_txt_rad_grad";
        rpDesc = [[templateTexturePipelineDesc copy] autorelease];
    } else {
        rpDesc = [[templateRenderPipelineDesc copy] autorelease];
    }

    struct RadGradFrameUniforms uf = {
            {},
            {},
            _numFracts,
            _linear,
            _cyclic,
            {_m00, _m01, _m02},
            {_m10, _m11, _m12},
            {},
            [mtlc.composite getExtraAlpha]
    };

    uf.precalc[0] = _focusX;
    uf.precalc[1] = 1.0 - (_focusX * _focusX);
    uf.precalc[2] = 1.0 / uf.precalc[1];

    memcpy(uf.fract, _fract, _numFracts*sizeof(jfloat));
    for (int i = 0; i < _numFracts; i++) {
        vector_float4 v = RGBA_TO_V4(_pixel[i]);
        uf.color[i] = v;
    }
    [encoder setFragmentBytes:&uf length:sizeof(uf) atIndex:0];
    id <MTLRenderPipelineState> pipelineState = [pipelineStateStorage getPipelineState:rpDesc
                                                                        vertexShaderId:vertShader
                                                                      fragmentShaderId:fragShader
                                                                             composite:mtlc.composite
                                                                         renderOptions:renderOptions
                                                                         stencilNeeded:[mtlc.clip isShape]];
    [encoder setRenderPipelineState:pipelineState];
}


@end

@implementation MTLTexturePaint {
    struct AnchorData _anchor;
    id <MTLTexture> _paintTexture;
    jboolean _isOpaque;
}

- (id)initWithUseMask:(jboolean)useMask
              textureID:(id)textureId
               isOpaque:(jboolean)isOpaque
                 filter:(jboolean)filter
                    xp0:(jdouble)xp0
                    xp1:(jdouble)xp1
                    xp3:(jdouble)xp3
                    yp0:(jdouble)yp0
                    yp1:(jdouble)yp1
                    yp3:(jdouble)yp3
{
    self = [super initWithState:sun_java2d_SunGraphics2D_PAINT_TEXTURE];

    if (self) {
        _paintTexture = textureId;
        _anchor.xParams[0] = xp0;
        _anchor.xParams[1] = xp1;
        _anchor.xParams[2] = xp3;

        _anchor.yParams[0] = yp0;
        _anchor.yParams[1] = yp1;
        _anchor.yParams[2] = yp3;
        _isOpaque = isOpaque;
    }
    return self;

}

- (BOOL)isEqual:(MTLTexturePaint *)other {
    if (other == self)
        return YES;
    if (!other || ![[other class] isEqual:[self class]])
        return NO;

    return [_paintTexture isEqual:other->_paintTexture]
            && _anchor.xParams[0] == other->_anchor.xParams[0]
            && _anchor.xParams[1] == other->_anchor.xParams[1]
            && _anchor.xParams[2] == other->_anchor.xParams[2]
            && _anchor.yParams[0] == other->_anchor.yParams[0]
            && _anchor.yParams[1] == other->_anchor.yParams[1]
            && _anchor.yParams[2] == other->_anchor.yParams[2];
}

- (NSString *)description {
    return [NSString stringWithFormat:@"texture_paint"];
}

- (void)setPipelineState:(id)encoder
                 context:(MTLContext *)mtlc
           renderOptions:(const RenderOptions *)renderOptions
    pipelineStateStorage:(MTLPipelineStatesStorage *)pipelineStateStorage
{
    initTemplatePipelineDescriptors();
    MTLRenderPipelineDescriptor *rpDesc = nil;

    NSString* vertShader = @"vert_tp";
    NSString* fragShader = @"frag_tp";

    [encoder setVertexBytes:&_anchor length:sizeof(_anchor) atIndex:FrameUniformBuffer];

    if (renderOptions->isTexture) {
        vertShader = @"vert_txt_tp";
        fragShader = @"frag_txt_tp";
        rpDesc = [[templateTexturePipelineDesc copy] autorelease];
        [encoder setFragmentTexture:_paintTexture atIndex:1];
    } else {
        rpDesc = [[templateRenderPipelineDesc copy] autorelease];
        [encoder setFragmentTexture:_paintTexture atIndex:0];
    }
    const SurfaceRasterFlags srcFlags = {_isOpaque, renderOptions->srcFlags.isPremultiplied};
    setTxtUniforms(encoder, 0, 0,
                   renderOptions->interpolation, YES, [mtlc.composite getExtraAlpha],
                   &srcFlags, &renderOptions->dstFlags);

    id <MTLRenderPipelineState> pipelineState = [pipelineStateStorage getPipelineState:rpDesc
                                                                        vertexShaderId:vertShader
                                                                      fragmentShaderId:fragShader
                                                                             composite:mtlc.composite
                                                                         renderOptions:renderOptions
                                                                         stencilNeeded:[mtlc.clip isShape]];
    [encoder setRenderPipelineState:pipelineState];
}

- (void)setXorModePipelineState:(id)encoder
                        context:(MTLContext *)mtlc
                  renderOptions:(const RenderOptions *)renderOptions
           pipelineStateStorage:(MTLPipelineStatesStorage *)pipelineStateStorage
{
    // This block is not reached in current implementation.
    // Texture paint XOR mode rendering uses a tile based rendering using a SW pipe (similar to OGL)
    NSString* vertShader = @"vert_tp_xorMode";
    NSString* fragShader = @"frag_tp_xorMode";
    MTLRenderPipelineDescriptor *rpDesc = [[templateRenderPipelineDesc copy] autorelease];
    jint xorColor = (jint) [mtlc.composite getXorColor];

    [encoder setVertexBytes:&_anchor length:sizeof(_anchor) atIndex:FrameUniformBuffer];
    [encoder setFragmentTexture:_paintTexture atIndex: 0];
    BMTLSDOps *dstOps = MTLRenderQueue_GetCurrentDestination();
    [encoder setFragmentTexture:dstOps->pTexture atIndex:1];
    [encoder setFragmentBytes:&xorColor length:sizeof(xorColor) atIndex: 0];

    J2dTraceLn(J2D_TRACE_INFO, "MTLPaints - setXorModePipelineState -- PAINT_TEXTURE");

    id <MTLRenderPipelineState> pipelineState = [pipelineStateStorage getPipelineState:rpDesc
                                                                        vertexShaderId:vertShader
                                                                      fragmentShaderId:fragShader
                                                                             composite:mtlc.composite
                                                                         renderOptions:renderOptions
                                                                         stencilNeeded:[mtlc.clip isShape]];
    [encoder setRenderPipelineState:pipelineState];
}

@end

@implementation MTLPaint {
    jint _paintState;
}

- (instancetype)init {
    self = [super init];
    if (self) {
        _paintState = sun_java2d_SunGraphics2D_PAINT_UNDEFINED;
    }

    return self;
}

- (instancetype)initWithState:(jint)state {
    self = [super init];
    if (self) {
        _paintState = state;
    }
    return self;
}

- (BOOL)isEqual:(MTLPaint *)other {
    if (other == self)
        return YES;
    if (!other || ![other isKindOfClass:[self class]])
        return NO;
    return _paintState == other->_paintState;
}

- (NSUInteger)hash {
    return _paintState;
}

- (NSString *)description {
    return @"unknown-paint";
}

static id<MTLSamplerState> samplerNearestClamp = nil;
static id<MTLSamplerState> samplerLinearClamp = nil;
static id<MTLSamplerState> samplerNearestRepeat = nil;
static id<MTLSamplerState> samplerLinearRepeat = nil;

void initSamplers(id<MTLDevice> device) {
    // TODO: move this code into SamplerManager (need implement)

    if (samplerNearestClamp != nil)
        return;

    MTLSamplerDescriptor *samplerDescriptor = [[MTLSamplerDescriptor new] autorelease];

    samplerDescriptor.rAddressMode = MTLSamplerAddressModeClampToEdge;
    samplerDescriptor.sAddressMode = MTLSamplerAddressModeClampToEdge;
    samplerDescriptor.tAddressMode = MTLSamplerAddressModeClampToEdge;

    samplerDescriptor.minFilter = MTLSamplerMinMagFilterNearest;
    samplerDescriptor.magFilter = MTLSamplerMinMagFilterNearest;
    samplerNearestClamp = [device newSamplerStateWithDescriptor:samplerDescriptor];

    samplerDescriptor.minFilter = MTLSamplerMinMagFilterLinear;
    samplerDescriptor.magFilter = MTLSamplerMinMagFilterLinear;
    samplerLinearClamp = [device newSamplerStateWithDescriptor:samplerDescriptor];

    samplerDescriptor.rAddressMode = MTLSamplerAddressModeRepeat;
    samplerDescriptor.sAddressMode = MTLSamplerAddressModeRepeat;
    samplerDescriptor.tAddressMode = MTLSamplerAddressModeRepeat;

    samplerDescriptor.minFilter = MTLSamplerMinMagFilterNearest;
    samplerDescriptor.magFilter = MTLSamplerMinMagFilterNearest;
    samplerNearestRepeat = [device newSamplerStateWithDescriptor:samplerDescriptor];

    samplerDescriptor.minFilter = MTLSamplerMinMagFilterLinear;
    samplerDescriptor.magFilter = MTLSamplerMinMagFilterLinear;
    samplerLinearRepeat = [device newSamplerStateWithDescriptor:samplerDescriptor];
}

static void setSampler(id<MTLRenderCommandEncoder> encoder, int interpolation, bool repeat) {
    id<MTLSamplerState> sampler;
    if (repeat) {
        sampler = interpolation == INTERPOLATION_BILINEAR ? samplerLinearRepeat : samplerNearestRepeat;
    } else {
        sampler = interpolation == INTERPOLATION_BILINEAR ? samplerLinearClamp : samplerNearestClamp;
    }
    [encoder setFragmentSamplerState:sampler atIndex:0];
}

static void setTxtUniforms(
        id<MTLRenderCommandEncoder> encoder, int color, int mode, int interpolation, bool repeat, jfloat extraAlpha,
        const SurfaceRasterFlags * srcFlags, const SurfaceRasterFlags * dstFlags
) {
    struct TxtFrameUniforms uf = {RGBA_TO_V4(color), mode, srcFlags->isOpaque, dstFlags->isOpaque, extraAlpha};
    [encoder setFragmentBytes:&uf length:sizeof(uf) atIndex:FrameUniformBuffer];

    setSampler(encoder, interpolation, repeat);
}

// For the current paint mode:
// 1. Selects vertex+fragment shaders (and corresponding pipelineDesc) and set pipelineState
// 2. Set vertex and fragment buffers
// Base implementation is used in drawTex2Tex
- (void)setPipelineState:(id <MTLRenderCommandEncoder>)encoder
                 context:(MTLContext *)mtlc
           renderOptions:(const RenderOptions *)renderOptions
    pipelineStateStorage:(MTLPipelineStatesStorage *)pipelineStateStorage
{
    initTemplatePipelineDescriptors();
    // Called from drawTex2Tex used in flushBuffer and for buffered image ops
    if (renderOptions->isTexture) {
        NSString * vertShader = @"vert_txt";
        NSString * fragShader = @"frag_txt";
        MTLRenderPipelineDescriptor* rpDesc = [[templateTexturePipelineDesc copy] autorelease];

        NSObject *bufImgOp = [mtlc getBufImgOp];
        if (bufImgOp != nil) {
            if ([bufImgOp isKindOfClass:[MTLRescaleOp class]]) {
                MTLRescaleOp *rescaleOp = bufImgOp;
                fragShader = @"frag_txt_op_rescale";

                struct TxtFrameOpRescaleUniforms uf = {
                        RGBA_TO_V4(0), [mtlc.composite getExtraAlpha], renderOptions->srcFlags.isOpaque,
                        rescaleOp.isNonPremult,
                        FLOAT_ARR_TO_V4([rescaleOp getScaleFactors]), FLOAT_ARR_TO_V4([rescaleOp getOffsets])
                };
                [encoder setFragmentBytes:&uf length:sizeof(uf) atIndex:FrameUniformBuffer];
                setSampler(encoder, renderOptions->interpolation, NO);
            } else if ([bufImgOp isKindOfClass:[MTLConvolveOp class]]) {
                MTLConvolveOp *convolveOp = bufImgOp;
                fragShader = @"frag_txt_op_convolve";

                struct TxtFrameOpConvolveUniforms uf = {
                        [mtlc.composite getExtraAlpha], renderOptions->srcFlags.isOpaque,
                        FLOAT_ARR_TO_V4([convolveOp getImgEdge]),
                        convolveOp.kernelSize, convolveOp.isEdgeZeroFill,
                };
                [encoder setFragmentBytes:&uf length:sizeof(uf) atIndex:FrameUniformBuffer];
                setSampler(encoder, renderOptions->interpolation, NO);

                [encoder setFragmentBuffer:[convolveOp getBuffer] offset:0 atIndex:2];
            } else if ([bufImgOp isKindOfClass:[MTLLookupOp class]]) {
                MTLLookupOp *lookupOp = bufImgOp;
                fragShader = @"frag_txt_op_lookup";

                struct TxtFrameOpLookupUniforms uf = {
                        [mtlc.composite getExtraAlpha], renderOptions->srcFlags.isOpaque,
                        FLOAT_ARR_TO_V4([lookupOp getOffset]), lookupOp.isUseSrcAlpha, lookupOp.isNonPremult,
                };
                [encoder setFragmentBytes:&uf length:sizeof(uf) atIndex:FrameUniformBuffer];
                setSampler(encoder, renderOptions->interpolation, NO);
                [encoder setFragmentTexture:[lookupOp getLookupTexture] atIndex:1];
            }
        } else {
            setTxtUniforms(encoder, 0, 0,
                           renderOptions->interpolation, NO, [mtlc.composite getExtraAlpha],
                           &renderOptions->srcFlags,
                           &renderOptions->dstFlags);

        }
        id <MTLRenderPipelineState> pipelineState = [pipelineStateStorage getPipelineState:rpDesc
                                                                            vertexShaderId:vertShader
                                                                          fragmentShaderId:fragShader
                                                                                 composite:mtlc.composite
                                                                             renderOptions:renderOptions
                                                                             stencilNeeded:[mtlc.clip isShape]];
        [encoder setRenderPipelineState:pipelineState];
    }
}

// For the current paint mode:
// 1. Selects vertex+fragment shaders (and corresponding pipelineDesc) and set pipelineState
// 2. Set vertex and fragment buffers
- (void)setXorModePipelineState:(id <MTLRenderCommandEncoder>)encoder
                 context:(MTLContext *)mtlc
           renderOptions:(const RenderOptions *)renderOptions
    pipelineStateStorage:(MTLPipelineStatesStorage *)pipelineStateStorage
{
    if (renderOptions->isTexture) {
        initTemplatePipelineDescriptors();
        jint xorColor = (jint) [mtlc.composite getXorColor];
        NSString * vertShader = @"vert_txt_xorMode";
        NSString * fragShader = @"frag_txt_xorMode";
        MTLRenderPipelineDescriptor * rpDesc = [[templateTexturePipelineDesc copy] autorelease];

        const int col = 0 ^ xorColor;
        setTxtUniforms(encoder, col, 0,
                       renderOptions->interpolation, NO, [mtlc.composite getExtraAlpha],
                       &renderOptions->srcFlags, &renderOptions->dstFlags);
        [encoder setFragmentBytes:&xorColor length:sizeof(xorColor) atIndex: 0];

        BMTLSDOps *dstOps = MTLRenderQueue_GetCurrentDestination();
        [encoder setFragmentTexture:dstOps->pTexture atIndex:1];

        setTxtUniforms(encoder, 0, 0,
                       renderOptions->interpolation, NO, [mtlc.composite getExtraAlpha],
                       &renderOptions->srcFlags,
                       &renderOptions->dstFlags);

        id <MTLRenderPipelineState> pipelineState = [pipelineStateStorage getPipelineState:rpDesc
                                                                            vertexShaderId:vertShader
                                                                          fragmentShaderId:fragShader
                                                                                 composite:mtlc.composite
                                                                             renderOptions:renderOptions
                                                                             stencilNeeded:[mtlc.clip isShape]];
        [encoder setRenderPipelineState:pipelineState];
    }
}

@end


/************************* GradientPaint support ****************************/

static void
MTLPaints_InitGradientTexture()
{
    //TODO
    J2dTraceLn(J2D_TRACE_INFO, "MTLPaints_InitGradientTexture -- :TODO");
}

/****************** Shared MultipleGradientPaint support ********************/

/**
 * These constants are identical to those defined in the
 * MultipleGradientPaint.CycleMethod enum; they are copied here for
 * convenience (ideally we would pull them directly from the Java level,
 * but that entails more hassle than it is worth).
 */
#define CYCLE_NONE    0
#define CYCLE_REFLECT 1
#define CYCLE_REPEAT  2

/**
 * The following constants are flags that can be bitwise-or'ed together
 * to control how the MultipleGradientPaint shader source code is generated:
 *
 *   MULTI_CYCLE_METHOD
 *     Placeholder for the CycleMethod enum constant.
 *
 *   MULTI_LARGE
 *     If set, use the (slower) shader that supports a larger number of
 *     gradient colors; otherwise, use the optimized codepath.  See
 *     the MAX_FRACTIONS_SMALL/LARGE constants below for more details.
 *
 *   MULTI_USE_MASK
 *     If set, apply the alpha mask value from texture unit 0 to the
 *     final color result (only used in the MaskFill case).
 *
 *   MULTI_LINEAR_RGB
 *     If set, convert the linear RGB result back into the sRGB color space.
 */
#define MULTI_CYCLE_METHOD (3 << 0)
#define MULTI_LARGE        (1 << 2)
#define MULTI_USE_MASK     (1 << 3)
#define MULTI_LINEAR_RGB   (1 << 4)

/**
 * This value determines the size of the array of programs for each
 * MultipleGradientPaint type.  This value reflects the maximum value that
 * can be represented by performing a bitwise-or of all the MULTI_*
 * constants defined above.
 */
#define MAX_PROGRAMS 32

/** Evaluates to true if the given bit is set on the local flags variable. */
#define IS_SET(flagbit) \
    (((flags) & (flagbit)) != 0)

/** Composes the given parameters as flags into the given flags variable.*/
#define COMPOSE_FLAGS(flags, cycleMethod, large, useMask, linear) \
    do {                                                   \
        flags |= ((cycleMethod) & MULTI_CYCLE_METHOD);     \
        if (large)   flags |= MULTI_LARGE;                 \
        if (useMask) flags |= MULTI_USE_MASK;              \
        if (linear)  flags |= MULTI_LINEAR_RGB;            \
    } while (0)

/** Extracts the CycleMethod enum value from the given flags variable. */
#define EXTRACT_CYCLE_METHOD(flags) \
    ((flags) & MULTI_CYCLE_METHOD)

/**
 * The maximum number of gradient "stops" supported by the fragment shader
 * and related code.  When the MULTI_LARGE flag is set, we will use
 * MAX_FRACTIONS_LARGE; otherwise, we use MAX_FRACTIONS_SMALL.  By having
 * two separate values, we can have one highly optimized shader (SMALL) that
 * supports only a few fractions/colors, and then another, less optimal
 * shader that supports more stops.
 */
#define MAX_FRACTIONS sun_java2d_pipe_BufferedPaints_MULTI_MAX_FRACTIONS
#define MAX_FRACTIONS_LARGE MAX_FRACTIONS
#define MAX_FRACTIONS_SMALL 4

/**
 * The maximum number of gradient colors supported by all of the gradient
 * fragment shaders.  Note that this value must be a power of two, as it
 * determines the size of the 1D texture created below.  It also must be
 * greater than or equal to MAX_FRACTIONS (there is no strict requirement
 * that the two values be equal).
 */
#define MAX_COLORS 16

/**
 * The handle to the gradient color table texture object used by the shaders.
 */
static jint multiGradientTexID = 0;

/**
 * This is essentially a template of the shader source code that can be used
 * for either LinearGradientPaint or RadialGradientPaint.  It includes the
 * structure and some variables that are common to each; the remaining
 * code snippets (for CycleMethod, ColorSpaceType, and mask modulation)
 * are filled in prior to compiling the shader at runtime depending on the
 * paint parameters.  See MTLPaints_CreateMultiGradProgram() for more details.
 */
static const char *multiGradientShaderSource =
    // gradient texture size (in texels)
    "const int TEXTURE_SIZE = %d;"
    // maximum number of fractions/colors supported by this shader
    "const int MAX_FRACTIONS = %d;"
    // size of a single texel
    "const float FULL_TEXEL = (1.0 / float(TEXTURE_SIZE));"
    // size of half of a single texel
    "const float HALF_TEXEL = (FULL_TEXEL / 2.0);"
    // texture containing the gradient colors
    "uniform sampler1D colors;"
    // array of gradient stops/fractions
    "uniform float fractions[MAX_FRACTIONS];"
    // array of scale factors (one for each interval)
    "uniform float scaleFactors[MAX_FRACTIONS-1];"
    // (placeholder for mask variable)
    "%s"
    // (placeholder for Linear/RadialGP-specific variables)
    "%s"
    ""
    "void main(void)"
    "{"
    "    float dist;"
         // (placeholder for Linear/RadialGradientPaint-specific code)
    "    %s"
    ""
    "    float tc;"
         // (placeholder for CycleMethod-specific code)
    "    %s"
    ""
         // calculate interpolated color
    "    vec4 result = texture1D(colors, tc);"
    ""
         // (placeholder for ColorSpace conversion code)
    "    %s"
    ""
         // (placeholder for mask modulation code)
    "    %s"
    ""
         // modulate with gl_Color in order to apply extra alpha
    "    gl_FragColor = result * gl_Color;"
    "}";

/**
 * This code takes a "dist" value as input (as calculated earlier by the
 * LGP/RGP-specific code) in the range [0,1] and produces a texture
 * coordinate value "tc" that represents the position of the chosen color
 * in the one-dimensional gradient texture (also in the range [0,1]).
 *
 * One naive way to implement this would be to iterate through the fractions
 * to figure out in which interval "dist" falls, and then compute the
 * relative distance between the two nearest stops.  This approach would
 * require an "if" check on every iteration, and it is best to avoid
 * conditionals in fragment shaders for performance reasons.  Also, one might
 * be tempted to use a break statement to jump out of the loop once the
 * interval was found, but break statements (and non-constant loop bounds)
 * are not natively available on most graphics hardware today, so that is
 * a non-starter.
 *
 * The more optimal approach used here avoids these issues entirely by using
 * an accumulation function that is equivalent to the process described above.
 * The scaleFactors array is pre-initialized at enable time as follows:
 *     scaleFactors[i] = 1.0 / (fractions[i+1] - fractions[i]);
 *
 * For each iteration, we subtract fractions[i] from dist and then multiply
 * that value by scaleFactors[i].  If we are within the target interval,
 * this value will be a fraction in the range [0,1] indicating the relative
 * distance between fraction[i] and fraction[i+1].  If we are below the
 * target interval, this value will be negative, so we clamp it to zero
 * to avoid accumulating any value.  If we are above the target interval,
 * the value will be greater than one, so we clamp it to one.  Upon exiting
 * the loop, we will have accumulated zero or more 1.0's and a single
 * fractional value.  This accumulated value tells us the position of the
 * fragment color in the one-dimensional gradient texture, i.e., the
 * texcoord called "tc".
 */
static const char *texCoordCalcCode =
    "int i;"
    "float relFraction = 0.0;"
    "for (i = 0; i < MAX_FRACTIONS-1; i++) {"
    "    relFraction +="
    "        clamp((dist - fractions[i]) * scaleFactors[i], 0.0, 1.0);"
    "}"
    // we offset by half a texel so that we find the linearly interpolated
    // color between the two texel centers of interest
    "tc = HALF_TEXEL + (FULL_TEXEL * relFraction);";

/** Code for NO_CYCLE that gets plugged into the CycleMethod placeholder. */
static const char *noCycleCode =
    "if (dist <= 0.0) {"
    "    tc = 0.0;"
    "} else if (dist >= 1.0) {"
    "    tc = 1.0;"
    "} else {"
         // (placeholder for texcoord calculation)
    "    %s"
    "}";

/** Code for REFLECT that gets plugged into the CycleMethod placeholder. */
static const char *reflectCode =
    "dist = 1.0 - (abs(fract(dist * 0.5) - 0.5) * 2.0);"
    // (placeholder for texcoord calculation)
    "%s";

/** Code for REPEAT that gets plugged into the CycleMethod placeholder. */
static const char *repeatCode =
    "dist = fract(dist);"
    // (placeholder for texcoord calculation)
    "%s";

static void
MTLPaints_InitMultiGradientTexture()
{
    J2dTraceLn(J2D_TRACE_INFO, "MTLPaints_InitMultiGradientTexture -- :TODO");
}

/**
 * Compiles and links the MultipleGradientPaint shader program.  If
 * successful, this function returns a handle to the newly created
 * shader program; otherwise returns 0.
 */
static void*
MTLPaints_CreateMultiGradProgram(jint flags,
                                 char *paintVars, char *distCode)
{

    //TODO
    J2dTraceLn(J2D_TRACE_INFO, "MTLPaints_CreateMultiGradProgram -- :TODO");

    return NULL;
}

/**
 * Called from the MTLPaints_SetLinear/RadialGradientPaint() methods
 * in order to setup the fraction/color values that are common to both.
 */
static void
MTLPaints_SetMultiGradientPaint(void* multiGradProgram,
                                jint numStops,
                                void *pFractions, void *pPixels)
{
    //TODO
    J2dTraceLn(J2D_TRACE_INFO, "MTLPaints_SetMultiGradientPaint -- :TODO");

}

/********************** LinearGradientPaint support *************************/

/**
 * The handles to the LinearGradientPaint fragment program objects.  The
 * index to the array should be a bitwise-or'ing of the MULTI_* flags defined
 * above.  Note that most applications will likely need to initialize one
 * or two of these elements, so the array is usually sparsely populated.
 */
static void* linearGradPrograms[MAX_PROGRAMS];

/**
 * Compiles and links the LinearGradientPaint shader program.  If successful,
 * this function returns a handle to the newly created shader program;
 * otherwise returns 0.
 */
static void*
MTLPaints_CreateLinearGradProgram(jint flags)
{
    char *paintVars;
    char *distCode;

    J2dTraceLn1(J2D_TRACE_INFO,
                "MTLPaints_CreateLinearGradProgram",
                flags);

    /*
     * To simplify the code and to make it easier to upload a number of
     * uniform values at once, we pack a bunch of scalar (float) values
     * into vec3 values below.  Here's how the values are related:
     *
     *   params.x = p0
     *   params.y = p1
     *   params.z = p3
     *
     *   yoff = dstOps->yOffset + dstOps->height
     */
    paintVars =
        "uniform vec3 params;"
        "uniform float yoff;";
    distCode =
        // note that gl_FragCoord is in window space relative to the
        // lower-left corner, so we have to flip the y-coordinate here
        "vec3 fragCoord = vec3(gl_FragCoord.x, yoff-gl_FragCoord.y, 1.0);"
        "dist = dot(params, fragCoord);";

    return MTLPaints_CreateMultiGradProgram(flags, paintVars, distCode);
}

/********************** RadialGradientPaint support *************************/

/**
 * The handles to the RadialGradientPaint fragment program objects.  The
 * index to the array should be a bitwise-or'ing of the MULTI_* flags defined
 * above.  Note that most applications will likely need to initialize one
 * or two of these elements, so the array is usually sparsely populated.
 */
static void* radialGradPrograms[MAX_PROGRAMS];

/**
 * Compiles and links the RadialGradientPaint shader program.  If successful,
 * this function returns a handle to the newly created shader program;
 * otherwise returns 0.
 */
static void*
MTLPaints_CreateRadialGradProgram(jint flags)
{
    char *paintVars;
    char *distCode;

    J2dTraceLn1(J2D_TRACE_INFO,
                "MTLPaints_CreateRadialGradProgram",
                flags);

    /*
     * To simplify the code and to make it easier to upload a number of
     * uniform values at once, we pack a bunch of scalar (float) values
     * into vec3 and vec4 values below.  Here's how the values are related:
     *
     *   m0.x = m00
     *   m0.y = m01
     *   m0.z = m02
     *
     *   m1.x = m10
     *   m1.y = m11
     *   m1.z = m12
     *
     *   precalc.x = focusX
     *   precalc.y = yoff = dstOps->yOffset + dstOps->height
     *   precalc.z = 1.0 - (focusX * focusX)
     *   precalc.w = 1.0 / precalc.z
     */
    paintVars =
        "uniform vec3 m0;"
        "uniform vec3 m1;"
        "uniform vec4 precalc;";

    /*
     * The following code is derived from Daniel Rice's whitepaper on
     * radial gradient performance (attached to the bug report for 6521533).
     * Refer to that document as well as the setup code in the Java-level
     * BufferedPaints.setRadialGradientPaint() method for more details.
     */
    distCode =
        // note that gl_FragCoord is in window space relative to the
        // lower-left corner, so we have to flip the y-coordinate here
        "vec3 fragCoord ="
        "    vec3(gl_FragCoord.x, precalc.y - gl_FragCoord.y, 1.0);"
        "float x = dot(fragCoord, m0);"
        "float y = dot(fragCoord, m1);"
        "float xfx = x - precalc.x;"
        "dist = (precalc.x*xfx + sqrt(xfx*xfx + y*y*precalc.z))*precalc.w;";

    return MTLPaints_CreateMultiGradProgram(flags, paintVars, distCode);
}

#endif /* !HEADLESS */
