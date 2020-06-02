#import <limits.h>
#ifndef MTLClip_h_Included
#define MTLClip_h_Included

#import <Metal/Metal.h>

#include <jni.h>

#include "MTLSurfaceDataBase.h"

enum Clip {
    NO_CLIP,
    RECT_CLIP,
    SHAPE_CLIP
};

@class MTLContext;
@class MTLPipelineStatesStorage;

/**
 * The MTLClip class represents clip mode (rect or stencil)
 * */

@interface MTLClip : NSObject
@property (readonly) id<MTLTexture> stencilAADataRef;
@property (readonly) id<MTLTexture> stencilTextureRef;
@property (readonly) BOOL stencilMaskGenerationInProgress;

- (id)init;
- (BOOL)isEqual:(MTLClip *)other; // used to compare requested with cached
- (void)copyFrom:(MTLClip *)other; // used to save cached

- (BOOL)isShape;
- (BOOL)isRect;

// returns null when clipType != RECT_CLIP
- (const MTLScissorRect *) getRect;

- (void)reset;
- (void)setClipRectX1:(jint)x1 Y1:(jint)y1 X2:(jint)x2 Y2:(jint)y2;
- (void)beginShapeClip:(BMTLSDOps *)dstOps context:(MTLContext *)mtlc;
- (void)endShapeClip:(BMTLSDOps *)dstOps context:(MTLContext *)mtlc;

- (void)setScissorOrStencil:(id<MTLRenderCommandEncoder>)encoder
                  destWidth:(NSUInteger)dw
                 destHeight:(NSUInteger)dh
                     device:(id<MTLDevice>)device;

- (void)setMaskGenerationPipelineState:(id<MTLRenderCommandEncoder>)encoder
                             destWidth:(NSUInteger)dw
                            destHeight:(NSUInteger)dh
                  pipelineStateStorage:(MTLPipelineStatesStorage *)pipelineStateStorage;

- (NSString *)getDescription __unused; // creates autorelease string
@end

#endif // MTLClip_h_Included