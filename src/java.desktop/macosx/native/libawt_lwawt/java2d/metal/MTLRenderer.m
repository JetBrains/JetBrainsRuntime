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

#ifndef HEADLESS

#include <jlong.h>
#include <jni_util.h>
#include <math.h>


#define DEBUG 0

#include "sun_java2d_metal_MTLRenderer.h"

#include "MTLRenderer.h"
#include "MTLRenderQueue.h"
#include "MTLSurfaceData.h"
#include "MTLUtils.h"
#import "MTLLayer.h"

void MTLRenderer_FillParallelogramMetal(
    MTLContext* mtlc, id<MTLTexture> dest, jfloat x, jfloat y, jfloat dx1, jfloat dy1, jfloat dx2, jfloat dy2)
{
    if (mtlc == NULL || dest == nil)
        return;

    J2dTraceLn7(J2D_TRACE_INFO,
                "MTLRenderer_FillParallelogramMetal "
                "(x=%6.2f y=%6.2f "
                "dx1=%6.2f dy1=%6.2f "
                "dx2=%6.2f dy2=%6.2f dst tex=%p)",
                x, y,
                dx1, dy1,
                dx2, dy2, dest);

    mtlc->mtlEmptyCommandBuffer = NO;

    struct Vertex verts[PGRAM_VERTEX_COUNT] = {
    { {(2.0*x/dest.width) - 1.0,
       2.0*(1.0 - y/dest.height) - 1.0, 0.0}},

    { {2.0*(x+dx1)/dest.width - 1.0,
      2.0*(1.0 - (y+dy1)/dest.height) - 1.0, 0.0}},

    { {2.0*(x+dx2)/dest.width - 1.0,
      2.0*(1.0 - (y+dy2)/dest.height) - 1.0, 0.0}},

    { {2.0*(x+dx1)/dest.width - 1.0,
      2.0*(1.0 - (y+dy1)/dest.height) - 1.0, 0.0}},

    { {2.0*(x + dx1 + dx2)/dest.width - 1.0,
      2.0*(1.0 - (y+ dy1 + dy2)/dest.height) - 1.0, 0.0}},

    { {2.0*(x+dx2)/dest.width - 1.0,
      2.0*(1.0 - (y+dy2)/dest.height) - 1.0, 0.0},
    }};

    // Encode render command.
    id<MTLRenderCommandEncoder> mtlEncoder = MTLContext_CreateRenderEncoder(mtlc, dest);
    if (mtlEncoder == nil)
        return;

    [mtlEncoder setVertexBytes:verts length:sizeof(verts) atIndex:MeshVertexBuffer];
    [mtlEncoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount: PGRAM_VERTEX_COUNT];
    [mtlEncoder endEncoding];
}

/**
 * Note: Some of the methods in this file apply a "magic number"
 * translation to line segments.  The OpenGL specification lays out the
 * "diamond exit rule" for line rasterization, but it is loose enough to
 * allow for a wide range of line rendering hardware.  (It appears that
 * some hardware, such as the Nvidia GeForce2 series, does not even meet
 * the spec in all cases.)  As such it is difficult to find a mapping
 * between the Java2D and OpenGL line specs that works consistently across
 * all hardware combinations.
 *
 * Therefore the "magic numbers" you see here have been empirically derived
 * after testing on a variety of graphics hardware in order to find some
 * reasonable middle ground between the two specifications.  The general
 * approach is to apply a fractional translation to vertices so that they
 * hit pixel centers and therefore touch the same pixels as in our other
 * pipelines.  Emphasis was placed on finding values so that MTL lines with
 * a slope of +/- 1 hit all the same pixels as our other (software) loops.
 * The stepping in other diagonal lines rendered with MTL may deviate
 * slightly from those rendered with our software loops, but the most
 * important thing is that these magic numbers ensure that all MTL lines
 * hit the same endpoints as our software loops.
 *
 * If you find it necessary to change any of these magic numbers in the
 * future, just be sure that you test the changes across a variety of
 * hardware to ensure consistent rendering everywhere.
 */

void MTLRenderer_DrawLineMetal(MTLContext *mtlc, id<MTLTexture> dest, jfloat x1, jfloat y1, jfloat x2, jfloat y2) {
    if (mtlc == NULL || dest == nil)
        return;

    J2dTraceLn5(J2D_TRACE_INFO, "MTLRenderer_DrawLineMetal (x1=%1.2f y1=%1.2f x2=%1.2f y2=%1.2f), dst tex=%p", x1, y1, x2, y2, dest);

    id<MTLRenderCommandEncoder> mtlEncoder = MTLContext_CreateRenderEncoder(mtlc, dest);
    if (mtlEncoder == nil)
        return;

    mtlc->mtlEmptyCommandBuffer = NO;

    struct Vertex verts[2] = {
            {{MTLUtils_normalizeX(dest, x1), MTLUtils_normalizeY(dest, y1), 0.0}},
            {{MTLUtils_normalizeX(dest, x2), MTLUtils_normalizeY(dest, y2), 0.0}}
    };

    [mtlEncoder setVertexBytes:verts length:sizeof(verts) atIndex:MeshVertexBuffer];
    [mtlEncoder drawPrimitives:MTLPrimitiveTypeLine vertexStart:0 vertexCount:2];
    [mtlEncoder endEncoding];
}

void MTLRenderer_DrawLine(MTLContext *mtlc, jint x1, jint y1, jint x2, jint y2) {
    BMTLSDOps *dstOps = MTLRenderQueue_GetCurrentDestination();
    if (dstOps == NULL || dstOps->privOps == NULL) {
        J2dTraceLn(J2D_TRACE_ERROR, "MTLRenderer_DrawLine: dest is null");
        return;
    }

    MTLSDOps *dstCGLOps = (MTLSDOps *)dstOps->privOps;
    MTLRenderer_DrawLineMetal(dstCGLOps->configInfo->context, dstOps->pTexture, x1, y1, x2, y2);
}

void MTLRenderer_DrawRectMetal(MTLContext *mtlc, id<MTLTexture> dest, jint x, jint y, jint w, jint h) {
    if (mtlc == NULL || dest == nil)
        return;

    J2dTraceLn5(J2D_TRACE_INFO, "MTLRenderer_DrawRectMetal (x=%d y=%d w=%d h=%d), dst tex=%p", x, y, w, h, dest);

    // TODO: use DrawParallelogram(x, y, w, h, lw=1, lh=1)
    id<MTLRenderCommandEncoder> mtlEncoder = MTLContext_CreateRenderEncoder(mtlc, dest);
    if (mtlEncoder == nil)
        return;

    mtlc->mtlEmptyCommandBuffer = NO;

    const int verticesCount = 5;
    struct Vertex vertices[verticesCount] = {
            {{MTLUtils_normalizeX(dest, x), MTLUtils_normalizeY(dest, y), 0.0}},
            {{MTLUtils_normalizeX(dest, x + w), MTLUtils_normalizeY(dest, y), 0.0}},
            {{MTLUtils_normalizeX(dest, x + w), MTLUtils_normalizeY(dest, y + h), 0.0}},
            {{MTLUtils_normalizeX(dest, x), MTLUtils_normalizeY(dest, y + h), 0.0}},
            {{MTLUtils_normalizeX(dest, x), MTLUtils_normalizeY(dest, y), 0.0}},
    };
    [mtlEncoder setVertexBytes:vertices length:sizeof(vertices) atIndex:MeshVertexBuffer];
    [mtlEncoder drawPrimitives:MTLPrimitiveTypeLineStrip vertexStart:0 vertexCount:verticesCount];
    [mtlEncoder endEncoding];
}

void MTLRenderer_DrawRect(MTLContext *mtlc, jint x, jint y, jint w, jint h) {
    BMTLSDOps *bmtldst = MTLRenderQueue_GetCurrentDestination();
    if (bmtldst == NULL || bmtldst->privOps == NULL) {
        J2dTraceLn(J2D_TRACE_ERROR, "MTLRenderer_DrawRect: dest is null");
        return;
    }

    MTLSDOps *dstCGLOps = (MTLSDOps *)bmtldst->privOps;
    MTLRenderer_DrawRectMetal(dstCGLOps->configInfo->context, bmtldst->pTexture, x, y, w, h);
}

void _tracePoints(jint nPoints, jint *xPoints, jint *yPoints) {
    for (int i = 0; i < nPoints; i++)
        J2dTraceLn2(J2D_TRACE_INFO, "\t(%d, %d)", *(xPoints++), *(yPoints++));
}

const int POLYLINE_BUF_SIZE = 64;

void _fillVertex(struct Vertex * vertex, int x, int y, int destW, int destH) {
    vertex->position[0] = 2.0*x/destW - 1.0;
    vertex->position[1] = 2.0*(1.0 - y/destH) - 1.0;
    vertex->position[2] = 0;
}

void MTLRenderer_DrawPoly(MTLContext *mtlc,
                     jint nPoints, jint isClosed,
                     jint transX, jint transY,
                     jint *xPoints, jint *yPoints)
{
    // Note that BufferedRenderPipe.drawPoly() has already rejected polys
    // with nPoints<2, so we can be certain here that we have nPoints>=2.
    if (xPoints == NULL || yPoints == NULL || nPoints < 2) { // just for insurance
        J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLRenderer_DrawPoly: points array is empty");
        return;
    }

    BMTLSDOps *dstOps = MTLRenderQueue_GetCurrentDestination();
    if (dstOps == NULL || dstOps->privOps == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLRenderer_DrawPoly: current dest is null");
        return;
    }

    MTLSDOps *dstCGLOps = (MTLSDOps *) dstOps->privOps;
    MTLContext* ctx = dstCGLOps->configInfo->context;
    if (ctx == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLRenderer_DrawPoly: current mtlContext os null");
        return;
    }

    J2dTraceLn4(J2D_TRACE_INFO, "MTLRenderer_DrawPoly: %d points, transX=%d, transY=%d, dst tex=%p", nPoints, transX, transY, dstOps->pTexture);

    ctx->mtlEmptyCommandBuffer = NO;

    __block struct {
        struct Vertex verts[POLYLINE_BUF_SIZE];
    } pointsChunk;

    jint prevX = *(xPoints++);
    jint prevY = *(yPoints++);
    --nPoints;
    const jint firstX = prevX;
    const jint firstY = prevY;
    while (nPoints > 0) {
        _fillVertex(pointsChunk.verts, prevX + transX, prevY + transY, dstOps->width, dstOps->height);

        const bool isLastChunk = nPoints + 1 <= POLYLINE_BUF_SIZE;
        __block int chunkSize = isLastChunk ? nPoints : POLYLINE_BUF_SIZE - 1;

        for (int i = 1; i < chunkSize; i++) {
            prevX = *(xPoints++);
            prevY = *(yPoints++);
            _fillVertex(pointsChunk.verts + i, prevX + transX, prevY + transY, dstOps->width, dstOps->height);
        }

        bool drawCloseSegment = false;
        if (isClosed && isLastChunk) {
            if (chunkSize + 2 <= POLYLINE_BUF_SIZE) {
                _fillVertex(pointsChunk.verts + chunkSize, firstX + transX, firstY + transY, dstOps->width, dstOps->height);
                ++chunkSize;
            } else
                drawCloseSegment = true;
        }

        nPoints -= chunkSize;
        id<MTLRenderCommandEncoder> mtlEncoder = MTLContext_CreateRenderEncoder(ctx, dstOps->pTexture);
        if (mtlEncoder == nil)
            return;

        [mtlEncoder setVertexBytes:pointsChunk.verts length:sizeof(pointsChunk.verts) atIndex:MeshVertexBuffer];
        [mtlEncoder drawPrimitives:MTLPrimitiveTypeLineStrip vertexStart:0 vertexCount:chunkSize + 1];
        if (drawCloseSegment) {
            struct Vertex vertices[2] = {
                    {{MTLUtils_normalizeX(dstOps->pTexture, prevX + transX),     MTLUtils_normalizeY(dstOps->pTexture, prevY + transY), 0.0}},
                    {{MTLUtils_normalizeX(dstOps->pTexture, firstX + transX),    MTLUtils_normalizeY(dstOps->pTexture, firstY + transY), 0.0}},
            };
            [mtlEncoder setVertexBytes:vertices length:sizeof(vertices) atIndex:MeshVertexBuffer];
            [mtlEncoder drawPrimitives:MTLPrimitiveTypeLine vertexStart:0 vertexCount:2];
        }

        [mtlEncoder endEncoding];
    }
}

JNIEXPORT void JNICALL
Java_sun_java2d_metal_MTLRenderer_drawPoly
    (JNIEnv *env, jobject mtlr,
     jintArray xpointsArray, jintArray ypointsArray,
     jint nPoints, jboolean isClosed,
     jint transX, jint transY)
{
    jint *xPoints, *yPoints;
    //TODO
    J2dTraceNotImplPrimitive("MTLRenderer_drawPoly");
    J2dTraceLn(J2D_TRACE_INFO, "MTLRenderer_drawPoly");
}

void
MTLRenderer_DrawScanlines(MTLContext *mtlc,
                          jint scanlineCount, jint *scanlines)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLRenderer_DrawScanlines");
    J2dTraceLn(J2D_TRACE_INFO, "MTLRenderer_DrawScanlines");
}

void
MTLRenderer_FillRect(MTLContext *mtlc, jint x, jint y, jint w, jint h)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLRenderer_FillRect");
    J2dTraceLn(J2D_TRACE_INFO, "MTLRenderer_FillRect");
}

const int SPAN_BUF_SIZE=64;

void
MTLRenderer_FillSpans(MTLContext *mtlc, jint spanCount, jint *spans)
{
    J2dTraceLn(J2D_TRACE_INFO, "MTLRenderer_FillSpans");
    BMTLSDOps *dstOps = MTLRenderQueue_GetCurrentDestination();
    if (dstOps == NULL || dstOps->privOps == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLRenderer_FillSpans: current dest is null");
        return;
    }
    MTLSDOps *dstCGLOps = (MTLSDOps *) dstOps->privOps;
    MTLContext* ctx = dstCGLOps->configInfo->context;
    if (ctx == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLRenderer_FillSpans: current mtlContext os null");
        return;
    }

    while (spanCount > 0) {
        __block struct {
            jfloat spns[SPAN_BUF_SIZE*4];
        } spanStruct;

        __block jfloat sc = spanCount > SPAN_BUF_SIZE ? SPAN_BUF_SIZE : spanCount;

        for (int i = 0; i < sc; i++) {
            spanStruct.spns[i * 4] = *(spans++);
            spanStruct.spns[i * 4 + 1] = *(spans++);
            spanStruct.spns[i * 4 + 2] = *(spans++);
            spanStruct.spns[i * 4 + 3] = *(spans++);
        }

        spanCount -= sc;

        id<MTLTexture> dest = dstOps->pTexture;
        id<MTLRenderCommandEncoder> mtlEncoder = MTLContext_CreateRenderEncoder(ctx, dest);
        if (mtlEncoder == nil)
            return;

        ctx->mtlEmptyCommandBuffer = NO;

        for (int i = 0; i < sc; i++) {
            jfloat x1 = spanStruct.spns[i * 4];
            jfloat y1 = spanStruct.spns[i * 4 + 1];
            jfloat x2 = spanStruct.spns[i * 4 + 2];
            jfloat y2 = spanStruct.spns[i * 4 + 3];

            struct Vertex verts[PGRAM_VERTEX_COUNT] = {
                {{(2.0 * x1 / dest.width) - 1.0,
                2.0 * (1.0 - y1 / dest.height) - 1.0, 0.0}},

                {{2.0 * (x2) / dest.width - 1.0,
                2.0 * (1.0 - y1 / dest.height) - 1.0, 0.0}},

                {{2.0 * x1 / dest.width - 1.0,
                2.0 * (1.0 - y2 / dest.height) - 1.0, 0.0}},

                {{2.0 * x2 / dest.width - 1.0,
                2.0 * (1.0 - y1 / dest.height) - 1.0, 0.0}},

                {{2.0 * (x2) / dest.width - 1.0,
                2.0 * (1.0 - y2 / dest.height) - 1.0, 0.0}},

                {{2.0 * (x1) / dest.width - 1.0,
                2.0 * (1.0 - y2 / dest.height) - 1.0, 0.0},
            }};

            [mtlEncoder setVertexBytes:verts length:sizeof(verts) atIndex:MeshVertexBuffer];
            [mtlEncoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:PGRAM_VERTEX_COUNT];
        }

        [mtlEncoder endEncoding];
    }
}

void
MTLRenderer_FillParallelogram(MTLContext *mtlc,
                              jfloat fx11, jfloat fy11,
                              jfloat dx21, jfloat dy21,
                              jfloat dx12, jfloat dy12)
{
    J2dTracePrimitive("MTLRenderer_FillParallelogram");

    BMTLSDOps *dstOps = MTLRenderQueue_GetCurrentDestination();
    if (dstOps == NULL || dstOps->privOps == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLRenderer_FillParallelogram: current dest is null");
        return;
    }
    MTLSDOps *dstCGLOps = (MTLSDOps *) dstOps->privOps;
    MTLContext* ctx = dstCGLOps->configInfo->context;
    if (ctx == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLRenderer_FillParallelogram: current mtlContext os null");
        return;
    }

     MTLRenderer_FillParallelogramMetal(
        dstCGLOps->configInfo->context, dstOps->pTexture,
        fx11, fy11, dx21, dy21, dx12, dy12);
}

void
MTLRenderer_DrawParallelogram(MTLContext *mtlc,
                              jfloat fx11, jfloat fy11,
                              jfloat dx21, jfloat dy21,
                              jfloat dx12, jfloat dy12,
                              jfloat lwr21, jfloat lwr12)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLRenderer_DrawParallelogram");
    // dx,dy for line width in the "21" and "12" directions.
    jfloat ldx21 = dx21 * lwr21;
    jfloat ldy21 = dy21 * lwr21;
    jfloat ldx12 = dx12 * lwr12;
    jfloat ldy12 = dy12 * lwr12;

    // calculate origin of the outer parallelogram
    jfloat ox11 = fx11 - (ldx21 + ldx12) / 2.0f;
    jfloat oy11 = fy11 - (ldy21 + ldy12) / 2.0f;

    J2dTraceLn8(J2D_TRACE_INFO,
                "MTLRenderer_DrawParallelogram "
                "(x=%6.2f y=%6.2f "
                "dx1=%6.2f dy1=%6.2f lwr1=%6.2f "
                "dx2=%6.2f dy2=%6.2f lwr2=%6.2f)",
                fx11, fy11,
                dx21, dy21, lwr21,
                dx12, dy12, lwr12);

}

static GLhandleARB aaPgramProgram = 0;

/*
 * This shader fills the space between an outer and inner parallelogram.
 * It can be used to draw an outline by specifying both inner and outer
 * values.  It fills pixels by estimating what portion falls inside the
 * outer shape, and subtracting an estimate of what portion falls inside
 * the inner shape.  Specifying both inner and outer values produces a
 * standard "wide outline".  Specifying an inner shape that falls far
 * outside the outer shape allows the same shader to fill the outer
 * shape entirely since pixels that fall within the outer shape are never
 * inside the inner shape and so they are filled based solely on their
 * coverage of the outer shape.
 *
 * The setup code renders this shader over the bounds of the outer
 * shape (or the only shape in the case of a fill operation) and
 * sets the texture 0 coordinates so that 0,0=>0,1=>1,1=>1,0 in those
 * texture coordinates map to the four corners of the parallelogram.
 * Similarly the texture 1 coordinates map the inner shape to the
 * unit square as well, but in a different coordinate system.
 *
 * When viewed in the texture coordinate systems the parallelograms
 * we are filling are unit squares, but the pixels have then become
 * tiny parallelograms themselves.  Both of the texture coordinate
 * systems are affine transforms so the rate of change in X and Y
 * of the texture coordinates are essentially constants and happen
 * to correspond to the size and direction of the slanted sides of
 * the distorted pixels relative to the "square mapped" boundary
 * of the parallelograms.
 *
 * The shader uses the dFdx() and dFdy() functions to measure the "rate
 * of change" of these texture coordinates and thus gets an accurate
 * measure of the size and shape of a pixel relative to the two
 * parallelograms.  It then uses the bounds of the size and shape
 * of a pixel to intersect with the unit square to estimate the
 * coverage of the pixel.  Unfortunately, without a lot more work
 * to calculate the exact area of intersection between a unit
 * square (the original parallelogram) and a parallelogram (the
 * distorted pixel), this shader only approximates the pixel
 * coverage, but emperically the estimate is very useful and
 * produces visually pleasing results, if not theoretically accurate.
 */
static const char *aaPgramShaderSource =
    "void main() {"
    // Calculate the vectors for the "legs" of the pixel parallelogram
    // for the outer parallelogram.
    "    vec2 oleg1 = dFdx(gl_TexCoord[0].st);"
    "    vec2 oleg2 = dFdy(gl_TexCoord[0].st);"
    // Calculate the bounds of the distorted pixel parallelogram.
    "    vec2 corner = gl_TexCoord[0].st - (oleg1+oleg2)/2.0;"
    "    vec2 omin = min(corner, corner+oleg1);"
    "    omin = min(omin, corner+oleg2);"
    "    omin = min(omin, corner+oleg1+oleg2);"
    "    vec2 omax = max(corner, corner+oleg1);"
    "    omax = max(omax, corner+oleg2);"
    "    omax = max(omax, corner+oleg1+oleg2);"
    // Calculate the vectors for the "legs" of the pixel parallelogram
    // for the inner parallelogram.
    "    vec2 ileg1 = dFdx(gl_TexCoord[1].st);"
    "    vec2 ileg2 = dFdy(gl_TexCoord[1].st);"
    // Calculate the bounds of the distorted pixel parallelogram.
    "    corner = gl_TexCoord[1].st - (ileg1+ileg2)/2.0;"
    "    vec2 imin = min(corner, corner+ileg1);"
    "    imin = min(imin, corner+ileg2);"
    "    imin = min(imin, corner+ileg1+ileg2);"
    "    vec2 imax = max(corner, corner+ileg1);"
    "    imax = max(imax, corner+ileg2);"
    "    imax = max(imax, corner+ileg1+ileg2);"
    // Clamp the bounds of the parallelograms to the unit square to
    // estimate the intersection of the pixel parallelogram with
    // the unit square.  The ratio of the 2 rectangle areas is a
    // reasonable estimate of the proportion of coverage.
    "    vec2 o1 = clamp(omin, 0.0, 1.0);"
    "    vec2 o2 = clamp(omax, 0.0, 1.0);"
    "    float oint = (o2.y-o1.y)*(o2.x-o1.x);"
    "    float oarea = (omax.y-omin.y)*(omax.x-omin.x);"
    "    vec2 i1 = clamp(imin, 0.0, 1.0);"
    "    vec2 i2 = clamp(imax, 0.0, 1.0);"
    "    float iint = (i2.y-i1.y)*(i2.x-i1.x);"
    "    float iarea = (imax.y-imin.y)*(imax.x-imin.x);"
    // Proportion of pixel in outer shape minus the proportion
    // of pixel in the inner shape == the coverage of the pixel
    // in the area between the two.
    "    float coverage = oint/oarea - iint / iarea;"
    "    gl_FragColor = gl_Color * coverage;"
    "}";

#define ADJUST_PGRAM(V1, DV, V2) \
    do { \
        if ((DV) >= 0) { \
            (V2) += (DV); \
        } else { \
            (V1) += (DV); \
        } \
    } while (0)

// Invert the following transform:
// DeltaT(0, 0) == (0,       0)
// DeltaT(1, 0) == (DX1,     DY1)
// DeltaT(0, 1) == (DX2,     DY2)
// DeltaT(1, 1) == (DX1+DX2, DY1+DY2)
// TM00 = DX1,   TM01 = DX2,   (TM02 = X11)
// TM10 = DY1,   TM11 = DY2,   (TM12 = Y11)
// Determinant = TM00*TM11 - TM01*TM10
//             =  DX1*DY2  -  DX2*DY1
// Inverse is:
// IM00 =  TM11/det,   IM01 = -TM01/det
// IM10 = -TM10/det,   IM11 =  TM00/det
// IM02 = (TM01 * TM12 - TM11 * TM02) / det,
// IM12 = (TM10 * TM02 - TM00 * TM12) / det,

#define DECLARE_MATRIX(MAT) \
    jfloat MAT ## 00, MAT ## 01, MAT ## 02, MAT ## 10, MAT ## 11, MAT ## 12

#define GET_INVERTED_MATRIX(MAT, X11, Y11, DX1, DY1, DX2, DY2, RET_CODE) \
    do { \
        jfloat det = DX1*DY2 - DX2*DY1; \
        if (det == 0) { \
            RET_CODE; \
        } \
        MAT ## 00 = DY2/det; \
        MAT ## 01 = -DX2/det; \
        MAT ## 10 = -DY1/det; \
        MAT ## 11 = DX1/det; \
        MAT ## 02 = (DX2 * Y11 - DY2 * X11) / det; \
        MAT ## 12 = (DY1 * X11 - DX1 * Y11) / det; \
    } while (0)

#define TRANSFORM(MAT, TX, TY, X, Y) \
    do { \
        TX = (X) * MAT ## 00 + (Y) * MAT ## 01 + MAT ## 02; \
        TY = (X) * MAT ## 10 + (Y) * MAT ## 11 + MAT ## 12; \
    } while (0)

void
MTLRenderer_FillAAParallelogram(MTLContext *mtlc, BMTLSDOps *dstOps,
                                jfloat fx11, jfloat fy11,
                                jfloat dx21, jfloat dy21,
                                jfloat dx12, jfloat dy12)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLRenderer_FillAAParallelogram");
    DECLARE_MATRIX(om);
    // parameters for parallelogram bounding box
    jfloat bx11, by11, bx22, by22;
    // parameters for uv texture coordinates of parallelogram corners
    jfloat u11, v11, u12, v12, u21, v21, u22, v22;

    J2dTraceLn6(J2D_TRACE_INFO,
                "MTLRenderer_FillAAParallelogram "
                "(x=%6.2f y=%6.2f "
                "dx1=%6.2f dy1=%6.2f "
                "dx2=%6.2f dy2=%6.2f)",
                fx11, fy11,
                dx21, dy21,
                dx12, dy12);

}

void
MTLRenderer_FillAAParallelogramInnerOuter(MTLContext *mtlc, MTLSDOps *dstOps,
                                          jfloat ox11, jfloat oy11,
                                          jfloat ox21, jfloat oy21,
                                          jfloat ox12, jfloat oy12,
                                          jfloat ix11, jfloat iy11,
                                          jfloat ix21, jfloat iy21,
                                          jfloat ix12, jfloat iy12)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLRenderer_FillAAParallelogramInnerOuter");
}

void
MTLRenderer_DrawAAParallelogram(MTLContext *mtlc, BMTLSDOps *dstOps,
                                jfloat fx11, jfloat fy11,
                                jfloat dx21, jfloat dy21,
                                jfloat dx12, jfloat dy12,
                                jfloat lwr21, jfloat lwr12)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLRenderer_DrawAAParallelogram");
    // dx,dy for line width in the "21" and "12" directions.
    jfloat ldx21, ldy21, ldx12, ldy12;
    // parameters for "outer" parallelogram
    jfloat ofx11, ofy11, odx21, ody21, odx12, ody12;
    // parameters for "inner" parallelogram
    jfloat ifx11, ify11, idx21, idy21, idx12, idy12;

    J2dTraceLn8(J2D_TRACE_INFO,
                "MTLRenderer_DrawAAParallelogram "
                "(x=%6.2f y=%6.2f "
                "dx1=%6.2f dy1=%6.2f lwr1=%6.2f "
                "dx2=%6.2f dy2=%6.2f lwr2=%6.2f)",
                fx11, fy11,
                dx21, dy21, lwr21,
                dx12, dy12, lwr12);

}

void
MTLRenderer_EnableAAParallelogramProgram()
{
    //TODO
    J2dTraceNotImplPrimitive("MTLRenderer_EnableAAParallelogramProgram");
    J2dTraceLn(J2D_TRACE_INFO, "MTLRenderer_EnableAAParallelogramProgram");
}

void
MTLRenderer_DisableAAParallelogramProgram()
{
    //TODO
    J2dTraceNotImplPrimitive("MTLRenderer_DisableAAParallelogramProgram");
    J2dTraceLn(J2D_TRACE_INFO, "MTLRenderer_DisableAAParallelogramProgram");
}

void debugDraw(MTLContext *ctx, id<MTLTexture> dst, int red/*othrewise blue*/, int freq) {
    J2dTraceLn1(J2D_TRACE_VERBOSE, "draw debug onto dst tex=%p", dst);
    MTLContext_SetColor(ctx, red > 0 ? red : 0, 0, red <= 0 ? 255 : 0, 255);
    id <MTLRenderCommandEncoder> encoder = MTLContext_CreateRenderEncoder(ctx, dst);
    const int w = dst.width;
    const int h = dst.height;
    int x = 2;
    int y = 2;
    do {
        struct Vertex vvv[2] = {
                {{MTLUtils_normalizeX(dst,x), MTLUtils_normalizeY(dst, y), 0.0}},
                {{MTLUtils_normalizeX(dst, dst.width - x), MTLUtils_normalizeY(dst, dst.height - y), 0.0}},
        };
        [encoder setVertexBytes:vvv length:sizeof(vvv) atIndex:MeshVertexBuffer];
        [encoder drawPrimitives:MTLPrimitiveTypeLine vertexStart:0 vertexCount:2];

        if (freq == 0)
            break;
        if (freq > 0)
            x += (w - 3)/freq;
        else
            y += (h - 3)/freq;
    } while (x < w && y < h);

    [encoder endEncoding];
}

#endif /* !HEADLESS */
