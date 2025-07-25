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
#include <limits.h>
#include <math.h>
#include <jlong.h>

#include "sun_java2d_metal_MTLTextRenderer.h"

#include "SurfaceData.h"
#include "MTLContext.h"
#include "MTLRenderQueue.h"
#include "MTLTextRenderer.h"
#include "MTLVertexCache.h"
#include "MTLGlyphCache.h"
#include "MTLBlitLoops.h"

/**
 * The following constants define the inner and outer bounds of the
 * accelerated glyph cache.
 */
#define MTLTR_CACHE_WIDTH       512
#define MTLTR_CACHE_HEIGHT      512
#define MTLTR_CACHE_CELL_WIDTH  32
#define MTLTR_CACHE_CELL_HEIGHT 32

/**
 * The current "glyph mode" state.  This variable is used to track the
 * codepath used to render a particular glyph.  This variable is reset to
 * MODE_NOT_INITED at the beginning of every call to MTLTR_DrawGlyphList().
 * As each glyph is rendered, the glyphMode variable is updated to reflect
 * the current mode, so if the current mode is the same as the mode used
 * to render the previous glyph, we can avoid doing costly setup operations
 * each time.
 */
typedef enum {
    MODE_NOT_INITED,
    MODE_USE_CACHE_GRAY,
    MODE_USE_CACHE_LCD,
    MODE_NO_CACHE_GRAY,
    MODE_NO_CACHE_LCD,
    MODE_NO_CACHE_COLOR
} GlyphMode;
static GlyphMode glyphMode = MODE_NOT_INITED;

/**
 * This value tracks the previous LCD rgbOrder setting, so if the rgbOrder
 * value has changed since the last time, it indicates that we need to
 * invalidate the cache, which may already store glyph images in the reverse
 * order.  Note that in most real world applications this value will not
 * change over the course of the application, but tests like Font2DTest
 * allow for changing the ordering at runtime, so we need to handle that case.
 */
static jboolean lastRGBOrder = JNI_TRUE;

/**
 * This constant defines the size of the tile to use in the
 * MTLTR_DrawLCDGlyphNoCache() method.  See below for more on why we
 * restrict this value to a particular size.
 */
#define MTLTR_NOCACHE_TILE_SIZE 32

static struct TxtVertex txtVertices[6];
static jint vertexCacheIndex = 0;

#define LCD_ADD_VERTEX(TX, TY, DX, DY, DZ) \
    do { \
        struct TxtVertex *v = &txtVertices[vertexCacheIndex++]; \
        v->txtpos[0] = TX; \
        v->txtpos[1] = TY; \
        v->position[0]= DX; \
        v->position[1] = DY; \
    } while (0)

#define LCD_ADD_TRIANGLES(TX1, TY1, TX2, TY2, DX1, DY1, DX2, DY2) \
    do { \
        LCD_ADD_VERTEX(TX1, TY1, DX1, DY1, 0); \
        LCD_ADD_VERTEX(TX2, TY1, DX2, DY1, 0); \
        LCD_ADD_VERTEX(TX2, TY2, DX2, DY2, 0); \
        LCD_ADD_VERTEX(TX2, TY2, DX2, DY2, 0); \
        LCD_ADD_VERTEX(TX1, TY2, DX1, DY2, 0); \
        LCD_ADD_VERTEX(TX1, TY1, DX1, DY1, 0); \
    } while (0)

static void MTLTR_SyncFlushGlyphVertexCache(MTLContext *mtlc) {
    MTLVertexCache_FlushGlyphVertexCache(mtlc);
    [mtlc commitCommandBuffer:YES display:NO];
}

/**
 * Initializes the one glyph cache (texture and data structure).
 * If lcdCache is JNI_TRUE, the texture will contain RGB data,
 * otherwise we will simply store the grayscale/monochrome glyph images
 * as intensity values.
 */
static jboolean
MTLTR_ValidateGlyphCache(MTLContext *mtlc, BMTLSDOps *dstOps, jboolean lcdCache)
{
    J2dTraceLn(J2D_TRACE_INFO, "MTLTR_InitGlyphCache");
    // init glyph cache data structure
    MTLGlyphCache* glyphCache = (lcdCache)?mtlc.glyphCacheLCD:mtlc.glyphCacheAA;
    if (glyphCache.cacheInfo == NULL && ![glyphCache glyphCacheInitWidth:MTLTR_CACHE_WIDTH
                                  height:MTLTR_CACHE_HEIGHT
                               cellWidth:MTLTR_CACHE_CELL_WIDTH
                              cellHeight:MTLTR_CACHE_CELL_HEIGHT
                             pixelFormat:(lcdCache)?MTLPixelFormatBGRA8Unorm:MTLPixelFormatA8Unorm
                                    func:MTLTR_SyncFlushGlyphVertexCache])
    {
        J2dRlsTraceLn(J2D_TRACE_ERROR,
                      "MTLTR_InitGlyphCache: could not init MTL glyph cache");
        return JNI_FALSE;
    }
    glyphCache.cacheInfo->encoder = (lcdCache)?
            [mtlc.encoderManager getLCDEncoder:dstOps->pTexture isSrcOpaque:YES isDstOpaque:YES]:
            [mtlc.encoderManager getTextEncoder:dstOps isSrcOpaque:NO gammaCorrection:YES];
    return JNI_TRUE;
}

void
MTLTR_EnableGlyphVertexCache(MTLContext *mtlc, BMTLSDOps *dstOps)
{
    J2dTraceLn(J2D_TRACE_INFO, "MTLTR_EnableGlyphVertexCache");

    if (!MTLVertexCache_InitVertexCache()) {
        return;
    }

    if (!MTLTR_ValidateGlyphCache(mtlc, dstOps, JNI_FALSE)) {
        return;
    }
}

void
MTLTR_DisableGlyphVertexCache(MTLContext *mtlc)
{
    J2dTraceLn(J2D_TRACE_INFO, "MTLTR_DisableGlyphVertexCache");
    MTLVertexCache_FlushGlyphVertexCache(mtlc);
    MTLVertexCache_FreeVertexCache();
}

/**
 * Adds the given glyph to the glyph cache (texture and data structure)
 * associated with the given MTLContext.
 */
static void
MTLTR_AddToGlyphCache(GlyphInfo *glyph, MTLContext *mtlc,
                      BMTLSDOps *dstOps, jboolean lcdCache, jint subimage)
{
    MTLCacheCellInfo *ccinfo;
    MTLGlyphCache* gc;
    jint w = glyph->width;
    jint h = glyph->height;

    J2dTraceLn(J2D_TRACE_INFO, "MTLTR_AddToGlyphCache");
    if (!lcdCache) {
        gc = mtlc.glyphCacheAA;
    } else {
        gc = mtlc.glyphCacheLCD;
    }

    if ((gc.cacheInfo == NULL) || (glyph->image == NULL)) {
        return;
    }

    if ([gc isCacheFull:glyph]) {
        if (lcdCache) {
            [mtlc.glyphCacheLCD free];
            MTLTR_ValidateGlyphCache(mtlc, dstOps, lcdCache);
        } else {
            MTLTR_DisableGlyphVertexCache(mtlc);
            [mtlc.glyphCacheAA free];
            MTLTR_EnableGlyphVertexCache(mtlc, dstOps);
        }
    }
    ccinfo = [gc addGlyph:glyph];

    if (ccinfo != NULL) {
        // store glyph image in texture cell
        MTLRegion region = {
                {ccinfo->x,  ccinfo->y,   0},
                {w, h, 1}
        };
        if (!lcdCache) {
            ccinfo->glyphSubimage = subimage;
            NSUInteger bytesPerRow = 1 * w;
            [gc.cacheInfo->texture replaceRegion:region
                             mipmapLevel:0
                             withBytes:glyph->image +
                                       (glyph->rowBytes * glyph->height) * subimage
                             bytesPerRow:bytesPerRow];
        } else {
            unsigned int imageBytes = w * h * 4;
            unsigned char imageData[imageBytes];
            memset(&imageData, 0, sizeof(imageData));

            int srcIndex = 0;
            int dstIndex = 0;
            for (int i = 0; i < (w * h); i++) {
                imageData[dstIndex++] = glyph->image[srcIndex++];
                imageData[dstIndex++] = glyph->image[srcIndex++];
                imageData[dstIndex++] = glyph->image[srcIndex++];
                imageData[dstIndex++] = 0xFF;
            }

            NSUInteger bytesPerRow = 4 * w;
            [gc.cacheInfo->texture replaceRegion:region
                             mipmapLevel:0
                             withBytes:imageData
                             bytesPerRow:bytesPerRow];
        }
    }
}

static jboolean
MTLTR_SetLCDContrast(MTLContext *mtlc,
                     jint contrast,
                     id<MTLRenderCommandEncoder> encoder)
{
    if (![mtlc.paint isKindOfClass:[MTLColorPaint class]]) {
        return JNI_FALSE;
    }
    MTLColorPaint* cPaint = (MTLColorPaint *) mtlc.paint;
    // update the current color settings
    double gamma = ((double)contrast) / 100.0;
    double invgamma = 1.0/gamma;
    jfloat radj, gadj, badj;
    jfloat clr[4];
    jint col = cPaint.color;

    J2dTraceLn(J2D_TRACE_INFO, "primary color %x, contrast %d", col, contrast);
    J2dTraceLn(J2D_TRACE_INFO, "gamma %f, invgamma %f", gamma, invgamma);

    clr[0] = ((col >> 16) & 0xFF)/255.0f;
    clr[1] = ((col >> 8) & 0xFF)/255.0f;
    clr[2] = ((col) & 0xFF)/255.0f;

    // gamma adjust the primary color
    radj = (float)pow(clr[0], gamma);
    gadj = (float)pow(clr[1], gamma);
    badj = (float)pow(clr[2], gamma);

    struct LCDFrameUniforms uf = {
            {radj, gadj, badj},
            {gamma, gamma, gamma},
            {invgamma, invgamma, invgamma}};
    [encoder setFragmentBytes:&uf length:sizeof(uf) atIndex:FrameUniformBuffer];
    return JNI_TRUE;
}

static MTLPaint* storedPaint = nil;

static void EnableColorGlyphPainting(MTLContext *mtlc) {
    storedPaint = mtlc.paint;
    mtlc.paint = [[MTLPaint alloc] init];
}

static void DisableColorGlyphPainting(MTLContext *mtlc) {
    [mtlc.paint release];
    mtlc.paint = storedPaint;
    storedPaint = nil;
}

static jboolean
MTLTR_DrawGrayscaleGlyphViaCache(MTLContext *mtlc, GlyphInfo *ginfo,
                                 jint x, jint y, BMTLSDOps *dstOps, jint subimage)
{
    jfloat x1, y1, x2, y2;

    if (glyphMode != MODE_USE_CACHE_GRAY) {
        if (glyphMode == MODE_NO_CACHE_GRAY) {
            MTLVertexCache_DisableMaskCache(mtlc);
        } else if (glyphMode == MODE_USE_CACHE_LCD) {
            [mtlc.encoderManager endEncoder];
        } else if (glyphMode == MODE_NO_CACHE_COLOR) {
            DisableColorGlyphPainting(mtlc);
        }
        MTLTR_EnableGlyphVertexCache(mtlc, dstOps);
        glyphMode = MODE_USE_CACHE_GRAY;
    }

    MTLCacheCellInfo *cell;
    int rx = ginfo->subpixelResolutionX;
    int ry = ginfo->subpixelResolutionY;
    if (subimage == 0 && ((rx == 1 && ry == 1) || rx <= 0 || ry <= 0)) {
        // Subpixel rendering disabled, there must be only one cell info
        cell = (MTLCacheCellInfo *) (ginfo->cellInfo);
    } else {
        // Subpixel rendering enabled, find subimage in cell list
        cell = NULL;
        MTLCacheCellInfo *c = (MTLCacheCellInfo *) (ginfo->cellInfo);
        while (c != NULL) {
            if (c->glyphSubimage == subimage) {
                cell = c;
                break;
            }
            c = c->nextGCI;
        }
    }


    if (cell == NULL || cell->cacheInfo->mtlc != mtlc) {
        if (cell != NULL) {
            MTLGlyphCache_RemoveCellInfo(cell->glyphInfo, cell);
        }
        // attempt to add glyph to accelerated glyph cache
        MTLTR_AddToGlyphCache(ginfo, mtlc, dstOps, JNI_FALSE, subimage);

        if (ginfo->cellInfo == NULL) {
            // we'll just no-op in the rare case that the cell is NULL
            return JNI_TRUE;
        }
        // Our image, added to cache will be the first, so we take it.
        // If for whatever reason we failed to add it to our cache,
        // take first cell anyway, it's still better to render glyph
        // image with wrong subpixel offset than render nothing.
        cell = (MTLCacheCellInfo *) (ginfo->cellInfo);
    }
    cell->timesRendered++;

    x1 = (jfloat)x;
    y1 = (jfloat)y;
    x2 = x1 + ginfo->width;
    y2 = y1 + ginfo->height;

    MTLVertexCache_AddGlyphQuad(mtlc,
                                cell->tx1, cell->ty1,
                                cell->tx2, cell->ty2,
                                x1, y1, x2, y2);

    return JNI_TRUE;
}

static jboolean
MTLTR_DrawLCDGlyphViaCache(MTLContext *mtlc, BMTLSDOps *dstOps,
                           GlyphInfo *ginfo, jint x, jint y,
                           jboolean rgbOrder, jint contrast)
{
    MTLCacheCellInfo *cell;
    jfloat tx1, ty1, tx2, ty2;
    jint w = ginfo->width;
    jint h = ginfo->height;

    if (glyphMode != MODE_USE_CACHE_LCD) {
        if (glyphMode == MODE_NO_CACHE_GRAY) {
            MTLVertexCache_DisableMaskCache(mtlc);
        } else if (glyphMode == MODE_USE_CACHE_GRAY) {
            MTLTR_DisableGlyphVertexCache(mtlc);
        } else if (glyphMode == MODE_NO_CACHE_COLOR) {
            DisableColorGlyphPainting(mtlc);
        }

        if (rgbOrder != lastRGBOrder) {
            // need to invalidate the cache in this case; see comments
            // for lastRGBOrder above
            [mtlc.glyphCacheLCD free];
            lastRGBOrder = rgbOrder;
        }

        if (!MTLTR_ValidateGlyphCache(mtlc, dstOps, JNI_TRUE)) {
            return JNI_FALSE;
        }

        glyphMode = MODE_USE_CACHE_LCD;
    }

    if (ginfo->cellInfo == NULL) {
        // attempt to add glyph to accelerated glyph cache
        // TODO : Handle RGB order
        MTLTR_AddToGlyphCache(ginfo, mtlc, dstOps, JNI_TRUE, 0);

        if (ginfo->cellInfo == NULL) {
            // we'll just no-op in the rare case that the cell is NULL
            return JNI_TRUE;
        }
    }
    cell = (MTLCacheCellInfo *) (ginfo->cellInfo);
    cell->timesRendered++;

    MTLTR_SetLCDContrast(mtlc, contrast, mtlc.glyphCacheLCD.cacheInfo->encoder);
    tx1 = cell->tx1;
    ty1 = cell->ty1;
    tx2 = cell->tx2;
    ty2 = cell->ty2;

    J2dTraceLn(J2D_TRACE_INFO, "tx1 = %f, ty1 = %f, tx2 = %f, ty2 = %f", tx1, ty1, tx2, ty2);
    J2dTraceLn(J2D_TRACE_INFO, "width = %d height = %d", dstOps->width, dstOps->height);

    LCD_ADD_TRIANGLES(tx1, ty1, tx2, ty2, x, y, x+w, y+h);

    [mtlc.glyphCacheLCD.cacheInfo->encoder setVertexBytes:txtVertices length:sizeof(txtVertices) atIndex:MeshVertexBuffer];
    [mtlc.glyphCacheLCD.cacheInfo->encoder setFragmentTexture:mtlc.glyphCacheLCD.cacheInfo->texture atIndex:0];
    [mtlc.glyphCacheLCD.cacheInfo->encoder setFragmentTexture:dstOps->pTexture atIndex:1];

    [mtlc.glyphCacheLCD.cacheInfo->encoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:6];

    vertexCacheIndex = 0;

    return JNI_TRUE;
}

static jboolean
MTLTR_DrawGrayscaleGlyphNoCache(MTLContext *mtlc,
                                GlyphInfo *ginfo, jint x, jint y, BMTLSDOps *dstOps, jint subimage)
{
    jint tw, th;
    jint sx, sy, sw, sh;
    jint x0;
    jint w = ginfo->width;
    jint h = ginfo->height;

    J2dTraceLn(J2D_TRACE_INFO, "MTLTR_DrawGrayscaleGlyphNoCache");
    if (glyphMode != MODE_NO_CACHE_GRAY) {
        if (glyphMode == MODE_USE_CACHE_GRAY) {
            MTLTR_DisableGlyphVertexCache(mtlc);
        } else if (glyphMode == MODE_USE_CACHE_LCD) {
            [mtlc.encoderManager endEncoder];
        } else if (glyphMode == MODE_NO_CACHE_COLOR) {
            DisableColorGlyphPainting(mtlc);
        }
        MTLVertexCache_EnableMaskCache(mtlc, dstOps);
        glyphMode = MODE_NO_CACHE_GRAY;
    }

    x0 = x;
    tw = MTLVC_MASK_CACHE_TILE_WIDTH;
    th = MTLVC_MASK_CACHE_TILE_HEIGHT;

    for (sy = 0; sy < h; sy += th, y += th) {
        x = x0;
        sh = ((sy + th) > h) ? (h - sy) : th;

        for (sx = 0; sx < w; sx += tw, x += tw) {
            sw = ((sx + tw) > w) ? (w - sx) : tw;

            J2dTraceLn(J2D_TRACE_INFO,
                       "sx = %d sy = %d x = %d y = %d sw = %d sh = %d w = %d",
                       sx, sy, x, y, sw, sh, w);
            MTLVertexCache_AddMaskQuad(mtlc,
                                       sx, sy, x, y, sw, sh,
                                       w, ginfo->image +
                                          (ginfo->rowBytes * ginfo->height) * subimage,
                                       dstOps);
        }
    }

    return JNI_TRUE;
}


static jboolean
MTLTR_DrawLCDGlyphNoCache(MTLContext *mtlc, BMTLSDOps *dstOps,
                          GlyphInfo *ginfo, jint x, jint y,
                          jint rowBytesOffset,
                          jboolean rgbOrder, jint contrast)
{
    jfloat tx1, ty1, tx2, ty2;
    jint tw, th;
    jint w = ginfo->width;
    jint h = ginfo->height;
    id<MTLTexture> blitTexture = nil;

    J2dTraceLn(J2D_TRACE_INFO, "MTLTR_DrawLCDGlyphNoCache x %d, y%d", x, y);
    J2dTraceLn(J2D_TRACE_INFO,
               "MTLTR_DrawLCDGlyphNoCache rowBytesOffset=%d, rgbOrder=%d, contrast=%d",
               rowBytesOffset, rgbOrder, contrast);


    id<MTLRenderCommandEncoder> encoder = nil;

    MTLTextureDescriptor *textureDescriptor =
        [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
                                                            width:w
                                                            height:h
                                                            mipmapped:NO];

    blitTexture = [mtlc.device newTextureWithDescriptor:textureDescriptor];

    if (glyphMode != MODE_NO_CACHE_LCD) {
        if (glyphMode == MODE_NO_CACHE_GRAY) {
            MTLVertexCache_DisableMaskCache(mtlc);
        } else if (glyphMode == MODE_USE_CACHE_GRAY) {
            MTLTR_DisableGlyphVertexCache(mtlc);
        } else if (glyphMode == MODE_USE_CACHE_LCD) {
            [mtlc.encoderManager endEncoder];
        } else if (glyphMode == MODE_NO_CACHE_COLOR) {
            DisableColorGlyphPainting(mtlc);
        }

        if (blitTexture == nil) {
            J2dTraceLn(J2D_TRACE_ERROR, "can't obtain temporary texture object from pool");
            return JNI_FALSE;
        }


        glyphMode = MODE_NO_CACHE_LCD;
    }
    encoder = [mtlc.encoderManager getLCDEncoder:dstOps->pTexture isSrcOpaque:YES isDstOpaque:YES];
    MTLTR_SetLCDContrast(mtlc, contrast, encoder);

    unsigned int imageBytes = w * h * 4;
    unsigned char imageData[imageBytes];
    memset(&imageData, 0, sizeof(imageData));

    int srcIndex = 0;
    int dstIndex = 0;
    for (int i = 0; i < (w * h); i++) {
        imageData[dstIndex++] = ginfo->image[srcIndex++ + rowBytesOffset];
        imageData[dstIndex++] = ginfo->image[srcIndex++ + rowBytesOffset];
        imageData[dstIndex++] = ginfo->image[srcIndex++ + rowBytesOffset];
        imageData[dstIndex++] = 0xFF;
    }

    // copy LCD mask into glyph texture tile
    MTLRegion region = MTLRegionMake2D(0, 0, w, h);

    NSUInteger bytesPerRow = 4 * ginfo->width;
    [blitTexture replaceRegion:region
                 mipmapLevel:0
                 withBytes:imageData
                 bytesPerRow:bytesPerRow];

    tx1 = 0.0f;
    ty1 = 0.0f;
    tx2 = 1.0f;
    ty2 = 1.0f;

    J2dTraceLn(J2D_TRACE_INFO,
               "MTLTR_DrawLCDGlyphNoCache : dstOps->width = %d, dstOps->height = %d",
               dstOps->width, dstOps->height);

    LCD_ADD_TRIANGLES(tx1, ty1, tx2, ty2, x, y, x+w, y+h);

    [encoder setVertexBytes:txtVertices length:sizeof(txtVertices) atIndex:MeshVertexBuffer];
    [encoder setFragmentTexture:blitTexture atIndex:0];
    [encoder setFragmentTexture:dstOps->pTexture atIndex:1];

    [encoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:6];

    vertexCacheIndex = 0;
    [mtlc.encoderManager endEncoder];
    [blitTexture release];

    [mtlc commitCommandBuffer:YES display:NO];
    return JNI_TRUE;
}

static jboolean
MTLTR_DrawColorGlyphNoCache(MTLContext *mtlc,
                            GlyphInfo *ginfo, jint x, jint y, BMTLSDOps *dstOps)
{
    id<MTLTexture> dest = dstOps->pTexture;
    const void *src = ginfo->image;
    jint w = ginfo->width;
    jint h = ginfo->height;
    jint rowBytes = ginfo->rowBytes;
    unsigned int imageSize = rowBytes * h;

    J2dTraceLn(J2D_TRACE_INFO, "MTLTR_DrawColorGlyphNoCache");

    if (glyphMode != MODE_NO_CACHE_COLOR) {
        if (glyphMode == MODE_NO_CACHE_GRAY) {
            MTLVertexCache_DisableMaskCache(mtlc);
        } else if (glyphMode == MODE_USE_CACHE_GRAY) {
            MTLTR_DisableGlyphVertexCache(mtlc);
        } else if (glyphMode == MODE_USE_CACHE_LCD) {
            [mtlc.encoderManager endEncoder];
        }
        glyphMode = MODE_NO_CACHE_COLOR;
        EnableColorGlyphPainting(mtlc);
    }

    MTLPooledTextureHandle* texHandle = [mtlc.texturePool getTexture:w height:h format:MTLPixelFormatBGRA8Unorm];
    if (texHandle == nil) {
        J2dTraceLn(J2D_TRACE_ERROR, "MTLTR_DrawColorGlyphNoCache: can't obtain temporary texture object from pool");
        return JNI_FALSE;
    }

    [[mtlc getCommandBufferWrapper] registerPooledTexture:texHandle];

    [texHandle.texture replaceRegion:MTLRegionMake2D(0, 0, w, h)
                         mipmapLevel:0
                           withBytes:src
                         bytesPerRow:rowBytes];

    drawTex2Tex(mtlc, texHandle.texture, dest, JNI_FALSE, dstOps->isOpaque, INTERPOLATION_NEAREST_NEIGHBOR,
                0, 0, w, h, x, y, x + w, y + h);

    return JNI_TRUE;
}

#define ADJUST_SUBPIXEL_GLYPH_POSITION(coord, res) \
    if ((res) > 1) (coord) += 0.5f / ((float)(res)) - 0.5f

void
MTLTR_DrawGlyphList(JNIEnv *env, MTLContext *mtlc, BMTLSDOps *dstOps,
                    jint totalGlyphs, jboolean usePositions,
                    jboolean subPixPos, jboolean rgbOrder, jint lcdContrast,
                    jfloat glyphListOrigX, jfloat glyphListOrigY,
                    unsigned char *images, unsigned char *positions)
{
    int glyphCounter;

    J2dTraceLn(J2D_TRACE_INFO, "MTLTR_DrawGlyphList");

    RETURN_IF_NULL(mtlc);
    RETURN_IF_NULL(dstOps);
    RETURN_IF_NULL(images);
    if (usePositions) {
        RETURN_IF_NULL(positions);
    }

    glyphMode = MODE_NOT_INITED;
    J2dTraceLn(J2D_TRACE_INFO, "totalGlyphs = %d", totalGlyphs);
    jboolean flushBeforeLCD = JNI_FALSE;

    for (glyphCounter = 0; glyphCounter < totalGlyphs; glyphCounter++) {
        J2dTraceLn(J2D_TRACE_INFO, "Entered for loop for glyph list");
        jint x, y;
        jfloat glyphx, glyphy;
        jboolean ok;
        GlyphInfo *ginfo = (GlyphInfo *)jlong_to_ptr(NEXT_LONG(images));

        if (ginfo == NULL || ginfo == (void*) -1) {
            // this shouldn't happen, but if it does we'll just break out...
            J2dRlsTraceLn(J2D_TRACE_ERROR,
                          "MTLTR_DrawGlyphList: glyph info is %d", ginfo);
            break;
        }

        if (usePositions) {
            jfloat posx = NEXT_FLOAT(positions);
            jfloat posy = NEXT_FLOAT(positions);
            glyphx = glyphListOrigX + posx + ginfo->topLeftX;
            glyphy = glyphListOrigY + posy + ginfo->topLeftY;
        } else {
            glyphx = glyphListOrigX + ginfo->topLeftX;
            glyphy = glyphListOrigY + ginfo->topLeftY;
            glyphListOrigX += ginfo->advanceX;
            glyphListOrigY += ginfo->advanceY;
        }

        int rx = ginfo->subpixelResolutionX;
        int ry = ginfo->subpixelResolutionY;
        ADJUST_SUBPIXEL_GLYPH_POSITION(glyphx, rx);
        ADJUST_SUBPIXEL_GLYPH_POSITION(glyphy, ry);
        float fx = floor(glyphx);
        float fy = floor(glyphy);
        x = (int) fx;
        y = (int) fy;
        int subimage;
        if ((rx == 1 && ry == 1) || rx <= 0 || ry <= 0) {
            subimage = 0;
        } else {
            int subx = (int) ((glyphx - fx) * (float) rx);
            int suby = (int) ((glyphy - fy) * (float) ry);
            subimage = subx + suby * rx;
        }

        if (ginfo->image == NULL) {
            J2dTraceLn(J2D_TRACE_INFO, "Glyph image is null");
            continue;
        }

        J2dTraceLn(J2D_TRACE_INFO, "Glyph width = %d height = %d", ginfo->width, ginfo->height);
        J2dTraceLn(J2D_TRACE_INFO, "rowBytes = %d", ginfo->rowBytes);
        if (ginfo->format == sun_font_StrikeCache_PIXEL_FORMAT_GREYSCALE) {
            // grayscale or monochrome glyph data
            if (ginfo->width <= MTLTR_CACHE_CELL_WIDTH &&
                ginfo->height <= MTLTR_CACHE_CELL_HEIGHT)
            {
                J2dTraceLn(J2D_TRACE_INFO, "MTLTR_DrawGlyphList Grayscale cache");
                ok = MTLTR_DrawGrayscaleGlyphViaCache(mtlc, ginfo, x, y, dstOps, subimage);
            } else {
                J2dTraceLn(J2D_TRACE_INFO, "MTLTR_DrawGlyphList Grayscale no cache");
                ok = MTLTR_DrawGrayscaleGlyphNoCache(mtlc, ginfo, x, y, dstOps, subimage);
            }
        } else if (ginfo->format == sun_font_StrikeCache_PIXEL_FORMAT_BGRA) {
            J2dTraceLn(J2D_TRACE_INFO, "MTLTR_DrawGlyphList color glyph no cache");
            ok = MTLTR_DrawColorGlyphNoCache(mtlc, ginfo, x, y, dstOps);
            flushBeforeLCD = JNI_FALSE;
        } else {
            if (!flushBeforeLCD) {
                [mtlc commitCommandBuffer:NO display:NO];
                flushBeforeLCD = JNI_TRUE;
            }

            // LCD-optimized glyph data
            jint rowBytesOffset = 0;

            if (subPixPos) {
                jint frac = (jint)((glyphx - x) * 3);
                if (frac != 0) {
                    rowBytesOffset = 3 - frac;
                    x += 1;
                }
            }

            if (rowBytesOffset == 0 &&
                ginfo->width <= MTLTR_CACHE_CELL_WIDTH &&
                ginfo->height <= MTLTR_CACHE_CELL_HEIGHT)
            {
                J2dTraceLn(J2D_TRACE_INFO, "MTLTR_DrawGlyphList LCD cache");
                ok = MTLTR_DrawLCDGlyphViaCache(mtlc, dstOps,
                                                ginfo, x, y,
                                                rgbOrder, lcdContrast);
            } else {
                J2dTraceLn(J2D_TRACE_INFO, "MTLTR_DrawGlyphList LCD no cache");
                ok = MTLTR_DrawLCDGlyphNoCache(mtlc, dstOps,
                                               ginfo, x, y,
                                               rowBytesOffset,
                                               rgbOrder, lcdContrast);
            }
        }

        if (!ok) {
            break;
        }
    }
    /*
     * Only in case of grayscale text drawing we need to flush
     * cache. Still in case of LCD we are not using any intermediate
     * cache.
     */
    if (glyphMode == MODE_NO_CACHE_GRAY) {
        MTLVertexCache_DisableMaskCache(mtlc);
    } else if (glyphMode == MODE_USE_CACHE_GRAY) {
        MTLTR_DisableGlyphVertexCache(mtlc);
    } else if (glyphMode == MODE_USE_CACHE_LCD) {
        [mtlc.encoderManager endEncoder];
    } else if (glyphMode == MODE_NO_CACHE_COLOR) {
        DisableColorGlyphPainting(mtlc);
    }
}

JNIEXPORT void JNICALL
Java_sun_java2d_metal_MTLTextRenderer_drawGlyphList
    (JNIEnv *env, jobject self,
     jint numGlyphs, jboolean usePositions,
     jboolean subPixPos, jboolean rgbOrder, jint lcdContrast,
     jfloat glyphListOrigX, jfloat glyphListOrigY,
     jlongArray imgArray, jfloatArray posArray)
{
    unsigned char *images;

    J2dTraceLn(J2D_TRACE_INFO, "MTLTextRenderer_drawGlyphList");

    images = (unsigned char *)
        (*env)->GetPrimitiveArrayCritical(env, imgArray, NULL);
    if (images != NULL) {
        MTLContext *mtlc = MTLRenderQueue_GetCurrentContext();
        BMTLSDOps *dstOps = MTLRenderQueue_GetCurrentDestination();

        if (usePositions) {
            unsigned char *positions = (unsigned char *)
                (*env)->GetPrimitiveArrayCritical(env, posArray, NULL);
            if (positions != NULL) {
                MTLTR_DrawGlyphList(env, mtlc, dstOps,
                                    numGlyphs, usePositions,
                                    subPixPos, rgbOrder, lcdContrast,
                                    glyphListOrigX, glyphListOrigY,
                                    images, positions);
                (*env)->ReleasePrimitiveArrayCritical(env, posArray,
                                                      positions, JNI_ABORT);
            }
        } else {
            MTLTR_DrawGlyphList(env, mtlc, dstOps,
                                numGlyphs, usePositions,
                                subPixPos, rgbOrder, lcdContrast,
                                glyphListOrigX, glyphListOrigY,
                                images, NULL);
        }
        if (mtlc != NULL) {
            RESET_PREVIOUS_OP();
            [mtlc commitCommandBuffer:NO display:NO];
        }

        (*env)->ReleasePrimitiveArrayCritical(env, imgArray,
                                              images, JNI_ABORT);
    }
}
