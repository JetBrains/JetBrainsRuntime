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

#include <stdlib.h>
#import <ThreadUtilities.h>

#include "sun_java2d_SunGraphics2D.h"

#include "jlong.h"
#import "MTLContext.h"
#include "MTLRenderQueue.h"
#import "MTLSamplerManager.h"
#import "MTLStencilManager.h"

// Keep displaylink thread alive for KEEP_ALIVE_COUNT more times
// to avoid multiple start/stop displaylink operations. It speeds up
// scenarios with multiple subsequent updates.
#define KEEP_ALIVE_COUNT 4

#define TO_MS(x) (1000.0 * (x))
#define TO_FPS(x) (1.0 / (x))

// Min interval(s) between 2 display link callbacks as the appkit (Main)
// thread may be 'blocked' (waiting), then callbacks will be invoked in batches.
// ~ 0.250ms (shorter than best monitor frame rate = 4 khz)
#define KEEP_ALIVE_MIN_INTERVAL (0.250 / 1000.0)

// Amount of blit operations per update to make sure that everything is
// rendered into the window drawable. It does not slow things down as we
// use separate command queue for blitting.
#define REDRAW_COUNT 1

extern jboolean MTLSD_InitMTLWindow(JNIEnv *env, MTLSDOps *mtlsdo);
extern BOOL isDisplaySyncEnabled();
extern BOOL MTLLayer_isExtraRedrawEnabled();
extern int getBPPFromModeString(CFStringRef mode);

#define STATS_CVLINK        0

#define TRACE_PWM_NOTIF     0

#define TRACE_CVLINK        0
#define TRACE_CVLINK_WARN   0
#define TRACE_CVLINK_DEBUG  0

#define TRACE_DISPLAY       0

#define CHECK_CVLINK(op, source, dl, cmd)                                               \
{                                                                                       \
    CVReturn ret = (CVReturn) (cmd);                                                    \
    if (ret != kCVReturnSuccess) {                                                      \
        J2dTraceImpl(J2D_TRACE_ERROR, JNI_TRUE, "CVDisplayLink[%s - %s][%p] Error: %d", \
                     op, (source != nil) ? source : "", dl, ret);                       \
    } else if (TRACE_CVLINK) {                                                          \
        J2dTraceImpl(J2D_TRACE_VERBOSE, JNI_TRUE, "CVDisplayLink[%s - %s][%p]: OK",     \
                     op, (source != nil) ? source : "", dl);                            \
    }                                                                                   \
}

boolean_t mtlc_IsDisplayReallyActive(CGDirectDisplayID displayID) {
    return CGDisplayIsActive(displayID) & !CGDisplayIsAsleep(displayID) && CGDisplayIsOnline(displayID);
}

/* 60 fps typically => exponential smoothing on 0.5s */
static const NSTimeInterval EXP_AVG_WEIGHT = (1.0 / 30.0);
static const NSTimeInterval EXP_INV_WEIGHT = (1.0 - EXP_AVG_WEIGHT);

static struct TxtVertex verts[PGRAM_VERTEX_COUNT] = {
        {{-1.0, 1.0}, {0.0, 0.0}},
        {{1.0, 1.0}, {1.0, 0.0}},
        {{1.0, -1.0}, {1.0, 1.0}},
        {{1.0, -1.0}, {1.0, 1.0}},
        {{-1.0, -1.0}, {0.0, 1.0}},
        {{-1.0, 1.0}, {0.0, 0.0}}
};

typedef struct {
    jint                displayID;
    CVDisplayLinkRef    displayLink;
    MTLContext*         mtlc;
    jint                redrawCount;

    jint                avgDisplayLinkSamples;
    CFTimeInterval      lastRedrawTime;
    CFTimeInterval      lastDisplayLinkTime;
    CFTimeInterval      avgDisplayLinkTime;
    CFTimeInterval      lastStatTime;

} MTLDisplayLinkState;

@implementation MTLCommandBufferWrapper {
    id<MTLCommandBuffer> _commandBuffer;
    NSMutableArray * _pooledTextures;
    NSLock* _lock;
}

- (id) initWithCommandBuffer:(id<MTLCommandBuffer>)cmdBuf {
    self = [super init];
    if (self) {
        _commandBuffer = [cmdBuf retain];
        _pooledTextures = [[NSMutableArray alloc] init];
        _lock = [[NSLock alloc] init];
    }
    return self;
}

- (id<MTLCommandBuffer>) getCommandBuffer {
    return _commandBuffer;
}

- (void) onComplete { // invoked from completion handler in some pooled thread
    [_lock lock];
    @try {
        for (int c = 0; c < [_pooledTextures count]; ++c)
            [[_pooledTextures objectAtIndex:c] releaseTexture];
        [_pooledTextures removeAllObjects];
    } @finally {
        [_lock unlock];
    }

}

- (void) registerPooledTexture:(MTLPooledTextureHandle *)handle {
    [_lock lock];
    @try {
        [_pooledTextures addObject:handle];
    } @finally {
        [_lock unlock];
    }
}

- (void) dealloc {
    [self onComplete];

    [_pooledTextures release];
    _pooledTextures = nil;

    [_commandBuffer release];
    _commandBuffer = nil;

    [_lock release];
    _lock = nil;
    [super dealloc];
}

@end


@implementation MTLContext {
    MTLCommandBufferWrapper * _commandBufferWrapper;
    NSMutableSet*      _layers;

    MTLComposite *     _composite;
    MTLPaint *         _paint;
    MTLTransform *     _transform;
    MTLTransform *     _tempTransform;
    MTLClip *          _clip;

    NSObject*          _bufImgOp; // TODO: pass as parameter of IsoBlit
    EncoderManager *   _encoderManager;
    MTLSamplerManager * _samplerManager;
    MTLStencilManager * _stencilManager;
}

@synthesize textureFunction,
            vertexCacheEnabled, aaEnabled, useMaskColor,
            device, shadersLib, pipelineStateStorage,
            commandQueue, blitCommandQueue, vertexBuffer,
            texturePool, paint=_paint, encoderManager=_encoderManager,
            samplerManager=_samplerManager, stencilManager=_stencilManager;

extern void initSamplers(id<MTLDevice> device);

+ (void)mtlc_systemOrScreenWillSleep:(NSNotification*)notification {
    if (TRACE_PWM_NOTIF) {
        NSLog(@"MTLContext_systemOrScreenWillSleep[%@]", [notification name]);
    }
    if (isDisplaySyncEnabled()) {
        [ThreadUtilities performOnMainThreadNowOrLater:NO // critical
                                         block:^(){

            for (MTLContext *mtlc in [MTLContext.contextStore allValues]) {
                const NSArray<NSNumber*> *displayIDs = [mtlc getDisplayLinkDisplayIds]; // old ids
                if (TRACE_PWM_NOTIF) {
                    NSLog(@"MTLContext_systemOrScreenWillSleep: ctx=%p (%d displayLinks)",
                          mtlc, (int) displayIDs.count);
                }
                for (NSNumber* displayIDVal in displayIDs) {
                    const jint displayID = [displayIDVal intValue];
                    const BOOL active = mtlc_IsDisplayReallyActive(displayID);

                    if (TRACE_PWM_NOTIF) {
                        NSLog(@"MTLContext_systemOrScreenWillSleep: displayId=%d active=%d", displayID, active);
                    }
                    if (TRACE_DISPLAY) {
                        [MTLContext dumpDisplayInfo:displayID];
                    }
                    if ((notification.name == NSWorkspaceWillSleepNotification)|| !active) {
                         [mtlc destroyDisplayLink:displayID];
                    }
                }
            }
        }];
    }
}

+ (void)mtlc_systemOrScreenDidWake:(NSNotification*)notification {
    if (TRACE_PWM_NOTIF) {
        NSLog(@"MTLContext_systemOrScreenDidWake[%@]", [notification name]);
    }
    if (isDisplaySyncEnabled()) {
        [ThreadUtilities performOnMainThreadNowOrLater:NO // critical
                                         block:^(){

            for (MTLContext *mtlc in [MTLContext.contextStore allValues]) {
                const NSArray<NSNumber*>* displayIDs = [mtlc getDisplayLinkDisplayIds]; // old ids
                if (TRACE_PWM_NOTIF) {
                    NSLog(@"MTLContext_systemOrScreenDidWake: ctx=%p (%d displayLinks)",
                          mtlc, (int)displayIDs.count);
                }
                for (NSNumber* displayIDVal in displayIDs) {
                    const jint displayID = [displayIDVal intValue];
                    const BOOL active = mtlc_IsDisplayReallyActive(displayID);

                    if (TRACE_PWM_NOTIF) {
                        NSLog(@"MTLContext_systemOrScreenDidWake: displayId=%d active=%d", displayID, active);
                    }
                    if (TRACE_DISPLAY) {
                        [MTLContext dumpDisplayInfo:displayID];
                    }
                    if (active) {
                        // (if needed will start a new display link thread):
                        [mtlc createDisplayLinkIfAbsent:displayID];
                    }
                }
            }
        }];
    }
}

+ (void)registerForSystemAndScreenNotifications {
    static BOOL notificationRegistered = false;
    if (!notificationRegistered) {
        notificationRegistered = true;

        NSNotificationCenter *ctr = [[NSWorkspace sharedWorkspace] notificationCenter];
        Class clz = [MTLContext class];

        [ctr addObserver:clz selector:@selector(mtlc_systemOrScreenWillSleep:) name:NSWorkspaceWillSleepNotification object:nil];
        [ctr addObserver:clz selector:@selector(mtlc_systemOrScreenDidWake:) name:NSWorkspaceDidWakeNotification object:nil];

        // NSWorkspaceScreensDidWakeNotification is first sent:
        [ctr addObserver:clz selector:@selector(mtlc_systemOrScreenWillSleep:) name:NSWorkspaceScreensDidSleepNotification object:nil];
        [ctr addObserver:clz selector:@selector(mtlc_systemOrScreenDidWake:) name:NSWorkspaceScreensDidWakeNotification object:nil];
    }
}

+ (NSMutableDictionary*) contextStore {
    static NSMutableDictionary<id<NSCopying>, MTLContext*> *_contextStore;
    static dispatch_once_t oncePredicate;

    dispatch_once(&oncePredicate, ^{
        _contextStore = [[NSMutableDictionary alloc] init];

        [MTLContext registerForSystemAndScreenNotifications];
    });
    return _contextStore;
}

+ (MTLContext*) createContextWithDeviceIfAbsent:(jint)displayID shadersLib:(NSString*)mtlShadersLib {
    // Initialization code here.
    // note: the device reference is NS_RETURNS_RETAINED, should be released by the caller:
    bool shouldReleaseDevice = true;

    id<MTLDevice> device = CGDirectDisplayCopyCurrentMetalDevice(displayID);
    if (device == nil) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLContext_createContextWithDeviceIfAbsent(): Cannot create device from "
                                       "displayID=%d", displayID);
        // Fallback to the default metal device for main display
        jint mainDisplayID = CGMainDisplayID();
        if (displayID == mainDisplayID) {
            device = MTLCreateSystemDefaultDevice();
        }
        if (device == nil) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLContext_createContextWithDeviceIfAbsent(): Cannot fallback to default "
                                           "metal device");
            return nil;
        }
    }

    id<NSCopying> deviceID = nil;
    if (@available(macOS 10.13, *)) {
        deviceID = @(device.registryID);
    } else {
        deviceID = device.name;
    }
    MTLContext* mtlc = MTLContext.contextStore[deviceID];
    if (mtlc == nil) {
        mtlc = [[MTLContext alloc] initWithDevice:device shadersLib:mtlShadersLib];
        if (mtlc != nil) {
            shouldReleaseDevice = false;
            // transfer ownership (objc ref):
            MTLContext.contextStore[deviceID] = mtlc;
            [mtlc release];
            J2dRlsTraceLn(J2D_TRACE_INFO,
                          "MTLContext_createContextWithDeviceIfAbsent: new context(%p) for display=%d device=\"%s\" "
                          "retainCount=%d", mtlc, displayID, [mtlc.device.name UTF8String],
                          mtlc.retainCount);
        }
    } else {
        if (![mtlc.shadersLib isEqualToString:mtlShadersLib]) {
            J2dRlsTraceLn(J2D_TRACE_ERROR,
                          "MTLContext_createContextWithDeviceIfAbsent: cannot reuse context(%p) for display=%d "
                          "device=\"%s\", shaders lib has been changed", mtlc, displayID, [mtlc.device.name UTF8String]);
            [device release];
            return nil;
        }
        [mtlc retain];
        J2dRlsTraceLn(J2D_TRACE_INFO,
                      "MTLContext_createContextWithDeviceIfAbsent: reuse context(%p) for display=%d device=\"%s\" "
                      "retainCount=%d", mtlc, displayID, [mtlc.device.name UTF8String],
                      mtlc.retainCount);
    }
    if (shouldReleaseDevice) {
        [device release];
    }
    // (will start a new display link thread if needed):
    [mtlc createDisplayLinkIfAbsent:displayID];
    return mtlc;
}

+ (void) releaseContext:(MTLContext*) mtlc {
    id<NSCopying> deviceID = nil;
    if (@available(macOS 10.13, *)) {
        deviceID = @(mtlc.device.registryID);
    } else {
        deviceID = mtlc.device.name;
    }
    MTLContext* ctx = MTLContext.contextStore[deviceID];
    if (mtlc == ctx) {
        if (mtlc.retainCount > 1) {
            [mtlc release];
            J2dRlsTraceLn(J2D_TRACE_INFO, "MTLContext_releaseContext: release context(%p) retainCount=%d", mtlc, mtlc.retainCount);
        } else {
            // explicit halt redraw to shutdown CVDisplayLink threads before dealloc():
            [mtlc haltRedraw];
            // remove ownership (objc ref):
            [MTLContext.contextStore removeObjectForKey:deviceID];
            J2dRlsTraceLn(J2D_TRACE_INFO, "MTLContext_releaseContext: dealloc context(%p)", mtlc);
        }
    } else {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLContext_releaseContext: cannot release context(%p) != context store(%p)",
                      mtlc, ctx);
    }
}

- (id)initWithDevice:(id<MTLDevice>)mtlDevice shadersLib:(NSString*)mtlShadersLib {
    self = [super init];
    if (self) {
        device = mtlDevice;
        shadersLib = [[NSString alloc] initWithString:mtlShadersLib];
        pipelineStateStorage = [[MTLPipelineStatesStorage alloc] initWithDevice:device shaderLibPath:shadersLib];
        if (pipelineStateStorage == nil) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLContext.initWithDevice(): Failed to initialize MTLPipelineStatesStorage.");
            return nil;
        }

        texturePool = [[MTLTexturePool alloc] initWithDevice:device];

        vertexBuffer = [device newBufferWithBytes:verts
                                           length:sizeof(verts)
                                          options:MTLResourceCPUCacheModeDefaultCache];

        _encoderManager = [[EncoderManager alloc] init];
        [_encoderManager setContext:self];
        _samplerManager = [[MTLSamplerManager alloc] initWithDevice:device];
        _stencilManager = [[MTLStencilManager alloc] initWithDevice:device];
        _composite = [[MTLComposite alloc] init];
        _paint = [[MTLPaint alloc] init];
        _transform = [[MTLTransform alloc] init];
        _clip = [[MTLClip alloc] init];
        _bufImgOp = nil;

        _commandBufferWrapper = nil;

        // Create command queue
        commandQueue = [device newCommandQueue];
        blitCommandQueue = [device newCommandQueue];

        _tempTransform = [[MTLTransform alloc] init];

        if (isDisplaySyncEnabled()) {
            _displayLinkStates = [[NSMutableDictionary alloc] init];
            _layers = [[NSMutableSet alloc] init];
        } else {
            _displayLinkStates = nil;
            _layers = nil;
        }
        _glyphCacheLCD = [[MTLGlyphCache alloc] initWithContext:self];
        _glyphCacheAA = [[MTLGlyphCache alloc] initWithContext:self];
    }
    return self;
}

+ (void)dumpDisplayInfo: (jint)displayID {
    // Returns a Boolean value indicating whether a display is active.
    jint displayIsActive = CGDisplayIsActive(displayID);

    // Returns a Boolean value indicating whether a display is always in a mirroring set.
    jint displayIsalwaysInMirrorSet = CGDisplayIsAlwaysInMirrorSet(displayID);

    // Returns a Boolean value indicating whether a display is sleeping (and is therefore not drawable).
    jint displayIsAsleep = CGDisplayIsAsleep(displayID);

    // Returns a Boolean value indicating whether a display is built-in, such as the internal display in portable systems.
    jint displayIsBuiltin = CGDisplayIsBuiltin(displayID);

    // Returns a Boolean value indicating whether a display is in a mirroring set.
    jint displayIsInMirrorSet = CGDisplayIsInMirrorSet(displayID);

    // Returns a Boolean value indicating whether a display is in a hardware mirroring set.
    jint displayIsInHWMirrorSet = CGDisplayIsInHWMirrorSet(displayID);

    // Returns a Boolean value indicating whether a display is the main display.
    jint displayIsMain = CGDisplayIsMain(displayID);

    // Returns a Boolean value indicating whether a display is connected or online.
    jint displayIsOnline = CGDisplayIsOnline(displayID);

    // Returns a Boolean value indicating whether a display is running in a stereo graphics mode.
    jint displayIsStereo = CGDisplayIsStereo(displayID);

    // For a secondary display in a mirroring set, returns the primary display.
    CGDirectDisplayID displayMirrorsDisplay = CGDisplayMirrorsDisplay(displayID);

    // Returns the primary display in a hardware mirroring set.
    CGDirectDisplayID displayPrimaryDisplay = CGDisplayPrimaryDisplay(displayID);

    // Returns the width and height of a display in millimeters.
    CGSize size = CGDisplayScreenSize(displayID);

    NSLog(@"CGDisplay[%d]{\n"
           "displayIsActive=%d\n"
           "displayIsalwaysInMirrorSet=%d\n"
           "displayIsAsleep=%d\n"
           "displayIsBuiltin=%d\n"
           "displayIsInMirrorSet=%d\n"
           "displayIsInHWMirrorSet=%d\n"
           "displayIsMain=%d\n"
           "displayIsOnline=%d\n"
           "displayIsStereo=%d\n"
           "displayMirrorsDisplay=%d\n"
           "displayPrimaryDisplay=%d\n"
           "displayScreenSizey=[%.1lf %.1lf]\n",
           displayID,
           displayIsActive,
           displayIsalwaysInMirrorSet,
           displayIsAsleep,
           displayIsBuiltin,
           displayIsInMirrorSet,
           displayIsInHWMirrorSet,
           displayIsMain,
           displayIsOnline,
           displayIsStereo,
           displayMirrorsDisplay,
           displayPrimaryDisplay,
           size.width, size.height
    );

    // CGDisplayCopyDisplayMode can return NULL if displayID is invalid
    CGDisplayModeRef mode = CGDisplayCopyDisplayMode(displayID);
    if (mode) {
        // Getting Information About a Display Mode
        jint h = -1, w = -1, bpp = -1;
        jdouble refreshRate = 0.0;

        // Returns the width of the specified display mode.
        w = CGDisplayModeGetWidth(mode);

        // Returns the height of the specified display mode.
        h = CGDisplayModeGetHeight(mode);

        // Returns the pixel encoding of the specified display mode.
        // Deprecated
        CFStringRef currentBPP = CGDisplayModeCopyPixelEncoding(mode);
        bpp = getBPPFromModeString(currentBPP);
        CFRelease(currentBPP);

        // Returns the refresh rate of the specified display mode.
        refreshRate = CGDisplayModeGetRefreshRate(mode);

        NSLog(@"CGDisplayMode[%d]: w=%d, h=%d, bpp=%d, freq=%.2lf hz",
              displayID, w, h, bpp, refreshRate);

        CGDisplayModeRelease(mode);
    }
}

- (NSArray<NSNumber*>*)getDisplayLinkDisplayIds {
    return [_displayLinkStates allKeys];
}

- (void)createDisplayLinkIfAbsent: (jint)displayID {
    if (isDisplaySyncEnabled()) {
        MTLDisplayLinkState *dlState = [self getDisplayLinkState:displayID];
        if ((dlState != nil) && (dlState->displayLink != nil)) {
            return;
        }
        if (TRACE_DISPLAY) {
            [MTLContext dumpDisplayInfo:displayID];
        }
        CVDisplayLinkRef _displayLink;
        if (TRACE_CVLINK) {
            J2dRlsTraceLn(J2D_TRACE_VERBOSE, "MTLContext_createDisplayLinkIfAbsent: "
                                             "ctx=%p displayID=%d", self, displayID);
        }
        CHECK_CVLINK("CreateWithCGDisplay", nil, &_displayLink,
                     CVDisplayLinkCreateWithCGDisplay(displayID, &_displayLink));

        if (_displayLink == nil) {
            J2dRlsTraceLn(J2D_TRACE_ERROR,
                          "MTLContext_createDisplayLinkIfAbsent: Failed to initialize CVDisplayLink.");
        } else {
            J2dRlsTraceLn(J2D_TRACE_INFO, "MTLContext_destroyDisplayLinkMTLContext_createDisplayLinkIfAbsent["
                                          "ctx=%p displayID=%d] displayLink=%p",
                                          self, displayID, _displayLink);
            bool isNewDisplayLink = false;
            if (dlState == nil) {
                dlState = malloc(sizeof(MTLDisplayLinkState));
                isNewDisplayLink = true;
            }
            // update:
            dlState->displayID = displayID;
            dlState->displayLink = _displayLink;
            dlState->mtlc = self;

            dlState->redrawCount = 0;
            dlState->avgDisplayLinkSamples = 0;
            dlState->lastRedrawTime = 0.0;
            dlState->lastDisplayLinkTime = 0.0;
            dlState->avgDisplayLinkTime = 0.0;
            dlState->lastStatTime = 0.0;

            if (isNewDisplayLink) {
                // publish fully initialized object:
                _displayLinkStates[@(displayID)] = [NSValue valueWithPointer:dlState];
            }

            CHECK_CVLINK("SetOutputCallback", nil, &_displayLink,
                         CVDisplayLinkSetOutputCallback(_displayLink, &mtlDisplayLinkCallback,
                                                        (__bridge MTLDisplayLinkState*) dlState));
        }
    }
}

- (NSValue*)getDisplayLinkState: (jint)displayID {
    if (_displayLinkStates == nil) {
        if (TRACE_CVLINK_WARN) {
            J2dRlsTraceLn(J2D_TRACE_VERBOSE, "MTLContext_getDisplayLinkState[ctx=%p displayID=%d]: "
                                             "displayLinkStates is nil!", self, displayID);
        }
        return nil;
    }
    NSValue* dlStateVal = _displayLinkStates[@(displayID)];
    if (dlStateVal == nil) {
        if (TRACE_CVLINK_WARN) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLContext_getDisplayLinkState[ctx=%p displayID=%d]: "
                                           "dlState is nil!", self, displayID);
        }
        return nil;
    }
    return [dlStateVal pointerValue];
}

- (void)destroyDisplayLink: (jint)displayID {
    if (isDisplaySyncEnabled()) {
        MTLDisplayLinkState *dlState = [self getDisplayLinkState:displayID];
        if (dlState == nil) {
            return;
        }

        CVDisplayLinkRef _displayLink = dlState->displayLink;
        if (_displayLink == nil) {
            return;
        }
        if (TRACE_CVLINK) {
            J2dRlsTraceLn(J2D_TRACE_VERBOSE, "MTLContext_destroyDisplayLink: "
                                             "ctx=%p, displayID=%d", self, displayID);
        }
        if (CVDisplayLinkIsRunning(_displayLink)) {
            CHECK_CVLINK("Stop", "destroyDisplayLink", &_displayLink,
                         CVDisplayLinkStop(_displayLink));
        }

        J2dRlsTraceLn(J2D_TRACE_INFO, "MTLContext_destroyDisplayLink["
                                      "ctx=%p displayID=%d] displayLink=%p",
                                      self, displayID, _displayLink);
        // Release display link thread:
        CVDisplayLinkRelease(_displayLink);
        dlState->displayLink = nil;
    }
}

- (void)handleDisplayLink: (BOOL)enabled displayID:(jint)displayID source:(const char*)src {
    MTLDisplayLinkState *dlState = [self getDisplayLinkState:displayID];
    if (dlState == nil) {
        return;
    }
    CVDisplayLinkRef _displayLink = dlState->displayLink;
    if (_displayLink == nil) {
        if (TRACE_CVLINK) {
            J2dRlsTraceLn(J2D_TRACE_VERBOSE, "MTLContext_handleDisplayLink[%s]: "
                                             "displayLink is nil (disabled).", src);
        }
        return;
    }
    if (enabled) {
        if (!CVDisplayLinkIsRunning(_displayLink)) {
            CHECK_CVLINK("Start", src, &_displayLink,
                         CVDisplayLinkStart(_displayLink));
            if (TRACE_CVLINK) {
                J2dRlsTraceLn(J2D_TRACE_VERBOSE, "MTLContext_CVDisplayLinkStart[%s]: "
                                                 "ctx=%p", src, self);
            }
        }
    } else {
        if (CVDisplayLinkIsRunning(_displayLink)) {
            CHECK_CVLINK("Stop", src, &_displayLink,
                         CVDisplayLinkStop(_displayLink));
            if (TRACE_CVLINK) {
                J2dRlsTraceLn(J2D_TRACE_VERBOSE, "MTLContext_CVDisplayLinkStop[%s]: "
                                                 "ctx=%p", src, self);
            }
        }
    }
}

- (void)dealloc {
    J2dTraceLn(J2D_TRACE_INFO, "MTLContext.dealloc");

    if (_displayLinkStates != nil) {
        [self haltRedraw];
        [_displayLinkStates release];
        _displayLinkStates = nil;
    }

    [shadersLib release];
    // TODO : Check that texturePool is completely released.
    // texturePool content is released in MTLCommandBufferWrapper.onComplete()
    //self.texturePool = nil;
    [_glyphCacheLCD release];
    [_glyphCacheAA release];

    self.vertexBuffer = nil;
    self.commandQueue = nil;
    self.blitCommandQueue = nil;
    self.pipelineStateStorage = nil;

    if (_encoderManager != nil) {
        [_encoderManager release];
        _encoderManager = nil;
    }

    if (_samplerManager != nil) {
        [_samplerManager release];
        _samplerManager = nil;
    }

    if (_stencilManager != nil) {
        [_stencilManager release];
        _stencilManager = nil;
    }

    if (_composite != nil) {
        [_composite release];
        _composite = nil;
    }

    if (_paint != nil) {
        [_paint release];
        _paint = nil;
    }

    if (_transform != nil) {
        [_transform release];
        _transform = nil;
    }

    if (_tempTransform != nil) {
        [_tempTransform release];
        _tempTransform = nil;
    }

    if (_clip != nil) {
        [_clip release];
        _clip = nil;
    }

    if (_layers != nil) {
        [_layers release];
        _layers = nil;
    }

    [super dealloc];
}

- (void) reset {
    J2dTraceLn(J2D_TRACE_VERBOSE, "MTLContext : reset");

    // Add code for context state reset here
}

 - (MTLCommandBufferWrapper *) getCommandBufferWrapper {
    if (_commandBufferWrapper == nil) {
        J2dTraceLn(J2D_TRACE_VERBOSE, "MTLContext : commandBuffer is nil!");
        // NOTE: Command queues are thread-safe and allow multiple outstanding command buffers to be encoded simultaneously.
        _commandBufferWrapper = [[MTLCommandBufferWrapper alloc] initWithCommandBuffer:[self.commandQueue commandBuffer]];// released in [layer blitTexture]
    }
    return _commandBufferWrapper;
}

- (MTLCommandBufferWrapper *) pullCommandBufferWrapper {
    MTLCommandBufferWrapper * result = _commandBufferWrapper;
    _commandBufferWrapper = nil;
    return result;
}

+ (MTLContext*) setSurfacesEnv:(JNIEnv*)env src:(jlong)pSrc dst:(jlong)pDst {
    BMTLSDOps *srcOps = (BMTLSDOps *)jlong_to_ptr(pSrc);
    BMTLSDOps *dstOps = (BMTLSDOps *)jlong_to_ptr(pDst);
    MTLContext *mtlc = NULL;

    if (srcOps == NULL || dstOps == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLContext_SetSurfaces: ops are null");
        return NULL;
    }

    J2dTraceLn(J2D_TRACE_VERBOSE,
               "MTLContext_SetSurfaces: bsrc=%p (tex=%p type=%d), bdst=%p (tex=%p type=%d)",
               srcOps, srcOps->pTexture, srcOps->drawableType,
               dstOps, dstOps->pTexture, dstOps->drawableType);

    if (dstOps->drawableType == MTLSD_TEXTURE) {
        J2dRlsTraceLn(J2D_TRACE_ERROR,
                      "MTLContext_SetSurfaces: texture cannot be used as destination");
        return NULL;
    }

    if (dstOps->drawableType == MTLSD_UNDEFINED) {
        // initialize the surface as an MTLSD_WINDOW
        if (!MTLSD_InitMTLWindow(env, dstOps)) {
            J2dRlsTraceLn(J2D_TRACE_ERROR,
                          "MTLContext_SetSurfaces: could not init MTL window");
            return NULL;
        }
    }

    // make the context current
    MTLSDOps *dstMTLOps = (MTLSDOps *)dstOps->privOps;
    mtlc = dstMTLOps->configInfo->context;

    if (mtlc == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR,
                      "MTLContext_SetSurfaces: could not make context current");
        return NULL;
    }

    return mtlc;
}

- (void)resetClip {
    J2dTraceLn(J2D_TRACE_INFO, "MTLContext.resetClip");
    [_clip reset];
}

- (void)setClipRectX1:(jint)x1 Y1:(jint)y1 X2:(jint)x2 Y2:(jint)y2 {
    J2dTraceLn(J2D_TRACE_INFO, "MTLContext.setClipRect: %d,%d - %d,%d", x1, y1, x2, y2);
    [_clip setClipRectX1:x1 Y1:y1 X2:x2 Y2:y2];
}

- (void)beginShapeClip:(BMTLSDOps *)dstOps {
    J2dTraceLn(J2D_TRACE_INFO, "MTLContext.beginShapeClip");
    [_clip beginShapeClip:dstOps context:self];

    // Store the current transform as we need to use identity transform
    // for clip spans rendering
    [_tempTransform copyFrom:_transform];
    [self resetTransform];
}

- (void)endShapeClip:(BMTLSDOps *)dstOps {
    J2dTraceLn(J2D_TRACE_INFO, "MTLContext.endShapeClip");
    [_clip endShapeClip:dstOps context:self];

    // Reset transform for further rendering
    [_transform copyFrom:_tempTransform];
}

- (void)resetComposite {
    J2dTraceLn(J2D_TRACE_VERBOSE, "MTLContext_ResetComposite");
    [_composite reset];
}

- (void)setAlphaCompositeRule:(jint)rule extraAlpha:(jfloat)extraAlpha
                        flags:(jint)flags {
    J2dTraceLn(J2D_TRACE_INFO,
               "MTLContext_SetAlphaComposite: rule=%d, extraAlpha=%1.2f, flags=%d",
               rule, extraAlpha, flags);

    [_composite setRule:rule extraAlpha:extraAlpha];
}

- (NSString*)getCompositeDescription {
    return [_composite getDescription];
}

- (NSString*)getPaintDescription {
    return [_paint getDescription];
}

- (void)setXorComposite:(jint)xp {
    J2dTraceLn(J2D_TRACE_INFO, "MTLContext.setXorComposite: xorPixel=%08x", xp);

    [_composite setXORComposite:xp];
}

- (jboolean) useXORComposite {
    return ([_composite getCompositeState] == sun_java2d_SunGraphics2D_COMP_XOR);
}

- (void)resetTransform {
    J2dTraceLn(J2D_TRACE_INFO, "MTLContext_ResetTransform");
    [_transform resetTransform];
}

- (void)setTransformM00:(jdouble) m00 M10:(jdouble) m10
                    M01:(jdouble) m01 M11:(jdouble) m11
                    M02:(jdouble) m02 M12:(jdouble) m12 {
    J2dTraceLn(J2D_TRACE_INFO, "MTLContext_SetTransform");
    [_transform setTransformM00:m00 M10:m10 M01:m01 M11:m11 M02:m02 M12:m12];
}

- (void)resetPaint {
    J2dTraceLn(J2D_TRACE_INFO, "MTLContext.resetPaint");
    self.paint = [[[MTLPaint alloc] init] autorelease];
}

- (void)setColorPaint:(int)pixel {
    J2dTraceLn(J2D_TRACE_INFO,
               "MTLContext.setColorPaint: pixel=%08x [r=%d g=%d b=%d a=%d]",
               pixel, (pixel >> 16) & (0xFF), (pixel >> 8) & 0xFF,
               (pixel) & 0xFF, (pixel >> 24) & 0xFF);
    self.paint = [[[MTLColorPaint alloc] initWithColor:pixel] autorelease];
}

- (void)setGradientPaintUseMask:(jboolean)useMask
                         cyclic:(jboolean)cyclic
                             p0:(jdouble)p0
                             p1:(jdouble)p1
                             p3:(jdouble)p3
                         pixel1:(jint)pixel1
                         pixel2:(jint) pixel2
{
    J2dTraceLn(J2D_TRACE_INFO, "MTLContext.setGradientPaintUseMask");
    self.paint = [[[MTLGradPaint alloc] initWithUseMask:useMask
                                                cyclic:cyclic
                                                    p0:p0
                                                    p1:p1
                                                    p3:p3
                                                pixel1:pixel1
                                                pixel2:pixel2] autorelease];
}

- (void)setLinearGradientPaint:(jboolean)useMask
                        linear:(jboolean)linear
                   cycleMethod:(jint)cycleMethod
                                // 0 - NO_CYCLE
                                // 1 - REFLECT
                                // 2 - REPEAT

                      numStops:(jint)numStops
                            p0:(jfloat)p0
                            p1:(jfloat)p1
                            p3:(jfloat)p3
                     fractions:(jfloat*)fractions
                        pixels:(jint*)pixels
{
    J2dTraceLn(J2D_TRACE_INFO, "MTLContext.setLinearGradientPaint");
    self.paint = [[[MTLLinearGradPaint alloc] initWithUseMask:useMask
                       linear:linear
                  cycleMethod:cycleMethod
                     numStops:numStops
                           p0:p0
                           p1:p1
                           p3:p3
                    fractions:fractions
                       pixels:pixels] autorelease];
}

- (void)setRadialGradientPaint:(jboolean)useMask
                        linear:(jboolean)linear
                   cycleMethod:(jboolean)cycleMethod
                      numStops:(jint)numStops
                           m00:(jfloat)m00
                           m01:(jfloat)m01
                           m02:(jfloat)m02
                           m10:(jfloat)m10
                           m11:(jfloat)m11
                           m12:(jfloat)m12
                        focusX:(jfloat)focusX
                     fractions:(void *)fractions
                        pixels:(void *)pixels
{
    J2dTraceLn(J2D_TRACE_INFO, "MTLContext.setRadialGradientPaint");
    self.paint = [[[MTLRadialGradPaint alloc] initWithUseMask:useMask
                                                      linear:linear
                                                 cycleMethod:cycleMethod
                                                    numStops:numStops
                                                         m00:m00
                                                         m01:m01
                                                         m02:m02
                                                         m10:m10
                                                         m11:m11
                                                         m12:m12
                                                      focusX:focusX
                                                   fractions:fractions
                                                      pixels:pixels] autorelease];
}

- (void)setTexturePaint:(jboolean)useMask
                pSrcOps:(jlong)pSrcOps
                 filter:(jboolean)filter
                    xp0:(jdouble)xp0
                    xp1:(jdouble)xp1
                    xp3:(jdouble)xp3
                    yp0:(jdouble)yp0
                    yp1:(jdouble)yp1
                    yp3:(jdouble)yp3
{
    BMTLSDOps *srcOps = (BMTLSDOps *)jlong_to_ptr(pSrcOps);

    if (srcOps == NULL || srcOps->pTexture == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLContext_setTexturePaint: texture paint - texture is null");
        return;
    }

    J2dTraceLn(J2D_TRACE_INFO, "MTLContext.setTexturePaint [tex=%p]", srcOps->pTexture);

    self.paint = [[[MTLTexturePaint alloc] initWithUseMask:useMask
                                                textureID:srcOps->pTexture
                                                  isOpaque:srcOps->isOpaque
                                                   filter:filter
                                                      xp0:xp0
                                                      xp1:xp1
                                                      xp3:xp3
                                                      yp0:yp0
                                                      yp1:yp1
                                                      yp3:yp3] autorelease];
}

- (id<MTLCommandBuffer>)createCommandBuffer {
    return [self.commandQueue commandBuffer];
}

/*
 * This should be exclusively used only for final blit
 * and present of CAMetalDrawable in MTLLayer
 */
- (id<MTLCommandBuffer>)createBlitCommandBuffer {
    return [self.blitCommandQueue commandBuffer];
}

-(void)setBufImgOp:(NSObject*)bufImgOp {
    if (_bufImgOp != nil) {
        [_bufImgOp release]; // context owns bufImgOp object
    }
    _bufImgOp = bufImgOp;
}

-(NSObject*)getBufImgOp {
    return _bufImgOp;
}

- (void)commitCommandBuffer:(BOOL)waitUntilCompleted display:(BOOL)updateDisplay {
    [self.encoderManager endEncoder];
    BMTLSDOps *dstOps = MTLRenderQueue_GetCurrentDestination();
    MTLLayer *layer = nil;
    if (dstOps != NULL) {
        MTLSDOps *dstMTLOps = (MTLSDOps *) dstOps->privOps;
        layer = (MTLLayer *) dstMTLOps->layer;
    }

    if (layer != nil) {
        [layer commitCommandBuffer:self wait:waitUntilCompleted display:updateDisplay];
    } else {
        MTLCommandBufferWrapper * cbwrapper = [self pullCommandBufferWrapper];
        if (cbwrapper != nil) {
            id <MTLCommandBuffer> commandbuf =[cbwrapper getCommandBuffer];
            [commandbuf addCompletedHandler:^(id <MTLCommandBuffer> cb) {
                [cbwrapper release];
            }];
            [commandbuf commit];
            if (waitUntilCompleted) {
                [commandbuf waitUntilCompleted];
            }
        }
    }
}

- (void) redraw:(NSNumber*)displayIDNumber {
    AWT_ASSERT_APPKIT_THREAD;
    const CFTimeInterval now = CACurrentMediaTime();

    const jint displayID = [displayIDNumber intValue];
    MTLDisplayLinkState *dlState = [self getDisplayLinkState:displayID];
    if (dlState == nil) {
        return;
    }
    /*
     * Avoid repeated invocations by UIKit Main Thread
     * if blocked while many mtlDisplayLinkCallback() are dispatched
     */
    const CFTimeInterval elapsed = (dlState->lastRedrawTime != 0.0) ? (now - dlState->lastRedrawTime) : -1.0;

    CFTimeInterval threshold = (dlState->avgDisplayLinkSamples >= 10) ?
            (dlState->avgDisplayLinkTime / 20.0) : KEEP_ALIVE_MIN_INTERVAL;

    if (threshold < KEEP_ALIVE_MIN_INTERVAL) {
        threshold = KEEP_ALIVE_MIN_INTERVAL;
    }

    if ((elapsed >= 0.0) && (elapsed <= threshold)) {
        if (TRACE_CVLINK) {
            NSLog(@"MTLContext_redraw[displayID: %d]: %.3f < %.3f ms, skip redraw.",
                  displayID, TO_MS(elapsed), TO_MS(threshold));
        }
        return;
    }
    if (TRACE_CVLINK_DEBUG) {
        NSLog(@"MTLContext_redraw[displayID: %d]: elapsed: %.3f ms (> %.3f)",
              displayID, TO_MS(elapsed), TO_MS(threshold));
    }
    dlState->lastRedrawTime = now;

    // Process layers:
    for (MTLLayer *layer in _layers) {
        if (layer.displayID == displayID) {
            [layer setNeedsDisplay];
        }
    }
    if (dlState->redrawCount > 0) {
        dlState->redrawCount--;
    } else {
        // dlState->redrawCount == 0:
        if (_layers.count > 0) {
            for (MTLLayer *layer in _layers.allObjects) {
                if (layer.displayID == displayID) {
                    [_layers removeObject:layer];
                }
            }
        }
        [self handleDisplayLink:NO displayID:displayID source:"redraw"];
    }
}

CVReturn mtlDisplayLinkCallback(CVDisplayLinkRef displayLink, const CVTimeStamp* nowTime, const CVTimeStamp* outputTime, CVOptionFlags flagsIn, CVOptionFlags* flagsOut, void* displayLinkContext) {
    JNI_COCOA_ENTER(env);
        J2dTraceLn(J2D_TRACE_VERBOSE, "MTLContext_mtlDisplayLinkCallback: ctx=%p", displayLinkContext);

        MTLDisplayLinkState *dlState = (__bridge MTLDisplayLinkState*) displayLinkContext;
        if (dlState == nil) {
            if (TRACE_CVLINK_WARN) {
                NSLog(@"MTLContext_mtlDisplayLinkCallback: dlState is nil!");
            }
            return kCVReturnError;
        }
        const MTLContext *mtlc = dlState->mtlc;
        const jint displayID = dlState->displayID;

        if (STATS_CVLINK) {
            const CFTimeInterval now = outputTime->videoTime / (double) outputTime->videoTimeScale; // seconds
            const CFTimeInterval delta = (dlState->lastDisplayLinkTime != 0.0) ? (now - dlState->lastDisplayLinkTime)
                                                                               : -1.0;
            dlState->lastDisplayLinkTime = now;

            dlState->avgDisplayLinkSamples++;
            dlState->avgDisplayLinkTime = EXP_AVG_WEIGHT * delta + EXP_INV_WEIGHT * dlState->avgDisplayLinkTime;

            if (dlState->lastStatTime == 0.0) {
                dlState->lastStatTime = now;
            } else if ((now - dlState->lastStatTime) > 1.0) {
                dlState->lastStatTime = now;
                // dump stats:
                NSLog(@"mtlDisplayLinkCallback[displayID: %d]: avg interval = %.3lf ms (%.1lf fps) on %d samples", displayID,
                      TO_MS(dlState->avgDisplayLinkTime), TO_FPS(dlState->avgDisplayLinkTime), dlState->avgDisplayLinkSamples);
            }
        }

        const BOOL active = mtlc_IsDisplayReallyActive(displayID);
        if (TRACE_CVLINK_DEBUG) {
            NSLog(@"MTLContext_mtlDisplayLinkCallback: ctx=%p displayID=%d active=%d (display link = %p)",
                  mtlc, displayID, active, dlState->displayLink);
        }
        if (!active) {
            if (TRACE_CVLINK) {
                NSLog(@"MTLContext_mtlDisplayLinkCallback: displayId=%d active=%d "
                      "=> destroyDisplayLink", displayID, active);
            }
            if (TRACE_DISPLAY) {
                [MTLContext dumpDisplayInfo:displayID];
            }
            [mtlc destroyDisplayLink:displayID];
        } else {
            [ThreadUtilities performOnMainThread:@selector(redraw:) on:mtlc withObject:@(displayID)
                                   waitUntilDone:NO useJavaModes:NO]; // critical
        }
    JNI_COCOA_EXIT(env);
    return kCVReturnSuccess;
}

- (void)startRedraw:(MTLLayer*)layer {
    AWT_ASSERT_APPKIT_THREAD;
    J2dTraceLn(J2D_TRACE_VERBOSE, "MTLContext_startRedraw: ctx=%p layer=%p", self, layer);
    const jint displayID = layer.displayID;
    MTLDisplayLinkState *dlState = [self getDisplayLinkState:displayID];
    if (dlState == nil) {
        return;
    }
    dlState->redrawCount = KEEP_ALIVE_COUNT;

    layer.redrawCount = REDRAW_COUNT;
    [_layers addObject:layer];

    if (MTLLayer_isExtraRedrawEnabled()) {
        // Request for redraw before starting display link to avoid rendering problem on M2 processor
        [layer setNeedsDisplay];
    }
    [self handleDisplayLink:YES displayID:displayID source:"startRedraw"];
}

- (void)stopRedraw:(MTLLayer*) layer {
    [self stopRedraw:layer.displayID layer:layer];
}

- (void)stopRedraw:(jint)displayID layer:(MTLLayer*)layer {
    AWT_ASSERT_APPKIT_THREAD;
    J2dTraceLn(J2D_TRACE_VERBOSE, "MTLContext_stopRedraw: ctx=%p displayID=%d layer=%p",
               self, displayID, layer);
    MTLDisplayLinkState *dlState = [self getDisplayLinkState:displayID];
    if (dlState == nil) {
        return;
    }
    if (--layer.redrawCount <= 0) {
        [_layers removeObject:layer];
        layer.redrawCount = 0;
    }
    bool hasLayers = false;
    if (_layers.count > 0) {
        for (MTLLayer *l in _layers) {
            if (l.displayID == displayID) {
                hasLayers = true;
                break;
            }
        }
    }
    if (!hasLayers && (dlState->redrawCount == 0)) {
        [self handleDisplayLink:NO displayID:displayID source:"stopRedraw"];
    }
}

- (void)haltRedraw {
    if (_displayLinkStates != nil) {
        if (TRACE_CVLINK) {
            J2dRlsTraceLn(J2D_TRACE_VERBOSE, "MTLContext_haltRedraw: ctx=%p", self);
        }
        if (_layers.count > 0) {
            for (MTLLayer *layer in _layers) {
                layer.redrawCount = 0;
            }
            [_layers removeAllObjects];
        }
        if (_displayLinkStates.count > 0) {
            for (NSNumber* displayIDVal in [self getDisplayLinkDisplayIds]) {
                const jint displayID = [displayIDVal intValue];
                [self destroyDisplayLink:displayID];

                MTLDisplayLinkState *dlState = [self getDisplayLinkState:displayID];
                if (dlState != nil) {
                    // Remove reference:
                    [_displayLinkStates removeObjectForKey:@(displayID)];
                    free(dlState);
                }
            }
        }
    }
}

@end
