#ifndef MTLPipelineStatesStorage_h_Included
#define MTLPipelineStatesStorage_h_Included

#import "MTLUtils.h"
#include "RenderOptions.h"

@class MTLComposite;

/**
 * The MTLPipelineStatesStorage class used to obtain MTLRenderPipelineState
 * */


@interface MTLPipelineStatesStorage : NSObject {
@private

id<MTLDevice>       device;
id<MTLLibrary>      library;
NSMutableDictionary<NSString*, id<MTLFunction>> * shaders;
NSMutableDictionary<NSString*, id<MTLComputePipelineState>> * computeStates;
}

@property (readwrite, assign) id<MTLDevice> device;
@property (readwrite, retain) id<MTLLibrary> library;
@property (readwrite, retain) NSMutableDictionary<NSString*, id<MTLFunction>> * shaders;
@property (readwrite, retain) NSMutableDictionary<NSString*, NSMutableDictionary *> * states;

- (id) initWithDevice:(id<MTLDevice>)device shaderLibPath:(NSString *)shadersLib;

- (id<MTLRenderPipelineState>) getPipelineState:(MTLRenderPipelineDescriptor *) pipelineDescriptor
                                 vertexShaderId:(NSString *)vertexShaderId
                               fragmentShaderId:(NSString *)fragmentShaderId;

- (id<MTLRenderPipelineState>) getPipelineState:(MTLRenderPipelineDescriptor *) pipelineDescriptor
                                 vertexShaderId:(NSString *)vertexShaderId
                               fragmentShaderId:(NSString *)fragmentShaderId
                                      composite:(MTLComposite*)composite
                                  renderOptions:(const RenderOptions *)renderOptions
                                  stencilNeeded:(bool)stencilNeeded;

- (id<MTLComputePipelineState>) getComputePipelineState:(NSString *)computeShaderId;

- (id<MTLFunction>) getShader:(NSString *)name;
@end


#endif // MTLPipelineStatesStorage_h_Included