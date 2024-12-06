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

#import <sys/sysctl.h>
#import "PropertiesUtilities.h"
#import "MTLGraphicsConfig.h"
#import "MTLLayer.h"
#import "ThreadUtilities.h"
#import "LWCToolkit.h"
#import "MTLSurfaceData.h"
#import "JNIUtilities.h"

#define MAX_DRAWABLE    3
#define LAST_DRAWABLE   (MAX_DRAWABLE - 1)

static jclass jc_JavaLayer = NULL;
#define GET_MTL_LAYER_CLASS() \
    GET_CLASS(jc_JavaLayer, "sun/java2d/metal/MTLLayer");

#define TRACE_DISPLAY   0

const NSTimeInterval DF_BLIT_FRAME_TIME=1.0/120.0;

extern BOOL isColorMatchingEnabled();

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

BOOL MTLLayer_isM2CPU() {
    static int m2CPU = -1;
    if (m2CPU == -1) {
        char cpuBrandDefaultStr[16];
        char *cpuBrand = cpuBrandDefaultStr;
        size_t len;
        sysctlbyname("machdep.cpu.brand_string", NULL, &len, NULL, 0);
        if (len >= sizeof(cpuBrandDefaultStr)) {
            cpuBrand = malloc(len);
        }
        sysctlbyname("machdep.cpu.brand_string", cpuBrand, &len, NULL, 0);
        m2CPU = strstr(cpuBrand, "M2") != NULL;

        J2dRlsTraceLn(J2D_TRACE_INFO, "MTLLayer_isM2CPU: %d (%s)", m2CPU, cpuBrand);

        if (cpuBrand != cpuBrandDefaultStr) {
            free(cpuBrand);
        }
    }
    return m2CPU;
}

BOOL MTLLayer_isSpansDisplays() {
    static int spansDisplays = -1;
    if (spansDisplays == -1) {
        NSUserDefaults * defaults = [NSUserDefaults standardUserDefaults];
        NSDictionary<NSString*,id> *spaces = [defaults persistentDomainForName:@"com.apple.spaces"];
        spansDisplays = [(NSNumber*)[spaces valueForKey:@"spans-displays"] intValue];
        J2dRlsTraceLn(J2D_TRACE_INFO, "MTLLayer_isSpansDisplays: %d", spansDisplays);
    }
    return spansDisplays;
}

BOOL MTLLayer_isExtraRedrawEnabled() {
    static int redrawEnabled = -1;
    if (redrawEnabled == -1) {
        JNIEnv *env = [ThreadUtilities getJNIEnvUncached];
        if (env == NULL) return NO;
        NSString *syncEnabledProp = [PropertiesUtilities javaSystemPropertyForKey:@"sun.java2d.metal.extraRedraw"
                                                                          withEnv:env];
        redrawEnabled = [@"false" isCaseInsensitiveLike:syncEnabledProp] ? NO : -1;
        if (redrawEnabled == -1) {
            redrawEnabled = [@"true" isCaseInsensitiveLike:syncEnabledProp] ?
                    YES : MTLLayer_isSpansDisplays() && MTLLayer_isM2CPU();
        }
        J2dRlsTraceLn(J2D_TRACE_INFO, "MTLLayer_isExtraRedrawEnabled: %d", redrawEnabled);
    }
    return (BOOL)redrawEnabled;
}

@implementation MTLLayer {
    NSLock* _lock;
}

- (id) initWithJavaLayer:(jobject)layer usePerfCounters:(jboolean)perfCountersEnabled
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

    // Validation with MTL_DEBUG_LAYER=1 environment variable
    // prohibits blit operations on to the drawable texture
    // obtained from a MTLLayer with framebufferOnly=YES
    self.framebufferOnly = NO;
    self.nextDrawableCount = 0;
    self.opaque = YES;
    self.redrawCount = 0;
    if (@available(macOS 10.13, *)) {
        self.displaySyncEnabled = isDisplaySyncEnabled();
    }
    if (@available(macOS 10.13.2, *)) {
        self.maximumDrawableCount = MAX_DRAWABLE;
    }
    self.presentsWithTransaction = NO;
    self.avgBlitFrameTime = DF_BLIT_FRAME_TIME;
    self.perfCountersEnabled = perfCountersEnabled ? YES : NO;
    self.lastPresentedTime = 0.0;
    _lock = [[NSLock alloc] init];
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

    @autoreleasepool {
        // MTLDrawable pool barrier:
        BOOL skipDrawable = YES;
        [_lock lock];
        @try {
            if (self.nextDrawableCount < LAST_DRAWABLE) {
                // increment used drawables to act as the CPU fence:
                self.nextDrawableCount++;
                skipDrawable = NO;
            }
        } @finally {
            [_lock unlock];
        }
        if (skipDrawable) {
            if (TRACE_DISPLAY) {
                J2dRlsTraceLn(J2D_TRACE_VERBOSE, "[%.6lf] MTLLayer_blitTexture: skip drawable [skip blit] nextDrawableCount = %d",
                              CACurrentMediaTime(), self.nextDrawableCount);
            }
            [self startRedraw];
            return;
        }

        // Perform blit:
        // Decrement redrawCount:
        [self stopRedraw:NO];

        // try-finally block to ensure releasing the CPU fence (abort blit):
        BOOL releaseFence = YES;
        @try {
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

            // Acquire CAMetalDrawable without blocking:
            const id<CAMetalDrawable> mtlDrawable = [self nextDrawable];
            const CFTimeInterval nextDrawableTime = (TRACE_DISPLAY) ? CACurrentMediaTime() : 0.0;
            if (mtlDrawable == nil) {
                J2dTraceLn(J2D_TRACE_VERBOSE, "MTLLayer.blitTexture: nextDrawable is null)");
                return;
            }
            // Keep Fence from now:
            releaseFence = NO;

            id <MTLBlitCommandEncoder> blitEncoder = [commandBuf blitCommandEncoder];

            [blitEncoder
                    copyFromTexture:(isDisplaySyncEnabled()) ? (*self.buffer) : (*self.outBuffer)
                    sourceSlice:0 sourceLevel:0
                    sourceOrigin:MTLOriginMake(src_x, src_y, 0)
                    sourceSize:MTLSizeMake(src_w, src_h, 1)
                    toTexture:mtlDrawable.texture destinationSlice:0 destinationLevel:0
                    destinationOrigin:MTLOriginMake(0, 0, 0)];
            [blitEncoder endEncoding];

            BOOL usePresentHandler = NO;
            NSUInteger drawableId = -1;

            if (@available(macOS 10.15.4, *)) {
                usePresentHandler = YES;
                if (TRACE_DISPLAY) {
                    drawableId = mtlDrawable.drawableID;
                }
                [self retain];
                [mtlDrawable addPresentedHandler:^(id <MTLDrawable> drawable) {
                    // note: called anyway even if drawable.present() not called!
                    // free drawable once processed:
                    [self freeDrawableCount];

                    const CFTimeInterval presentedTime = drawable.presentedTime;
                    if (presentedTime != 0.0) {
                        if (self.perfCountersEnabled) {
                            [self countFramePresentedCallback];
                        }
                        if (TRACE_DISPLAY) {
                            const CFTimeInterval now = CACurrentMediaTime();
                            const CFTimeInterval presentedHandlerLatency = (now - nextDrawableTime);
                            const CFTimeInterval frameInterval = (self.lastPresentedTime != 0.0) ? (presentedTime - self.lastPresentedTime) : -1.0;
                            J2dRlsTraceLn(J2D_TRACE_VERBOSE,
                                          "[%.6lf] MTLLayer_blitTexture_PresentedHandler: drawable(%d) presented"
                                          " - presentedHandlerLatency = %.3lf ms frameInterval = %.3lf ms",
                                          CACurrentMediaTime(), drawable.drawableID,
                                          1000.0 * presentedHandlerLatency, 1000.0 * frameInterval
                            );
                        }
                        self.lastPresentedTime = presentedTime;
                    } else {
                        if (self.perfCountersEnabled) {
                            [self countFrameDroppedCallback];
                        }
                        if (TRACE_DISPLAY) {
                            const CFTimeInterval now = CACurrentMediaTime();
                            const CFTimeInterval presentedHandlerLatency = (now - nextDrawableTime);
                            J2dRlsTraceLn(J2D_TRACE_VERBOSE,
                                          "[%.6lf] MTLLayer_blitTexture_PresentedHandler: drawable(%d) skipped"
                                          " - presentedHandlerLatency = %.3lf ms",
                                          CACurrentMediaTime(), drawable.drawableID, 1000.0 * presentedHandlerLatency
                            );
                        }
                    }
                    [self release];
                }];
            }
            // Present drawable:
            if (TRACE_DISPLAY) {
                J2dRlsTraceLn(J2D_TRACE_INFO,
                              "[%.6lf] MTLLayer.blitTexture: layer[%p] present drawable(%d)",
                              CACurrentMediaTime(), self, drawableId);
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
            [commandBuf addCompletedHandler:^(id <MTLCommandBuffer> commandbuf) {
                if (usePresentHandler == NO) {
                    // free drawable:
                    [self freeDrawableCount];
                }
                if (!isDisplaySyncEnabled()) {
                    if (@available(macOS 10.15.4, *)) {
                        const NSTimeInterval gpuTime = commandBuf.GPUEndTime - commandBuf.GPUStartTime;
                        const NSTimeInterval a = 0.25;
                        self.avgBlitFrameTime = gpuTime * a + self.avgBlitFrameTime * (1.0 - a);
                    }
                }
                [self release];
            }];

            [commandBuf commit];

            if (TRACE_DISPLAY) {
                J2dRlsTraceLn(J2D_TRACE_INFO,
                              "[%.6lf] MTLLayer.blitTexture: layer[%p] commit with drawable(%d)",
                              CACurrentMediaTime(), self, drawableId);
            }
        } @finally {
            // try-finally block to ensure releasing the CPU fence:
            if (releaseFence) {
                // free drawable:
                [self freeDrawableCount];

                if (isDisplaySyncEnabled()) {
                    // Increment redrawCount:
                    [self startRedraw];
                }
            }
        }
    }
}

- (void) freeDrawableCount {
    [_lock lock];
    --self.nextDrawableCount;
    [_lock unlock];
    if (TRACE_DISPLAY) {
        J2dRlsTraceLn(J2D_TRACE_VERBOSE, "[%.6lf] MTLLayer_freeDrawableCount: layer[%p] nextDrawableCount = %d",
                      CACurrentMediaTime(), self, self.nextDrawableCount);
    }
}

- (void) dealloc {
    if (TRACE_DISPLAY) {
        J2dRlsTraceLn(J2D_TRACE_INFO, "MTLLayer.dealloc: layer[%p]", self);
    }
    JNIEnv *env = [ThreadUtilities getJNIEnvUncached];
    (*env)->DeleteWeakGlobalRef(env, self.javaLayer);
    self.javaLayer = nil;
    [self stopRedraw:YES];
    self.buffer = NULL;
    self.outBuffer = NULL;
    [_lock release];
    [super dealloc];
}

- (void) blitCallback {

    JNIEnv *env = [ThreadUtilities getJNIEnv];
    GET_MTL_LAYER_CLASS();
    DECLARE_METHOD(jm_drawInMTLContext, jc_JavaLayer, "drawInMTLContext", "()V");

    jobject javaLayerLocalRef = (*env)->NewLocalRef(env, self.javaLayer);
    if (javaLayerLocalRef == NULL) {
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

- (void)startRedrawIfNeeded {
    AWT_ASSERT_APPKIT_THREAD;
    if (isDisplaySyncEnabled()) {
        if (self.redrawCount == 0) {
            [self.ctx startRedraw:self];
        }
    }
}

- (void)startRedraw {
    if (isDisplaySyncEnabled()) {
        if (self.ctx != nil) {
            [ThreadUtilities performOnMainThreadNowOrLater:NO // critical
                                                     block:^(){
                [self.ctx startRedraw:self];
            }];
        }
    } else {
            [ThreadUtilities performOnMainThreadNowOrLater:NO // critical
                                                     block:^(){
            [self setNeedsDisplay];
        }];
    }
}

- (void)stopRedraw:(BOOL)force {
    if (isDisplaySyncEnabled()) {
        if (force) {
            self.redrawCount = 0;
        }
        if (self.ctx != nil) {
            [ThreadUtilities performOnMainThreadNowOrLater:NO // critical
                                                     block:^(){
                [self.ctx stopRedraw:self];
            }];
        }
    }
}
- (void) flushBuffer {
    if (self.ctx == nil || self.buffer == NULL) {
        return;
    }
    // Copy the rendered texture to the output buffer (blit later) using the render command queue:
    id <MTLCommandBuffer> commandbuf = [self.ctx createCommandBuffer];
    id <MTLBlitCommandEncoder> blitEncoder = [commandbuf blitCommandEncoder];
    [blitEncoder
            copyFromTexture:(*self.buffer) sourceSlice:0 sourceLevel:0
               sourceOrigin:MTLOriginMake(0, 0, 0)
                 sourceSize:MTLSizeMake((*self.buffer).width, (*self.buffer).height, 1)
                  toTexture:(*self.outBuffer) destinationSlice:0 destinationLevel:0
          destinationOrigin:MTLOriginMake(0, 0, 0)];
    [blitEncoder endEncoding];
    [self retain];
    [commandbuf addCompletedHandler:^(id <MTLCommandBuffer> commandBuf) {
        [self startRedraw];
        [self release];
    }];
    [commandbuf commit];
}

- (void)commitCommandBuffer:(MTLContext*)mtlc wait:(BOOL)waitUntilCompleted display:(BOOL)updateDisplay {
    MTLCommandBufferWrapper * cbwrapper = [mtlc pullCommandBufferWrapper];

    if (cbwrapper != nil) {
        id <MTLCommandBuffer> commandbuf = [cbwrapper getCommandBuffer];

        [self retain];
        [commandbuf addCompletedHandler:^(id <MTLCommandBuffer> commandBuf) {
            [cbwrapper release];
            if (updateDisplay && isDisplaySyncEnabled()) {
                if (TRACE_DISPLAY) {
                    J2dRlsTraceLn(J2D_TRACE_VERBOSE, "[%.6lf] MTLLayer_commitCommandBuffer: CompletedHandler", CACurrentMediaTime());
                }
                // Ensure layer will be redrawn asap to display new content:
                [ThreadUtilities performOnMainThread:@selector(startRedrawIfNeeded) on:self withObject:nil
                                       waitUntilDone:NO useJavaModes:NO]; // critical
            }
            [self release];
        }];

        [commandbuf commit];

        if (updateDisplay) {
            if (isDisplaySyncEnabled()) {
                [self startRedraw];
            } else {
                [self flushBuffer];
            }
        }
        if (waitUntilCompleted && isDisplaySyncEnabled()) {
           [commandbuf waitUntilCompleted];
        }
    } else if (updateDisplay) {
        [self startRedraw];
    }
}

- (void) countFramePresentedCallback {
    // attach the current thread to the JVM if necessary, and get an env
    JNIEnv* env = [ThreadUtilities getJNIEnvUncached];
    GET_MTL_LAYER_CLASS();
    DECLARE_METHOD(jm_countNewFrame, jc_JavaLayer, "countNewFrame", "()V");

    jobject javaLayerLocalRef = (*env)->NewLocalRef(env, self.javaLayer);
    if (javaLayerLocalRef != NULL) {
        (*env)->CallVoidMethod(env, javaLayerLocalRef, jm_countNewFrame);
        CHECK_EXCEPTION();
        (*env)->DeleteLocalRef(env, javaLayerLocalRef);
    }
}

- (void) countFrameDroppedCallback {
    // attach the current thread to the JVM if necessary, and get an env
    JNIEnv* env = [ThreadUtilities getJNIEnvUncached];
    GET_MTL_LAYER_CLASS();
    DECLARE_METHOD(jm_countDroppedFrame, jc_JavaLayer, "countDroppedFrame", "()V");

    jobject javaLayerLocalRef = (*env)->NewLocalRef(env, self.javaLayer);
    if (javaLayerLocalRef != NULL) {
        (*env)->CallVoidMethod(env, javaLayerLocalRef, jm_countDroppedFrame);
        CHECK_EXCEPTION();
        (*env)->DeleteLocalRef(env, javaLayerLocalRef);
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
(JNIEnv *env, jobject obj, jboolean perfCountersEnabled)
{
    __block MTLLayer *layer = nil;

JNI_COCOA_ENTER(env);

    jobject javaLayer = (*env)->NewWeakGlobalRef(env, obj);

    // Wait and ensure main thread creates the MTLLayer instance now:
    [ThreadUtilities performOnMainThreadWaiting:YES useJavaModes:NO // critical
                                          block:^(){
            AWT_ASSERT_APPKIT_THREAD;
            layer = [[MTLLayer alloc] initWithJavaLayer: javaLayer usePerfCounters: perfCountersEnabled];
    }];
    if (TRACE_DISPLAY) {
        J2dRlsTraceLn(J2D_TRACE_INFO, "MTLLayer_nativeCreateLayer: created layer[%p]", layer);
    }
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
        layer.outBuffer = &bmtlsdo->pOutTexture;
        layer.ctx = ((MTLSDOps *)bmtlsdo->privOps)->configInfo->context;
        layer.displayID = ((MTLSDOps *)bmtlsdo->privOps)->configInfo->displayID;
        layer.device = layer.ctx.device;
        layer.pixelFormat = MTLPixelFormatBGRA8Unorm;

        if (!isColorMatchingEnabled() && (layer.colorspace != nil)) {
            // disable color matching:
            layer.colorspace = nil;
        }

        layer.drawableSize =
            CGSizeMake((*layer.buffer).width,
                       (*layer.buffer).height);
        if (isDisplaySyncEnabled()) {
            [layer startRedraw];
        } else {
            [layer flushBuffer];
        }
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
    // Ensure main thread changes the MTLLayer instance later:
    [ThreadUtilities performOnMainThreadNowOrLater:NO // critical
                                             block:^(){
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
    JNI_COCOA_ENTER(env);
    MTLLayer *layer = jlong_to_ptr(layerPtr);
    MTLContext * ctx = layer.ctx;
    if (layer == nil || ctx == nil) {
        J2dTraceLn(J2D_TRACE_VERBOSE, "MTLLayer_blitTexture : Layer or Context is null");
        if (layer != nil) {
            [layer stopRedraw:YES];
        }
        return;
    }

    [layer blitTexture];
    JNI_COCOA_EXIT(env);
}

JNIEXPORT void JNICALL
Java_sun_java2d_metal_MTLLayer_nativeSetOpaque
(JNIEnv *env, jclass cls, jlong layerPtr, jboolean opaque)
{
    JNI_COCOA_ENTER(env);

    MTLLayer *layer = jlong_to_ptr(layerPtr);
    // Ensure main thread changes the MTLLayer instance later:
    [ThreadUtilities performOnMainThreadWaiting:NO useJavaModes:NO // critical
                                          block:^(){
        [layer setOpaque:(opaque == JNI_TRUE)];
    }];

    JNI_COCOA_EXIT(env);
}
