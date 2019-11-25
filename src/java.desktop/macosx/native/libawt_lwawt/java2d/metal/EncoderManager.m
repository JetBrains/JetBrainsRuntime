#include "EncoderManager.h"

#include "MTLContext.h"

// Internal utility class
@interface EncoderStates : NSObject
- (id)init;
- (void)dealloc;

- (void)reset:(id<MTLTexture>)destination
           isDstOpaque:(jboolean)isDstOpaque
    isDstPremultiplied:(jboolean)isDstPremultiplied;

- (void)updateEncoder:(id<MTLRenderCommandEncoder>)encoder
                paint:(MTLPaint *)paint
            composite:(MTLComposite *)composite
            isTexture:(jboolean)isTexture
             srcFlags:(const SurfaceRasterFlags * )srcFlags
             clipRect:(const MTLScissorRect *)clipRect
            transform:(MTLTransform *)transform
 pipelineStateStorage:(MTLPipelineStatesStorage *)pipelineStateStorage;
@end

@implementation EncoderStates {
    // destination with flags
    id<MTLTexture> _destination;
    SurfaceRasterFlags _dstFlags;

    jboolean _forceUpdate;

    //
    // Cached states of encoder
    //

    // Composite rule and paint mode (for CAD-multipliers and fragment shader)
    MTLComposite * _composite;
    MTLPaint * _paint;
    jboolean _isTexture;
    SurfaceRasterFlags _srcFlags;

    // Clip-rect
    MTLScissorRect _clipRect;

    // Transform (for vertex shader)
    MTLTransform * _transform;
    jint _destWidth;
    jint _destHeight;
}

- (id)init {
    self = [super init];
    if (self) {
        _destination = nil;

        _composite = [[MTLComposite alloc] init];
        _paint = [[MTLPaint alloc] init];
        _transform = [[MTLTransform alloc] init];

        _destWidth = -1;
        _destHeight = -1;

        memset(&(_clipRect), 0, sizeof(_clipRect));

        _forceUpdate = JNI_TRUE;
    }
    return self;
}

- (void)dealloc {
    [_composite release];
    [_paint release];
    [_transform release];
    [super dealloc];
}

- (void)reset:(id<MTLTexture>)destination
           isDstOpaque:(jboolean)isDstOpaque
    isDstPremultiplied:(jboolean)isDstPremultiplied
{
    _destination = destination;
    _dstFlags.isOpaque = isDstOpaque;
    _dstFlags.isPremultiplied = isDstPremultiplied;

    _forceUpdate = JNI_TRUE;
}

- (void)updateEncoder:(id<MTLRenderCommandEncoder>)encoder
                paint:(MTLPaint *)paint
            composite:(MTLComposite *)composite
            isTexture:(jboolean)isTexture
             srcFlags:(const SurfaceRasterFlags * )srcFlags
             clipRect:(const MTLScissorRect *)clipRect
            transform:(MTLTransform *)transform
 pipelineStateStorage:(MTLPipelineStatesStorage *)pipelineStateStorage
{
    [self updatePipelineState:encoder
                        paint:paint
                    composite:composite
                    isTexture:isTexture
                     srcFlags:srcFlags
         pipelineStateStorage:pipelineStateStorage];
    [self updateTransform:encoder transform:transform];
    [self updateClip:encoder clipRect:clipRect];
    _forceUpdate = JNI_FALSE;
}

//
// Internal methods that update states when necessary (compare with cached states)
//

- (void)updatePipelineState:(id<MTLRenderCommandEncoder>)encoder
                      paint:(MTLPaint *)paint
                  composite:(MTLComposite *)composite
                  isTexture:(jboolean)isTexture
                   srcFlags:(const SurfaceRasterFlags * )srcFlags
       pipelineStateStorage:(MTLPipelineStatesStorage *)pipelineStateStorage
{
    if (!_forceUpdate
        && [_paint isEqual:paint]
        && [_composite isEqual:composite]
        && _isTexture == isTexture
        && _srcFlags.isOpaque == srcFlags->isOpaque && _srcFlags.isPremultiplied == srcFlags->isPremultiplied)
        return;

    [_paint copyFrom:paint];
    [_composite copyFrom:composite];
    _isTexture = isTexture;
    _srcFlags = *srcFlags;

    [paint setPipelineState:encoder
                  composite:_composite
                  isTexture:_isTexture
                   srcFlags:&_srcFlags
                   dstFlags:&_dstFlags
       pipelineStateStorage:pipelineStateStorage];
}

- (void) updateClip:(id<MTLRenderCommandEncoder>)encoder clipRect:(const MTLScissorRect *)clipRect
{
    if (clipRect != NULL) {
        if (!_forceUpdate
            && clipRect->x == _clipRect.x && clipRect->y == _clipRect.y
            && clipRect->width == _clipRect.width && clipRect->height == _clipRect.height)
            return;

        _clipRect = *clipRect;
    } else {
        if (!_forceUpdate
            && _clipRect.x == 0 && _clipRect.y == 0
            && _clipRect.width == _destination.width && _clipRect.height == _destination.height)
            return;

        _clipRect.x = 0;
        _clipRect.y = 0;
        _clipRect.width = _destination.width;
        _clipRect.height = _destination.height;
    }

    [encoder setScissorRect:_clipRect];
}

- (void) updateTransform:(id<MTLRenderCommandEncoder>)encoder transform:(MTLTransform *)transform {
    if (!_forceUpdate
        && _destWidth == _destination.width && _destHeight == _destination.height
        && [_transform isEqual:transform])
        return;

    _destWidth = _destination.width;
    _destHeight = _destination.height;
    [_transform copyFrom:transform];
    [_transform setVertexMatrix:encoder destWidth:_destWidth destHeight:_destHeight];
}

@end

@implementation EncoderManager {
    MTLContext * _mtlc; // used to obtain CommandBufferWrapper and Composite/Paint/Transform

    id<MTLRenderCommandEncoder> _encoder;
    id<MTLTexture> _destination;
    EncoderStates * _encoderStates;
}

- (id)init {
    self = [super init];
    if (self) {
        _encoder = nil;
        _destination = nil;
        _encoderStates = [[EncoderStates alloc] init];

    }
    return self;
}

- (void)dealloc {
    [_encoderStates release];
    [super dealloc];
}

- (void)setContext:(void *)mtlc {
    self->_mtlc = mtlc;
}

- (id<MTLRenderCommandEncoder>) getRenderEncoder:(const BMTLSDOps *)dstOps
{
    SurfaceRasterFlags sf = { JNI_FALSE, JNI_TRUE };
    return [self getEncoder:dstOps isTexture:JNI_FALSE srcFlags:&sf];
}

- (id<MTLRenderCommandEncoder>) getTextureEncoder:(const BMTLSDOps *)dstOps
                                      isSrcOpaque:(bool)isSrcOpaque
{
    return [self getTextureEncoder:dstOps->pTexture isSrcOpaque:isSrcOpaque isDstOpaque:dstOps->isOpaque];
}

- (id<MTLRenderCommandEncoder>) getTextureEncoder:(id<MTLTexture>)dest
                                      isSrcOpaque:(bool)isSrcOpaque
                                      isDstOpaque:(bool)isDstOpaque
{
    SurfaceRasterFlags srcFlags = { isSrcOpaque, JNI_TRUE };
    return [self getEncoder:dest
                   isOpaque:isDstOpaque
                  isTexture:JNI_TRUE
                   srcFlags:&srcFlags];
}

- (id<MTLRenderCommandEncoder>) getEncoder:(const BMTLSDOps *)dstOps
                                 isTexture:(jboolean)isTexture
                                  srcFlags:(const SurfaceRasterFlags * )srcFlags
{
    return [self getEncoder:dstOps->pTexture isOpaque:dstOps->isOpaque isTexture:isTexture srcFlags:srcFlags];
}

- (id<MTLRenderCommandEncoder>) getEncoder:(id<MTLTexture>)dest
                                  isOpaque:(jboolean)isOpaque
                                 isTexture:(jboolean)isTexture
                                  srcFlags:(const SurfaceRasterFlags * )srcFlags
{
    if (_destination != dest && _encoder != nil) {
        J2dTraceLn2(J2D_TRACE_VERBOSE, "end common encoder because of dest change: %p -> %p", _destination, destination);
        [self endEncoder];
    }
    if (_encoder == nil) {
        _destination = dest;

        MTLCommandBufferWrapper * cbw = [_mtlc getCommandBufferWrapper];
        MTLRenderPassDescriptor * rpd = [MTLRenderPassDescriptor renderPassDescriptor];
        MTLRenderPassColorAttachmentDescriptor * ca = rpd.colorAttachments[0];
        ca.texture = dest;
        ca.loadAction = MTLLoadActionLoad;
        ca.clearColor = MTLClearColorMake(0.0f, 0.0f, 0.0f, 1.0f);
        ca.storeAction = MTLStoreActionStore;

        // J2dTraceLn1(J2D_TRACE_VERBOSE, "created render encoder to draw on tex=%p", dest);
        _encoder = [[cbw getCommandBuffer] renderCommandEncoderWithDescriptor:rpd];
        [rpd release];

        [_encoderStates reset:dest
                  isDstOpaque:isOpaque
           isDstPremultiplied:YES];
    }

    [_encoderStates updateEncoder:_encoder
                            paint:_mtlc.paint
                        composite:_mtlc.composite
                        isTexture:isTexture
                         srcFlags:srcFlags
                         clipRect:[_mtlc clipRect]
                        transform:_mtlc.transform
             pipelineStateStorage:_mtlc.pipelineStateStorage];

    return _encoder;
}

- (id<MTLBlitCommandEncoder>) createBlitEncoder {
    [self endEncoder];
    return [[[_mtlc getCommandBufferWrapper] getCommandBuffer] blitCommandEncoder];
}

- (void) endEncoder {
    if (_encoder != nil) {
        [_encoder endEncoding];
        [_encoder release];
        _encoder = nil;
        _destination = nil;
    }
}

@end
