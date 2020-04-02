#import "MTLPipelineStatesStorage.h"

#include "GraphicsPrimitiveMgr.h"
#import "MTLComposite.h"

// A special value for xor composite
const int XOR_COMPOSITE_RULE = 20;

extern const SurfaceRasterFlags defaultRasterFlags;

static void setBlendingFactors(
        MTLRenderPipelineColorAttachmentDescriptor * cad,
        int compositeRule,
        MTLComposite* composite,
        const SurfaceRasterFlags * srcFlags, const SurfaceRasterFlags * dstFlags);

@implementation MTLPipelineStatesStorage

@synthesize device;
@synthesize library;
@synthesize shaders;
@synthesize states;

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
    return self;
}

- (NSPointerArray * ) getSubStates:(NSString *)vertexShaderId fragmentShader:(NSString *)fragmentShaderId {
    NSMutableDictionary * vSubStates = states[vertexShaderId];
    if (vSubStates == nil) {
        @autoreleasepool {
            vSubStates = [NSMutableDictionary dictionary];
            [states setObject:vSubStates forKey:vertexShaderId];
        }
    }
    NSPointerArray * sSubStates = vSubStates[fragmentShaderId];
    if (sSubStates == nil) {
        @autoreleasepool {
            sSubStates = [NSPointerArray strongObjectsPointerArray];
            [vSubStates setObject:sSubStates forKey:fragmentShaderId];
        }
    }
    return sSubStates;
}

- (id<MTLRenderPipelineState>) getPipelineState:(MTLRenderPipelineDescriptor *) pipelineDescriptor
                                 vertexShaderId:(NSString *)vertexShaderId
                               fragmentShaderId:(NSString *)fragmentShaderId
{
    return [self getPipelineState:pipelineDescriptor
                   vertexShaderId:vertexShaderId
                 fragmentShaderId:fragmentShaderId
                    compositeRule:RULE_Src
                         srcFlags:NULL
                         dstFlags:NULL
                    stencilNeeded:NO];
}

- (id<MTLRenderPipelineState>) getPipelineState:(MTLRenderPipelineDescriptor *) pipelineDescriptor
                                 vertexShaderId:(NSString *)vertexShaderId
                               fragmentShaderId:(NSString *)fragmentShaderId
                                  compositeRule:(jint)compositeRule
                                  stencilNeeded:(bool)stencilNeeded;
{
    return [self getPipelineState:pipelineDescriptor
                   vertexShaderId:vertexShaderId
                 fragmentShaderId:fragmentShaderId
                    compositeRule:compositeRule
                         srcFlags:&defaultRasterFlags
                         dstFlags:&defaultRasterFlags
                    stencilNeeded:stencilNeeded];
}

- (id<MTLRenderPipelineState>) getPipelineState:(MTLRenderPipelineDescriptor *) pipelineDescriptor
                                 vertexShaderId:(NSString *)vertexShaderId
                               fragmentShaderId:(NSString *)fragmentShaderId
                                  compositeRule:(jint)compositeRule
                                       srcFlags:(const SurfaceRasterFlags *)srcFlags
                                       dstFlags:(const SurfaceRasterFlags *)dstFlags
                                  stencilNeeded:(bool)stencilNeeded;
{
    return [self getPipelineState:pipelineDescriptor
                   vertexShaderId:vertexShaderId
                 fragmentShaderId:fragmentShaderId
                    compositeRule:compositeRule
                             isAA:JNI_FALSE
                         srcFlags:srcFlags
                         dstFlags:dstFlags
            stencilNeeded:stencilNeeded];
}

- (id<MTLRenderPipelineState>) getPipelineState:(MTLRenderPipelineDescriptor *) pipelineDescriptor
                                 vertexShaderId:(NSString *)vertexShaderId
                               fragmentShaderId:(NSString *)fragmentShaderId
                                  compositeRule:(jint)compositeRule
                                           isAA:(jboolean)isAA
                                       srcFlags:(const SurfaceRasterFlags *)srcFlags
                                       dstFlags:(const SurfaceRasterFlags *)dstFlags
                                  stencilNeeded:(bool)stencilNeeded;
{
    return [self getPipelineState:pipelineDescriptor
                   vertexShaderId:vertexShaderId
                 fragmentShaderId:fragmentShaderId
                    compositeRule:compositeRule
                        composite:nil
                             isAA:isAA
                         srcFlags:srcFlags
                         dstFlags:dstFlags
                    stencilNeeded:stencilNeeded];
}

// Base method to obtain MTLRenderPipelineState.
// NOTE: parameters compositeRule, srcFlags, dstFlags are used to set MTLRenderPipelineColorAttachmentDescriptor multipliers
- (id<MTLRenderPipelineState>) getPipelineState:(MTLRenderPipelineDescriptor *) pipelineDescriptor
                                 vertexShaderId:(NSString *)vertexShaderId
                               fragmentShaderId:(NSString *)fragmentShaderId
                                  compositeRule:(jint)compositeRule
                                      composite:(MTLComposite*) composite
                                           isAA:(jboolean)isAA
                                       srcFlags:(const SurfaceRasterFlags *)srcFlags
                                       dstFlags:(const SurfaceRasterFlags *)dstFlags
                                  stencilNeeded:(bool)stencilNeeded;
{
    const jboolean useXorComposite = (compositeRule == XOR_COMPOSITE_RULE);
    const jboolean useComposite = compositeRule >= 0
        && compositeRule < java_awt_AlphaComposite_MAX_RULE
        && srcFlags != NULL && dstFlags != NULL;

    // Calculate index by flags and compositeRule
    // TODO: reimplement, use map with convenient key (calculated by all arguments)
    int subIndex = 0;
    if (useXorComposite) {
        // compositeRule value is already XOR_COMPOSITE_RULE
    }
    else {
        if (useComposite) {
            if (!srcFlags->isPremultiplied)
                subIndex |= 1;
            if (srcFlags->isOpaque)
                subIndex |= 1 << 1;
            if (!dstFlags->isPremultiplied)
                subIndex |= 1 << 2;
            if (dstFlags->isOpaque)
                subIndex |= 1 << 3;
        } else
            compositeRule = RULE_Src;
    }

    if (stencilNeeded) {
        subIndex |= 1 << 4;
    }

    if (isAA) {
        subIndex |= 1 << 5;
    }

    if ((composite != nil && FLT_LT([composite getExtraAlpha], 1.0f))) {
        subIndex |= 1 << 6;
    }
    int index = compositeRule*64 + subIndex;

    NSPointerArray * subStates = [self getSubStates:vertexShaderId fragmentShader:fragmentShaderId];
    while (index >= [subStates count]) {
        [subStates addPointer:NULL]; // obj-c collections haven't resize methods, so do that
    }

    id<MTLRenderPipelineState> result = [subStates pointerAtIndex:index];
    if (result == nil) {
        @autoreleasepool {
            id <MTLFunction> vertexShader = [self getShader:vertexShaderId];
            id <MTLFunction> fragmentShader = [self getShader:fragmentShaderId];
            MTLRenderPipelineDescriptor *pipelineDesc = [[pipelineDescriptor copy] autorelease];
            pipelineDesc.vertexFunction = vertexShader;
            pipelineDesc.fragmentFunction = fragmentShader;

            if (useXorComposite) {
                pipelineDesc.colorAttachments[0].blendingEnabled = YES;
 
                pipelineDesc.colorAttachments[0].rgbBlendOperation = MTLBlendOperationAdd;
                pipelineDesc.colorAttachments[0].sourceRGBBlendFactor = MTLBlendFactorOneMinusDestinationColor;
                pipelineDesc.colorAttachments[0].destinationRGBBlendFactor =  MTLBlendFactorOneMinusSourceColor;

            } else if (useComposite ||
                       (composite != nil  &&
                        FLT_GE([composite getExtraAlpha], 1.0f)))
            {
                setBlendingFactors(
                        pipelineDesc.colorAttachments[0],
                        compositeRule,
                        composite,
                        srcFlags, dstFlags
                );
            }
            if (stencilNeeded) {
                pipelineDesc.stencilAttachmentPixelFormat = MTLPixelFormatStencil8;
            }

            if (isAA) {
                pipelineDesc.sampleCount = MTLAASampleCount;
                pipelineDesc.colorAttachments[0].rgbBlendOperation =   MTLBlendOperationAdd;
                pipelineDesc.colorAttachments[0].alphaBlendOperation = MTLBlendOperationAdd;
                pipelineDesc.colorAttachments[0].sourceRGBBlendFactor = MTLBlendFactorSourceAlpha;
                pipelineDesc.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorSourceAlpha;
                pipelineDesc.colorAttachments[0].destinationRGBBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
                pipelineDesc.colorAttachments[0].destinationAlphaBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
                pipelineDesc.colorAttachments[0].blendingEnabled = YES;
            }

            NSError *error = nil;
            result = [[self.device newRenderPipelineStateWithDescriptor:pipelineDesc error:&error] autorelease];
            if (result == nil) {
                NSLog(@"Failed to create pipeline state, error %@", error);
                exit(0);
            }

            [subStates insertPointer:result atIndex:index];
        }
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

- (id<MTLRenderPipelineState>) getXorModePipelineState:(MTLRenderPipelineDescriptor *) pipelineDescriptor
                                 vertexShaderId:(NSString *)vertexShaderId
                               fragmentShaderId:(NSString *)fragmentShaderId
                                                  srcFlags:(const SurfaceRasterFlags * )srcFlags
                                                  dstFlags:(const SurfaceRasterFlags * )dstFlags
                                             stencilNeeded:(bool)stencilNeeded {
            return [self getPipelineState:pipelineDescriptor
                   vertexShaderId:vertexShaderId
                 fragmentShaderId:fragmentShaderId
                    compositeRule:XOR_COMPOSITE_RULE
                         srcFlags:NULL
                         dstFlags:NULL
                    stencilNeeded:stencilNeeded];
} 
@end

static void setBlendingFactors(
        MTLRenderPipelineColorAttachmentDescriptor * cad,
        int compositeRule,
        MTLComposite* composite,
        const SurfaceRasterFlags * srcFlags,
        const SurfaceRasterFlags * dstFlags
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
            if (srcFlags->isOpaque &&
                (composite == nil ||
                 FLT_GE([composite getExtraAlpha], 1.0f)))
            {
                J2dTraceLn(J2D_TRACE_VERBOSE, "rule=RULE_Src, but blending is disabled because src is opaque");
                cad.blendingEnabled = NO;
                return;
            }
            if (dstFlags->isOpaque) {
                // Ar = 1, can be ignored, so
                // Cr = Cs + Cd*(1-As)
                // TODO: select any multiplier with best performance
                // for example: cad.destinationAlphaBlendFactor = MTLBlendFactorZero;
            } else {
                cad.destinationAlphaBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
            }
            if (!srcFlags->isPremultiplied) {
                cad.sourceRGBBlendFactor = MTLBlendFactorSourceAlpha;
            }
            if (composite != nil && FLT_LT([composite getExtraAlpha], 1.0f)) {
                cad.sourceRGBBlendFactor = MTLBlendFactorSourceAlpha;
            }
            cad.destinationRGBBlendFactor = MTLBlendFactorOneMinusSourceAlpha;

            J2dTraceLn(J2D_TRACE_VERBOSE, "set RULE_SrcOver");
            break;
        }
        case RULE_DstOver: {
            // Ar = As*(1-Ad) + Ad
            // Cr = Cs*(1-Ad) + Cd
            if (srcFlags->isOpaque) {
                J2dTraceLn(J2D_TRACE_ERROR, "Composite rule RULE_DstOver with opaque src isn't implemented (src alpha won't be ignored)");
            }
            if (dstFlags->isOpaque) {
                J2dTraceLn(J2D_TRACE_ERROR, "Composite rule RULE_DstOver with opaque dest hasn't any sense");
            }
            if (!srcFlags->isPremultiplied) {
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
            if (srcFlags->isOpaque) {
                J2dTraceLn(J2D_TRACE_ERROR, "Composite rule RULE_SrcIn with opaque src isn't implemented (src alpha won't be ignored)");
            }
            if (dstFlags->isOpaque) {
                J2dTraceLn(J2D_TRACE_VERBOSE, "rule=RULE_SrcIn, but blending is disabled because dest is opaque");
                cad.blendingEnabled = NO;
                return;
            }
            if (!srcFlags->isPremultiplied) {
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
            if (srcFlags->isOpaque) {
                J2dTraceLn(J2D_TRACE_ERROR, "Composite rule RULE_DstIn with opaque src isn't implemented (src alpha won't be ignored)");
            }
            if (dstFlags->isOpaque) {
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
            if (!srcFlags->isPremultiplied) {
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
            if (!srcFlags->isPremultiplied) {
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
