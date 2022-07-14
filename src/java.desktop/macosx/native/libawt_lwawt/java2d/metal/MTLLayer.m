/*
 * Copyright (c) 2019, 2021, Oracle and/or its affiliates. All rights reserved.
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
#import "JNIUtilities.h"
#define KEEP_ALIVE_INC 4
#define FRAMES_BUFF_SIZE 8

@implementation MTLLayer {
    id<MTLTexture> framesBuffer[FRAMES_BUFF_SIZE];
    int framesNumbers[FRAMES_BUFF_SIZE];
    int inFramesIndex;
    int outFramesIndex;
}


@synthesize javaLayer;
@synthesize ctx;
@synthesize bufferWidth;
@synthesize bufferHeight;
@synthesize buffer;
@synthesize topInset;
@synthesize leftInset;
@synthesize nextDrawableCount;
@synthesize displayLink;
@synthesize displayLinkCount;
@synthesize frameCount;
@synthesize blittedFrame;

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
    self.framebufferOnly = NO;
    self.nextDrawableCount = 0;
    self.opaque = YES;
    CVDisplayLinkCreateWithActiveCGDisplays(&displayLink);
    CVDisplayLinkSetOutputCallback(displayLink, &displayLinkCallback, (__bridge void*)self);
    self.displayLinkCount = 0;
    self.frameCount = 0;
    blittedFrame = -1;
    return self;
}

- (void) blitTexture {
    int fCount = self.frameCount;
    if (self.displayLinkCount > 0) {
        self.displayLinkCount--;
    }

    if (self.ctx == NULL || self.javaLayer == NULL || self.buffer == nil || self.ctx.device == nil) {
        J2dTraceLn4(J2D_TRACE_VERBOSE,
                    "MTLLayer.blitTexture: uninitialized (mtlc=%p, javaLayer=%p, buffer=%p, devide=%p)", self.ctx,
                    self.javaLayer, self.buffer, ctx.device);
        [self stopDisplayLink];
        return;
    }

    if (self.nextDrawableCount >= FRAMES_BUFF_SIZE) {
        return;
    }
    BOOL bufEmpty = outFramesIndex == inFramesIndex;
    BOOL directBlit = NO;
    BOOL blitFromBuf = NO;
    BOOL storeToBuf = NO;
    if (bufEmpty && self.nextDrawableCount == 0) {
        if (blittedFrame == fCount) {
            J2dTraceLn1(J2D_TRACE_VERBOSE, "MTLLayer_blitTexture: frame=%d already present", blittedFrame);
            return;
        }
        blittedFrame = fCount;
        directBlit = YES;
    } else if (bufEmpty && self.nextDrawableCount > 0) {
        if (blittedFrame == fCount) {
            J2dTraceLn1(J2D_TRACE_VERBOSE, "MTLLayer_blitTexture: frame=%d already present", blittedFrame);
            return;
        }
        storeToBuf = YES;
        blittedFrame = fCount;
    } else if (!bufEmpty) {
        if (framesNumbers[(inFramesIndex + FRAMES_BUFF_SIZE - 1) % FRAMES_BUFF_SIZE] != fCount)  {
            storeToBuf = YES;
        }
        blittedFrame = framesNumbers[outFramesIndex];
        blitFromBuf = YES;
    }

    @autoreleasepool {
        if ((self.buffer.width == 0) || (self.buffer.height == 0)) {
            J2dTraceLn(J2D_TRACE_VERBOSE, "MTLLayer.blitTexture: cannot create drawable of size 0");
            [self stopDisplayLink];
            return;
        }

        NSUInteger src_x = self.leftInset * self.contentsScale;
        NSUInteger src_y = self.topInset * self.contentsScale;
        NSUInteger src_w = self.buffer.width - src_x;
        NSUInteger src_h = self.buffer.height - src_y;

        if (src_h <= 0 || src_w <= 0) {
            J2dTraceLn(J2D_TRACE_VERBOSE, "MTLLayer.blitTexture: Invalid src width or height.");
            [self stopDisplayLink];
            return;
        }

        id<MTLCommandBuffer> commandBuf = [self.ctx createBlitCommandBuffer];
        if (commandBuf == nil) {
            J2dTraceLn(J2D_TRACE_VERBOSE, "MTLLayer.blitTexture: commandBuf is null");
            [self stopDisplayLink];
            return;
        }
        id<CAMetalDrawable> mtlDrawable = [self nextDrawable];
        if (mtlDrawable == nil) {
            J2dTraceLn(J2D_TRACE_VERBOSE, "MTLLayer.blitTexture: nextDrawable is null)");
            [self stopDisplayLink];
            return;
        }
        self.nextDrawableCount++;
        BOOL present = YES;
        id <MTLBlitCommandEncoder> blitEncoder = nil;
        __block int frame = fCount;
        if (directBlit) {
            blitEncoder = [commandBuf blitCommandEncoder];
            [blitEncoder
                    copyFromTexture:self.buffer sourceSlice:0 sourceLevel:0
                       sourceOrigin:MTLOriginMake(src_x, src_y, 0)
                         sourceSize:MTLSizeMake(src_w, src_h, 1)
                          toTexture:mtlDrawable.texture destinationSlice:0 destinationLevel:0
                  destinationOrigin:MTLOriginMake(0, 0, 0)];
            [blitEncoder endEncoding];
        } else {
            if (storeToBuf || blitFromBuf) {
                blitEncoder = [commandBuf blitCommandEncoder];
            }
            if (storeToBuf) {
                id <MTLTexture> inFrame = framesBuffer[inFramesIndex];
                framesNumbers[inFramesIndex] = fCount;
                inFramesIndex = (inFramesIndex + 1) % FRAMES_BUFF_SIZE;

                [blitEncoder
                        copyFromTexture:self.buffer sourceSlice:0 sourceLevel:0
                           sourceOrigin:MTLOriginMake(src_x, src_y, 0)
                             sourceSize:MTLSizeMake(src_w, src_h, 1)
                              toTexture:inFrame destinationSlice:0 destinationLevel:0
                      destinationOrigin:MTLOriginMake(0, 0, 0)];
            }

            if (blitFromBuf) {
                frame = framesNumbers[outFramesIndex];
                id <MTLTexture> outFrame = framesBuffer[outFramesIndex];
                outFramesIndex = (outFramesIndex + 1) % FRAMES_BUFF_SIZE;
                [blitEncoder
                        copyFromTexture:outFrame sourceSlice:0 sourceLevel:0
                           sourceOrigin:MTLOriginMake(0, 0, 0)
                             sourceSize:MTLSizeMake(outFrame.width, outFrame.height, 1)
                              toTexture:mtlDrawable.texture destinationSlice:0 destinationLevel:0
                      destinationOrigin:MTLOriginMake(0, 0, 0)];
            } else {
                present = NO;
            }
            if (storeToBuf || blitFromBuf) {
                [blitEncoder endEncoding];
            }
        }

        if (present) {
            [commandBuf presentDrawable:mtlDrawable];
        }

        [commandBuf addCompletedHandler:^(id <MTLCommandBuffer> commandBuf) {
            if (self.nextDrawableCount > 0) {
                self.nextDrawableCount--;
            }
            J2dTraceLn1(J2D_TRACE_VERBOSE, "MTLLayer_blitTexture: frame=%d blitted", frame);
        }];

        [commandBuf commit];
        if (self.displayLinkCount <= 0) {
            [self stopDisplayLink];
        }
    }
}

- (void) dealloc {
    JNIEnv *env = [ThreadUtilities getJNIEnvUncached];
    (*env)->DeleteWeakGlobalRef(env, self.javaLayer);
    self.javaLayer = nil;
    [self stopDisplayLink];
    CVDisplayLinkRelease(self.displayLink);
    for (int i = 0; i < FRAMES_BUFF_SIZE; i++) {
        [framesBuffer[i] release];
        framesBuffer[i] = nil;
    }
    self.displayLink = nil;
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

- (void) redraw {
    AWT_ASSERT_APPKIT_THREAD;
    [self setNeedsDisplay];
}

- (void) startDisplayLink {
    if (!CVDisplayLinkIsRunning(self.displayLink)) {
        CVDisplayLinkStart(self.displayLink);
        J2dTraceLn(J2D_TRACE_VERBOSE, "MTLLayer_startDisplayLink");
    }
    displayLinkCount += KEEP_ALIVE_INC; // Keep alive displaylink counter
}

- (void) stopDisplayLink {
    if (CVDisplayLinkIsRunning(self.displayLink)) {
        CVDisplayLinkStop(self.displayLink);
        J2dTraceLn(J2D_TRACE_VERBOSE, "MTLLayer_stopDisplayLink");
    }
}

CVReturn displayLinkCallback(CVDisplayLinkRef displayLink, const CVTimeStamp* now, const CVTimeStamp* outputTime, CVOptionFlags flagsIn, CVOptionFlags* flagsOut, void* displayLinkContext)
{
    J2dTraceLn(J2D_TRACE_VERBOSE, "MTLLayer_displayLinkCallback() called");
    @autoreleasepool {
        MTLLayer *layer = (__bridge MTLLayer *)displayLinkContext;
        [layer performSelectorOnMainThread:@selector(redraw) withObject:nil waitUntilDone:NO];
    }
    return kCVReturnSuccess;
}

- (void) validateEnv:(JNIEnv*)env sData:(jobject)surfaceData {
    if (surfaceData != NULL) {
        BMTLSDOps *bmtlsdo = (BMTLSDOps *) SurfaceData_GetOps(env, surfaceData);
        self.ctx = ((MTLSDOps *) bmtlsdo->privOps)->configInfo->context;
        self.device = self.ctx.device;

        inFramesIndex = 0;
        outFramesIndex = 0;
        BOOL releaseFramesTextures = self.bufferWidth != bmtlsdo->width || self.bufferHeight != bmtlsdo->height;
        for (int i = 0; i < FRAMES_BUFF_SIZE; i++) {
            if (framesBuffer[i] != nil && releaseFramesTextures) {
                [framesBuffer[i] release];
                framesBuffer[i] = nil;
            }

            if (framesBuffer[i] == nil) {
                MTLTextureDescriptor *textureDescriptor =
                        [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
                                                                           width:bmtlsdo->width
                                                                          height:bmtlsdo->height mipmapped:NO];
                textureDescriptor.usage = MTLTextureUsageUnknown;
                textureDescriptor.storageMode = MTLStorageModePrivate;
                framesBuffer[i] = [ctx.device newTextureWithDescriptor:textureDescriptor];
                [framesBuffer[i] retain];
            }
        }
        self.buffer = bmtlsdo->pTexture;
        self.bufferWidth = bmtlsdo->width;
        self.bufferHeight = bmtlsdo->height;
        self.pixelFormat = MTLPixelFormatBGRA8Unorm;
        self.drawableSize =
                CGSizeMake(self.buffer.width,
                           self.buffer.height);
        self.frameCount = 0;
        self.blittedFrame = -2;
        [self performSelectorOnMainThread:@selector(redraw) withObject:nil waitUntilDone:NO];
        [self startDisplayLink];
    } else {
        self.ctx = NULL;
        [self stopDisplayLink];
    }
}

- (void) resetBlittedFrame {
    self.blittedFrame = -2;
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
    [layer validateEnv:env sData:surfaceData];
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
    if (layer == NULL || ctx == NULL) {
        J2dTraceLn(J2D_TRACE_VERBOSE, "MTLLayer_blit : Layer or Context is null");
        [layer stopDisplayLink];
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
