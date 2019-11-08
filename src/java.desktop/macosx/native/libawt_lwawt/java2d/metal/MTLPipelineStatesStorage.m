#import "MTLPipelineStatesStorage.h"
#import "Trace.h"

#include "GraphicsPrimitiveMgr.h"
#import "common.h"

static void setBlendingFactors(MTLRenderPipelineColorAttachmentDescriptor * cad,
    int compositeRule,
    bool isSourcePremultiplied,
    bool isDestPremultiplied,
    bool isSrcOpaque,
    bool isDstOpaque);

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
        vertDesc.attributes[VertexAttributePosition].format = MTLVertexFormatFloat2;
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
        self.templateTexturePipelineDesc.vertexDescriptor.attributes[VertexAttributeTexPos].offset = 2*sizeof(float);
        self.templateTexturePipelineDesc.vertexDescriptor.attributes[VertexAttributeTexPos].bufferIndex = MeshVertexBuffer;
        self.templateTexturePipelineDesc.vertexDescriptor.layouts[MeshVertexBuffer].stride = sizeof(struct TxtVertex);
        self.templateTexturePipelineDesc.vertexDescriptor.layouts[MeshVertexBuffer].stepRate = 1;
        self.templateTexturePipelineDesc.vertexDescriptor.layouts[MeshVertexBuffer].stepFunction = MTLVertexStepFunctionPerVertex;
        self.templateTexturePipelineDesc.label = @"template_texture";
    }

    { // pre-create main states
        [self getRenderPipelineState:YES];
        [self getRenderPipelineState:NO];
    }

    return self;
}

- (id<MTLRenderPipelineState>) getRenderPipelineState:(bool)isGradient {

    NSString * uid = @"render_grad[0]";
    if (isGradient == TRUE) {
        uid = @"render_grad[1]";
    }

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

- (id<MTLRenderPipelineState>) getTexturePipelineState:(bool)isSourcePremultiplied
    isDestPremultiplied:(bool)isDestPremultiplied
    isSrcOpaque:(bool)isSrcOpaque
    isDstOpaque:(bool)isDstOpaque
    compositeRule:(int)compositeRule
{
    @autoreleasepool {
        NSString *uid = [NSString stringWithFormat:@"texture_compositeRule[%d]", compositeRule];

        id <MTLRenderPipelineState> result = [self.states valueForKey:uid];
        if (result == nil) {
            id <MTLFunction> vertexShader = [self getShader:@"vert_txt"];
            id <MTLFunction> fragmentShader = [self getShader:@"frag_txt"];
            MTLRenderPipelineDescriptor *pipelineDesc = [[self.templateTexturePipelineDesc copy] autorelease];
            pipelineDesc.vertexFunction = vertexShader;
            pipelineDesc.fragmentFunction = fragmentShader;

            setBlendingFactors(
                pipelineDesc.colorAttachments[0],
                compositeRule,
                isSourcePremultiplied, isDestPremultiplied,
                isSrcOpaque, isDstOpaque
            );

            NSError *error = nil;
            result = [[self.device newRenderPipelineStateWithDescriptor:pipelineDesc error:&error] autorelease];
            if (result == nil) {
                NSLog(@"Failed to create texture pipeline state '%@', error %@", uid, error);
                exit(0);
            }

            [self.states setValue:result forKey:uid];
        }

        return result;
    }
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

static void setBlendingFactors(
        MTLRenderPipelineColorAttachmentDescriptor * cad,
        int compositeRule,
        bool isSourcePremultiplied,
        bool isDestPremultiplied,
        bool isSrcOpaque,
        bool isDstOpaque
) {
    if (compositeRule == RULE_Src) {
        J2dTraceLn(J2D_TRACE_VERBOSE, "set RULE_Src");
        return;
    }

    cad.blendingEnabled = YES;

    // RGB = Source.rgb * SBFc + Dest.rgb * DBFc
    // A = Source.a * SBFa + Dest.a * DBFa
    //
    // default mode == RULE_Src with constants:
    // DBFa=0
    // DBFc=0
    // SBFa=1
    // SBFc=1
    //
    // NOTE: constants MTLBlendFactorBlendAlpha, MTLBlendFactorOneMinusBlendAlpha refers to [encoder setBlendColorRed:green:blue:alpha:] (default value is zero)
    //
    // TODO: implement alpha-composite via shaders (will be much more simpler and can support all rules and modes)

    switch (compositeRule) {
        case RULE_SrcOver: {
            // Ar = As + Ad*(1-As)
            // Cr = Cs + Cd*(1-As)
            if (isSrcOpaque) {
                J2dTraceLn(J2D_TRACE_VERBOSE, "rule=RULE_Src, but blending is disabled because src is opaque");
                cad.blendingEnabled = NO;
                return;
            }
            if (isDstOpaque) {
                J2dTraceLn(J2D_TRACE_ERROR, "Composite rule RULE_SrcOver with opaque dest isn't implemented (dst alpha won't be ignored)");
            }
            if (!isSourcePremultiplied) {
                cad.sourceRGBBlendFactor = MTLBlendFactorSourceAlpha;
            }
            cad.destinationAlphaBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
            cad.destinationRGBBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
            J2dTraceLn(J2D_TRACE_VERBOSE, "set RULE_SrcOver");
            break;
        }
        case RULE_DstOver: {
            // Ar = As*(1-Ad) + Ad
            // Cr = Cs*(1-Ad) + Cd
            if (isSrcOpaque) {
                J2dTraceLn(J2D_TRACE_ERROR, "Composite rule RULE_DstOver with opaque src isn't implemented (src alpha won't be ignored)");
            }
            if (isDstOpaque) {
                J2dTraceLn(J2D_TRACE_ERROR, "Composite rule RULE_DstOver with opaque dest hasn't any sense");
            }
            if (!isSourcePremultiplied) {
                J2dTrace(J2D_TRACE_ERROR, "Composite rule RULE_DstOver with non-premultiplied source isn't implemented (scr alpha will be ignored for rgb-component)");
            }
            cad.sourceAlphaBlendFactor = MTLBlendFactorOneMinusDestinationAlpha;
            cad.sourceRGBBlendFactor = MTLBlendFactorOneMinusDestinationAlpha;
            cad.destinationAlphaBlendFactor = MTLBlendFactorOne;
            cad.destinationRGBBlendFactor = MTLBlendFactorOne;
            J2dTraceLn(J2D_TRACE_VERBOSE, "set RULE_DstOver");
            break;
        }
        case RULE_SrcIn: {
            // Ar = As*Ad
            // Cr = Cs*Ad
            if (isSrcOpaque) {
                J2dTraceLn(J2D_TRACE_ERROR, "Composite rule RULE_SrcIn with opaque src isn't implemented (src alpha won't be ignored)");
            }
            if (isDstOpaque) {
                J2dTraceLn(J2D_TRACE_VERBOSE, "rule=RULE_SrcIn, but blending is disabled because dest is opaque");
                cad.blendingEnabled = NO;
                return;
            }
            if (!isSourcePremultiplied) {
                J2dTrace(J2D_TRACE_ERROR, "Composite rule RULE_SrcIn with non-premultiplied source isn't implemented (scr alpha will be ignored for rgb-component)");
            }
            cad.sourceAlphaBlendFactor = MTLBlendFactorDestinationAlpha;
            cad.sourceRGBBlendFactor = MTLBlendFactorDestinationAlpha;
            cad.destinationAlphaBlendFactor = MTLBlendFactorZero;
            cad.destinationRGBBlendFactor = MTLBlendFactorZero;
            J2dTraceLn(J2D_TRACE_VERBOSE, "set RULE_SrcIn");
            break;
        }
        case RULE_DstIn: {
            // Ar = Ad*As
            // Cr = Cd*As
            if (isSrcOpaque) {
                J2dTraceLn(J2D_TRACE_ERROR, "Composite rule RULE_DstIn with opaque src isn't implemented (src alpha won't be ignored)");
            }
            if (isDstOpaque) {
                J2dTraceLn(J2D_TRACE_ERROR, "Composite rule RULE_DstIn with opaque dest isn't implemented (dest alpha won't be ignored)");
            }
            cad.sourceAlphaBlendFactor = MTLBlendFactorZero;
            cad.sourceRGBBlendFactor = MTLBlendFactorZero;
            cad.destinationAlphaBlendFactor = MTLBlendFactorSourceAlpha;
            cad.destinationRGBBlendFactor = MTLBlendFactorSourceAlpha;
            J2dTraceLn(J2D_TRACE_VERBOSE, "set RULE_DstIn");
            break;
        }
        case RULE_SrcOut: {
            // Ar = As*(1-Ad)
            // Cr = Cs*(1-Ad)
            if (!isSourcePremultiplied) {
                J2dTrace(J2D_TRACE_ERROR, "Composite rule SrcOut with non-premultiplied source isn't implemented (scr alpha will be ignored for rgb-component)");
            }
            cad.sourceAlphaBlendFactor = MTLBlendFactorOneMinusDestinationAlpha;
            cad.sourceRGBBlendFactor = MTLBlendFactorOneMinusDestinationAlpha;
            cad.destinationAlphaBlendFactor = MTLBlendFactorZero;
            cad.destinationRGBBlendFactor = MTLBlendFactorZero;
            J2dTraceLn(J2D_TRACE_VERBOSE, "set RULE_SrcOut");
            break;
        }
        case RULE_DstOut: {
            // Ar = Ad*(1-As)
            // Cr = Cd*(1-As)
            cad.sourceAlphaBlendFactor = MTLBlendFactorZero;
            cad.sourceRGBBlendFactor = MTLBlendFactorZero;
            cad.destinationAlphaBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
            cad.destinationRGBBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
            J2dTraceLn(J2D_TRACE_VERBOSE, "set RULE_DstOut");
            break;
        }
        case RULE_Xor: {
            // Ar = As*(1-Ad) + Ad*(1-As)
            // Cr = Cs*(1-Ad) + Cd*(1-As)
            if (!isSourcePremultiplied) {
                J2dTrace(J2D_TRACE_ERROR, "Composite rule Xor with non-premultiplied source isn't implemented (scr alpha will be ignored for rgb-component)");
            }
            cad.sourceAlphaBlendFactor = MTLBlendFactorOneMinusDestinationAlpha;
            cad.sourceRGBBlendFactor = MTLBlendFactorOneMinusDestinationAlpha;
            cad.destinationAlphaBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
            cad.destinationRGBBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
            J2dTraceLn(J2D_TRACE_VERBOSE, "set RULE_Xor");
            break;
        }
        case RULE_Clear: {
            // Ar = 0
            // Cr = 0
            cad.sourceAlphaBlendFactor = MTLBlendFactorZero;
            cad.sourceRGBBlendFactor = MTLBlendFactorZero;
            cad.destinationAlphaBlendFactor = MTLBlendFactorZero;
            cad.destinationRGBBlendFactor = MTLBlendFactorZero;
            J2dTraceLn(J2D_TRACE_VERBOSE, "set RULE_Clear");
            break;
        }

        default: {
            J2dTrace1(J2D_TRACE_ERROR, "Unimplemented composite rule %d (will be used Src)", compositeRule);
            cad.blendingEnabled = NO;
        }
    }

}