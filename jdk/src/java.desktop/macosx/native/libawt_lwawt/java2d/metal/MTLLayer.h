/*
 * Copyright 2018 JetBrains s.r.o.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
#import <MetalKit/MetalKit.h>
#import "common.h"

#import <JavaNativeFoundation/JavaNativeFoundation.h>

@interface MTLLayer : CAMetalLayer
{
@private
    JNFWeakJObjectWrapper *javaLayer;

    // intermediate buffer, used the RQ lock to synchronize
    GLuint textureID;
    GLenum target;
    float textureWidth;
    float textureHeight;
}

@property (nonatomic, retain) JNFWeakJObjectWrapper *javaLayer;
@property (readwrite, assign) GLuint textureID;
@property (readwrite, assign) GLenum target;
@property (readwrite, assign) float textureWidth;
@property (readwrite, assign) float textureHeight;

#ifdef REMOTELAYER
@property (nonatomic, retain) CGLLayer *parentLayer;
@property (nonatomic, retain) CGLLayer *remoteLayer;
@property (nonatomic, retain) NSObject<JRSRemoteLayer> *jrsRemoteLayer;
#endif

- (id) initWithJavaLayer:(JNFWeakJObjectWrapper *)layer;

- (void) blitTexture;
- (void) fillParallelogramCtx:(MTLCtxInfo*)ctx
                          X:(jfloat)x
                          Y:(jfloat)y
                        DX1:(jfloat)dx1
                        DY1:(jfloat)dy1
                        DX2:(jfloat)dx2
                        DY2:(jfloat)dy2;
- (void) beginFrameCtx:(MTLCtxInfo*)ctx;
- (void) endFrameCtx:(MTLCtxInfo*)ctx;

@end

#endif /* CGLLayer_h_Included */
