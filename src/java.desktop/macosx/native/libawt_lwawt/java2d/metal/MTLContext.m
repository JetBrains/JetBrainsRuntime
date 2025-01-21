/*
 * Copyright (c) 2019, 2024, Oracle and/or its affiliates. All rights reserved.
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

// Min interval between 2 display link callbacks (Main thread may be busy)
// ~ 1ms (shorter than best monitor frame rate = 1 khz)
#define KEEP_ALIVE_MIN_INTERVAL 1.0 / TO_MS(1.0)

// Amount of blit operations per update to make sure that everything is
// rendered into the window drawable. It does not slow things down as we
// use separate command queue for blitting.
#define REDRAW_COUNT 1

extern jboolean MTLSD_InitMTLWindow(JNIEnv *env, MTLSDOps *mtlsdo);
extern BOOL isDisplaySyncEnabled();
extern BOOL MTLLayer_isExtraRedrawEnabled();
extern int getBPPFromModeString(CFStringRef mode);

#define STATS_CVLINK        1
#define TRACE_CVLINK        1
#define TRACE_CVLINK_DEBUG  0
#define TRACE_DISPLAY       1

#define CHECK_CVLINK(op, source, cmd)                                               \
{                                                                                   \
    CVReturn ret = (CVReturn) (cmd);                                                \
    if (ret != kCVReturnSuccess) {                                                  \
        J2dTraceImpl(J2D_TRACE_ERROR, JNI_TRUE, "CVDisplayLink[%s - %s] Error: %d", \
                     op, (source != nil) ? source : "", ret);                       \
    } else if (TRACE_CVLINK) {                                                      \
        J2dTraceImpl(J2D_TRACE_VERBOSE, JNI_TRUE, "CVDisplayLink[%s - %s]: OK",     \
                     op, (source != nil) ? source : "");                            \
    }                                                                                                                                                                   \
}

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
    NSMutableSet* _layers;

    MTLComposite *     _composite;
    MTLPaint *         _paint;
    MTLTransform *     _transform;
    MTLTransform *     _tempTransform;
    MTLClip *          _clip;

    NSObject*          _bufImgOp; // TODO: pass as parameter of IsoBlit
    EncoderManager * _encoderManager;
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

+ (NSMutableDictionary*) contextStore {
    static NSMutableDictionary<NSNumber*, MTLContext*> *_contextStore;
    static dispatch_once_t oncePredicate;

    dispatch_once(&oncePredicate, ^{
        _contextStore = [[NSMutableDictionary alloc] init];
    });

    return _contextStore;
}

+ (MTLContext*) createContextWithDeviceIfAbsent:(jint)displayID shadersLib:(NSString*)mtlShadersLib {
    // Initialization code here.
    // note: the device reference is NS_RETURNS_RETAINED, should be released by the caller:
    bool shouldReleaseDevice = true;

    id<MTLDevice> device = CGDirectDisplayCopyCurrentMetalDevice(displayID);
    if (device == nil) {
        J2dRlsTraceLn1(J2D_TRACE_ERROR, "MTLContext_createContextWithDeviceIfAbsent(): Cannot create device from "
                                        "displayID=%d", displayID)
        // Fallback to the default metal device for main display
        jint mainDisplayID = CGMainDisplayID();
        if (displayID == mainDisplayID) {
            device = MTLCreateSystemDefaultDevice();
        }
        if (device == nil) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLContext_createContextWithDeviceIfAbsent(): Cannot fallback to default "
                                           "metal device")
            return nil;
        }
    }

    id<NSCopying> devID = nil;

    if (@available(macOS 10.13, *)) {
        devID = @(device.registryID);
    } else {
        devID = device.name;
    }

    MTLContext* mtlc = MTLContext.contextStore[devID];
    if (mtlc == nil) {
        mtlc = [[MTLContext alloc] initWithDevice:device display:displayID shadersLib:mtlShadersLib];
        if (mtlc != nil) {
            shouldReleaseDevice = false;
            MTLContext.contextStore[devID] = mtlc;
            [mtlc release];
            J2dRlsTraceLn4(J2D_TRACE_INFO,
                           "MTLContext_createContextWithDeviceIfAbsent: new context(%p) for display=%d device=\"%s\" "
                           "retainCount=%d", mtlc, displayID, [mtlc.device.name UTF8String], mtlc.retainCount)
        }
    } else {
        if (![mtlc.shadersLib isEqualToString:mtlShadersLib]) {
            J2dRlsTraceLn3(J2D_TRACE_ERROR,
                           "MTLContext_createContextWithDeviceIfAbsent: cannot reuse context(%p) for display=%d "
                           "device=\"%s\", shaders lib has been changed", mtlc, displayID, [mtlc.device.name UTF8String])
            [device release];
            return nil;
        }
        [mtlc retain];
        J2dRlsTraceLn4(J2D_TRACE_INFO,
                       "MTLContext_createContextWithDeviceIfAbsent: reuse context(%p) for display=%d device=\"%s\" "
                       "retainCount=%d", mtlc, displayID, [mtlc.device.name UTF8String], mtlc.retainCount)
    }
    if (shouldReleaseDevice) {
        [device release];
    }
    [mtlc createDisplayLinkIfAbsent:displayID];
    return mtlc;
}

+ (void) releaseContext:(MTLContext*) mtlc {
    id<NSCopying> devID = nil;

    if (@available(macOS 10.13, *)) {
        devID = @(mtlc.device.registryID);
    } else {
        devID = mtlc.device.name;
    }
    MTLContext* ctx = MTLContext.contextStore[devID];
    if (mtlc == ctx) {
        if (mtlc.retainCount > 1) {
            [mtlc release];
            J2dRlsTraceLn2(J2D_TRACE_INFO, "MTLContext_releaseContext: release context(%p) retainCount=%d", mtlc, mtlc.retainCount);
        } else {
            // explicit halt redraw to shutdown CVDisplayLink threads before dealloc():
            [mtlc haltRedraw];
            [MTLContext.contextStore removeObjectForKey:devID];
            J2dRlsTraceLn1(J2D_TRACE_INFO, "MTLContext_releaseContext: dealloc context(%p)", mtlc);
        }
    } else {
        J2dRlsTraceLn2(J2D_TRACE_ERROR, "MTLContext_releaseContext: cannot release context(%p) != context store(%p)",
                       mtlc, ctx);
    }
}

- (id)initWithDevice:(id<MTLDevice>)mtlDevice display:(jint) displayID shadersLib:(NSString*)mtlShadersLib {
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


- (void)createDisplayLinkIfAbsent: (jint)displayID {
    if (isDisplaySyncEnabled() && (_displayLinkStates[@(displayID)] == nil)) {
        if (TRACE_DISPLAY) {
            [MTLContext dumpDisplayInfo:displayID];
        }

        CVDisplayLinkRef _displayLink;
        if (TRACE_CVLINK) {
            J2dRlsTraceLn2(J2D_TRACE_VERBOSE, "MTLContext_createDisplayLinkIfAbsent: "
                                              "ctx=%p displayID=%d", self, displayID);
        }
        CHECK_CVLINK("CreateWithCGDisplay", nil,
                     CVDisplayLinkCreateWithCGDisplay(displayID, &_displayLink));

        if (_displayLink == nil) {
            J2dRlsTraceLn(J2D_TRACE_ERROR,
                          "MTLContext_createDisplayLinkIfAbsent: Failed to initialize CVDisplayLink.");
        } else {
            MTLDisplayLinkState* dlState = malloc(sizeof (MTLDisplayLinkState));
            dlState->displayID = displayID;
            dlState->displayLink = _displayLink;
            dlState->mtlc = self;

            dlState->redrawCount = 0;
            dlState->lastRedrawTime = 0.0;
            dlState->lastDisplayLinkTime = 0.0;
            dlState->avgDisplayLinkTime = 0.0;
            dlState->lastStatTime = 0.0;

            _displayLinkStates[@(displayID)] = [NSValue valueWithPointer:dlState];

            CHECK_CVLINK("SetOutputCallback", nil,
                         CVDisplayLinkSetOutputCallback(_displayLink, &mtlDisplayLinkCallback,
                                                        (__bridge MTLDisplayLinkState*) dlState));
        }
    }
}

- (NSValue*)getDisplayLinkState: (jint)displayID {
    if (_displayLinkStates == nil) {
        if (TRACE_CVLINK) {
            J2dRlsTraceLn2(J2D_TRACE_VERBOSE, "MTLContext_getDisplayLinkState[ctx=%p displayID=%d]: "
                                              "displayLinkStates=nil", self, displayID);
        }
        return nil;
    }
    NSValue* dlStateVal = _displayLinkStates[@(displayID)];
    if (dlStateVal == nil) {
        J2dRlsTraceLn2(J2D_TRACE_ERROR, "MTLContext_getDisplayLinkState[ctx=%p displayID=%d]: "
                                        "dlState is nil!", self, displayID);
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
        // Remove reference:
        [_displayLinkStates removeObjectForKey:@(displayID)];

        CVDisplayLinkRef _displayLink = dlState->displayLink;
        if (TRACE_CVLINK) {
            J2dRlsTraceLn2(J2D_TRACE_VERBOSE, "MTLContext_destroyDisplayLink: "
                                              "ctx=%p, displayID=%d", self, displayID);
        }
        if (CVDisplayLinkIsRunning(_displayLink)) {
            CHECK_CVLINK("Stop", "destroyDisplayLink", CVDisplayLinkStop(_displayLink));
            if (TRACE_CVLINK) {
                J2dRlsTraceLn1(J2D_TRACE_VERBOSE, "MTLContext_CVDisplayLinkStop[destroyDisplayLink]: "
                                                  "ctx=%p", self);
            }
        }
        // Release display link thread:
        CVDisplayLinkRelease(_displayLink);
        free(dlState);
    }
}

- (void)handleDisplayLink: (BOOL)enabled display:(jint)displayID source:(const char*)src {
    MTLDisplayLinkState *dlState = [self getDisplayLinkState:displayID];
    if (dlState == nil) {
        return;
    }
    CVDisplayLinkRef _displayLink = dlState->displayLink;
    if (enabled) {
        if (!CVDisplayLinkIsRunning(_displayLink)) {
            CHECK_CVLINK("Start", src, CVDisplayLinkStart(_displayLink));
            if (TRACE_CVLINK) {
                J2dRlsTraceLn2(J2D_TRACE_VERBOSE, "MTLContext_CVDisplayLinkStart[%s]: "
                                                  "ctx=%p", src, self);
            }
        }
    } else {
        if (CVDisplayLinkIsRunning(_displayLink)) {
            CHECK_CVLINK("Stop", src, CVDisplayLinkStop(_displayLink));
            if (TRACE_CVLINK) {
                J2dRlsTraceLn2(J2D_TRACE_VERBOSE, "MTLContext_CVDisplayLinkStop[%s]: "
                                                  "ctx=%p", src, self);
            }
        }
    }
}

- (void)dealloc {
    J2dTraceLn(J2D_TRACE_INFO, "MTLContext.dealloc");

    if (_displayLinkStates != nil) {
        [self haltRedraw];
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
        J2dTraceLn(J2D_TRACE_VERBOSE, "MTLContext : commandBuffer is NULL");
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

    J2dTraceLn6(J2D_TRACE_VERBOSE, "MTLContext_SetSurfaces: bsrc=%p (tex=%p type=%d), bdst=%p (tex=%p type=%d)", srcOps, srcOps->pTexture, srcOps->drawableType, dstOps, dstOps->pTexture, dstOps->drawableType);

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
    J2dTraceLn4(J2D_TRACE_INFO, "MTLContext.setClipRect: %d,%d - %d,%d", x1, y1, x2, y2);
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
    J2dTraceLn3(J2D_TRACE_INFO, "MTLContext_SetAlphaComposite: rule=%d, extraAlpha=%1.2f, flags=%d", rule, extraAlpha, flags);

    [_composite setRule:rule extraAlpha:extraAlpha];
}

- (NSString*)getCompositeDescription {
    return [_composite getDescription];
}

- (NSString*)getPaintDescription {
    return [_paint getDescription];
}

- (void)setXorComposite:(jint)xp {
    J2dTraceLn1(J2D_TRACE_INFO, "MTLContext.setXorComposite: xorPixel=%08x", xp);

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
    J2dTraceLn5(J2D_TRACE_INFO, "MTLContext.setColorPaint: pixel=%08x [r=%d g=%d b=%d a=%d]", pixel, (pixel >> 16) & (0xFF), (pixel >> 8) & 0xFF, (pixel) & 0xFF, (pixel >> 24) & 0xFF);
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

    J2dTraceLn1(J2D_TRACE_INFO, "MTLContext.setTexturePaint [tex=%p]", srcOps->pTexture);

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
    const jint displayID = [displayIDNumber intValue];
    MTLDisplayLinkState *dlState = [self getDisplayLinkState:displayID];
    if (dlState == nil) {
        NSLog(@"MTLContext_redraw[displayID: %d]: dlState is nil!", displayID);
        return;
    }
    /*
     * Avoid repeated invocations by UIKit Main Thread
     * if blocked while many mtlDisplayLinkCallback() are dispatched
     */
    const CFTimeInterval now = CACurrentMediaTime();
    const CFTimeInterval elapsed = (dlState->lastRedrawTime != 0.0) ? (now - dlState->lastRedrawTime) : -1.0;

    // TODO: use a fraction of avgDisplayLinkTime like 10% ?
    if ((elapsed >= 0.0) && (elapsed <= KEEP_ALIVE_MIN_INTERVAL)) {
        NSLog(@"MTLContext_redraw[displayID: %d]: %.3f < %.3f ms, skip redraw.",
              displayID, TO_MS(elapsed), TO_MS(KEEP_ALIVE_MIN_INTERVAL));
        return;
    }
    if (TRACE_CVLINK_DEBUG) {
        NSLog(@"MTLContext_redraw[displayID: %d]: elapsed: %.3f ms", displayID, TO_MS(elapsed));
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
            for (MTLLayer *layer in _layers) {
                if (layer.displayID == displayID) {
                    [_layers removeObject:layer];
                }
            }
        }
        [self handleDisplayLink:NO display:displayID source:"redraw"];
    }
    /* explicit release MTLC after async block */
    [dlState->mtlc release];
}

CVReturn mtlDisplayLinkCallback(CVDisplayLinkRef displayLink, const CVTimeStamp* nowTime, const CVTimeStamp* outputTime, CVOptionFlags flagsIn, CVOptionFlags* flagsOut, void* displayLinkContext) {
    JNI_COCOA_ENTER(env);
        J2dTraceLn1(J2D_TRACE_VERBOSE, "MTLContext_mtlDisplayLinkCallback: ctx=%p", displayLinkContext);

        MTLDisplayLinkState *dlState = (__bridge MTLDisplayLinkState*) displayLinkContext;
        if (dlState == nil) {
            NSLog(@"MTLContext_mtlDisplayLinkCallback: dlState is nil!");
            return kCVReturnError;
        }
        const MTLContext *mtlc = dlState->mtlc;
        const jint displayID = dlState->displayID;

        if (TRACE_CVLINK_DEBUG) {
            NSLog(@"MTLContext_mtlDisplayLinkCallback: ctx=%p displayID=%d", mtlc, displayID);
        }
        if (STATS_CVLINK) {
            const CFTimeInterval now = outputTime->videoTime / (double) outputTime->videoTimeScale; // seconds
            const CFTimeInterval delta = (dlState->lastDisplayLinkTime != 0.0) ? (now - dlState->lastDisplayLinkTime)
                                                                               : -1.0;
            dlState->lastDisplayLinkTime = now;

            // 60 fps typically => exponential smoothing on 0.5s:
            const NSTimeInterval a = 1.0 / 30.0;
            dlState->avgDisplayLinkTime = delta * a + dlState->avgDisplayLinkTime * (1.0 - a);

            if (dlState->lastStatTime == 0.0) {
                dlState->lastStatTime = now;
            } else if ((now - dlState->lastStatTime) > 1.0) {
                dlState->lastStatTime = now;
                // dump stats:
                NSLog(@"mtlDisplayLinkCallback[displayID: %d]: avg interval = %.3lf ms (%.1lf fps)", displayID,
                      TO_MS(dlState->avgDisplayLinkTime), 1.0 / dlState->avgDisplayLinkTime);
            }
        }

        /* explicit retain MTLC before async block */
        [dlState->mtlc retain];
        [ThreadUtilities performOnMainThread:@selector(redraw:) on:mtlc withObject:@(displayID)
                               waitUntilDone:NO useJavaModes:NO]; // critical
    JNI_COCOA_EXIT(env);
    return kCVReturnSuccess;
}

- (void)startRedraw:(MTLLayer*)layer {
    AWT_ASSERT_APPKIT_THREAD;
    const jint displayID = layer.displayID;
    MTLDisplayLinkState *dlState = [self getDisplayLinkState:displayID];
    if (dlState == nil) {
        return;
    }
    dlState->redrawCount = KEEP_ALIVE_COUNT;

    layer.redrawCount = REDRAW_COUNT;
    J2dTraceLn2(J2D_TRACE_VERBOSE, "MTLContext_startRedraw: ctx=%p layer=%p", self, layer);
    [_layers addObject:layer];

    if (MTLLayer_isExtraRedrawEnabled()) {
        // Request for redraw before starting display link to avoid rendering problem on M2 processor
        [layer setNeedsDisplay];
    }
    [self handleDisplayLink:YES display:displayID source:"startRedraw"];
}

- (void)stopRedraw:(MTLLayer*) layer {
    AWT_ASSERT_APPKIT_THREAD;
    const jint displayID = layer.displayID;
    MTLDisplayLinkState *dlState = [self getDisplayLinkState:displayID];
    if (dlState == nil) {
        return;
    }
    J2dTraceLn2(J2D_TRACE_VERBOSE, "MTLContext_stopRedraw: ctx=%p layer=%p", self, layer);
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
        [self handleDisplayLink:NO display:displayID source:"stopRedraw"];
    }
}

- (void)haltRedraw {
    if (_displayLinkStates != nil) {
        if (TRACE_CVLINK) {
            J2dRlsTraceLn1(J2D_TRACE_VERBOSE, "MTLContext_haltRedraw: ctx=%p", self);
        }
        if (_layers.count > 0) {
            for (MTLLayer *layer in _layers) {
                layer.redrawCount = 0;
            }
            [_layers removeAllObjects];
        }

        NSEnumerator<NSNumber*>* keyEnum = _displayLinkStates.keyEnumerator;
        NSNumber* displayIDVal;

        while ((displayIDVal = [keyEnum nextObject])) {
            MTLDisplayLinkState *dlState = [self getDisplayLinkState:[displayIDVal intValue]];
            if (dlState == nil) {
                continue;
            }
            CVDisplayLinkRef _displayLink = dlState->displayLink;
            if (TRACE_CVLINK) {
                J2dRlsTraceLn2(J2D_TRACE_VERBOSE, "MTLContext_haltRedraw: "
                                                  "ctx=%p, displayID=%d", self, displayIDVal);
            }
            if (CVDisplayLinkIsRunning(_displayLink)) {
                CHECK_CVLINK("Stop", "haltRedraw", CVDisplayLinkStop(_displayLink));
                if (TRACE_CVLINK) {
                    J2dRlsTraceLn2(J2D_TRACE_VERBOSE, "MTLContext_CVDisplayLinkStop[%s]: "
                                                      "ctx=%p", "haltRedraw", self);
                }
            }
            // Release display link thread:
            CVDisplayLinkRelease(dlState->displayLink);
            free(dlState);
        }
        [_displayLinkStates release];
        _displayLinkStates = nil;
    }
}

@end
