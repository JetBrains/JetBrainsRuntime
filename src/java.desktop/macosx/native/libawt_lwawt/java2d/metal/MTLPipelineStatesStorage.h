#ifndef MTLPipelineStatesStorage_h_Included
#define MTLPipelineStatesStorage_h_Included

#import "MTLUtils.h"
#include "MTLSurfaceDataBase.h"

/**
 * The MTLPipelineStatesStorage class used to obtain MTLRenderPipelineState
 * */


@interface MTLPipelineStatesStorage : NSObject {
@private

id<MTLDevice>       device;
id<MTLLibrary>      library;
NSMutableDictionary<NSString*, id<MTLFunction>> * shaders;
}

@property (readwrite, assign) id<MTLDevice> device;
@property (readwrite, retain) id<MTLLibrary> library;
@property (readwrite, retain) NSMutableDictionary<NSString*, id<MTLFunction>> * shaders;
@property (readwrite, retain) NSMutableDictionary<NSString*, NSMutableDictionary *> * states;

- (id) initWithDevice:(id<MTLDevice>)device shaderLibPath:(NSString *)shadersLib;

// returns pipelineState with disabled blending and stencil
- (id<MTLRenderPipelineState>) getPipelineState:(MTLRenderPipelineDescriptor *) pipelineDescriptor
                                 vertexShaderId:(NSString *)vertexShaderId
                               fragmentShaderId:(NSString *)fragmentShaderId;

// returns pipelineState with composite for default SurfaceRasterFlags
- (id<MTLRenderPipelineState>) getPipelineState:(MTLRenderPipelineDescriptor *) pipelineDescriptor
                                 vertexShaderId:(NSString *)vertexShaderId
                               fragmentShaderId:(NSString *)fragmentShaderId
                                  compositeRule:(jint)compositeRule
                                  stencilNeeded:(bool)stencilNeeded;

// base method to obtain MTLRenderPipelineState
- (id<MTLRenderPipelineState>) getPipelineState:(MTLRenderPipelineDescriptor *) pipelineDescriptor
                                 vertexShaderId:(NSString *)vertexShaderId
                               fragmentShaderId:(NSString *)fragmentShaderId
                                  compositeRule:(jint)compositeRule
                                       srcFlags:(const SurfaceRasterFlags * )srcFlags
                                       dstFlags:(const SurfaceRasterFlags * )dstFlags
                                  stencilNeeded:(bool)stencilNeeded;

- (id<MTLRenderPipelineState>) getPipelineState:(MTLRenderPipelineDescriptor *) pipelineDescriptor
                                 vertexShaderId:(NSString *)vertexShaderId
                               fragmentShaderId:(NSString *)fragmentShaderId
                                  compositeRule:(jint)compositeRule
                                           isAA:(jboolean)isAA
                                       srcFlags:(const SurfaceRasterFlags *)srcFlags
                                       dstFlags:(const SurfaceRasterFlags *)dstFlags
                                  stencilNeeded:(bool)stencilNeeded;

- (id<MTLFunction>) getShader:(NSString *)name;
@end


#endif // MTLPipelineStatesStorage_h_Included