/*
 * Copyright (c) 2019, 2023, Oracle and/or its affiliates. All rights reserved.
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
#import <sys/time.h>
#import "PropertiesUtilities.h"
#import "MTLGraphicsConfig.h"
#import "MTLLayer.h"
#import "ThreadUtilities.h"
#import "LWCToolkit.h"
#import "MTLSurfaceData.h"
#import "JNIUtilities.h"

const NSTimeInterval DF_BLIT_FRAME_TIME = 1.0 / 120.0;

const int MAX_DRAWABLE = 3;
const BOOL VSYNC = YES;

#define TRACE_DRAWABLE_REFS (0)

extern BOOL isTraceDisplayEnabled();

long rqCurrentTimestamp() {
    struct timeval t;
    gettimeofday(&t, 0);
    return ((long)t.tv_sec) * 1000000 + (long)(t.tv_usec);
}

long rqCurrentTimeMicroSeconds() {
    static long startTimeStamp = -1L;
    if (startTimeStamp == -1L) {
        startTimeStamp = rqCurrentTimestamp();
    }
    return rqCurrentTimestamp() - startTimeStamp;
}


BOOL isDisplaySyncEnabled() {
    static int syncEnabled = -1;
    if (syncEnabled == -1) {
        JNIEnv *env = [ThreadUtilities getJNIEnvUncached];
        if (env == NULL) return NO;
        NSString *syncEnabledProp = [PropertiesUtilities javaSystemPropertyForKey:@"sun.java2d.metal.displaySync"
                                                                          withEnv:env];
        syncEnabled = [@"false" isCaseInsensitiveLike:syncEnabledProp] ? NO : YES;
        J2dRlsTraceLn1(J2D_TRACE_INFO, "MTLLayer_isDisplaySyncEnabled: %d", syncEnabled);
    }
    return (BOOL)syncEnabled;
}

BOOL isColorMatchingEnabled() {
    static int colorMatchingEnabled = -1;
    if (colorMatchingEnabled == -1) {
        JNIEnv *env = [ThreadUtilities getJNIEnvUncached];
        if (env == NULL) return NO;
        NSString *colorMatchingEnabledProp = [PropertiesUtilities javaSystemPropertyForKey:@"sun.java2d.metal.colorMatching"
                                                                                   withEnv:env];
        colorMatchingEnabled = [@"false" isCaseInsensitiveLike:colorMatchingEnabledProp] ? NO : YES;
        J2dRlsTraceLn1(J2D_TRACE_INFO, "MTLLayer_isColorMatchingEnabled: %d", colorMatchingEnabled);
    }
    return (BOOL)colorMatchingEnabled;
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

        J2dRlsTraceLn2(J2D_TRACE_INFO, "MTLLayer_isM2CPU: %d (%s)", m2CPU, cpuBrand);

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
        J2dRlsTraceLn1(J2D_TRACE_INFO, "MTLLayer_isSpansDisplays: %d", spansDisplays);
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
        J2dRlsTraceLn1(J2D_TRACE_INFO, "MTLLayer_isExtraRedrawEnabled: %d", redrawEnabled);
    }
    return (BOOL)redrawEnabled;
}

NSTimeInterval MTLLayer_smoothAverage(const NSTimeInterval avgTime, const NSTimeInterval gpuTime) {
    const NSTimeInterval a = 0.25; // exponential smoothing
    return gpuTime * a + avgTime * (1.0 - a);
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

    // Validation with MTL_DEBUG_LAYER=1 environment variable
    // prohibits blit operations on to the drawable texture
    // obtained from a MTLLayer with framebufferOnly=YES
    self.framebufferOnly = NO;
    self.opaque = YES;
    self.nextDrawableCount = 0;

    self.paintCount = 0;
    self.paintedCount = 0;
    self.drawCount = 0;
    self.displayedCount = 0;
    self.lastDisplayedTime = 0.0;

    self.redrawCount = 0;
    if (@available(macOS 10.13, *)) {
        self.displaySyncEnabled = VSYNC; // || isDisplaySyncEnabled();
        self.maximumDrawableCount = MAX_DRAWABLE;
    }
    // NOTHING DISPLAYED from callback:
    if (false) {
        self.presentsWithTransaction = YES;
    }
    self.

    self.currentDrawable = nil;

    self.avgBlitFrameTime = DF_BLIT_FRAME_TIME;
    self.avgRenderFrameTime = DF_BLIT_FRAME_TIME;
    return self;
}

- (id<CAMetalDrawable>) getCurrentDrawable:(BOOL)reset {
    id<CAMetalDrawable> mtlDrawable = [self currentDrawable];
    if (mtlDrawable == nil) {
        J2dRlsTraceLn(J2D_TRACE_INFO, "MTLLayer.getCurrentDrawable: currentDrawable is null)");
        reset = YES;
    }
    if (reset) {
        mtlDrawable = [self nextDrawable];
        if (mtlDrawable == nil) {
            J2dRlsTraceLn(J2D_TRACE_INFO, "MTLLayer.getCurrentDrawable: nextDrawable is null)");
        }
        [self setCurrentDrawable: mtlDrawable];
    }
    if (TRACE_DRAWABLE_REFS) {
        J2dRlsTraceLn4(J2D_TRACE_INFO, "[%ld] getCurrentDrawable: layer[%p] drawable(%d) %d refs",
                       rqCurrentTimeMicroSeconds(), self, [mtlDrawable drawableID], [mtlDrawable retainCount]);
    }
    return mtlDrawable;
}

- (void) blitTexture {
    if (self.ctx == NULL || self.javaLayer == NULL || self.buffer == NULL || *self.buffer == nil ||
        self.ctx.device == nil)
    {
        J2dTraceLn4(J2D_TRACE_VERBOSE,
                    "MTLLayer.blitTexture: uninitialized (mtlc=%p, javaLayer=%p, buffer=%p, device=%p)", self.ctx,
                    self.javaLayer, self.buffer, self.ctx.device);
        [self stopRedraw:YES];
        return;
    }

    if (isTraceDisplayEnabled()) {
        J2dRlsTraceLn5(J2D_TRACE_INFO, "[%ld] blitTexture: layer[%p] buffer = %p (redraw = %d nextDrawableCount = %d)",
                       rqCurrentTimeMicroSeconds(), self, self.buffer, self.redrawCount, self.nextDrawableCount);
        J2dRlsTraceLn4(J2D_TRACE_INFO, "[%ld] blitTexture: texture[%p] (usage = %d hazardTrackingMode = %d)",
                       rqCurrentTimeMicroSeconds(), self.buffer, (*self.buffer).usage, (*self.buffer).hazardTrackingMode);
    }

    // 1 to act as a re-entrance barrier:
    if (self.nextDrawableCount >= (MAX_DRAWABLE - 1)) {
        if (isTraceDisplayEnabled()) {
            J2dRlsTraceLn4(J2D_TRACE_INFO, "[%ld] blitTexture: layer[%p] (redraw = %d) skip blit (nextDrawableCount = %d)",
                           rqCurrentTimeMicroSeconds(), self, self.redrawCount, self.nextDrawableCount);
        }
        if (!isDisplaySyncEnabled()) {
            [self performSelectorOnMainThread:@selector(setNeedsDisplay) withObject:nil waitUntilDone:NO];
        }
        return;
    }

    const int paintVersion = self.paintedCount;

    // skip useless blit ie paint version == displayed version:
    if (false && (paintVersion == self.drawCount)) {
        if (isTraceDisplayEnabled()) {
            J2dRlsTraceLn8(J2D_TRACE_INFO, "[%ld] blitTexture: layer[%p] (paint = %d painted = %d draw = %d disp = %d - redraw = %d) skip blit (same version)",
                           rqCurrentTimeMicroSeconds(), self, self.paintCount, self.paintedCount, self.drawCount, self.displayedCount, self.redrawCount, self.nextDrawableCount);
        }
        return;
    }

    // decrease redraw count:
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

        if (isTraceDisplayEnabled()) {
            J2dRlsTraceLn5(J2D_TRACE_INFO, "[%ld] blitTexture: layer[%p] (redraw = %d) nextDrawable() ? for buffer %p (nextDrawableCount = %d)",
                           rqCurrentTimeMicroSeconds(), self, self.redrawCount, self.buffer, self.nextDrawableCount);
        }

//        id<CAMetalDrawable> mtlDrawable = [self getCurrentDrawable: NO];

        id<CAMetalDrawable> mtlDrawable = [self nextDrawable];
        if (mtlDrawable == nil) {
            J2dRlsTraceLn(J2D_TRACE_INFO, "MTLLayer.blitTexture: mtlDrawable is null)");
            return;
        }
        self.nextDrawableCount++;

        if (TRACE_DRAWABLE_REFS) {
            J2dRlsTraceLn4(J2D_TRACE_INFO, "[%ld] blitTexture: layer[%p] drawable(%d) %d refs",
                           rqCurrentTimeMicroSeconds(), self, [mtlDrawable drawableID], [mtlDrawable retainCount]);
        }

        BOOL EXPLICIT_PRESENT = true;

        const CFTimeInterval commitTime = CACurrentMediaTime();

        if (isTraceDisplayEnabled()) {
            J2dRlsTraceLn9(J2D_TRACE_INFO, "[%ld] blitTexture: layer[%p] (paint = %d painted = %d draw = %d disp = %d - redraw = %d) next drawable(%d) for buffer %p",
                        rqCurrentTimeMicroSeconds(), self, self.paintCount, self.paintedCount, self.drawCount, self.displayedCount, self.redrawCount, [mtlDrawable drawableID], self.buffer);
            J2dRlsTraceLn4(J2D_TRACE_INFO, "[%ld] blitTexture: drawable texture[%p] (usage = %d hazardTrackingMode = %d)",
                           rqCurrentTimeMicroSeconds(), mtlDrawable.texture, mtlDrawable.texture.usage, mtlDrawable.texture.hazardTrackingMode);
        }

        if (@available(macOS 10.15.4, *)) {
            [self retain];

            [mtlDrawable addPresentedHandler:^(id <MTLDrawable> drawable) {
                self.displayedCount = paintVersion;

                if (isTraceDisplayEnabled()) {
                    const CFTimeInterval presentedTime = CACurrentMediaTime();
                    const CFTimeInterval presTime = [drawable presentedTime];
                    const CFTimeInterval interval = ((self.lastDisplayedTime != 0.0) && (presTime != 0.0)) ? (presTime - self.lastDisplayedTime) : 0.0;
                    const CFTimeInterval frameRate = (interval > 0.0) ? (1.0 / interval) : 0.0;
                    if (presTime > 0.0) {
                        self.lastDisplayedTime = presTime;
                        self.ctx.presCount++;
                    } else {
                        J2dRlsTraceLn7(J2D_TRACE_INFO, "[%ld] blitTexture: PresentedHandler layer[%p] (paint = %d painted = %d draw = %d DISP = %d - redraw = %d) SKIPPED Present",
                                       rqCurrentTimeMicroSeconds(), self, self.paintCount, self.paintedCount, self.drawCount, self.displayedCount, self.redrawCount);
                    }
                    const CFTimeInterval presentedOffset = (presTime != 0.0) ? presentedTime - presTime : 0.0;
                    const CFTimeInterval cmdDelay = (presentedTime - commitTime);

                    J2dRlsTraceLn9(J2D_TRACE_INFO, "[%ld] blitTexture: PresentedHandler layer[%p] (paint = %d painted = %d draw = %d DISP = %d - redraw = %d) interval: %.3lf ms / presentDelay: %lf ms",
                                   rqCurrentTimeMicroSeconds(), self, self.paintCount, self.paintedCount, self.drawCount, self.displayedCount, self.redrawCount, interval * 1000.0, cmdDelay * 1000.0);

                    J2dRlsTraceLn5(J2D_TRACE_INFO, "[%ld] blitTexture: PresentedHandler layer[%p]: drawable(%d) presented at off = %lf ms - FPS: %lf",
                                   rqCurrentTimeMicroSeconds(), self, [drawable drawableID],
                                   1000.0 * presentedOffset, frameRate);
                }
                [self release];
                if (TRACE_DRAWABLE_REFS) {
                    J2dRlsTraceLn2(J2D_TRACE_INFO, "Exit PresentedHandler: drawable(%d) %d refs",
                                   [drawable drawableID], [drawable retainCount]);
                }
            }];
        }

        self.ctx.syncCount++;
        if (false) {
            id<MTLCommandBuffer> renderBuffer =  [self.ctx createCommandBuffer];
            if (@available(macOS 10.14, *)) {
                [renderBuffer encodeWaitForEvent:self.ctx.syncEvent value:self.ctx.syncCount];
            }
        }

        id<MTLBlitCommandEncoder> blitEncoder = [commandBuf blitCommandEncoder];

        [blitEncoder
                copyFromTexture:(*self.buffer) sourceSlice:0 sourceLevel:0
                sourceOrigin:MTLOriginMake(src_x, src_y, 0)
                sourceSize:MTLSizeMake(src_w, src_h, 1)
                toTexture:mtlDrawable.texture destinationSlice:0 destinationLevel:0
                destinationOrigin:MTLOriginMake(0, 0, 0)];
        [blitEncoder endEncoding];

        if (false) {
            if (@available(macOS 10.14, *)) {
                [commandBuf encodeSignalEvent:self.ctx.syncEvent value:self.ctx.syncCount];
            }
        }

        if (!EXPLICIT_PRESENT) {
            if (isDisplaySyncEnabled()) {
                [commandBuf presentDrawable:mtlDrawable];
            } else {
                if (@available(macOS 10.15.4, *)) {
                    [commandBuf presentDrawable:mtlDrawable afterMinimumDuration:self.avgBlitFrameTime];
                } else {
                    [commandBuf presentDrawable:mtlDrawable];
                }
            }
        }

        if (false) {
            [self retain];
            [commandBuf addScheduledHandler:^(id <MTLCommandBuffer> commandbuf) {
                if (isTraceDisplayEnabled()) {
                    J2dRlsTraceLn8(J2D_TRACE_INFO,
                                   "[%ld] blitTexture: ScheduledHandler layer[%p] (paint = %d painted = %d draw = %d disp = %d - redraw = %d) (nextDrawableCount = %d)",
                                   rqCurrentTimeMicroSeconds(), self, self.paintCount, self.paintedCount,
                                   self.drawCount,
                                   self.displayedCount, self.redrawCount, self.nextDrawableCount);
                }
                [self release];
                if (TRACE_DRAWABLE_REFS) {
                    J2dRlsTraceLn2(J2D_TRACE_INFO, "Exit ScheduledHandler: drawable(%d) %d refs",
                                   [mtlDrawable drawableID], [mtlDrawable retainCount]);
                }
            }];
        }

        [self retain];
        [commandBuf addCompletedHandler:^(id <MTLCommandBuffer> commandbuf) {
            const CFTimeInterval completedTime = CACurrentMediaTime();

            self.drawCount = paintVersion; // NOT protected by texture barrier (hazard tracking)

            const NSTimeInterval gpuTime = (commandbuf.GPUEndTime - commandbuf.GPUStartTime);
            self.avgBlitFrameTime = MTLLayer_smoothAverage(self.avgBlitFrameTime, gpuTime);

            const CFTimeInterval cmdDelay = (completedTime - commitTime);

            if (isTraceDisplayEnabled()) {
                J2dRlsTraceLn9(J2D_TRACE_INFO, "[%ld] blitTexture: CompletedHandler layer[%p] (paint = %d painted = %d DRAW = %d disp = %d - redraw = %d) (nextDrawableCount = %d) = %lf ms",
                               rqCurrentTimeMicroSeconds(), self, self.paintCount, self.paintedCount, self.drawCount, self.displayedCount, self.redrawCount, self.nextDrawableCount, gpuTime * 1000.0);
                J2dRlsTraceLn5(J2D_TRACE_INFO, "[%ld] blitTexture: CompletedHandler layer[%p]: gpuTime:  %lf ms - %lf ms / completedDelay: %lf ms",
                               rqCurrentTimeMicroSeconds(), self, gpuTime * 1000.0, self.avgBlitFrameTime * 1000.0, cmdDelay * 1000.0);
            }

            if (EXPLICIT_PRESENT) {
                if (false) {
                    // reset layer drawable:
                    id<CAMetalDrawable> mtlDrawableNew = [self getCurrentDrawable: YES];
                    // retain more:
                    // [mtlDrawableNew retain];
                }

                if (self.paintedCount > paintVersion) {
                    if (isTraceDisplayEnabled()) {
                        J2dRlsTraceLn7(J2D_TRACE_INFO, "[%ld] blitTexture: CompletedHandler layer[%p] (paint = %d painted = %d draw = %d disp = %d - redraw = %d) skip present (new version)",
                                       rqCurrentTimeMicroSeconds(), self, self.paintCount, self.paintedCount,
                                       self.drawCount, self.displayedCount, self.redrawCount);
                    }
                } else {
                    // present this drawable:
                    if (true) {
                        // direct invoke (do not work with presentsWithTransaction):
                        [mtlDrawable present];
                    } else {
                        [mtlDrawable performSelectorOnMainThread:@selector(present) withObject:nil waitUntilDone:NO];
                    }
                    if (isTraceDisplayEnabled()) {
                        J2dRlsTraceLn3(J2D_TRACE_INFO, "[%ld] blitTexture: CompletedHandler layer[%p]: drawable(%d) Present",
                                       rqCurrentTimeMicroSeconds(), self, [mtlDrawable drawableID]);
                    }
                }
            }
            // Only once drawable is presented ?
            self.nextDrawableCount--;
            [self release];

            if (TRACE_DRAWABLE_REFS) {
                J2dRlsTraceLn2(J2D_TRACE_INFO, "Exit CompletedHandler: drawable(%d) %d refs",
                               [mtlDrawable drawableID], [mtlDrawable retainCount]);
            }
        }];

        [commandBuf commit];

        if (TRACE_DRAWABLE_REFS) {
            J2dRlsTraceLn2(J2D_TRACE_INFO, "Exit blitTexture: drawable(%d) %d refs",
                           [mtlDrawable drawableID], [mtlDrawable retainCount]);
        }
    }
}

- (void) dealloc {
    JNIEnv *env = [ThreadUtilities getJNIEnvUncached];
    (*env)->DeleteWeakGlobalRef(env, self.javaLayer);
    self.javaLayer = nil;
    [self stopRedraw:YES];
    self.buffer = NULL;
    self.currentDrawable = nil;
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

    if (isTraceDisplayEnabled()) {
        J2dRlsTraceLn(J2D_TRACE_INFO, "blitCallback: invoke MTLLayer.drawInMTLContext");
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
    if (isDisplaySyncEnabled()) {
        if (false) {
            NSThread* thread = [NSThread currentThread];
            J2dRlsTraceLn4(J2D_TRACE_INFO, "MTLLayer_startRedrawIfNeeded: thread (%p main=%d) layer[%p] (redraw = %d)",
                           thread, [thread isMainThread], self, self.redrawCount);
        }
        // Should check paint version ?
        if (self.paintCount > self.drawCount) {
            if (self.redrawCount == 0) {
                if (isTraceDisplayEnabled()) {
                    J2dRlsTraceLn7(J2D_TRACE_INFO,
                                   "[%ld] startRedrawIfNeeded: layer[%p] (paint = %d painted = %d draw = %d disp = %d - redraw = %d) => startRedraw()",
                                   rqCurrentTimeMicroSeconds(), self, self.paintCount, self.paintedCount,
                                   self.drawCount, self.displayedCount, self.redrawCount);
                }
                [self startRedraw];
            } else {
                if (isTraceDisplayEnabled()) {
                    J2dRlsTraceLn7(J2D_TRACE_INFO,
                                   "[%ld] startRedrawIfNeeded: layer[%p] (paint = %d painted = %d draw = %d disp = %d - redraw = %d) => setNeedsDisplay()",
                                   rqCurrentTimeMicroSeconds(), self, self.paintCount, self.paintedCount,
                                   self.drawCount, self.displayedCount, self.redrawCount);
                }
                [self setNeedsDisplay];
            }
        }
    }
}

- (void)startRedraw {
    if (isDisplaySyncEnabled()) {
        if (false) {
            NSThread* thread = [NSThread currentThread];
            J2dRlsTraceLn2(J2D_TRACE_INFO, "MTLLayer_startRedraw: thread (%p main=%d)",
                           thread, [thread isMainThread])
        }
        if (self.ctx != nil) {
            [self.ctx performSelectorOnMainThread:@selector(startRedraw:) withObject:self waitUntilDone:NO];
        }
    } else {
        [self performSelectorOnMainThread:@selector(setNeedsDisplay) withObject:nil waitUntilDone:NO];
    }
}

- (void)stopRedraw:(BOOL)force {
    if (self.ctx != nil && isDisplaySyncEnabled()) {
        if (false) {
            NSThread *thread = [NSThread currentThread];
            J2dRlsTraceLn2(J2D_TRACE_INFO, "MTLLayer_stopRedraw: thread (%p main=%d)",
                           thread, [thread isMainThread])
        }
        if (force) {
            self.redrawCount = 0;
        }
        [self.ctx performSelectorOnMainThread:@selector(stopRedraw:) withObject:self waitUntilDone:NO];
    }
}

- (void)commitCommandBuffer:(MTLContext*)mtlc wait:(BOOL)waitUntilCompleted display:(BOOL)updateDisplay {
    MTLCommandBufferWrapper * cbwrapper = [mtlc pullCommandBufferWrapper];

    if (cbwrapper != nil) {
        id <MTLCommandBuffer> commandbuf = [cbwrapper getCommandBuffer];
        if (!updateDisplay) {
            [commandbuf addCompletedHandler:^(id <MTLCommandBuffer> commandbuf) {
                [cbwrapper release];
            }];
        } else if (isDisplaySyncEnabled()) {
            if (isTraceDisplayEnabled()) {
                J2dRlsTraceLn6(J2D_TRACE_INFO, "[%ld] commitCommandBuffer(wait=%d display: %d) on layer[%p] (paint = %d redraw = %d)",
                               rqCurrentTimeMicroSeconds(), waitUntilCompleted, updateDisplay,
                               self, self.paintCount, self.redrawCount);
            }
            const CFTimeInterval paintTime = CACurrentMediaTime();
            const int paintCount = ++self.paintCount;

            if (isTraceDisplayEnabled()) {
                J2dRlsTraceLn8(J2D_TRACE_INFO, "[%ld] commitCommandBuffer: commit(%p) on layer[%p] (PAINT = %d painted = %d draw = %d disp = %d - redraw = %d)",
                               rqCurrentTimeMicroSeconds(), commandbuf, self, self.paintCount, self.paintedCount, self.drawCount, self.displayedCount, self.redrawCount);
            }

            [self retain];
            [commandbuf addCompletedHandler:^(id <MTLCommandBuffer> commandBuf) {
                const CFTimeInterval paintedTime = CACurrentMediaTime();
                const CFTimeInterval cmdDelay = (paintedTime - paintTime);

                self.paintedCount = paintCount;

                const NSTimeInterval gpuTime = (commandbuf.GPUEndTime - commandbuf.GPUStartTime);
                self.avgRenderFrameTime = MTLLayer_smoothAverage(self.avgRenderFrameTime, gpuTime);

                if (isTraceDisplayEnabled()) {
                    J2dRlsTraceLn9(J2D_TRACE_INFO, "[%ld] commitCommandBuffer: CompletedHandler(%p) on layer[%p] (paint = %d PAINTED = %d draw = %d disp = %d - redraw = %d) = %lf ms",
                                   rqCurrentTimeMicroSeconds(), commandBuf, self, self.paintCount, self.paintedCount, self.drawCount, self.displayedCount, self.redrawCount, gpuTime * 1000.0);
                    J2dRlsTraceLn6(J2D_TRACE_INFO, "[%ld] commitCommandBuffer: CompletedHandler(%p) on layer[%p]: gpuTime:  %lf ms - %lf ms / paintDelay: %lf ms",
                                   rqCurrentTimeMicroSeconds(), commandBuf, self, gpuTime * 1000.0, self.avgRenderFrameTime * 1000.0, cmdDelay * 1000.0);
                }
                [cbwrapper release];

                // if layer needs redraw to show new content:
                [self performSelectorOnMainThread:@selector(startRedrawIfNeeded) withObject:nil waitUntilDone:NO];
                [self release];
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
        // TODO: fix now or later ?
        if (false && isDisplaySyncEnabled()) {
            [self startRedraw];
        }

        if (waitUntilCompleted) {
           [commandbuf waitUntilCompleted];
           // TODO: wait blit texture too using lock ?
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

        if (!isColorMatchingEnabled() && (layer.colorspace != nil)) {
            J2dRlsTraceLn1(J2D_TRACE_VERBOSE,
                          "Java_sun_java2d_metal_MTLLayer_validate: disable color matching (colorspace was '%s')",
                           [(NSString *)CGColorSpaceCopyName(layer.colorspace) UTF8String]);
            // disable color matching:
            layer.colorspace = nil;
        }

        if (isTraceDisplayEnabled()) {
            J2dRlsTraceLn2(J2D_TRACE_INFO, "layer[%p] buffer=%p", layer, layer.buffer);
        }
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
