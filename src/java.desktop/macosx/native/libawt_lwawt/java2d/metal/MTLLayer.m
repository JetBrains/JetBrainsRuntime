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
@synthesize nextDrawableCount;
@synthesize topInset;
@synthesize leftInset;

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
    self.topInset = 0;
    self.leftInset = 0;
    self.framebufferOnly = NO;
    self.nextDrawableCount = 0;
    return self;
}

- (void) blitTexture {
    if (self.ctx == NULL || self.javaLayer == NULL || self.buffer == nil || self.ctx.device == nil) {
        J2dTraceLn4(J2D_TRACE_VERBOSE, "MTLLayer.blitTexture: uninitialized (mtlc=%p, javaLayer=%p, buffer=%p, devide=%p)", self.ctx, self.javaLayer, self.buffer, ctx.device);
        return;
    }

    id<MTLCommandBuffer> commandBuf = self.ctx.commandBuffer;
    if (commandBuf == nil) {
        J2dTraceLn(J2D_TRACE_VERBOSE, "MTLLayer.blitTexture: nothing to do (commandBuf is null)");
        return;
    }

    if (self.nextDrawableCount == 0) {
    @autoreleasepool {

        if ((self.buffer.width == 0) || (self.buffer.height == 0)) {
            J2dTraceLn(J2D_TRACE_VERBOSE, "MTLLayer.blitTexture: cannot create drawable of size 0");

            [self.ctx releaseCommandBuffer];
            [self.ctx.texturePool markAllTexturesFree];
            return;
        }

        if (@available(macOS 10.13, *)) {
            self.displaySyncEnabled = NO;
        }
        id<CAMetalDrawable> mtlDrawable = [self nextDrawable];
        if (mtlDrawable == nil) {
            J2dTraceLn(J2D_TRACE_VERBOSE, "MTLLayer.blitTexture: nextDrawable is null)");

            [self.ctx releaseCommandBuffer];
            [self.ctx.texturePool markAllTexturesFree];
            return;
        }
        self.nextDrawableCount++;
        J2dTraceLn6(J2D_TRACE_INFO, "MTLLayer.blitTexture: src tex=%p (w=%d, h=%d), dst tex=%p (w=%d, h=%d)", self.buffer, self.buffer.width, self.buffer.height, mtlDrawable.texture, mtlDrawable.texture.width, mtlDrawable.texture.height);
        id <MTLBlitCommandEncoder> blitEncoder = [commandBuf blitCommandEncoder];
        [blitEncoder
                copyFromTexture:self.buffer sourceSlice:0 sourceLevel:0
                sourceOrigin:MTLOriginMake((jint)(self.leftInset*self.contentsScale), (jint)(self.topInset*self.contentsScale), 0)
                sourceSize:MTLSizeMake(self.buffer.width, self.buffer.height, 1)
                toTexture:mtlDrawable.texture destinationSlice:0 destinationLevel:0 destinationOrigin:MTLOriginMake(0, 0, 0)];
        [blitEncoder endEncoding];

        [commandBuf presentDrawable:mtlDrawable];

        [commandBuf addCompletedHandler:^(id <MTLCommandBuffer> cmdBuff) {
                [self.ctx.texturePool markAllTexturesFree];
                [self.ctx releaseCommandBuffer];
                self.nextDrawableCount--;
                if (@available(macOS 10.13, *)) {
                    self.displaySyncEnabled = YES;
                }
        }];

        [commandBuf commit];
        [commandBuf waitUntilCompleted];
        [self.ctx releaseCommandBuffer];
    }
    }
}

- (void) dealloc {
    self.javaLayer = nil;
    [super dealloc];
}

- (void) blitCallback {

    JNIEnv *env = [ThreadUtilities getJNIEnv];
    static JNF_CLASS_CACHE(jc_JavaLayer, "sun/java2d/metal/MTLLayer");
    static JNF_MEMBER_CACHE(jm_drawInMTLContext, jc_JavaLayer, "drawInMTLContext", "()V");

    jobject javaLayerLocalRef = [self.javaLayer jObjectWithEnv:env];
    if ((*env)->IsSameObject(env, javaLayerLocalRef, NULL)) {
        return;
    }

    JNFCallVoidMethod(env, javaLayerLocalRef, jm_drawInMTLContext);
    (*env)->DeleteLocalRef(env, javaLayerLocalRef);
}

- (void) display {
    AWT_ASSERT_APPKIT_THREAD;
    J2dTraceLn(J2D_TRACE_VERBOSE, "MTLLayer_display() called");
    [self blitCallback];
    [super display];
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
        layer.device = layer.ctx.device;
        layer.pixelFormat = MTLPixelFormatBGRA8Unorm;
        layer.drawableSize =
            CGSizeMake(layer.buffer.width,
                       layer.buffer.height);
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
        return;
    }

    [layer blitTexture];

    [ctx releaseCommandBuffer];
}
