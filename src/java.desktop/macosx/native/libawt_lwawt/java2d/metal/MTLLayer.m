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

#import <dispatch/dispatch.h>
#import <sys/sysctl.h>
#import "PropertiesUtilities.h"
#import "MTLGraphicsConfig.h"
#import "MTLLayer.h"
#import "ThreadUtilities.h"
#import "LWCToolkit.h"
#import "MTLSurfaceData.h"
#import "JNIUtilities.h"
#import "ThreadUtilities.h"

#define MAX_DRAWABLE    3
#define LAST_DRAWABLE   (MAX_DRAWABLE - 1)

static jclass jc_JavaLayer = NULL;
#define GET_MTL_LAYER_CLASS() \
    GET_CLASS(jc_JavaLayer, "sun/java2d/metal/MTLLayer");

#define TRACE_DISPLAY          TRACE_DISPLAY_ENABLED

#define TRACE_DISPLAY_CALLER   0
#define TRACE_REDRAW           0

#define USE_BUSY_LOOP 0

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
        NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
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

static const CFTimeInterval THRESHOLD_S = 2.666 / 1000.0;

static void busyLoop(NSString* message) {
    const CFTimeInterval beforeMethod = CACurrentMediaTime();

    NSUInteger r = arc4random_uniform(20);
    if (r < 1) {
        r = 1;
    }
    r += 10;

    // BUSY LOOP to create interferences:
    for (CFTimeInterval now = 0; ;) {
        now = (CACurrentMediaTime() - beforeMethod);
        if (now > r * THRESHOLD_S) {
            break;
        }
    }
    NSLog(@"busyLoop(%@): busy loop: %lf", message, (CACurrentMediaTime() - beforeMethod));
}

@implementation MTLLayer {
    NSLock* _lock;
}

+ (NSUInteger) getDrawableId:(id<MTLDrawable>)mtlDrawable {
    if (@available(macOS 10.15.4, *)) {
        return [mtlDrawable drawableID];
    }
    return -1;
}

- (id) initWithJavaLayer:(jobject)layer usePerfCounters:(jboolean)perfCountersEnabled
{
    AWT_ASSERT_APPKIT_THREAD;
    // Initialize ourselves
    self = [super init];
    if (self == nil) return self;

    self.javaLayer = layer;
    self.ctx = nil;
    self.displayID = -1;
    self.contentsGravity = kCAGravityTopLeft;

    //Disable CALayer's default animation
    NSMutableDictionary * actions = [[NSMutableDictionary alloc] initWithObjectsAndKeys:
                                    [NSNull null], @"anchorPoint",
                                    [NSNull null], @"bounds",
                                    [NSNull null], @"contents",
                                    [NSNull null], @"cornerRadius",
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
    self.redrawCount = -1;
    if (@available(macOS 10.13, *)) {
        self.displaySyncEnabled = isDisplaySyncEnabled();
    }
    if (@available(macOS 10.13.2, *)) {
        self.maximumDrawableCount = MAX_DRAWABLE;
    }
    self.presentsWithTransaction = NO;
    self.willRedraw = NO;
    self.redrawTime = 0.0;
    self.willDisplay = NO;
    self.redrawVersion = 0;
    self.blitVersion = 0;
    self.avgBlitFrameTime = DF_BLIT_FRAME_TIME;
#if TRACE_DISPLAY_ON
    self.avgNextDrawableTime = 0.0;
    self.lastPresentedTime = 0.0;
#endif
    self.perfCountersEnabled = perfCountersEnabled ? YES : NO;
    _lock = [[NSLock alloc] init];
    return self;
}

+ (void) releaseLayer:(MTLLayer*)layer {
    [ThreadUtilities performOnMainThreadWaiting:NO block:^(){
        [layer release];
    }];
}

- (NSString*) getRedrawInfo {
    return [NSString stringWithFormat:@"layer[%p displayID: %ld] willRedraw=%d willDisplay=%d "
                                       "redrawCount=%d redrawVersion=%d blitVersion=%d redrawTime=%.3lf",
                                        self, self.displayID, self.willRedraw, self.willDisplay,
                                        self.redrawCount, self.redrawVersion, self.blitVersion, self.redrawTime];
}

- (void) blitTexture {
    // Note: blitTexture is called synchronously by the main thread holding the RenderQueue / AWT lock, by:
    // MTLLayer_blitTexture() <- MTLLayer.java:drawInMTLContext() <- blitCallback() <- display()

    AWT_ASSERT_APPKIT_THREAD;
    if (self.ctx == NULL || self.javaLayer == NULL || self.buffer == NULL || *self.buffer == nil ||
        self.ctx.device == nil)
    {
        J2dRlsTraceLn(J2D_TRACE_VERBOSE,
                   "MTLLayer.blitTexture: uninitialized (mtlc=%p, javaLayer=%p, buffer=%p, device=%p)", self.ctx,
                   self.javaLayer, self.buffer, self.ctx.device);
        [self stopRedraw:YES]; // SYNC (main thread)
        return;
    }

    // Unified logic:
    // - blit committed => stop
    // else retry = auto or start redraw (async) !

    @autoreleasepool {

        // MTLDrawable pool barrier:
        BOOL skipDrawable = YES;
        [_lock lock];
        if (self.nextDrawableCount < LAST_DRAWABLE) {
            // increment used drawables to act as the CPU fence:
            self.nextDrawableCount++;
            skipDrawable = NO;
        }
        [_lock unlock];

        // try-finally block to ensure releasing the CPU fence (abort blit - nextDrawableCount):
        BOOL blitCommitted = NO;
        BOOL releaseFence = YES;
        @try {
            if (skipDrawable) {
                releaseFence = NO;
                if (TRACE_DISPLAY) {
                    J2dRlsTraceLn(J2D_TRACE_VERBOSE, "[%.6lf] MTLLayer.blitTexture: layer[%p] skip drawable [skip blit] nextDrawableCount = %d",
                                  CACurrentMediaTime(), self, self.nextDrawableCount);
                }
                return;
            }
            // Perform blit:

            if (((*self.buffer).width == 0) || ((*self.buffer).height == 0)) {
                J2dTraceLn(J2D_TRACE_VERBOSE, "MTLLayer.blitTexture: layer[%p] Cannot create drawable of size 0", self);
                return;
            }

            NSUInteger src_x = self.leftInset * self.contentsScale;
            NSUInteger src_y = self.topInset * self.contentsScale;
            NSUInteger src_w = (*self.buffer).width - src_x;
            NSUInteger src_h = (*self.buffer).height - src_y;

            if (src_h <= 0 || src_w <= 0) {
                J2dTraceLn(J2D_TRACE_VERBOSE, "MTLLayer.blitTexture: layer[%p] Invalid src width or height.", self);
                return;
            }

            id<MTLCommandBuffer> commandBuf = [self.ctx createBlitCommandBuffer];
            if (commandBuf == nil) {
                J2dTraceLn(J2D_TRACE_VERBOSE, "MTLLayer.blitTexture: layer[%p] commandBuf is null", self);
                return;
            }

            // Acquire CAMetalDrawable without blocking:
            const CFTimeInterval beforeDrawableTime = CACurrentMediaTime();
            const id<CAMetalDrawable> mtlDrawable = [self nextDrawable];
            if (mtlDrawable == nil) {
                J2dTraceLn(J2D_TRACE_VERBOSE, "MTLLayer.blitTexture: layer[%p] nextDrawable is null", self);
                return;
            }
            const CFTimeInterval nextDrawableTime = CACurrentMediaTime();
            const CFTimeInterval nextDrawableLatency = (nextDrawableTime - beforeDrawableTime);

            // rolling mean weight (lerp):
            static const NSTimeInterval a = 0.25;

            if (nextDrawableLatency > 0.0) {
                if (self.perfCountersEnabled) {
                    [self addStatCallback:1 value:1000.0 * nextDrawableLatency]; // See MTLLayer.STAT_NAMES[1]
                }
#if TRACE_DISPLAY_ON
                self.avgNextDrawableTime = nextDrawableLatency * a + self.avgNextDrawableTime * (1.0 - a);

                J2dRlsTraceLn(J2D_TRACE_VERBOSE,
                          "[%.6lf] MTLLayer.blitTexture: drawable(%d) taken"
                          " - nextDrawableLatency = %.3lf ms - average = %.3lf ms",
                          CACurrentMediaTime(), [MTLLayer getDrawableId:mtlDrawable],
                          1000.0 * nextDrawableLatency, 1000.0 * self.avgNextDrawableTime
                );
#endif
            }

            // Keep Fence from now:
            releaseFence = NO;

            const int blitVersion = self.redrawVersion; // corresponds to last buffer state (work stealing)

            id <MTLBlitCommandEncoder> blitEncoder = [commandBuf blitCommandEncoder];

            [blitEncoder
                    copyFromTexture:(isDisplaySyncEnabled()) ? (*self.buffer) : (*self.outBuffer)
                    sourceSlice:0 sourceLevel:0
                    sourceOrigin:MTLOriginMake(src_x, src_y, 0)
                    sourceSize:MTLSizeMake(src_w, src_h, 1)
                    toTexture:mtlDrawable.texture destinationSlice:0 destinationLevel:0
                    destinationOrigin:MTLOriginMake(0, 0, 0)];
            [blitEncoder endEncoding];

            if (@available(macOS 10.15.4, *)) {
                [self retain];
                [mtlDrawable addPresentedHandler:^(id <MTLDrawable> drawable) {
                    @try {
                        // note: called anyway even if drawable.present() not called!
                        const CFTimeInterval presentedTime = drawable.presentedTime;
                        if (presentedTime != 0.0) {
                            if (self.perfCountersEnabled) {
                                [self countFramePresentedCallback];
                            }
#if TRACE_DISPLAY_ON
                            const CFTimeInterval now = CACurrentMediaTime();
                            const CFTimeInterval presentedHandlerLatency = (now - nextDrawableTime);
                            const CFTimeInterval frameInterval = (self.lastPresentedTime != 0.0) ? (presentedTime - self.lastPresentedTime) : -1.0;
                            J2dRlsTraceLn(J2D_TRACE_VERBOSE,
                                          "[%.6lf] MTLLayer.blitTexture_PresentedHandler: drawable(%d) presented"
                                          " - presentedHandlerLatency = %.3lf ms frameInterval = %.3lf ms",
                                          CACurrentMediaTime(), [MTLLayer getDrawableId:drawable],
                                          1000.0 * presentedHandlerLatency, 1000.0 * frameInterval
                            );
                            self.lastPresentedTime = presentedTime;
#endif
                        } else {
                            if (self.perfCountersEnabled) {
                                [self countFrameDroppedCallback];
                            }
                            if (TRACE_DISPLAY) {
                                const CFTimeInterval now = CACurrentMediaTime();
                                const CFTimeInterval presentedHandlerLatency = (now - nextDrawableTime);
                                J2dRlsTraceLn(J2D_TRACE_VERBOSE,
                                              "[%.6lf] MTLLayer.blitTexture_PresentedHandler: drawable(%d) skipped"
                                              " - presentedHandlerLatency = %.3lf ms",
                                              CACurrentMediaTime(), [MTLLayer getDrawableId:drawable],
                                              1000.0 * presentedHandlerLatency
                                );
                            }
                            // retry:
                            if (!self.willRedraw && (blitVersion == self.redrawVersion)) {
                                [self startRedraw]; // ASYNC
                            } else {
                                // TODO: fix
                                NSLog(@"MTLLayer_PresentedHandler: drawable skipped (v %d)-> skip startRedraw (v %d) willRedraw = %d",
                                    blitVersion, self.redrawVersion, self.willRedraw);
                            }
                        }
                    } @catch (NSException *exception) {
                        // report exception to the NSApplicationAWT exception handler:
                        NSAPP_AWT_REPORT_EXCEPTION(exception, NO);
                    } @finally {
                        // ensure releasing on main thread:
                        [MTLLayer releaseLayer:self];
                    }
                }];
            }
            // Present drawable:
            NSUInteger drawableId = -1;
            if (TRACE_DISPLAY) {
                drawableId = [MTLLayer getDrawableId:mtlDrawable];
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
                // free drawable once processed:
                [self freeDrawableCount];
                if (@available(macOS 10.15.4, *)) {
                    if (!isDisplaySyncEnabled()) {
                        const NSTimeInterval gpuTime = commandBuf.GPUEndTime - commandBuf.GPUStartTime;
                        self.avgBlitFrameTime = gpuTime * a + self.avgBlitFrameTime * (1.0 - a);
                    }
                }
                // ensure releasing on main thread:
                [MTLLayer releaseLayer:self];
            }];

            [commandBuf commit];

            // Mark version committed:
            blitCommitted = YES;
            self.blitVersion = blitVersion;

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
            }

            if (isDisplaySyncEnabled()) {
                // Reset flag willDisplay (blocking until this method returns by the main thread)
                self.willDisplay = NO;

                // Reset the reference frame time used by caller's blitCallback:
                self.redrawTime = 0.0;
            }

            if (blitCommitted) {
                // Decrement redrawCount:
                [self stopRedraw:NO]; // SYNC (main thread)
            } else {
                // blit will be repeated at next CVDL defered redraw call:
                // but anyway use startRedraw to ensure CVDL alive:
                [self startRedraw]; // SYNC (main thread)
            }
        }
    } // autorelease pool
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
    self.javaLayer = NULL;
    [self stopRedraw:YES]; // SYNC (main thread)
    self.buffer = NULL;
    self.outBuffer = NULL;
    [_lock release];
    [super dealloc];
}

- (void) blitCallback {
    AWT_ASSERT_APPKIT_THREAD;
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    JNI_COCOA_ENTER(env);
    GET_MTL_LAYER_CLASS();
    DECLARE_METHOD(jm_drawInMTLContext, jc_JavaLayer, "drawInMTLContext", "()V");

    jobject javaLayerLocalRef = (*env)->NewLocalRef(env, self.javaLayer);
    if (javaLayerLocalRef == NULL) {
        return;
    }

    // Get the reference frame time:
    const CFTimeInterval frameRedrawTime = self.redrawTime;

    const CFTimeInterval beforeMethod = CACurrentMediaTime();

    // SYNC call to drawInMTLContext to acquire RQ/AWT Lock to call back MTLLayer_blitTexture -> blitTexture:
    (*env)->CallVoidMethod(env, javaLayerLocalRef, jm_drawInMTLContext);
    (*env)->DeleteLocalRef(env, javaLayerLocalRef);
    CHECK_EXCEPTION();

    const CFTimeInterval afterMethod = CACurrentMediaTime();

    const CFTimeInterval drawInMTLContextLatency = (afterMethod - beforeMethod);
    if (drawInMTLContextLatency > 0.0) {
        const CFTimeInterval drawLatency = (frameRedrawTime > 0.0) ? (afterMethod - frameRedrawTime) : -1.0;
        if (TRACE_DISPLAY) {
            NSLog(@"MTLLayer_blitCallback: drawInMTLContextLatency = %.3lf ms, drawLatency = %.3lf ms",
                1000.0 * drawInMTLContextLatency, 1000.0 * drawLatency);
        }
        if (self.perfCountersEnabled) {
            [self addStatCallback:0 value:1000.0 * drawInMTLContextLatency]; // See MTLLayer.STAT_NAMES[0]
        }
    }
    JNI_COCOA_EXIT(env);
}

- (void) setNeedsDisplay {
    AWT_ASSERT_APPKIT_THREAD;
    if (!self.needsDisplay) {
        if (TRACE_DISPLAY_CALLER) {
            NSLog(@"MTLLayer_setNeedsDisplay: %@ CallStack:\n%@",
                [self getRedrawInfo], [ThreadUtilities getCallerStack:@"setNeedsDisplay"]);
        }
        [super setNeedsDisplay];
    }
}

- (void) display {
    AWT_ASSERT_APPKIT_THREAD;
    J2dTraceLn(J2D_TRACE_VERBOSE, "MTLLayer_display() called");

    if (TRACE_DISPLAY_CALLER) {
        NSLog(@"MTLLayer_display: %@ CallStack:\n%@",
            [self getRedrawInfo], [ThreadUtilities getCallerStack:@"display"]);
    }
    [self blitCallback];
    [super display];

    if (USE_BUSY_LOOP) {
        busyLoop(@"MTLLayer_display");

       J2dRlsTraceLn(J2D_TRACE_VERBOSE, "MTLLayer_display() exit");
    }
}

/* WillRedraw => 1 then will increase redrawVersion in MTLContext.startRedraw */
- (void)startRedraw {
    if (isDisplaySyncEnabled()) {
        if (self.ctx != nil) {
            if (self.willRedraw) {
                if (TRACE_REDRAW) {
                    NSLog(@"MTLLayer_startRedraw: %@ skipped startRedraw",
                        [self getRedrawInfo]);
                }
            } else {
                // set flag willRedraw
                self.willRedraw = YES;
                if (TRACE_REDRAW) {
                    NSLog(@"MTLLayer_startRedraw: %@ before performOnMainThread",
                        [self getRedrawInfo]);
                }
                [ThreadUtilities performOnMainThreadNowOrLater:NO // critical
                                                         block:^(){
                    // Reset flag willRedraw
                    self.willRedraw = NO;
                    [self.ctx startRedraw:self];
                }];
            }
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
        [self stopRedraw:self.ctx displayID:self.displayID force:force];
    }
}

- (void) stopRedraw:(MTLContext*)mtlc displayID:(jint)displayID force:(BOOL)force {
    if (isDisplaySyncEnabled()) {
        if (force) {
            // optimistic:
            self.redrawCount = 0;
        }
        if (mtlc != nil) {
            if (!force && self.willRedraw) {
                if (TRACE_REDRAW) {
                    NSLog(@"MTLLayer_stopRedraw: %@ skipped stopRedraw",
                        [self getRedrawInfo]);
                }
            } else {
                if (TRACE_REDRAW) {
                    NSLog(@"MTLLayer_stopRedraw: %@ before performOnMainThread",
                        [self getRedrawInfo]);
                }
                [ThreadUtilities performOnMainThreadNowOrLater:NO // critical
                                                         block:^(){
                    if (force) {
                        // double-check: ensure stopping redraw == KILL flag:
                        self.redrawCount = 0;
                    }
                    [mtlc stopRedraw:displayID layer:self];
                }];
            }
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
        [self startRedraw]; // ASYNC
        // ensure releasing on main thread:
        [MTLLayer releaseLayer:self];
    }];
    [commandbuf commit];
}

- (void)commitCommandBuffer:(MTLContext*)mtlc wait:(BOOL)waitUntilCompleted display:(BOOL)updateDisplay {
    MTLCommandBufferWrapper * cbwrapper = [mtlc pullCommandBufferWrapper];

    if (TRACE_DISPLAY) {
        J2dRlsTraceLn(J2D_TRACE_VERBOSE, "[%.6lf] MTLLayer_commitCommandBuffer[layer=%p displayID: %ld] wait: %d updateDisplay: %d",
            CACurrentMediaTime(), self, self.displayID, waitUntilCompleted, updateDisplay);
    }

    if (cbwrapper != nil) {
        id <MTLCommandBuffer> commandbuf = [cbwrapper getCommandBuffer];

        [self retain];
        [commandbuf addCompletedHandler:^(id <MTLCommandBuffer> commandBuf) {
            [cbwrapper release];
            if (updateDisplay && isDisplaySyncEnabled()) {
                if (TRACE_DISPLAY) {
                    J2dRlsTraceLn(J2D_TRACE_VERBOSE, "[%.6lf] MTLLayer_commitCommandBuffer[layer=%p displayID: %ld]: CompletedHandler",
                        CACurrentMediaTime(), self, self.displayID);
                }
                // Ensure layer will be redrawn to display new content:
                [self startRedraw]; // ASYNC
            }
            // ensure releasing on main thread:
            [MTLLayer releaseLayer:self];
        }];

        [commandbuf commit];

        if (updateDisplay && !isDisplaySyncEnabled()) {
            [self flushBuffer];
        }
        if (waitUntilCompleted && isDisplaySyncEnabled()) {
           [commandbuf waitUntilCompleted];
        }
    } else if (updateDisplay) {
        [self startRedraw]; // ASYNC
    }
}

#define CHECK_LAYER(caller) { \
    if (self.ctx == NULL || self.javaLayer == NULL || self.ctx.device == nil) \
    { \
        J2dRlsTraceLn(J2D_TRACE_VERBOSE, \
                      "MTLLayer.%s: uninitialized (mtlc=%p, javaLayer=%p, device=%p)", \
                      caller, self.ctx, self.javaLayer, self.ctx.device); \
        return; \
    } \
}

- (void) addStatCallback:(int)type value:(double)value {
    AWT_ASSERT_APPKIT_THREAD;
    CHECK_LAYER("addStatCallback");

    [self retain];
    JNI_SUBMIT_ASYNC(^{
        // Reuse a single JNIEnv:
        JNIEnv* env = [ThreadUtilities getJNIEnvUncached];
        JNI_COCOA_ENTER(env);
        GET_MTL_LAYER_CLASS();
        DECLARE_METHOD(jm_addStatFrame, jc_JavaLayer, "addStat", "(ID)V");

        if (self.javaLayer != NULL) {
            jobject javaLayerLocalRef = (*env)->NewLocalRef(env, self.javaLayer);
            if (javaLayerLocalRef != NULL) {
                (*env)->CallVoidMethod(env, javaLayerLocalRef, jm_addStatFrame, (jint)type, (jdouble)value);
                (*env)->DeleteLocalRef(env, javaLayerLocalRef);
                CHECK_EXCEPTION();
            }
        }
        JNI_COCOA_EXIT(env);
        // ensure releasing on main thread:
        [MTLLayer releaseLayer:self];
    });
}

- (void) countFramePresentedCallback {
    CHECK_LAYER("countFramePresentedCallback");

    [self retain];
    JNI_SUBMIT_ASYNC(^{
        // Reuse a single JNIEnv:
        JNIEnv* env = [ThreadUtilities getJNIEnvUncached];
        JNI_COCOA_ENTER(env);
        GET_MTL_LAYER_CLASS();
        DECLARE_METHOD(jm_countNewFrame, jc_JavaLayer, "countNewFrame", "()V");

        if (self.javaLayer != NULL) {
            jobject javaLayerLocalRef = (*env)->NewLocalRef(env, self.javaLayer);
            if (javaLayerLocalRef != NULL) {
                (*env)->CallVoidMethod(env, javaLayerLocalRef, jm_countNewFrame);
                (*env)->DeleteLocalRef(env, javaLayerLocalRef);
                CHECK_EXCEPTION();
            }
        }
        JNI_COCOA_EXIT(env);
        // ensure releasing on main thread:
        [MTLLayer releaseLayer:self];
    });
}

- (void) countFrameDroppedCallback {
    CHECK_LAYER("countFrameDroppedCallback");

    [self retain];
    JNI_SUBMIT_ASYNC(^{
        // Reuse a single JNIEnv:
        JNIEnv* env = [ThreadUtilities getJNIEnvUncached];
        JNI_COCOA_ENTER(env);
        GET_MTL_LAYER_CLASS();
        DECLARE_METHOD(jm_countDroppedFrame, jc_JavaLayer, "countDroppedFrame", "()V");

        if (self.javaLayer != NULL) {
            jobject javaLayerLocalRef = (*env)->NewLocalRef(env, self.javaLayer);
            if (javaLayerLocalRef != NULL) {
                (*env)->CallVoidMethod(env, javaLayerLocalRef, jm_countDroppedFrame);
                (*env)->DeleteLocalRef(env, javaLayerLocalRef);
                CHECK_EXCEPTION();
            }
        }
        JNI_COCOA_EXIT(env);
        // ensure releasing on main thread:
        [MTLLayer releaseLayer:self];
    });
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
    JNI_COCOA_ENTER(env);
    MTLLayer *layer = OBJC(layerPtr);

    if (surfaceData != NULL) {
        BMTLSDOps *bmtlsdo = (BMTLSDOps*) SurfaceData_GetOps(env, surfaceData);
        layer.buffer = &bmtlsdo->pTexture;
        layer.outBuffer = &bmtlsdo->pOutTexture;

        // Backup layer's context (device) and displayId to unregister if needed:
        MTLContext* oldMtlc    = layer.ctx;
        NSInteger oldDisplayID = layer.displayID;

        MTLContext* newMtlc    = ((MTLSDOps *)bmtlsdo->privOps)->configInfo->context;
        NSInteger newDisplayID = ((MTLSDOps *)bmtlsdo->privOps)->configInfo->displayID;

        if (isDisplaySyncEnabled()) {
            if (oldDisplayID != -1) {
                if ((oldMtlc != newMtlc) || (oldDisplayID != newDisplayID)) {
                    if (TRACE_DISPLAY_CHANGED) {
                        J2dRlsTraceLn(J2D_TRACE_INFO, "MTLLayer_validate: layer[%p] mtlc/displayID changed: "
                                                      "[%p - %d] => [%p - %d]",
                                      layer, oldMtlc, oldDisplayID, newMtlc, newDisplayID);
                    }
                    // unregister with display link on old mtlc/display before updating layer's state:
                    [layer stopRedraw:oldMtlc displayID:oldDisplayID force:YES]; // ASYNC
                }
            }
        }
        // Update layer's context (device) and displayId:
        layer.ctx       = newMtlc;
        layer.device    = layer.ctx.device;
        layer.displayID = newDisplayID;
        layer.pixelFormat = MTLPixelFormatBGRA8Unorm;

        if (!isColorMatchingEnabled() && (layer.colorspace != nil)) {
            // disable color matching:
            layer.colorspace = nil;
        }

        layer.drawableSize =
            CGSizeMake((*layer.buffer).width,
                       (*layer.buffer).height);

        if (isDisplaySyncEnabled()) {
            if (TRACE_DISPLAY) {
                J2dRlsTraceLn(J2D_TRACE_INFO, "[%.6lf] MTLLayer_validate: layer[%p] displayId = %d, drawableSize = %f x %f",
                              CACurrentMediaTime(), layer, layer.displayID, layer.drawableSize.width, layer.drawableSize.height);
            }
            [layer startRedraw]; // ASYNC
        } else {
            [layer flushBuffer];
        }
    } else {
        if (TRACE_DISPLAY) {
            J2dRlsTraceLn(J2D_TRACE_INFO, "[%.6lf] MTLLayer_validate: layer[%p] surface NULL", CACurrentMediaTime(), layer);
        }
        // dispose() breaks the surface !
        [layer stopRedraw:YES]; // ASYNC
        layer.ctx = NULL;
    }
    JNI_COCOA_EXIT(env);
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
    AWT_ASSERT_APPKIT_THREAD;
    J2dTraceLn(J2D_TRACE_VERBOSE, "MTLLayer_blitTexture");
    JNI_COCOA_ENTER(env);
    MTLLayer *layer = jlong_to_ptr(layerPtr);
    MTLContext * ctx = layer.ctx;
    if (layer == nil || ctx == nil) {
        J2dTraceLn(J2D_TRACE_VERBOSE, "MTLLayer_blitTexture : Layer or Context is null");
        if (layer != nil) {
            [layer stopRedraw:YES]; // SYNC (main thread)
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
