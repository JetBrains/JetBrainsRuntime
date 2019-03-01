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

#ifndef HEADLESS

#include <jlong.h>
#include <jni_util.h>
#include <math.h>
#include <Foundation/NSObjCRuntime.h>

#include "sun_java2d_metal_MetalRenderer.h"

#include "MetalRenderer.h"
#include "MetalRenderQueue.h"
#include "MetalSurfaceData.h"
#import "shaders/MetalShaderTypes.h"
#include "Trace.h"
#include "MetalLayer.h"
#import "VertexDataManager.h"


// TODO : Current Color, Gradient etc should have its own class
static float drawColor[4] = {0.0, 0.0, 0.0, 0.0};
static int ClipRectangle[4] = {0, 0, 0, 0};
// The current size of our view so we can use this in our render pipeline
// static unsigned int viewportSize[2] = {0, 0};

void
MetalRenderer_DrawLine(MetalContext *mtlc, jint x1, jint y1, jint x2, jint y2)
{
    //J2dTraceLn(J2D_TRACE_INFO, "OGLRenderer_DrawLine");

    //RETURN_IF_NULL(mtlc);

    //CHECK_PREVIOUS_OP(GL_LINES);
  
    float P1_X, P1_Y;
    float P2_X, P2_Y;
     
    if (y1 == y2) {
        // horizontal
        float fx1 = (float)x1;
        float fx2 = (float)x2;
        float fy  = ((float)y1) + 0.2f;

        if (x1 > x2) {
            float t = fx1; fx1 = fx2; fx2 = t;
        }

        P1_X = fx1+0.2f;
        P1_Y = fy;
        P2_X = fx2+1.2f;
        P2_Y = fy;
    } else if (x1 == x2) {
        // vertical
        float fx  = ((float)x1) + 0.2f;
        float fy1 = (float)y1;
        float fy2 = (float)y2;

        if (y1 > y2) {
            float t = fy1; fy1 = fy2; fy2 = t;
        }

        P1_X = fx;
        P1_Y = fy1+0.2f;
        P2_X = fx; 
        P2_Y = fy2+1.2f;
    } else {
        // diagonal
        float fx1 = (float)x1;
        float fy1 = (float)y1;
        float fx2 = (float)x2;
        float fy2 = (float)y2;

        if (x1 < x2) {
            fx1 += 0.2f;
            fx2 += 1.0f;
        } else {
            fx1 += 0.8f;
            fx2 -= 0.2f;
        }

        if (y1 < y2) {
            fy1 += 0.2f;
            fy2 += 1.0f;
        } else {
            fy1 += 0.8f;
            fy2 -= 0.2f;
        }

        P1_X = fx1;
        P1_Y = fy1;
        P2_X = fx2;
        P2_Y = fy2;
    }
    
    // The (x1, y1) & (x2, y2) are in coordinate system :
    //     Top Left (0, 0) : Bottom Right (width and height)
    //
    // Metal rendering coordinate system is :
    //     Top Left (-1.0, 1.0) : Bottom Right (1.0, -1.0)
    //
    // This coordinate transformation happens in shader code.
       
    MetalVertex lineVertexData[] =
    {
        { {P1_X, P1_Y, 0.0, 1.0}, {drawColor[0], drawColor[1], drawColor[2], drawColor[3]} },
        { {P2_X, P2_Y, 0.0, 1.0}, {drawColor[0], drawColor[1], drawColor[2], drawColor[3]} }
    };
    
    //NSLog(@"Drawline ----- x1 : %f, y1 : %f------ x2 : %f, y2 = %f", x1/halfWidth, y1/halfHeight,  x2/halfWidth, y2/halfHeight); 

    VertexDataManager_addLineVertexData(lineVertexData[0], lineVertexData[1]);
}



void
MetalRenderer_DrawParallelogram(MetalContext *mtlc,
                              jfloat fx11, jfloat fy11,
                              jfloat dx21, jfloat dy21,
                              jfloat dx12, jfloat dy12,
                              jfloat lwr21, jfloat lwr12)
{
    // dx,dy for line width in the "21" and "12" directions.
    jfloat ldx21 = dx21 * lwr21;
    jfloat ldy21 = dy21 * lwr21;
    jfloat ldx12 = dx12 * lwr12;
    jfloat ldy12 = dy12 * lwr12;

    // calculate origin of the outer parallelogram
    jfloat ox11 = fx11 - (ldx21 + ldx12) / 2.0f;
    jfloat oy11 = fy11 - (ldy21 + ldy12) / 2.0f;

    /*J2dTraceLn8(J2D_TRACE_INFO,
                "OGLRenderer_DrawParallelogram "
                "(x=%6.2f y=%6.2f "
                "dx1=%6.2f dy1=%6.2f lwr1=%6.2f "
                "dx2=%6.2f dy2=%6.2f lwr2=%6.2f)",
                fx11, fy11,
                dx21, dy21, lwr21,
                dx12, dy12, lwr12);*/

    // RETURN_IF_NULL(oglc);

    // CHECK_PREVIOUS_OP(GL_QUADS);

    // Only need to generate 4 quads if the interior still
    // has a hole in it (i.e. if the line width ratio was
    // less than 1.0)
    if (lwr21 < 1.0f && lwr12 < 1.0f) {

        // Note: "TOP", "BOTTOM", "LEFT" and "RIGHT" here are
        // relative to whether the dxNN variables are positive
        // and negative.  The math works fine regardless of
        // their signs, but for conceptual simplicity the
        // comments will refer to the sides as if the dxNN
        // were all positive.  "TOP" and "BOTTOM" segments
        // are defined by the dxy21 deltas.  "LEFT" and "RIGHT"
        // segments are defined by the dxy12 deltas.

        // Each segment includes its starting corner and comes
        // to just short of the following corner.  Thus, each
        // corner is included just once and the only lengths
        // needed are the original parallelogram delta lengths
        // and the "line width deltas".  The sides will cover
        // the following relative territories:
        //
        //     T T T T T R
        //      L         R
        //       L         R
        //        L         R
        //         L         R
        //          L B B B B B

        // TOP segment, to left side of RIGHT edge
        // "width" of original pgram, "height" of hor. line size
        fx11 = ox11;
        fy11 = oy11;
        FILL_PGRAM(fx11, fy11, dx21, dy21, ldx12, ldy12);

        // RIGHT segment, to top of BOTTOM edge
        // "width" of vert. line size , "height" of original pgram
        fx11 = ox11 + dx21;
        fy11 = oy11 + dy21;
        FILL_PGRAM(fx11, fy11, ldx21, ldy21, dx12, dy12);

        // BOTTOM segment, from right side of LEFT edge
        // "width" of original pgram, "height" of hor. line size
        fx11 = ox11 + dx12 + ldx21;
        fy11 = oy11 + dy12 + ldy21;
        FILL_PGRAM(fx11, fy11, dx21, dy21, ldx12, ldy12);

        // LEFT segment, from bottom of TOP edge
        // "width" of vert. line size , "height" of inner pgram
        fx11 = ox11 + ldx12;
        fy11 = oy11 + ldy12;
        FILL_PGRAM(fx11, fy11, ldx21, ldy21, dx12, dy12);
    } else {
        // The line width ratios were large enough to consume
        // the entire hole in the middle of the parallelogram
        // so we can just issue one large quad for the outer
        // parallelogram.
        dx21 += ldx21;
        dy21 += ldy21;
        dx12 += ldx12;
        dy12 += ldy12;
        FILL_PGRAM(ox11, oy11, dx21, dy21, dx12, dy12);
    }
}


void
MetalRenderer_FillParallelogram(MetalContext *mtlc,
                              jfloat fx11, jfloat fy11,
                              jfloat dx21, jfloat dy21,
                              jfloat dx12, jfloat dy12)
{
    /*J2dTraceLn6(J2D_TRACE_INFO,
                "OGLRenderer_FillParallelogram "
                "(x=%6.2f y=%6.2f "
                "dx1=%6.2f dy1=%6.2f "
                "dx2=%6.2f dy2=%6.2f)",
                fx11, fy11,
                dx21, dy21,
                dx12, dy12);

    RETURN_IF_NULL(oglc);

    CHECK_PREVIOUS_OP(GL_QUADS);*/

    FILL_PGRAM(fx11, fy11, dx21, dy21, dx12, dy12);
}


void FILL_PGRAM(float fx11, float fy11, float dx21, float dy21, float dx12, float dy12) {

    MetalRenderer_DrawQuad(fx11, fy11, 
                            fx11 + dx21, fy11 + dy21,
                            fx11 + dx21 + dx12, fy11 + dy21 + dy12,
                            fx11 + dx12, fy11 + dy12);
} 



void MetalRenderer_DrawQuad(float x1, float y1, float x2, float y2, float x3, float y3, float x4, float y4) {
   
    // Draw two triangles with given 4 vertices
  
    // The (x1, y1) & (x2, y2) are in coordinate system : 
    //     Top Left (0, 0) : Bottom Right (width and height)
    //
    // Metal rendering coordinate system is :
    //     Top Left (-1.0, 1.0) : Bottom Right (1.0, -1.0)
    //
    // This coordinate transformation happens in shader code. 

    MetalVertex QuadVertexData[] =
    {
        { {x1, y1, 0.0, 1.0}, {drawColor[0], drawColor[1], drawColor[2], drawColor[3]} },
        { {x2, y2, 0.0, 1.0}, {drawColor[0], drawColor[1], drawColor[2], drawColor[3]} },
        { {x3, y3, 0.0, 1.0}, {drawColor[0], drawColor[1], drawColor[2], drawColor[3]} },
        { {x4, y4, 0.0, 1.0}, {drawColor[0], drawColor[1], drawColor[2], drawColor[3]} },
    };

    VertexDataManager_addQuadVertexData(QuadVertexData[0], QuadVertexData[1], QuadVertexData[2], QuadVertexData[3]);
}


void MetalRenderer_SetColor(MetalContext *mtlc, jint color) {
    //J2dTraceLn(J2D_TRACE_INFO, "MetalRenderer_SetColor");
    unsigned char r = (unsigned char)(color >> 16);
    unsigned char g = (unsigned char)(color >>  8);
    unsigned char b = (unsigned char)(color >>  0);
    unsigned char a = 0xff;
    
    drawColor[0] = r/255.0;
    drawColor[1] = g/255.0;
    drawColor[2] = b/255.0;
    drawColor[3] = 1.0;
    
    NSLog(@"MetalRenderer SetColor  ----- (%d, %d, %d, %d)", r, g, b, a);
}


void MetalRenderer_DrawRect(MetalContext *mtlc,
                          jint x, jint y, jint w, jint h) {
    //J2dTraceLn(J2D_TRACE_INFO, "OGLRenderer_DrawRect");

    if (w < 0 || h < 0) {
        return;
    }

    //RETURN_IF_NULL(oglc);

    if (w < 2 || h < 2) {
        // If one dimension is less than 2 then there is no
        // gap in the middle - draw a solid filled rectangle.
        //CHECK_PREVIOUS_OP(GL_QUADS);
        //GLRECT_BODY_XYWH(x, y, w+1, h+1);
        MetalRenderer_FillRect(mtlc, x, y, w+1, h+1);
    } else {
        jint fx1 = (jint) (((float)x) + 0.2f);
        jint fy1 = (jint) (((float)y) + 0.2f);
        jint fx2 = fx1 + w;
        jint fy2 = fy1 + h;

        // Avoid drawing the endpoints twice.
        // Also prefer including the endpoints in the
        // horizontal sections which draw pixels faster.

        // top
        MetalRenderer_DrawLine(mtlc, fx1, fy1, fx2+1, fy1);
        
        // right
        MetalRenderer_DrawLine(mtlc, fx2, fy1+1, fx2, fy2);

        // bottom
        MetalRenderer_DrawLine(mtlc, fx1, fy2, fx2+1, fy2);


        // left
        MetalRenderer_DrawLine(mtlc, fx1, fy1+1, fx1, fy2);
    }
}


void
MetalRenderer_FillRect(MetalContext *mtlc, jint x, jint y, jint w, jint h)
{
    //J2dTraceLn(J2D_TRACE_INFO, "MetalRenderer_FillRect");

    if (w <= 0 || h <= 0) {
        return;
    }

    //RETURN_IF_NULL(oglc);

    //CHECK_PREVIOUS_OP(GL_QUADS);
    //GLRECT_BODY_XYWH(x, y, w, h);

    
    MetalRenderer_DrawQuad(x, y, x, y+h, x+w, y+h, x+w, y);

    //NSLog(@"MetalRenderer_FillRect: X, Y(%f, %f) with width, height(%f, %f)", (float)x, (float)y, (float)w, (float)h);
}

// TODO : I think, this should go to metal context
void MetalRenderer_SetRectClip(MetalContext *mtlc, jint x1, jint y1, jint x2, jint y2) {

    jint width = x2 - x1;
    jint height = y2 - y1;

    J2dTraceLn4(J2D_TRACE_INFO,
                "MetalRenderer_SetRectClip: x=%d y=%d w=%d h=%d",
                x1, y1, width, height);

    //RETURN_IF_NULL(dstOps);
    //RETURN_IF_NULL(oglc);
    //CHECK_PREVIOUS_OP(OGL_STATE_CHANGE);

    if ((width < 0) || (height < 0)) {
        // use an empty scissor rectangle when the region is empty
        width = 0;
        height = 0;
    }

    //j2d_glDisable(GL_DEPTH_TEST);
    //j2d_glEnable(GL_SCISSOR_TEST);

    // the scissor rectangle is specified using the lower-left
    // origin of the clip region (in the framebuffer's coordinate
    // space), so we must account for the x/y offsets of the
    // destination surface
    /*j2d_glScissor(dstOps->xOffset + x1,
                  dstOps->yOffset + dstOps->height - (y1 + height),
                  width, height);*/

    MetalSDOps *dstOps = MetalRenderQueue_GetCurrentDestination();  

    ClipRectangle[0] = x1;//dstOps->xOffset + x1;
    ClipRectangle[1] = y1;//dstOps->yOffset + dstOps->height - (y1 + height);
    ClipRectangle[2] = width;
    ClipRectangle[3] = height; 

}

void MetalRenderer_Flush() {

    MetalSDOps* dstOps = MetalRenderQueue_GetCurrentDestination();  
    MetalLayer* mtlLayer = dstOps->layer;

    unsigned int viewportSize[2] = {mtlLayer.textureWidth, mtlLayer.textureHeight};

    //Create a render pass descriptor
    MTLRenderPassDescriptor* mtlRenderPassDescriptor = [MTLRenderPassDescriptor renderPassDescriptor];
        
    //Set the SurfaceData offscreen texture as target texture for the rendering pipeline
    mtlRenderPassDescriptor.colorAttachments[0].texture = dstOps->mtlTexture;

    mtlRenderPassDescriptor.colorAttachments[0].loadAction = MTLLoadActionClear;
    mtlRenderPassDescriptor.colorAttachments[0].clearColor = MTLClearColorMake(0.8, 0.8, 0.8, 1.0);
    mtlRenderPassDescriptor.colorAttachments[0].storeAction = MTLStoreActionStore;

    id<MTLCommandBuffer> mtlCommandBuffer = [dstOps->configInfo->commandQueue commandBuffer];
    id<MTLRenderCommandEncoder> renderEncoder = [mtlCommandBuffer renderCommandEncoderWithDescriptor:mtlRenderPassDescriptor];

    // Configure render enconder with the pipeline state
    [renderEncoder setRenderPipelineState:mtlLayer.renderPipelineState];

    // Whatever outside this rectangle won't be drawn
    // TODO : ClipRectangle should be part of MetalContext or some state maintaining class
    NSLog(@"Setting Rect Clip : %d, %d, %d, %d", ClipRectangle[0], ClipRectangle[1], ClipRectangle[2], ClipRectangle[3]);
    MTLScissorRect clip = {ClipRectangle[0], ClipRectangle[1], ClipRectangle[2], ClipRectangle[3]};
    [renderEncoder setScissorRect:clip];

    // ---------------------------------------------------------
    // DRAW primitives from VertexDataManager
    // ---------------------------------------------------------    
    [renderEncoder setVertexBuffer:VertexDataManager_getVertexBuffer() offset:0 atIndex:0]; // 0th index 
    
    [renderEncoder setVertexBytes: &viewportSize
                           length: sizeof(viewportSize)
                          atIndex: 1]; // 1st index

    MetalPrimitiveData** allPrimitives = VertexDataManager_getAllPrimitives();

    int totalPrimitives = VertexDataManager_getNoOfPrimitives();
    for (int i = 0; i < totalPrimitives; i++ ) {
        MetalPrimitiveData* p = allPrimitives[i];

        NSLog(@"----------------------------------------------");
        NSLog(@"Encoding primitive %d", i);
        NSLog(@"indexCount %d", p->no_of_indices);
        NSLog(@"indexBufferOffset %d", p->offset_in_index_buffer);
        NSLog(@"primitiveInstances %d", p->primitiveInstances);    
        NSLog(@"----------------------------------------------");


        [renderEncoder drawIndexedPrimitives: p->type
                                  indexCount: (NSUInteger)p->no_of_indices 
                                   indexType: (MTLIndexType)MTLIndexTypeUInt16
                                 indexBuffer: (id<MTLBuffer>)VertexDataManager_getIndexBuffer() 
                           indexBufferOffset: (NSUInteger)p->offset_in_index_buffer 
                               instanceCount: (NSUInteger)p->primitiveInstances];
    }

    //--------------------------------------------------  

    [renderEncoder endEncoding];
   
    [mtlCommandBuffer commit];

    [mtlCommandBuffer waitUntilCompleted];
}


void MetalRenderer_blitToScreenDrawable() {
 
    MetalSDOps* dstOps = MetalRenderQueue_GetCurrentDestination();  
    MetalLayer* mtlLayer = dstOps->layer;
    
    @autoreleasepool {
        id <CAMetalDrawable> frameDrawable = [mtlLayer nextDrawable];

        id<MTLCommandBuffer> commandBuffer = [dstOps->configInfo->commandQueue commandBuffer];
    
        id<MTLBlitCommandEncoder> blitEncoder = [commandBuffer blitCommandEncoder];
   
        //[blitEncoder synchronizeResource:_texture];

        [blitEncoder copyFromTexture:dstOps->mtlTexture
                     sourceSlice:0
                     sourceLevel:0
                     sourceOrigin:MTLOriginMake(ClipRectangle[0], ClipRectangle[1], 0)
                     sourceSize:MTLSizeMake(dstOps->mtlTexture.width - ClipRectangle[0], dstOps->mtlTexture.height - ClipRectangle[1], 1)
                     toTexture:frameDrawable.texture
                     destinationSlice:0
                     destinationLevel:0
                     destinationOrigin:MTLOriginMake(0, 0, 0)];
       
        [blitEncoder endEncoding];
    
        [commandBuffer presentDrawable:frameDrawable];
    
        [commandBuffer commit];
    
        [commandBuffer waitUntilCompleted];
    }
}

#endif
