/*
 * Copyright (c) 2019, 2025, Oracle and/or its affiliates. All rights reserved.
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

#import "PropertiesUtilities.h"
#import "MTLGraphicsConfig.h"
#import "MTLLayer.h"
#import "ThreadUtilities.h"
#import "LWCToolkit.h"
#import "MTLSurfaceData.h"
#import "JNIUtilities.h"

const NSTimeInterval DF_BLIT_FRAME_TIME=1.0/120.0;

BOOL isDisplaySyncEnabled() {
    static int syncEnabled = -1;
    if (syncEnabled == -1) {
        JNIEnv *env = [ThreadUtilities getJNIEnvUncached];
        if (env == NULL) return NO;
        NSString *syncEnabledProp = [PropertiesUtilities javaSystemPropertyForKey:@"sun.java2d.metal.displaySync"
                                                                          withEnv:env];
        syncEnabled = [@"false" isCaseInsensitiveLike:syncEnabledProp] ? NO : YES;
        J2dRlsTraceLn(J2D_TRACE_INFO, "MTLLayer_isDisplaySyncEnabled: %d", syncEnabled);
    }
    return (BOOL)syncEnabled;
}

@implementation MTLLayer

- (id) initWithJavaLayer:(jobject)layer
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
    self.topInset = 0;
    self.leftInset = 0;
    self.framebufferOnly = YES;
    self.nextDrawableCount = 0;
    self.opaque = YES;
    self.redrawCount = 0;
    if (@available(macOS 10.13, *)) {
        self.displaySyncEnabled = isDisplaySyncEnabled();
    }
    self.avgBlitFrameTime = DF_BLIT_FRAME_TIME;
    return self;
}

- (void) blitTexture {
    if (self.ctx == NULL || self.javaLayer == NULL || self.buffer == NULL || *self.buffer == nil ||
        self.ctx.device == nil)
    {
        J2dTraceLn(J2D_TRACE_VERBOSE,
                   "MTLLayer.blitTexture: uninitialized (mtlc=%p, javaLayer=%p, buffer=%p, device=%p)", self.ctx,
                   self.javaLayer, self.buffer, self.ctx.device);
        [self stopRedraw:YES];
        return;
    }

    if (self.nextDrawableCount != 0) {
        if (!isDisplaySyncEnabled()) {
            [self performSelectorOnMainThread:@selector(setNeedsDisplay) withObject:nil waitUntilDone:NO];
        }
        return;
    }
    [self stopRedraw:NO];

    @autoreleasepool {
        if (((*self.buffer).width == 0) || ((*self.buffer).height == 0)) {
            J2dTraceLn(J2D_TRACE_VERBOSE, "MTLLayer.blitTexture: cannot create drawable of size 0");
            return;
        }

        NSUInteger src_x = self.leftInset * self.contentsScale;
        NSUInteger src_y = self.topInset * self.contentsScale;
        NSUInteger src_w = (*self.buffer).width - src_x;
        NSUInteger src_h = (*self.buffer).height - src_y;

        if (src_h <= 0 || src_w <= 0) {
            J2dTraceLn(J2D_TRACE_VERBOSE, "MTLLayer.blitTexture: Invalid src width or height.");
            return;
        }

        id<MTLCommandBuffer> commandBuf = [self.ctx createBlitCommandBuffer];
        if (commandBuf == nil) {
            J2dTraceLn(J2D_TRACE_VERBOSE, "MTLLayer.blitTexture: commandBuf is null");
            return;
        }
        id<CAMetalDrawable> mtlDrawable = [self nextDrawable];
        if (mtlDrawable == nil) {
            J2dTraceLn(J2D_TRACE_VERBOSE, "MTLLayer.blitTexture: nextDrawable is null)");
            return;
        }
        self.nextDrawableCount++;
        id<MTLCommandBuffer> renderBuffer =  [self.ctx createCommandBuffer];
        self.ctx.syncCount++;
        if (@available(macOS 10.14, *)) {
            [renderBuffer encodeWaitForEvent:self.ctx.syncEvent value:self.ctx.syncCount];
        }

        id <MTLBlitCommandEncoder> blitEncoder = [commandBuf blitCommandEncoder];

        [blitEncoder
                copyFromTexture:(*self.buffer) sourceSlice:0 sourceLevel:0
                sourceOrigin:MTLOriginMake(src_x, src_y, 0)
                sourceSize:MTLSizeMake(src_w, src_h, 1)
                toTexture:mtlDrawable.texture destinationSlice:0 destinationLevel:0
                destinationOrigin:MTLOriginMake(0, 0, 0)];
        [blitEncoder endEncoding];

        if (@available(macOS 10.14, *)) {
            [commandBuf encodeSignalEvent:self.ctx.syncEvent value:self.ctx.syncCount];
        }

        if (isDisplaySyncEnabled()) {
            [commandBuf presentDrawable:mtlDrawable];
        } else {
            if (@available(macOS 10.15.4, *)) {
                [commandBuf presentDrawable:mtlDrawable afterMinimumDuration:self.avgBlitFrameTime];
            } else {
                [commandBuf presentDrawable:mtlDrawable];
            }
        }

        [self retain];
        [commandBuf addCompletedHandler:^(id <MTLCommandBuffer> commandBuf) {
            if (@available(macOS 10.15.4, *)) {
                if (!isDisplaySyncEnabled()) {
                    const NSTimeInterval gpuTime = commandBuf.GPUEndTime - commandBuf.GPUStartTime;
                    const NSTimeInterval a = 0.25;
                    self.avgBlitFrameTime = gpuTime * a + self.avgBlitFrameTime * (1.0 - a);
                }
            }
            self.nextDrawableCount--;
            [self release];
        }];

        [commandBuf commit];
    }
}

- (void) dealloc {
    JNIEnv *env = [ThreadUtilities getJNIEnvUncached];
    (*env)->DeleteWeakGlobalRef(env, self.javaLayer);
    self.javaLayer = nil;
    [self stopRedraw:YES];
    self.buffer = NULL;
    [super dealloc];
}

- (void) blitCallback {

    JNIEnv *env = [ThreadUtilities getJNIEnv];
    DECLARE_CLASS(jc_JavaLayer, "sun/java2d/metal/MTLLayer");
    DECLARE_METHOD(jm_drawInMTLContext, jc_JavaLayer, "drawInMTLContext", "()V");

    jobject javaLayerLocalRef = (*env)->NewLocalRef(env, self.javaLayer);
    if ((*env)->IsSameObject(env, javaLayerLocalRef, NULL)) {
        return;
    }

    (*env)->CallVoidMethod(env, javaLayerLocalRef, jm_drawInMTLContext);
    CHECK_EXCEPTION();
    (*env)->DeleteLocalRef(env, javaLayerLocalRef);
}

- (void) display {
    AWT_ASSERT_APPKIT_THREAD;
    J2dTraceLn(J2D_TRACE_VERBOSE, "MTLLayer_display() called");
    [self blitCallback];
    [super display];
}

- (void)startRedraw {
    if (isDisplaySyncEnabled()) {
        if (self.ctx != nil) {
            [self.ctx performSelectorOnMainThread:@selector(startRedraw:) withObject:self waitUntilDone:NO];
        }
    } else {
        [self performSelectorOnMainThread:@selector(setNeedsDisplay) withObject:nil waitUntilDone:NO];
    }
}

- (void)stopRedraw:(BOOL)force {
    if (self.ctx != nil && isDisplaySyncEnabled()) {
        if (force) {
            self.redrawCount = 0;
        }
        [self.ctx performSelectorOnMainThread:@selector(stopRedraw:) withObject:self waitUntilDone:NO];
    }
}

- (void)commitCommandBuffer:(MTLContext*)mtlc wait:(BOOL)waitUntilCompleted display:(BOOL)updateDisplay {
    MTLCommandBufferWrapper * cbwrapper =[mtlc pullCommandBufferWrapper];

    if (cbwrapper != nil) {
        id <MTLCommandBuffer> commandbuf =[cbwrapper getCommandBuffer];
        if (isDisplaySyncEnabled() || !updateDisplay) {
            [commandbuf addCompletedHandler:^(id <MTLCommandBuffer> commandbuf) {
                [cbwrapper release];
            }];
        } else {
            [self retain];
            [commandbuf addCompletedHandler:^(id <MTLCommandBuffer> commandbuf) {
                [cbwrapper release];
                [self startRedraw];
                [self release];
            }];
       }
       [commandbuf commit];
       if (isDisplaySyncEnabled()) {
            [self startRedraw];
       }

       if (waitUntilCompleted) {
           [commandbuf waitUntilCompleted];
       }
    } else if (updateDisplay) {
        [self startRedraw];
    }
}
@end

/*
 * Class:     sun_java2d_metal_MTLLayer
 * Method:    nativeCreateLayer
 * Signature: ()J
 */
JNIEXPORT jlong JNICALL
Java_sun_java2d_metal_MTLLayer_nativeCreateLayer
(JNIEnv *env, jobject obj)
{
    __block MTLLayer *layer = nil;

JNI_COCOA_ENTER(env);

    jobject javaLayer = (*env)->NewWeakGlobalRef(env, obj);

    [ThreadUtilities performOnMainThreadWaiting:YES block:^(){
            AWT_ASSERT_APPKIT_THREAD;

            layer = [[MTLLayer alloc] initWithJavaLayer: javaLayer];
    }];

JNI_COCOA_EXIT(env);

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
        layer.buffer = &bmtlsdo->pTexture;
        layer.ctx = ((MTLSDOps *)bmtlsdo->privOps)->configInfo->context;
        layer.device = layer.ctx.device;
        layer.pixelFormat = MTLPixelFormatBGRA8Unorm;
        layer.drawableSize =
            CGSizeMake((*layer.buffer).width,
                       (*layer.buffer).height);
        [layer startRedraw];
    } else {
        layer.ctx = NULL;
        [layer stopRedraw:YES];
    }
}

JNIEXPORT void JNICALL
Java_sun_java2d_metal_MTLLayer_nativeSetScale
(JNIEnv *env, jclass cls, jlong layerPtr, jdouble scale)
{
    JNI_COCOA_ENTER(env);
    MTLLayer *layer = jlong_to_ptr(layerPtr);
    // We always call all setXX methods asynchronously, exception is only in
    // this method where we need to change native texture size and layer's scale
    // in one call on appkit, otherwise we'll get window's contents blinking,
    // during screen-2-screen moving.
    [ThreadUtilities performOnMainThreadWaiting:[NSThread isMainThread] block:^(){
        layer.contentsScale = scale;
    }];
    JNI_COCOA_EXIT(env);
}

JNIEXPORT void JNICALL
Java_sun_java2d_metal_MTLLayer_nativeSetInsets
(JNIEnv *env, jclass cls, jlong layerPtr, jint top, jint left)
{
    MTLLayer *layer = jlong_to_ptr(layerPtr);
    layer.topInset = top;
    layer.leftInset = left;
}

JNIEXPORT void JNICALL
Java_sun_java2d_metal_MTLLayer_blitTexture
(JNIEnv *env, jclass cls, jlong layerPtr)
{
    J2dTraceLn(J2D_TRACE_VERBOSE, "MTLLayer_blitTexture");
    MTLLayer *layer = jlong_to_ptr(layerPtr);
    MTLContext * ctx = layer.ctx;
    if (layer == nil || ctx == nil) {
        J2dTraceLn(J2D_TRACE_VERBOSE, "MTLLayer_blit : Layer or Context is null");
        if (layer != nil) {
            [layer stopRedraw:YES];
        }
        return;
    }

    [layer blitTexture];
}

JNIEXPORT void JNICALL
Java_sun_java2d_metal_MTLLayer_nativeSetOpaque
(JNIEnv *env, jclass cls, jlong layerPtr, jboolean opaque)
{
    JNI_COCOA_ENTER(env);

    MTLLayer *mtlLayer = OBJC(layerPtr);
    [ThreadUtilities performOnMainThreadWaiting:NO block:^(){
        [mtlLayer setOpaque:(opaque == JNI_TRUE)];
    }];

    JNI_COCOA_EXIT(env);
}
