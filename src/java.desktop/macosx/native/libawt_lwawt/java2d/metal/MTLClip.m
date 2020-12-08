#include "MTLClip.h"

#include "MTLContext.h"
#include "common.h"

static MTLRenderPipelineDescriptor * templateStencilPipelineDesc = nil;

static void initTemplatePipelineDescriptors() {
    if (templateStencilPipelineDesc != nil)
        return;

    MTLVertexDescriptor *vertDesc = [[MTLVertexDescriptor new] autorelease];
    vertDesc.attributes[VertexAttributePosition].format = MTLVertexFormatFloat2;
    vertDesc.attributes[VertexAttributePosition].offset = 0;
    vertDesc.attributes[VertexAttributePosition].bufferIndex = MeshVertexBuffer;
    vertDesc.layouts[MeshVertexBuffer].stride = sizeof(struct Vertex);
    vertDesc.layouts[MeshVertexBuffer].stepRate = 1;
    vertDesc.layouts[MeshVertexBuffer].stepFunction = MTLVertexStepFunctionPerVertex;

    templateStencilPipelineDesc = [MTLRenderPipelineDescriptor new];
    templateStencilPipelineDesc.sampleCount = 1;
    templateStencilPipelineDesc.vertexDescriptor = vertDesc;
    templateStencilPipelineDesc.colorAttachments[0].pixelFormat = MTLPixelFormatR8Uint; // A byte buffer format
    templateStencilPipelineDesc.label = @"template_stencil";
}

static id<MTLDepthStencilState> getStencilState(id<MTLDevice> device) {
    static id<MTLDepthStencilState> stencilState = nil;
    if (stencilState == nil) {
        MTLDepthStencilDescriptor* stencilDescriptor;
        stencilDescriptor = [[MTLDepthStencilDescriptor new] autorelease];
        stencilDescriptor.frontFaceStencil.stencilCompareFunction = MTLCompareFunctionEqual;
        stencilDescriptor.frontFaceStencil.stencilFailureOperation = MTLStencilOperationKeep;

        // TODO : backFaceStencil can be set to nil if all primitives are drawn as front-facing primitives
        // currently, fill parallelogram uses back-facing primitive drawing - that needs to be changed.
        // Once that part is changed, set backFaceStencil to nil
        //stencilDescriptor.backFaceStencil = nil;

        stencilDescriptor.backFaceStencil.stencilCompareFunction = MTLCompareFunctionEqual;
        stencilDescriptor.backFaceStencil.stencilFailureOperation = MTLStencilOperationKeep;

        stencilState = [device newDepthStencilStateWithDescriptor:stencilDescriptor];
    }

    return stencilState;
}

@implementation MTLClip {
    jint _clipType;
    MTLScissorRect  _clipRect;
    MTLContext* _mtlc;
    BMTLSDOps*  _dstOps;
    BOOL _stencilMaskGenerationInProgress;
    BOOL _clipReady;
    BOOL _aaClipReady;
}

- (id)init {
    self = [super init];
    if (self) {
        _clipType = NO_CLIP;
        _mtlc = nil;
        _dstOps = NULL;
        _stencilMaskGenerationInProgress = NO;
        _clipReady = NO;
        _aaClipReady = NO;
    }
    return self;
}

- (BOOL)isEqual:(MTLClip *)other {
    if (self == other)
        return YES;
    if (_stencilMaskGenerationInProgress == JNI_TRUE)
        return other->_stencilMaskGenerationInProgress == JNI_TRUE;
    if (_clipType != other->_clipType)
        return NO;
    if (_clipType == NO_CLIP)
        return YES;
    if (_clipType == RECT_CLIP) {
        return _clipRect.x == other->_clipRect.x && _clipRect.y == other->_clipRect.y
               && _clipRect.width == other->_clipRect.width && _clipRect.height == other->_clipRect.height;
    }

    // NOTE: can compare stencil-data pointers here
    return YES;
}

- (BOOL)isShape {
    return _clipType == SHAPE_CLIP;
}

- (BOOL)isRect __unused {
    return _clipType == RECT_CLIP;
}

- (const MTLScissorRect * _Nullable) getRect {
    return _clipType == RECT_CLIP ? &_clipRect : NULL;
}

- (void)copyFrom:(MTLClip *)other {
    _clipType = other->_clipType;
    _stencilMaskGenerationInProgress = other->_stencilMaskGenerationInProgress;
    _dstOps = other->_dstOps;
    if (other->_clipType == RECT_CLIP) {
        _clipRect = other->_clipRect;
    }
}

- (void)reset {
    _clipType = NO_CLIP;
    _stencilMaskGenerationInProgress = JNI_FALSE;
}

- (void)setClipRectX1:(jint)x1 Y1:(jint)y1 X2:(jint)x2 Y2:(jint)y2 {
    if (_clipType == SHAPE_CLIP) {
        _dstOps = NULL;
    }

    if (x1 >= x2 || y1 >= y2) {
        J2dTraceLn4(J2D_TRACE_ERROR, "MTLClip.setClipRect: invalid rect: x1=%d y1=%d x2=%d y2=%d", x1, y1, x2, y2);
        _clipType = NO_CLIP;
    }

    const jint width = x2 - x1;
    const jint height = y2 - y1;

    J2dTraceLn4(J2D_TRACE_INFO, "MTLClip.setClipRect: x=%d y=%d w=%d h=%d", x1, y1, width, height);

    _clipRect.x = (NSUInteger)((x1 >= 0) ? x1 : 0);
    _clipRect.y = (NSUInteger)((y1 >= 0) ? y1 : 0);
    _clipRect.width = (NSUInteger)((width >= 0) ? width : 0);
    _clipRect.height = (NSUInteger)((height >= 0) ? height : 0);
    _clipType = RECT_CLIP;
}

- (void)beginShapeClip:(BMTLSDOps *)dstOps context:(MTLContext *)mtlc {
    _stencilMaskGenerationInProgress = YES;

    if ((dstOps == NULL) || (dstOps->pStencilData == NULL) || (dstOps->pStencilTexture == NULL)) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLContext_beginShapeClip: stencil render target or stencil texture is NULL");
        return;
    }

    // Clear the stencil render buffer & stencil texture
    @autoreleasepool {
        if (dstOps->width <= 0 || dstOps->height <= 0) {
          return;
        }

        NSUInteger width = (NSUInteger)dstOps->width;
        NSUInteger height = (NSUInteger)dstOps->height;
        NSUInteger size = width * height;
        id <MTLBuffer> buff = [mtlc.device newBufferWithLength:size options:MTLResourceStorageModePrivate];
        id<MTLCommandBuffer> commandBuf = [mtlc createCommandBuffer];
        id<MTLBlitCommandEncoder> blitEncoder = [commandBuf blitCommandEncoder];
        [blitEncoder fillBuffer:buff range:NSMakeRange(0, size) value:0];

        MTLOrigin origin = MTLOriginMake(0, 0, 0);
        MTLSize sourceSize = MTLSizeMake(width, height, 1);
        [blitEncoder copyFromBuffer:buff
                       sourceOffset:0
                  sourceBytesPerRow:width
                sourceBytesPerImage:size
                         sourceSize:sourceSize
                          toTexture:dstOps->pStencilData
                   destinationSlice:0
                   destinationLevel:0
                  destinationOrigin:origin];
        [blitEncoder endEncoding];
        [commandBuf commit];

        [buff release];
    }
}

- (void)endShapeClip:(BMTLSDOps *)dstOps context:(MTLContext *)mtlc {

    if ((dstOps == NULL) || (dstOps->pStencilData == NULL) || (dstOps->pStencilTexture == NULL)) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLContext_endShapeClip: stencil render target or stencil texture is NULL");
        return;
    }

    // Complete the rendering to the stencil buffer ------------
    [mtlc.encoderManager endEncoder];

    MTLCommandBufferWrapper* cbWrapper = [mtlc pullCommandBufferWrapper];

    id<MTLCommandBuffer> commandBuffer = [cbWrapper getCommandBuffer];
    [commandBuffer addCompletedHandler:^(id <MTLCommandBuffer> c) {
        [cbWrapper release];
    }];

    [commandBuffer commit];

    // Now the stencil data is ready, this needs to be used while rendering further
    @autoreleasepool {
        if (dstOps->width > 0 && dstOps->height > 0) {
            NSUInteger width = (NSUInteger)dstOps->width;
            NSUInteger height = (NSUInteger)dstOps->height;
            NSUInteger size = width * height;

            id<MTLCommandBuffer> cb = [mtlc createCommandBuffer];
            id<MTLBlitCommandEncoder> blitEncoder = [cb blitCommandEncoder];
            MTLSize sourceSize = MTLSizeMake(width, height, 1);
            MTLOrigin origin = MTLOriginMake(0, 0, 0);
            [blitEncoder copyFromTexture:dstOps->pStencilData
                             sourceSlice:0
                             sourceLevel:0
                            sourceOrigin:origin
                              sourceSize:sourceSize
                                toBuffer:dstOps->pStencilDataBuf
                       destinationOffset:0
                  destinationBytesPerRow:width
                destinationBytesPerImage:size];
            [blitEncoder endEncoding];
            [cb commit];
        }
    }

    _stencilMaskGenerationInProgress = JNI_FALSE;
    _mtlc = mtlc;
    _dstOps = dstOps;
    _clipType = SHAPE_CLIP;
    _clipReady = NO;
    _aaClipReady = NO;
}

- (void)setMaskGenerationPipelineState:(id<MTLRenderCommandEncoder>)encoder
                  destWidth:(NSUInteger)dw
                 destHeight:(NSUInteger)dh
       pipelineStateStorage:(MTLPipelineStatesStorage *)pipelineStateStorage
{
    initTemplatePipelineDescriptors();

    // A  PipelineState for rendering to a byte-buffered texture that will be used as a stencil
    id <MTLRenderPipelineState> pipelineState = [pipelineStateStorage getPipelineState:templateStencilPipelineDesc
                                                                         vertexShaderId:@"vert_stencil"
                                                                       fragmentShaderId:@"frag_stencil"];
    [encoder setRenderPipelineState:pipelineState];

    struct FrameUniforms uf; // color is ignored while writing to stencil buffer
    memset(&uf, 0, sizeof(uf));
    [encoder setVertexBytes:&uf length:sizeof(uf) atIndex:FrameUniformBuffer];

    _clipRect.x = 0;
    _clipRect.y = 0;
    _clipRect.width = dw;
    _clipRect.height = dh;

    [encoder setScissorRect:_clipRect]; // just for insurance (to reset possible clip from previous drawing)
}

- (void)setScissorOrStencil:(id<MTLRenderCommandEncoder>)encoder
                  destWidth:(NSUInteger)dw
                 destHeight:(NSUInteger)dh
                     device:(id<MTLDevice>)device
{
    if (_clipType == NO_CLIP || _clipType == SHAPE_CLIP) {
        _clipRect.x = 0;
        _clipRect.y = 0;
        _clipRect.width = dw;
        _clipRect.height = dh;
    }

    [encoder setScissorRect:_clipRect];
    if (_clipType == NO_CLIP || _clipType == RECT_CLIP) {
        // NOTE: It seems that we can use the same encoder (with disabled stencil test) when mode changes from SHAPE to RECT.
        // But [encoder setDepthStencilState:nil] causes crash, so we have to recreate encoder in such case.
        // So we can omit [encoder setDepthStencilState:nil] here.
        return;
    }

    if (_clipType == SHAPE_CLIP) {
        // Enable stencil test
        [encoder setDepthStencilState:getStencilState(device)];
        [encoder setStencilReferenceValue:0xFF];
    }
}

- (NSString *)getDescription __unused {
    if (_clipType == NO_CLIP) {
        return @"NO_CLIP";
    }
    if (_clipType == RECT_CLIP) {
        return [NSString stringWithFormat:@"RECT_CLIP [%lu,%lu - %lux%lu]", _clipRect.x, _clipRect.y, _clipRect.width, _clipRect.height];
    }
    return [NSString stringWithFormat:@"SHAPE_CLIP"];
}

- (id<MTLTexture>) stencilTextureRef {
    if (_dstOps == NULL) return nil;

    id <MTLTexture> _stencilTextureRef = _dstOps->pStencilTexture;

    if (!_clipReady) {
        @autoreleasepool {
            id <MTLCommandBuffer> cb = [_mtlc createCommandBuffer];
            id <MTLBlitCommandEncoder> blitEncoder = [cb blitCommandEncoder];
            id <MTLBuffer> _stencilDataBufRef = _dstOps->pStencilDataBuf;

            NSUInteger width = _stencilTextureRef.width;
            NSUInteger height = _stencilTextureRef.height;

            [blitEncoder copyFromBuffer:_stencilDataBufRef
                           sourceOffset:0
                      sourceBytesPerRow:width
                    sourceBytesPerImage:width * height
                             sourceSize:MTLSizeMake(width, height, 1)
                              toTexture:_stencilTextureRef
                       destinationSlice:0
                       destinationLevel:0
                      destinationOrigin:MTLOriginMake(0, 0, 0)];
            [blitEncoder endEncoding];
            [cb commit];
            _clipReady = YES;
        }
    }
    return _stencilTextureRef;
}

- (id<MTLTexture>) stencilAADataRef {
    if (_dstOps == NULL) return nil;

    id <MTLTexture> _stencilAADataRef = _dstOps->pAAStencilData;

    if (!_aaClipReady) {

        @autoreleasepool {

            id <MTLCommandBuffer> cb = [_mtlc createCommandBuffer];
            id <MTLComputeCommandEncoder> computeEncoder = [cb computeCommandEncoder];
            id<MTLComputePipelineState> computePipelineState = [_mtlc.pipelineStateStorage getComputePipelineState:@"stencil2tex"];
            id <MTLBuffer> _stencilDataBufRef = _dstOps->pStencilDataBuf;
            id <MTLBuffer> _stencilAADataBufRef = _dstOps->pAAStencilDataBuf;
            NSUInteger width = _stencilAADataRef.width;
            NSUInteger height = _stencilAADataRef.height;
            NSUInteger size = width * height;

            [computeEncoder setComputePipelineState:computePipelineState];

            [computeEncoder setBuffer:_stencilDataBufRef offset:0 atIndex:0];
            [computeEncoder setBuffer:_stencilAADataBufRef offset:0 atIndex:1];
            NSUInteger threadGroupSize = computePipelineState.maxTotalThreadsPerThreadgroup;
            if (threadGroupSize > _stencilDataBufRef.length)
            {
                threadGroupSize = _stencilDataBufRef.length;
            }

            MTLSize threadgroupCounts = MTLSizeMake(threadGroupSize, 1, 1);
            MTLSize threadgroups = MTLSizeMake(_stencilDataBufRef.length / threadGroupSize,
                                               1, 1);
            [computeEncoder dispatchThreadgroups:threadgroups
                           threadsPerThreadgroup:threadgroupCounts];
            [computeEncoder endEncoding];

            id <MTLBlitCommandEncoder> blitEncoder = [cb blitCommandEncoder];

            [blitEncoder copyFromBuffer:_stencilAADataBufRef
                           sourceOffset:0
                      sourceBytesPerRow:width * 4
                    sourceBytesPerImage:size * 4
                             sourceSize:MTLSizeMake(width, height, 1)
                              toTexture:_stencilAADataRef
                       destinationSlice:0
                       destinationLevel:0
                      destinationOrigin:MTLOriginMake(0, 0, 0)];
            [blitEncoder endEncoding];

            [cb commit];
            _aaClipReady = YES;
        }
    }
    return _stencilAADataRef;
}


@end