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

#ifndef MTLLayer_h_Included
#define MTLLayer_h_Included
#import <Metal/Metal.h>
#import <QuartzCore/CAMetalLayer.h>
#include <CoreVideo/CVDisplayLink.h>
#import "common.h"
#import "MTLContext.h"

#define TRACE_DISPLAY_ENABLED   1
#define TRACE_DISPLAY_CHANGED   0

#define TRACE_DISPLAY_ON        (TRACE_DISPLAY_ENABLED == 1)

@interface MTLLayer : CAMetalLayer

@property (nonatomic) jobject javaLayer;
@property (readwrite, assign) MTLContext* ctx;
@property (readwrite, assign) NSInteger displayID;
@property (readwrite, assign) id<MTLTexture>* buffer;
@property (readwrite, assign) id<MTLTexture>* outBuffer;
@property (readwrite, atomic) int nextDrawableCount;
@property (readwrite, assign) int topInset;
@property (readwrite, assign) int leftInset;

@property (readwrite, atomic) int redrawCount;
/* avoid reentrance in startRedraw (main thread) (async) */
@property (readwrite, atomic) BOOL willRedraw;
/* reference frame time (only 1 display / blit) */
@property (readwrite, atomic) CFTimeInterval redrawTime;
/* avoid reentrance in CAMetalLayer.display (main thread) --- SYNC ---> blitTexture(RQ lock) */
@property (readwrite, atomic) BOOL willDisplay;
/* redraw version acts like a transaction ID to ensure last version displayed */
@property (readwrite, atomic) int redrawVersion;
@property (readwrite, atomic) int blitVersion;

@property (readwrite, atomic) NSTimeInterval avgBlitFrameTime;

#if TRACE_DISPLAY_ON
@property (readwrite, atomic) NSTimeInterval avgNextDrawableTime;
@property (readwrite, atomic) CFTimeInterval lastPresentedTime;
#endif
@property (readwrite, atomic) BOOL perfCountersEnabled;

- (id) initWithJavaLayer:(jobject)layer usePerfCounters:(jboolean)perfCountersEnabled;

- (void) blitTexture;
- (void) fillParallelogramCtxX:(jfloat)x
                             Y:(jfloat)y
                           DX1:(jfloat)dx1
                           DY1:(jfloat)dy1
                           DX2:(jfloat)dx2
                           DY2:(jfloat)dy2;
- (void) blitCallback;
- (void) display;
- (void) startRedraw;
- (void) stopRedraw:(BOOL)force;
- (void) stopRedraw:(MTLContext*)mtlc displayID:(jint)displayID force:(BOOL)force;
- (void) flushBuffer;
- (void) commitCommandBuffer:(MTLContext*)mtlc wait:(BOOL)waitUntilCompleted display:(BOOL)updateDisplay;

- (void) addStatCallback:(int)type value:(double)value;
- (void) countFramePresentedCallback;
- (void) countFrameDroppedCallback;

- (NSString*) getRedrawInfo;
@end

#endif /* MTLLayer_h_Included */
