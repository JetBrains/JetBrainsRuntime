#ifndef EncoderManager_h_Included
#define EncoderManager_h_Included

#import <Metal/Metal.h>

#include "RenderOptions.h"

@class MTLContex;

/**
 * The EncoderManager class used to obtain MTLRenderCommandEncoder (or MTLBlitCommandEncoder) corresponding
 * to the current state of MTLContext.
 *
 * Due to performance issues (creation of MTLRenderCommandEncoder isn't cheap), each getXXXEncoder invocation
 * updates properties of common (cached) encoder and returns this encoder.
 *
 * Base method getEncoder does the following:
 *  1. Checks whether common encoder must be closed and recreated (some of encoder properties is 'persistent',
 *  for example destination, stencil, or any other property of MTLRenderPassDescriptor)
 *  2. Updates 'mutable' properties encoder: pipelineState (with corresponding buffers), clip, transform, e.t.c. To avoid
 *  unnecessary calls of [encoder setXXX] this manager compares requested state with cached one.
 * */
@interface EncoderManager : NSObject
- (id _Nonnull)init;
- (void)dealloc;

- (void)setContext:(MTLContex * _Nonnull)mtlc;

// returns encoder that renders/fills geometry with current paint and composite
- (id<MTLRenderCommandEncoder> _Nonnull)getRenderEncoder:(const BMTLSDOps * _Nonnull)dstOps;

- (id<MTLRenderCommandEncoder> _Nonnull)getAARenderEncoder:(const BMTLSDOps * _Nonnull)dstOps;

- (id<MTLRenderCommandEncoder> _Nonnull)getRenderEncoder:(id<MTLTexture> _Nonnull)dest
                                             isDstOpaque:(bool)isOpaque;

// returns encoder that renders/fills geometry with current composite and with given texture
// (user must call [encoder setFragmentTexture] before any rendering)
- (id<MTLRenderCommandEncoder> _Nonnull)getTextureEncoder:(const BMTLSDOps * _Nonnull)dstOps
                                      isSrcOpaque:(bool)isSrcOpaque;

- (id<MTLRenderCommandEncoder> _Nonnull) getTextureEncoder:(id<MTLTexture> _Nonnull)dest
                                               isSrcOpaque:(bool)isSrcOpaque
                                               isDstOpaque:(bool)isDstOpaque;

- (id<MTLRenderCommandEncoder> _Nonnull)getTextureEncoder:(id<MTLTexture> _Nonnull)dest
                                      isSrcOpaque:(bool)isSrcOpaque
                                      isDstOpaque:(bool)isDstOpaque
                                    interpolation:(int)interpolation;

- (id<MTLRenderCommandEncoder> _Nonnull)getTextureEncoder:(id<MTLTexture> _Nonnull)dest
                                              isSrcOpaque:(bool)isSrcOpaque
                                              isDstOpaque:(bool)isDstOpaque
                                            interpolation:(int)interpolation
                                                     isAA:(jboolean)isAA;

- (id<MTLRenderCommandEncoder> _Nonnull)getTextEncoder:(const BMTLSDOps * _Nonnull)dstOps
                                      isSrcOpaque:(bool)isSrcOpaque;

// Base method to obtain any MTLRenderCommandEncoder
- (id<MTLRenderCommandEncoder> _Nonnull) getEncoder:(id<MTLTexture> _Nonnull)dest
                                       isDestOpaque:(jboolean)isDestOpaque
                                      renderOptions:(const RenderOptions * _Nonnull)renderOptions;

- (id<MTLBlitCommandEncoder> _Nonnull)createBlitEncoder;

- (void)endEncoder;
@end

#endif // EncoderManager_h_Included
