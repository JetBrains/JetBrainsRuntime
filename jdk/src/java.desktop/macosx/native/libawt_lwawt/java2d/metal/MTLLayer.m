/*
 * Copyright 2018 JetBrains s.r.o.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

#import "MTLGraphicsConfig.h"
#import "MTLLayer.h"
#import "ThreadUtilities.h"
#import "LWCToolkit.h"
#import "MTLSurfaceData.h"


static const int N = 2;
static struct Vertex verts[N*3];

@implementation MTLLayer


@synthesize javaLayer;
@synthesize ctx;
@synthesize bufferWidth;
@synthesize bufferHeight;

- (id) initWithJavaLayer:(JNFWeakJObjectWrapper *)layer
{
//fprintf(stderr, "MTLayer.initWithJavaLayer\n");
AWT_ASSERT_APPKIT_THREAD;
    // Initialize ourselves
    self = [super init];
    if (self == nil) return self;

    self.javaLayer = layer;

    self.contentsGravity = kCAGravityTopLeft;

    //Disable CALayer's default animation
    NSMutableDictionary * actions = [[NSMutableDictionary alloc] initWithObjectsAndKeys:
                                    [NSNull null], @"anchorPoint",
                                    [NSNull null], @"bounds",
                                    [NSNull null], @"contents",
                                    [NSNull null], @"contentsScale",
                                    [NSNull null], @"onOrderIn",
                                    [NSNull null], @"onOrderOut",
                                    [NSNull null], @"position",
                                    [NSNull null], @"sublayers",
                                    nil];
    self.actions = actions;
    [actions release];

    self.ctx = NULL;

    return self;
}



- (void) blitTexture {
    if (self.ctx == NULL) {
        return;
    }
fprintf(stderr, "blitTexture\n");
    if (ctx->mtlCommandBuffer) {
    // Encode render command.
        id<CAMetalDrawable> mtlDrawable = [self nextDrawable];
        if (!ctx->mtlRenderPassDesc) {
            ctx->mtlRenderPassDesc = [[MTLRenderPassDescriptor renderPassDescriptor] retain];
        }

        if (ctx->mtlRenderPassDesc) {

            verts[0].position[0] = -1.0;
            verts[0].position[1] = 1.0;
            verts[0].position[2] = 0;
            verts[0].txtpos[0] = 0;
            verts[0].txtpos[1] = 0;

            verts[1].position[0] = 1.0;
            verts[1].position[1] = 1.0;
            verts[1].position[2] = 0;
            verts[1].txtpos[0] = 1;
            verts[1].txtpos[1] = 0;

            verts[2].position[0] = 1.0;
            verts[2].position[1] = -1.0;
            verts[2].position[2] = 0;
            verts[2].txtpos[0] = 1;
            verts[2].txtpos[1] = 1;


            verts[3].position[0] = 1.0;
            verts[3].position[1] = -1.0;
            verts[3].position[2] = 0;
            verts[3].txtpos[0] = 1;
            verts[3].txtpos[1] = 1;


            verts[4].position[0] = -1.0;
            verts[4].position[1] = -1.0;
            verts[4].position[2] = 0;
            verts[4].txtpos[0] = 0;
            verts[4].txtpos[1] = 1;

            verts[5].position[0] = -1.0;
            verts[5].position[1] = 1.0;
            verts[5].position[2] = 0;
            verts[5].txtpos[0] = 0;
            verts[5].txtpos[1] = 0;

            ctx->mtlVertexBuffer = [ctx->mtlDevice newBufferWithBytes:verts
                                                           length:sizeof(verts)
                                                           options:MTLResourceCPUCacheModeDefaultCache];
            MTLRenderPassColorAttachmentDescriptor *colorAttachment = ctx->mtlRenderPassDesc.colorAttachments[0];

            colorAttachment.texture = mtlDrawable.texture;

            colorAttachment.loadAction = MTLLoadActionLoad;
            colorAttachment.clearColor = MTLClearColorMake(0.0f, 0.0f, 0.0f, 1.0f);

            colorAttachment.storeAction = MTLStoreActionStore;

            id<MTLRenderCommandEncoder>  mtlEncoder =
                [ctx->mtlCommandBuffer renderCommandEncoderWithDescriptor:ctx->mtlRenderPassDesc];
            MTLViewport vp = {0, 0, ctx->mtlFrameBuffer.width, ctx->mtlFrameBuffer.height, 0, 1};
            //fprintf(stderr, "%f %f \n", self.drawableSize.width, self.drawableSize.height);
            [mtlEncoder setViewport:vp];
            [mtlEncoder setRenderPipelineState:ctx->mtlBlitPipelineState];
            [mtlEncoder setVertexBuffer:ctx->mtlUniformBuffer
                              offset:0 atIndex:FrameUniformBuffer];

            [mtlEncoder setFragmentTexture: ctx->mtlFrameBuffer atIndex: 0];
            [mtlEncoder setVertexBuffer:ctx->mtlVertexBuffer offset:0 atIndex:MeshVertexBuffer];
            [mtlEncoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:N * 3];
            [mtlEncoder endEncoding];

            [ctx->mtlRenderPassDesc release];
            ctx->mtlRenderPassDesc = nil;
        }

        if (!ctx->mtlEmptyCommandBuffer) {
            [ctx->mtlCommandBuffer presentDrawable:mtlDrawable];
            [ctx->mtlCommandBuffer commit];
        }


        [ctx->mtlCommandBuffer release];
        ctx->mtlCommandBuffer = nil;
        ctx->mtlEmptyCommandBuffer = YES;
    }

    if (ctx->mtlDrawable) [ctx->mtlDrawable release];
    ctx->mtlDrawable = nil;
}

- (void) dealloc {
    self.javaLayer = nil;
    [super dealloc];
}

@end

/*
 * Class:     sun_java2d_metal_CGLLayer
 * Method:    nativeCreateLayer
 * Signature: ()J
 */
JNIEXPORT jlong JNICALL
Java_sun_java2d_metal_MTLLayer_nativeCreateLayer
(JNIEnv *env, jobject obj)
{
    __block MTLLayer *layer = nil;

JNF_COCOA_ENTER(env);

    JNFWeakJObjectWrapper *javaLayer = [JNFWeakJObjectWrapper wrapperWithJObject:obj withEnv:env];

    [ThreadUtilities performOnMainThreadWaiting:YES block:^(){
            AWT_ASSERT_APPKIT_THREAD;
        
            layer = [[MTLLayer alloc] initWithJavaLayer: javaLayer];
    }];
    
JNF_COCOA_EXIT(env);

    return ptr_to_jlong(layer);
}

// Must be called under the RQ lock.
JNIEXPORT void JNICALL
Java_sun_java2d_metal_MTLLayer_validate
(JNIEnv *env, jclass cls, jlong layerPtr, jobject surfaceData)
{
    MTLLayer *layer = OBJC(layerPtr);
fprintf(stderr, "Java_sun_java2d_metal_MTLLayer_validate\n");
    if (surfaceData != NULL) {
        BMTLSDOps *bmtlsdo = (BMTLSDOps*) SurfaceData_GetOps(env, surfaceData);
        layer.bufferWidth = bmtlsdo->width;
        layer.bufferHeight = bmtlsdo->height;
        layer.ctx = (MTLCtxInfo*)(((MTLSDOps *)bmtlsdo->privOps)->configInfo->context->ctxInfo);
        layer.device = layer.ctx->mtlDevice;
    } else {
        layer.ctx = NULL;
    }
}

// Must be called on the AppKit thread and under the RQ lock.
JNIEXPORT void JNICALL
Java_sun_java2d_metal_MTLLayer_blitTexture
(JNIEnv *env, jclass cls, jlong layerPtr)
{
    fprintf(stderr, "Java_sun_java2d_metal_MTLLayer_blitTexture\n");
    MTLLayer *layer = jlong_to_ptr(layerPtr);

    [layer blitTexture];
}

JNIEXPORT void JNICALL
Java_sun_java2d_metal_MTLLayer_nativeSetScale
(JNIEnv *env, jclass cls, jlong layerPtr, jdouble scale)
{
    JNF_COCOA_ENTER(env);
    MTLLayer *layer = jlong_to_ptr(layerPtr);
    // We always call all setXX methods asynchronously, exception is only in 
    // this method where we need to change native texture size and layer's scale
    // in one call on appkit, otherwise we'll get window's contents blinking, 
    // during screen-2-screen moving.
    [ThreadUtilities performOnMainThreadWaiting:[NSThread isMainThread] block:^(){
        layer.contentsScale = scale;
    }];
    JNF_COCOA_EXIT(env);
}
