/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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

#import "MTLBlitLoops.h"

@implementation MTLLayer


@synthesize javaLayer;
@synthesize ctx;
@synthesize bufferWidth;
@synthesize bufferHeight;
@synthesize buffer;

- (id) initWithJavaLayer:(JNFWeakJObjectWrapper *)layer
{
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


    return self;
}

- (void) blitTexture {
    if (self.ctx == NULL || self.javaLayer == NULL || self.buffer == nil || ctx->mtlDevice == nil) {
        J2dTraceLn4(J2D_TRACE_VERBOSE, "MTLLayer.blitTexture: uninitialized (mtlc=%p, javaLayer=%p, buffer=%p, devide=%p)", self.ctx, self.javaLayer, self.buffer, ctx->mtlDevice);
        return;
    }

    if (ctx->mtlCommandBuffer == nil) {
        J2dTraceLn(J2D_TRACE_VERBOSE, "MTLLayer.blitTexture: nothing to do (mtlCommandBuffer is null)");
        return;
    }

    @autoreleasepool {
        self.device = ctx->mtlDevice;
        self.pixelFormat = MTLPixelFormatBGRA8Unorm;
        self.framebufferOnly = NO;

        self.drawableSize =
            CGSizeMake(self.buffer.width,
                       self.buffer.height);

        id<CAMetalDrawable> mtlDrawable = [self nextDrawable];
        if (mtlDrawable == nil) {
            return;
        }
        J2dTraceLn6(J2D_TRACE_INFO, "MTLLayer.blitTexture: src tex=%p (w=%d, h=%d), dst tex=%p (w=%d, h=%d)", self.buffer, self.buffer.width, self.buffer.height, mtlDrawable.texture, mtlDrawable.texture.width, mtlDrawable.texture.height);
        MTLBlitTex2Tex(ctx, self.buffer, mtlDrawable.texture);

        dispatch_semaphore_wait(ctx->mtlRenderSemaphore, DISPATCH_TIME_FOREVER);

        [ctx->mtlCommandBuffer presentDrawable:mtlDrawable];

        [ctx->mtlCommandBuffer addCompletedHandler:^(id <MTLCommandBuffer> cmdBuff) {
                    [cmdBuff release];
        }];

        [ctx->mtlCommandBuffer commit];

        ctx->mtlCommandBuffer = nil;
        dispatch_semaphore_signal(ctx->mtlRenderSemaphore);
    }
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

    if (surfaceData != NULL) {
        BMTLSDOps *bmtlsdo = (BMTLSDOps*) SurfaceData_GetOps(env, surfaceData);
        layer.bufferWidth = bmtlsdo->width;
        layer.bufferHeight = bmtlsdo->width;
        layer.buffer = bmtlsdo->pTexture;
        layer.ctx = ((MTLSDOps *)bmtlsdo->privOps)->configInfo->context;
        layer.device = layer.ctx->mtlDevice;
    } else {
        layer.ctx = NULL;
    }
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
