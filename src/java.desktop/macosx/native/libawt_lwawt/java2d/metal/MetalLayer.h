/*
 * Copyright (c) 2019, 2019, Oracle and/or its affiliates. All rights reserved.
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

#ifndef MetalLayer_h_Included
#define MetalLayer_h_Included

#import <JavaNativeFoundation/JavaNativeFoundation.h>
#import <QuartzCore/CAMetalLayer.h>
#import <Metal/Metal.h>


@interface MetalLayer : CAMetalLayer
{
@private
    JNFWeakJObjectWrapper *javaLayer;

    // intermediate buffer, used the RQ lock to synchronize
    id<MTLTexture> mtlTexture;
    //GLenum target;
    float textureWidth;
    float textureHeight;
#ifdef REMOTELAYER
    //CGLLayer *parentLayer;
    //CGLLayer *remoteLayer;
    //NSObject<JRSRemoteLayer> *jrsRemoteLayer;
#endif /* REMOTELAYER */
}

@property (nonatomic, retain) JNFWeakJObjectWrapper *javaLayer;
@property (readwrite, assign) float textureWidth;
@property (readwrite, assign) float textureHeight;
@property (readwrite, assign) id<MTLTexture> mtlTexture;
@property (readwrite, assign) id<MTLLibrary> mtlLibrary;
@property (readwrite, assign) MTLRenderPipelineDescriptor* mtlRenderPipelineDescriptor;
@property (readwrite, assign) id<MTLRenderPipelineState> renderPipelineState;
//@property (readwrite, assign) id<MTLBuffer> LineVertexBuffer;
//@property (readwrite, assign) id<MTLBuffer> QuadVertexBuffer;
#ifdef REMOTELAYER
//@property (nonatomic, retain) CGLLayer *parentLayer;
//@property (nonatomic, retain) CGLLayer *remoteLayer;
//@property (nonatomic, retain) NSObject<JRSRemoteLayer> *jrsRemoteLayer;
#endif

- (id) initWithJavaLayer:(JNFWeakJObjectWrapper *)javaLayer;
- (void) blitTexture;
@end


// dummy for PoC purpose
void MetalLayer_drawLine(float x1, float y1, float x2, float y2);
void MetalLayer_setColor(int color);

#endif /* MetalLayer_h_Included */
