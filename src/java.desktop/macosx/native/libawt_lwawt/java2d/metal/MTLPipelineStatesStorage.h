#ifndef MTLPipelineStatesStorage_h_Included
#define MTLPipelineStatesStorage_h_Included

#import <Metal/Metal.h>

@interface MTLPipelineStatesStorage : NSObject {
@private

id<MTLDevice>       device;
id<MTLLibrary>      library;
NSMutableDictionary<NSString*, id<MTLFunction>> * shaders;
NSMutableDictionary<NSString*, id<MTLRenderPipelineState>> * states;
MTLRenderPipelineDescriptor * templateRenderPipelineDesc;
MTLRenderPipelineDescriptor * templateTexturePipelineDesc;
}

@property (readwrite, assign) id<MTLDevice> device;
@property (readwrite, retain) id<MTLLibrary> library;
@property (readwrite, retain) NSMutableDictionary<NSString*, id<MTLFunction>> * shaders;
@property (readwrite, retain) NSMutableDictionary<NSString*, id<MTLRenderPipelineState>> * states;
@property (readwrite, retain) MTLRenderPipelineDescriptor * templateRenderPipelineDesc;
@property (readwrite, retain) MTLRenderPipelineDescriptor * templateTexturePipelineDesc;

- (id) initWithDevice:(id<MTLDevice>)device shaderLibPath:(NSString *)shadersLib;
- (id<MTLRenderPipelineState>) getRenderPipelineState:(bool)isGradient;
- (id<MTLRenderPipelineState>) getTexturePipelineState:(bool)isSourcePremultiplied compositeRule:(int)compositeRule;
- (id<MTLFunction>) getShader:(NSString *)name;
@end


#endif // MTLPipelineStatesStorage_h_Included