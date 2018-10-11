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


extern NSOpenGLPixelFormat *sharedPixelFormat;
extern NSOpenGLContext *sharedContext;

const int N = 2;
static struct Vertex verts[N*3];

@implementation MTLLayer


@synthesize javaLayer;
@synthesize textureID;
@synthesize target;
@synthesize textureWidth;
@synthesize textureHeight;

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

    textureID = 0; // texture will be created by rendering pipe
    target = 0;

    return self;
}

- (void) fillParallelogramCtx:(MTLCtxInfo*)ctx
                            X:(jfloat)x
                            Y:(jfloat)y
                          DX1:(jfloat)dx1
                          DY1:(jfloat)dy1
                          DX2:(jfloat)dx2
                          DY2:(jfloat)dy2
{
    if (!ctx->mtlCommandBuffer) {
        [self beginFrameCtx:ctx];
    }
    ctx->mtlEmptyCommandBuffer = NO;
   // fprintf(stderr, "----fillParallelogramX----\n");

    verts[0].position[0] = (2.0*x/self.drawableSize.width) - 1.0;
    verts[0].position[1] = 2.0*(1.0 - y/self.drawableSize.height) - 1.0;
    verts[0].position[2] = 0;

    verts[1].position[0] = 2.0*(x+dx1)/self.drawableSize.width - 1.0;
    verts[1].position[1] = 2.0*(1.0 - (y+dy1)/self.drawableSize.height) - 1.0;
    verts[1].position[2] = 0;

    verts[2].position[0] = 2.0*(x+dx2)/self.drawableSize.width - 1.0;
    verts[2].position[1] = 2.0*(1.0 - (y+dy2)/self.drawableSize.height) - 1.0;
    verts[2].position[2] = 0;

    verts[3].position[0] = 2.0*(x+dx1)/self.drawableSize.width - 1.0;
    verts[3].position[1] = 2.0*(1.0 - (y+dy1)/self.drawableSize.height) - 1.0;
    verts[3].position[2] = 0;

    verts[4].position[0] = 2.0*(x + dx1 + dx2)/self.drawableSize.width - 1.0;
    verts[4].position[1] = 2.0*(1.0 - (y+ dy1 + dy2)/self.drawableSize.height) - 1.0;
    verts[4].position[2] = 0;

    verts[5].position[0] = 2.0*(x+dx2)/self.drawableSize.width - 1.0;
    verts[5].position[1] = 2.0*(1.0 - (y+dy2)/self.drawableSize.height) - 1.0;
    verts[5].position[2] = 0;

    verts[0].color[0] = (ctx->mtlColor >> 16)&(0xFF);
    verts[0].color[1] = (ctx->mtlColor >> 8)&0xFF;
    verts[0].color[2] = (ctx->mtlColor)&0xFF;
    verts[0].color[3] = (ctx->mtlColor >> 24)&0xFF;

    verts[1].color[0] = (ctx->mtlColor >> 16)&(0xFF);
    verts[1].color[1] = (ctx->mtlColor >> 8)&0xFF;
    verts[1].color[2] = (ctx->mtlColor)&0xFF;
    verts[1].color[3] = (ctx->mtlColor >> 24)&0xFF;

    verts[2].color[0] = (ctx->mtlColor >> 16)&(0xFF);
    verts[2].color[1] = (ctx->mtlColor >> 8)&0xFF;
    verts[2].color[2] = (ctx->mtlColor)&0xFF;
    verts[2].color[3] = (ctx->mtlColor >> 24)&0xFF;

    verts[3].color[0] = (ctx->mtlColor >> 16)&(0xFF);
    verts[3].color[1] = (ctx->mtlColor >> 8)&0xFF;
    verts[3].color[2] = (ctx->mtlColor)&0xFF;
    verts[3].color[3] = (ctx->mtlColor >> 24)&0xFF;

    verts[4].color[0] = (ctx->mtlColor >> 16)&(0xFF);
    verts[4].color[1] = (ctx->mtlColor >> 8)&0xFF;
    verts[4].color[2] = (ctx->mtlColor)&0xFF;
    verts[4].color[3] = (ctx->mtlColor >> 24)&0xFF;

    verts[5].color[0] = (ctx->mtlColor >> 16)&(0xFF);
    verts[5].color[1] = (ctx->mtlColor >> 8)&0xFF;
    verts[5].color[2] = (ctx->mtlColor)&0xFF;
    verts[5].color[3] = (ctx->mtlColor >> 24)&0xFF;

    ctx->mtlVertexBuffer = [ctx->mtlDevice newBufferWithBytes:verts
                                         length:sizeof(verts)
                                        options:
                                                MTLResourceCPUCacheModeDefaultCache];
    // Encode render command.
    if (!ctx->mtlRenderPassDesc) {
        ctx->mtlRenderPassDesc = [[MTLRenderPassDescriptor renderPassDescriptor] retain];
        if (ctx->mtlRenderPassDesc) {
          MTLRenderPassColorAttachmentDescriptor *colorAttachment = ctx->mtlRenderPassDesc.colorAttachments[0];
          colorAttachment.texture = ctx->mtlFrameBuffer;

          colorAttachment.loadAction = MTLLoadActionLoad;
          colorAttachment.clearColor = MTLClearColorMake(0.0f, 0.0f, 0.0f, 1.0f);

          colorAttachment.storeAction = MTLStoreActionStore;
        }
    }

    if (ctx->mtlRenderPassDesc) {
        id<MTLRenderCommandEncoder>  mtlEncoder =
            [ctx->mtlCommandBuffer renderCommandEncoderWithDescriptor:ctx->mtlRenderPassDesc];
        MTLViewport vp = {0, 0, ctx->mtlFrameBuffer.width, ctx->mtlFrameBuffer.height, 0, 1};
        //fprintf(stderr, "%f %f \n", self.drawableSize.width, self.drawableSize.height);
        [mtlEncoder setViewport:vp];
        [mtlEncoder setRenderPipelineState:ctx->mtlPipelineState];
        [mtlEncoder setVertexBuffer:ctx->mtlUniformBuffer
                          offset:0 atIndex:FrameUniformBuffer];

        [mtlEncoder setVertexBuffer:ctx->mtlVertexBuffer offset:0 atIndex:MeshVertexBuffer];
        [mtlEncoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:N * 3];
        [mtlEncoder endEncoding];
    }

}

- (void) beginFrameCtx:(MTLCtxInfo*)ctx {
    self.device = ctx->mtlDevice;
    if (ctx->mtlCommandBuffer) {
        [self endFrameCtx:ctx];
    }
    if (!ctx->mtlDrawable) {
        ctx->mtlDrawable = [[self nextDrawable] retain];
    }

    if (!ctx->mtlDrawable) {
        fprintf(stderr, "ERROR: Failed to get a valid drawable.\n");
    } else {
        vector_float4 X = {1, 0, 0, 0};
        vector_float4 Y = {0, 1, 0, 0};
        vector_float4 Z = {0, 0, 1, 0};
        vector_float4 W = {0, 0, 0, 1};

        matrix_float4x4 rot = {X, Y, Z, W};

        ctx->mtlUniforms = (struct FrameUniforms *) [ctx->mtlUniformBuffer contents];
        ctx->mtlUniforms->projectionViewModel = rot;

        // Create a command buffer.
        ctx->mtlCommandBuffer = [[ctx->mtlCommandQueue commandBuffer] retain];
    }

}

- (void) endFrameCtx:(MTLCtxInfo*)ctx {
    if (ctx->mtlCommandBuffer) {
    // Encode render command.
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
                colorAttachment.texture = ctx->mtlDrawable.texture;

                colorAttachment.loadAction = MTLLoadActionLoad;
                colorAttachment.clearColor = MTLClearColorMake(0.0f, 0.0f, 0.0f, 1.0f);

                colorAttachment.storeAction = MTLStoreActionStore;

            id<MTLRenderCommandEncoder>  mtlEncoder =
                [ctx->mtlCommandBuffer renderCommandEncoderWithDescriptor:ctx->mtlRenderPassDesc];
            MTLViewport vp = {0, 0, self.drawableSize.width, self.drawableSize.height, 0, 1};
            //fprintf(stderr, "%f %f \n", self.drawableSize.width, self.drawableSize.height);
            [mtlEncoder setViewport:vp];
            [mtlEncoder setRenderPipelineState:ctx->mtlBlitPipelineState];
            [mtlEncoder setVertexBuffer:ctx->mtlUniformBuffer
                              offset:0 atIndex:FrameUniformBuffer];

            [mtlEncoder setFragmentTexture: ctx->mtlFrameBuffer atIndex: 0];
            [mtlEncoder setVertexBuffer:ctx->mtlVertexBuffer offset:0 atIndex:MeshVertexBuffer];
            [mtlEncoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:N * 3];
            [mtlEncoder endEncoding];
        }

        if (!ctx->mtlEmptyCommandBuffer) {
            [ctx->mtlCommandBuffer presentDrawable:ctx->mtlDrawable];
            [ctx->mtlCommandBuffer commit];
        }


        [ctx->mtlCommandBuffer release];
        if (ctx->mtlDrawable) [ctx->mtlDrawable release];
        ctx->mtlDrawable = nil;
        ctx->mtlCommandBuffer = nil;
        ctx->mtlEmptyCommandBuffer = YES;
        [ctx->mtlRenderPassDesc release];
        ctx->mtlRenderPassDesc = nil;
    }
}

- (void) dealloc {
    self.javaLayer = nil;
    [super dealloc];
}

// use texture (intermediate buffer) as src and blit it to the layer
- (void) blitTexture
{
    if (textureID == 0) {
        return;
    }
/*
    glEnable(target);
    glBindTexture(target, textureID);

    glTexEnvf(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_REPLACE); // srccopy

    float swid = 1.0f, shgt = 1.0f;
    if (target == GL_TEXTURE_RECTANGLE_ARB) {
        swid = textureWidth;
        shgt = textureHeight;
    }
    glBegin(GL_QUADS);
    glTexCoord2f(0.0f, 0.0f); glVertex2f(-1.0f, -1.0f);
    glTexCoord2f(swid, 0.0f); glVertex2f( 1.0f, -1.0f);
    glTexCoord2f(swid, shgt); glVertex2f( 1.0f,  1.0f);
    glTexCoord2f(0.0f, shgt); glVertex2f(-1.0f,  1.0f);
    glEnd();

    glBindTexture(target, 0);
    glDisable(target);
    */
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
//fprintf(stderr, "Java_sun_java2d_metal_MTLLayer_validate\n");
    if (surfaceData != NULL) {
        BMTLSDOps *oglsdo = (BMTLSDOps*) SurfaceData_GetOps(env, surfaceData);
        layer.textureID = oglsdo->textureID;
        //layer.target =  GL_TEXTURE_2D;
        layer.textureWidth = oglsdo->width;
        layer.textureHeight = oglsdo->height;
    } else {
        layer.textureID = 0;
    }
}

// Must be called on the AppKit thread and under the RQ lock.
JNIEXPORT void JNICALL
Java_sun_java2d_metal_MTLLayer_blitTexture
(JNIEnv *env, jclass cls, jlong layerPtr)
{
//    fprintf(stderr, "Blit!!!\n");
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
