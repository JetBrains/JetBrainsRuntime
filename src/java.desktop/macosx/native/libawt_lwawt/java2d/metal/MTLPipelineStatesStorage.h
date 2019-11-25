#ifndef MTLPipelineStatesStorage_h_Included
#define MTLPipelineStatesStorage_h_Included

#import <Metal/Metal.h>
#include "MTLSurfaceDataBase.h"

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
- (id<MTLRenderPipelineState>) getPipelineState:(MTLRenderPipelineDescriptor *) pipelineDescriptor
                                 vertexShaderId:(NSString *)vertexShaderId
                               fragmentShaderId:(NSString *)fragmentShaderId
                                  compositeRule:(jint)compositeRule
                                       srcFlags:(const SurfaceRasterFlags * )srcFlags
                                       dstFlags:(const SurfaceRasterFlags * )dstFlags;
- (id<MTLFunction>) getShader:(NSString *)name;
@end


#endif // MTLPipelineStatesStorage_h_Included