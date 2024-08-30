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

// Min interval between 2 display link callbacks (Main thread may be busy)
// ~ 2ms (shorter than best monitor frame rate = 500 hz)
#define KEEP_ALIVE_MIN_INTERVAL 2.0 / 1000.0

// Amount of blit operations per update to make sure that everything is
// rendered into the window drawable. It does not slow things down as we
// use separate command queue for blitting.
#define REDRAW_COUNT 1

extern jboolean MTLSD_InitMTLWindow(JNIEnv *env, MTLSDOps *mtlsdo);
extern BOOL isDisplaySyncEnabled();
extern BOOL MTLLayer_isExtraRedrawEnabled();

#define TRACE_CVLINK  0

#define CHECK_CVLINK(op, cmd)                                                   \
{                                                                               \
    CVReturn ret = (CVReturn) (cmd);                                            \
    if (ret != kCVReturnSuccess) {                                              \
        J2dTraceImpl(J2D_TRACE_ERROR, JNI_TRUE, "CVDisplayLink[%s] Error: %d",  \
                     op, ret);                                                  \
    }                                                                           \
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
} DLParams;

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
    int _displayLinkCount;
    CFTimeInterval _lastRedrawTime;

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
            MTLContext.contextStore[devID] = mtlc;
            [mtlc release];
            J2dRlsTraceLn(J2D_TRACE_INFO,
                          "MTLContext_createContextWithDeviceIfAbsent: new context(%p) for display=%d device=\"%s\" "
                          "retainCount=%d", mtlc, displayID, [mtlc.device.name UTF8String], mtlc.retainCount)
        }
    } else {
        if (![mtlc.shadersLib isEqualToString:mtlShadersLib]) {
            J2dRlsTraceLn(J2D_TRACE_ERROR,
                          "MTLContext_createContextWithDeviceIfAbsent: cannot reuse context(%p) for display=%d "
                          "device=\"%s\", shaders lib has been changed", mtlc, displayID, [mtlc.device.name UTF8String]);
            return nil;
        }
        [mtlc retain];
        J2dRlsTraceLn(J2D_TRACE_INFO,
                      "MTLContext_createContextWithDeviceIfAbsent: reuse context(%p) for display=%d device=\"%s\" "
                      "retainCount=%d", mtlc, displayID, [mtlc.device.name UTF8String], mtlc.retainCount)
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
            J2dRlsTraceLn(J2D_TRACE_INFO, "MTLContext_releaseContext: release context(%p) retainCount=%d", mtlc, mtlc.retainCount);
        } else {
            [MTLContext.contextStore removeObjectForKey:devID];
            J2dRlsTraceLn(J2D_TRACE_INFO, "MTLContext_releaseContext: dealloc context(%p)", mtlc);
        }
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
        _displayLinkCount = 0;
        _lastRedrawTime = 0.0;
        if (isDisplaySyncEnabled()) {
            _displayLinks = [[NSMutableDictionary alloc] init];
            _layers = [[NSMutableSet alloc] init];
        }
        _glyphCacheLCD = [[MTLGlyphCache alloc] initWithContext:self];
        _glyphCacheAA = [[MTLGlyphCache alloc] initWithContext:self];
    }
    return self;
}

- (void)createDisplayLinkIfAbsent: (jint)displayID {
    if (isDisplaySyncEnabled() && _displayLinks[@(displayID)] == nil) {
        CVDisplayLinkRef _displayLink;
        if (TRACE_CVLINK) {
            J2dRlsTraceLn(J2D_TRACE_VERBOSE, "MTLContext_createDisplayLinkIfAbsent: "
                                             "ctx=%p displayID=%d", self, displayID);
        }
        CHECK_CVLINK("CreateWithCGDisplay",
                     CVDisplayLinkCreateWithCGDisplay(displayID, &_displayLink));
        if (_displayLink == nil) {
            J2dRlsTraceLn(J2D_TRACE_ERROR,
                          "MTLContext_createDisplayLinkIfAbsent: Failed to initialize CVDisplayLink.");
        } else {
            DLParams* dlParams = malloc(sizeof (DLParams ));
            dlParams->displayID = displayID;
            dlParams->displayLink = _displayLink;
            dlParams->mtlc = self;
            _displayLinks[@(displayID)] = [NSValue valueWithPointer:dlParams];
            CHECK_CVLINK("SetOutputCallback", CVDisplayLinkSetOutputCallback(
                    _displayLink,
                    &mtlDisplayLinkCallback,
                    (__bridge DLParams*) dlParams));
        }
    }
}
- (void)handleDisplayLink: (BOOL)enabled display:(jint) display source:(const char*)src {
    if (_displayLinks == nil) {
        if (TRACE_CVLINK) {
            J2dRlsTraceLn2(J2D_TRACE_VERBOSE, "MTLContext_handleDisplayLink[%s]: "
                           "ctx=%p - displayLinks = nil", src, self);
        }
    } else {
        NSValue* dlParamsVal = _displayLinks[@(display)];
        if (dlParamsVal == nil) {
            J2dRlsTraceLn3(J2D_TRACE_ERROR, "MTLContext_handleDisplayLink[%s]: "
                                            "ctx=%p, no display link for %d ", src, self, display);
            return;
        }

        DLParams *dlParams = [dlParamsVal pointerValue];
        CVDisplayLinkRef _displayLink = dlParams->displayLink;
        if (enabled) {
            if (!CVDisplayLinkIsRunning(_displayLink)) {
                CHECK_CVLINK("Start", CVDisplayLinkStart(_displayLink));
                if (TRACE_CVLINK) {
                    J2dRlsTraceLn(J2D_TRACE_VERBOSE, "MTLContext_CVDisplayLinkStart[%s]: "
                                                     "ctx=%p", src, self);
                }
            }
        } else {
            if (CVDisplayLinkIsRunning(_displayLink)) {
                CHECK_CVLINK("Stop", CVDisplayLinkStop(_displayLink));
                if (TRACE_CVLINK) {
                    J2dRlsTraceLn(J2D_TRACE_VERBOSE, "MTLContext_CVDisplayLinkStop[%s]: "
                                                     "ctx=%p", src, self);
                }
            }
        }
    }
}

- (void)dealloc {
    J2dTraceLn(J2D_TRACE_INFO, "MTLContext.dealloc");

    if (_displayLinks != nil) {
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
            [commandbuf addCompletedHandler:^(id <MTLCommandBuffer> commandbuf) {
                [cbwrapper release];
            }];
            [commandbuf commit];
            if (waitUntilCompleted) {
                [commandbuf waitUntilCompleted];
            }
        }
    }
}

- (void) redraw:(NSNumber*)displayIDNum {
    AWT_ASSERT_APPKIT_THREAD;
    /*
     * Avoid repeated invocations by UIKit Main Thread
     * if blocked while many mtlDisplayLinkCallback() are dispatched
     */
    const CFTimeInterval now = CACurrentMediaTime();
    const CFTimeInterval elapsed = (_lastRedrawTime != 0.0) ? (now - _lastRedrawTime) : -1.0;

    if ((elapsed >= 0.0) && (elapsed <= KEEP_ALIVE_MIN_INTERVAL)) {
        return;
    }
    _lastRedrawTime = now;
    // Process layers:
    for (MTLLayer *layer in _layers) {
        [layer setNeedsDisplay];
    }
    if (_displayLinkCount > 0) {
        _displayLinkCount--;
    } else {
        if (_layers.count > 0) {
            [_layers removeAllObjects];
        }
        [self handleDisplayLink:NO display:[displayIDNum integerValue] source:"redraw"];
    }
}

CVReturn mtlDisplayLinkCallback(CVDisplayLinkRef displayLink, const CVTimeStamp* now, const CVTimeStamp* outputTime, CVOptionFlags flagsIn, CVOptionFlags* flagsOut, void* displayLinkContext)
{
    J2dTraceLn1(J2D_TRACE_VERBOSE, "MTLContext_mtlDisplayLinkCallback: ctx=%p", displayLinkContext);
    @autoreleasepool {
        DLParams* dlParams = (__bridge DLParams* *)displayLinkContext;
        [ThreadUtilities performOnMainThread:@selector(redraw:) on:dlParams->mtlc withObject:@(dlParams->displayID) waitUntilDone:NO];
    }
    return kCVReturnSuccess;
}

- (void)startRedraw:(MTLLayer*)layer {
    AWT_ASSERT_APPKIT_THREAD;
    layer.redrawCount = REDRAW_COUNT;
    J2dTraceLn2(J2D_TRACE_VERBOSE, "MTLContext_startRedraw: ctx=%p layer=%p", self, layer);
    _displayLinkCount = KEEP_ALIVE_COUNT;
    [_layers addObject:layer];
    if (MTLLayer_isExtraRedrawEnabled()) {
        // Request for redraw before starting display link to avoid rendering problem on M2 processor
        [layer setNeedsDisplay];
    }
    [self handleDisplayLink:YES display:layer.displayID source:"startRedraw"];
}

- (void)stopRedraw:(MTLLayer*) layer {
    AWT_ASSERT_APPKIT_THREAD;
    J2dTraceLn2(J2D_TRACE_VERBOSE, "MTLContext_stopRedraw: ctx=%p layer=%p", self, layer);
    if (_displayLinks != nil) {
        if (--layer.redrawCount <= 0) {
            [_layers removeObject:layer];
            layer.redrawCount = 0;
        }
        if ((_layers.count == 0) && (_displayLinkCount == 0)) {
            [self handleDisplayLink:NO display:layer.displayID source:"stopRedraw"];
        }
    }
}

- (void)haltRedraw {
    if (_displayLinks != nil) {
        if (TRACE_CVLINK) {
            J2dRlsTraceLn(J2D_TRACE_VERBOSE, "MTLContext_haltRedraw: ctx=%p", self);
        }
        if (_layers.count > 0) {
            for (MTLLayer *layer in _layers) {
                layer.redrawCount = 0;
            }
            [_layers removeAllObjects];
        }
        _displayLinkCount = 0;
        NSEnumerator<NSNumber*>* keyEnum = _displayLinks.keyEnumerator;
        NSNumber* displayIDVal;
        while ((displayIDVal = [keyEnum nextObject])) {
            [self handleDisplayLink:NO display:[displayIDVal integerValue] source:"haltRedraw"];
            DLParams *dlParams = [(NSValue*)_displayLinks[displayIDVal] pointerValue];
            CVDisplayLinkRelease(dlParams->displayLink);
            free(dlParams);
        }
        [_displayLinks release];
        _displayLinks = NULL;
    }
}

@end
