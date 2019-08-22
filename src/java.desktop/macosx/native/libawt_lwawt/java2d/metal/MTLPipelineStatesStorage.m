#import "MTLPipelineStatesStorage.h"
#import "Trace.h"

#include "GraphicsPrimitiveMgr.h"
#import "common.h"

@implementation MTLPipelineStatesStorage

@synthesize device;
@synthesize library;
@synthesize shaders;
@synthesize states;
@synthesize templateRenderPipelineDesc;
@synthesize templateTexturePipelineDesc;

- (id) initWithDevice:(id<MTLDevice>)dev shaderLibPath:(NSString *)shadersLib {
    self = [super init];
    if (self == nil) return self;

    self.device = dev;

    NSError *error = nil;
    self.library = [dev newLibraryWithFile:shadersLib error:&error];
    if (!self.library) {
        NSLog(@"Failed to load library. error %@", error);
        exit(0);
    }
    self.shaders = [NSMutableDictionary dictionaryWithCapacity:10];
    self.states = [NSMutableDictionary dictionaryWithCapacity:10];

    { // init template descriptors
        MTLVertexDescriptor *vertDesc = [[MTLVertexDescriptor new] autorelease];
        vertDesc.attributes[VertexAttributePosition].format = MTLVertexFormatFloat3;
        vertDesc.attributes[VertexAttributePosition].offset = 0;
        vertDesc.attributes[VertexAttributePosition].bufferIndex = MeshVertexBuffer;
        vertDesc.layouts[MeshVertexBuffer].stride = sizeof(struct Vertex);
        vertDesc.layouts[MeshVertexBuffer].stepRate = 1;
        vertDesc.layouts[MeshVertexBuffer].stepFunction = MTLVertexStepFunctionPerVertex;

        self.templateRenderPipelineDesc = [[MTLRenderPipelineDescriptor new] autorelease];
        self.templateRenderPipelineDesc.sampleCount = 1;
        self.templateRenderPipelineDesc.vertexDescriptor = vertDesc;
        self.templateRenderPipelineDesc.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
        self.templateRenderPipelineDesc.label = @"template_render";

        self.templateTexturePipelineDesc = [[self.templateRenderPipelineDesc copy] autorelease];
        self.templateTexturePipelineDesc.vertexDescriptor.attributes[VertexAttributeTexPos].format = MTLVertexFormatFloat2;
        self.templateTexturePipelineDesc.vertexDescriptor.attributes[VertexAttributeTexPos].offset = 3*sizeof(float);
        self.templateTexturePipelineDesc.vertexDescriptor.attributes[VertexAttributeTexPos].bufferIndex = MeshVertexBuffer;
        self.templateTexturePipelineDesc.vertexDescriptor.layouts[MeshVertexBuffer].stride = sizeof(struct TxtVertex);
        self.templateTexturePipelineDesc.vertexDescriptor.layouts[MeshVertexBuffer].stepRate = 1;
        self.templateTexturePipelineDesc.vertexDescriptor.layouts[MeshVertexBuffer].stepFunction = MTLVertexStepFunctionPerVertex;
        self.templateTexturePipelineDesc.label = @"template_texture";
    }

    { // pre-create main states
        [self getRenderPipelineState:YES];
        [self getRenderPipelineState:NO];
        [self getTexturePipelineState:NO compositeRule:RULE_Src];
        [self getTexturePipelineState:NO compositeRule:RULE_SrcOver];
    }

    return self;
}

- (id<MTLRenderPipelineState>) getRenderPipelineState:(bool)isGradient {
    NSString * uid = [NSString stringWithFormat:@"render_grad[%d]", isGradient];

    id<MTLRenderPipelineState> result = [self.states valueForKey:uid];
    if (result == nil) {
        id<MTLFunction> vertexShader   = isGradient ? [self getShader:@"vert_grad"] : [self getShader:@"vert_col"];
        id<MTLFunction> fragmentShader = isGradient ? [self getShader:@"frag_grad"] : [self getShader:@"frag_col"];
        MTLRenderPipelineDescriptor *pipelineDesc = [[self.templateRenderPipelineDesc copy] autorelease];
        pipelineDesc.vertexFunction = vertexShader;
        pipelineDesc.fragmentFunction = fragmentShader;
        pipelineDesc.label = uid;

        NSError *error = nil;
        result = [self.device newRenderPipelineStateWithDescriptor:pipelineDesc error:&error];
        if (result == nil) {
            NSLog(@"Failed to create render pipeline state '%@', error %@", uid, error);
            exit(0);
        }

        [self.states setValue:result forKey:uid];
    }

    return result;
};

- (id<MTLRenderPipelineState>) getTexturePipelineState:(bool)isSourcePremultiplied compositeRule:(int)compositeRule {
    NSString * uid = [NSString stringWithFormat:@"texture_compositeRule[%d]", compositeRule];

    id<MTLRenderPipelineState> result = [self.states valueForKey:uid];
    if (result == nil) {
        id<MTLFunction> vertexShader   = [self getShader:@"vert_txt"];
        id<MTLFunction> fragmentShader = [self getShader:@"frag_txt"];
        MTLRenderPipelineDescriptor *pipelineDesc = [[self.templateTexturePipelineDesc copy] autorelease];
        pipelineDesc.vertexFunction = vertexShader;
        pipelineDesc.fragmentFunction = fragmentShader;

        if (compositeRule != RULE_Src) {
            pipelineDesc.colorAttachments[0].blendingEnabled = YES;

            if (!isSourcePremultiplied)
                pipelineDesc.colorAttachments[0].sourceRGBBlendFactor = MTLBlendFactorSourceAlpha;

            //RGB = Source.rgb * SBF + Dest.rgb * DBF
            //A = Source.a * SBF + Dest.a * DBF
            //
            //default SRC:
            //DBF=0
            //SBF=1
            if (compositeRule == RULE_SrcOver) {
                // SRC_OVER (Porter-Duff Source Over Destination rule):
                // Ar = As + Ad*(1-As)
                // Cr = Cs + Cd*(1-As)
                pipelineDesc.colorAttachments[0].destinationAlphaBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
                pipelineDesc.colorAttachments[0].destinationRGBBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
            } else {
                J2dTrace1(J2D_TRACE_ERROR, "Unimplemented composite rule %d (will be used Src)", compositeRule);
                pipelineDesc.colorAttachments[0].blendingEnabled = NO;
            }
        }

        NSError *error = nil;
        result = [self.device newRenderPipelineStateWithDescriptor:pipelineDesc error:&error];
        if (result == nil) {
            NSLog(@"Failed to create texture pipeline state '%@', error %@", uid, error);
            exit(0);
        }

        [self.states setValue:result forKey:uid];
    }

    return result;
}

- (id<MTLFunction>) getShader:(NSString *)name {
    id<MTLFunction> result = [self.shaders valueForKey:name];
    if (result == nil) {
        result = [[self.library newFunctionWithName:name] autorelease];
        [self.shaders setValue:result forKey:name];
    }
    return result;
}
@end