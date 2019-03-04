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
#include "AccelGlyphCache.h"

/**
 * The following constants define the inner and outer bounds of the
 * accelerated glyph cache.
 */
#define MTLTR_CACHE_WIDTH       1024
#define MTLTR_CACHE_HEIGHT      1024
#define MTLTR_CACHE_CELL_WIDTH  64
#define MTLTR_CACHE_CELL_HEIGHT 64

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
 * There are two separate glyph caches: for AA and for LCD.
 * Once one of them is initialized as either GRAY or LCD, it
 * stays in that mode for the duration of the application.  It should
 * be safe to use this one glyph cache for all screens in a multimon
 * environment, since the glyph cache texture is shared between all contexts,
 * and (in theory) OpenGL drivers should be smart enough to manage that
 * texture across all screens.
 */

static GlyphCacheInfo *glyphCacheLCD = NULL;
static GlyphCacheInfo *glyphCacheAA = NULL;

/**
 * The handle to the LCD text fragment program object.
 */
static GLhandleARB lcdTextProgram = 0;

/**
 * This value tracks the previous LCD contrast setting, so if the contrast
 * value hasn't changed since the last time the gamma uniforms were
 * updated (not very common), then we can skip updating the unforms.
 */
static jint lastLCDContrast = -1;

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
#define MTLTR_NOCACHE_TILE_SIZE 64

/**
 * These constants define the size of the "cached destination" texture.
 * This texture is only used when rendering LCD-optimized text, as that
 * codepath needs direct access to the destination.  There is no way to
 * access the framebuffer directly from an OpenGL shader, so we need to first
 * copy the destination region corresponding to a particular glyph into
 * this cached texture, and then that texture will be accessed inside the
 * shader.  Copying the destination into this cached texture can be a very
 * expensive operation (accounting for about half the rendering time for
 * LCD text), so to mitigate this cost we try to bulk read a horizontal
 * region of the destination at a time.  (These values are empirically
 * derived for the common case where text runs horizontally.)
 *
 * Note: It is assumed in various calculations below that:
 *     (MTLTR_CACHED_DEST_WIDTH  >= MTLTR_CACHE_CELL_WIDTH)  &&
 *     (MTLTR_CACHED_DEST_WIDTH  >= MTLTR_NOCACHE_TILE_SIZE) &&
 *     (MTLTR_CACHED_DEST_HEIGHT >= MTLTR_CACHE_CELL_HEIGHT) &&
 *     (MTLTR_CACHED_DEST_HEIGHT >= MTLTR_NOCACHE_TILE_SIZE)
 */
#define MTLTR_CACHED_DEST_WIDTH  1024
#define MTLTR_CACHED_DEST_HEIGHT (MTLTR_CACHE_CELL_HEIGHT * 2)

/**
 * The handle to the "cached destination" texture object.
 */
static GLuint cachedDestTextureID = 0;

/**
 * The current bounds of the "cached destination" texture, in destination
 * coordinate space.  The width/height of these bounds will not exceed the
 * MTLTR_CACHED_DEST_WIDTH/HEIGHT values defined above.  These bounds are
 * only considered valid when the isCachedDestValid flag is JNI_TRUE.
 */
static SurfaceDataBounds cachedDestBounds;

/**
 * This flag indicates whether the "cached destination" texture contains
 * valid data.  This flag is reset to JNI_FALSE at the beginning of every
 * call to MTLTR_DrawGlyphList().  Once we copy valid destination data
 * into the cached texture, this flag is set to JNI_TRUE.  This way, we can
 * limit the number of times we need to copy destination data, which is a
 * very costly operation.
 */
static jboolean isCachedDestValid = JNI_FALSE;

/**
 * The bounds of the previously rendered LCD glyph, in destination
 * coordinate space.  We use these bounds to determine whether the glyph
 * currently being rendered overlaps the previously rendered glyph (i.e.
 * its bounding box intersects that of the previously rendered glyph).  If
 * so, we need to re-read the destination area associated with that previous
 * glyph so that we can correctly blend with the actual destination data.
 */
static SurfaceDataBounds previousGlyphBounds;

/**
 * Initializes the one glyph cache (texture and data structure).
 * If lcdCache is JNI_TRUE, the texture will contain RGB data,
 * otherwise we will simply store the grayscale/monochrome glyph images
 * as intensity values (which work well with the GL_MODULATE function).
 */
static jboolean
MTLTR_InitGlyphCache(jboolean lcdCache)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLTR_InitGlyphCache");

    return JNI_TRUE;
}

/**
 * Adds the given glyph to the glyph cache (texture and data structure)
 * associated with the given MTLContext.
 */
static void
MTLTR_AddTmTLyphCache(GlyphInfo *glyph, GLenum pixelFormat)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLTR_AddTmTLyphCache");

    CacheCellInfo *ccinfo;
    GlyphCacheInfo *gcinfo;

    J2dTraceLn(J2D_TRACE_INFO, "MTLTR_AddTmTLyphCache");
    J2dTracePrimitive("MTLTR_InitGlyphCache");
}

/**
 * (Re)Initializes the gamma related uniforms.
 *
 * The given contrast value is an int in the range [100, 250] which we will
 * then scale to fit in the range [1.0, 2.5].
 */
static jboolean
MTLTR_UpdateLCDTextContrast(jint contrast)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLTR_UpdateLCDTextContrast");
    return JNI_TRUE;
}

/**
 * Updates the current gamma-adjusted source color ("src_adj") of the LCD
 * text shader program.  Note that we could calculate this value in the
 * shader (e.g. just as we do for "dst_adj"), but would be unnecessary work
 * (and a measurable performance hit, maybe around 5%) since this value is
 * constant over the entire glyph list.  So instead we just calculate the
 * gamma-adjusted value once and update the uniform parameter of the LCD
 * shader as needed.
 */
static jboolean
MTLTR_UpdateLCDTextColor(jint contrast)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLTR_UpdateLCDTextColor");
    return JNI_TRUE;
}

/**
 * Enables the LCD text shader and updates any related state, such as the
 * gamma lookup table textures.
 */
static jboolean
MTLTR_EnableLCDGlyphModeState(GLuint glyphTextureID,
                              GLuint dstTextureID,
                              jint contrast)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLTR_EnableLCDGlyphModeState");
    return JNI_TRUE;
}

void
MTLTR_EnableGlyphVertexCache(MTLContext *mtlc)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLTR_EnableGlyphVertexCache");
}

void
MTLTR_DisableGlyphVertexCache(MTLContext *mtlc)
{
    //TODO
    J2dTraceLn(J2D_TRACE_INFO, "MTLTR_DisableGlyphVertexCache");
    J2dTraceNotImplPrimitive("MTLTR_DisableGlyphVertexCache");

}

/**
 * Disables any pending state associated with the current "glyph mode".
 */
void
MTLTR_DisableGlyphModeState()
{
    //TODO
    J2dTraceNotImplPrimitive("MTLTR_DisableGlyphModeState");
    J2dTraceLn1(J2D_TRACE_VERBOSE,
                "MTLTR_DisableGlyphModeState: mode=%d", glyphMode);
}

static jboolean
MTLTR_DrawGrayscaleGlyphViaCache(MTLContext *mtlc,
                                 GlyphInfo *ginfo, jint x, jint y)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLTR_DisableGlyphVertexCache");
    return JNI_TRUE;
}

/**
 * Evaluates to true if the rectangle defined by gx1/gy1/gx2/gy2 is
 * inside outerBounds.
 */
#define INSIDE(gx1, gy1, gx2, gy2, outerBounds) \
    (((gx1) >= outerBounds.x1) && ((gy1) >= outerBounds.y1) && \
     ((gx2) <= outerBounds.x2) && ((gy2) <= outerBounds.y2))

/**
 * Evaluates to true if the rectangle defined by gx1/gy1/gx2/gy2 intersects
 * the rectangle defined by bounds.
 */
#define INTERSECTS(gx1, gy1, gx2, gy2, bounds) \
    ((bounds.x2 > (gx1)) && (bounds.y2 > (gy1)) && \
     (bounds.x1 < (gx2)) && (bounds.y1 < (gy2)))

/**
 * This method checks to see if the given LCD glyph bounds fall within the
 * cached destination texture bounds.  If so, this method can return
 * immediately.  If not, this method will copy a chunk of framebuffer data
 * into the cached destination texture and then update the current cached
 * destination bounds before returning.
 */
static void
MTLTR_UpdateCachedDestination(MTLSDOps *dstOps, GlyphInfo *ginfo,
                              jint gx1, jint gy1, jint gx2, jint gy2,
                              jint glyphIndex, jint totalGlyphs)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLTR_UpdateCachedDestination");
}

static jboolean
MTLTR_DrawLCDGlyphViaCache(MTLContext *mtlc, MTLSDOps *dstOps,
                           GlyphInfo *ginfo, jint x, jint y,
                           jint glyphIndex, jint totalGlyphs,
                           jboolean rgbOrder, jint contrast,
                           jint dstTextureID, jboolean * opened)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLTR_DrawLCDGlyphViaCache");
    return JNI_TRUE;
}

static jboolean
MTLTR_DrawGrayscaleGlyphNoCache(MTLContext *mtlc,
                                GlyphInfo *ginfo, jint x, jint y)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLTR_DrawGrayscaleGlyphNoCache");
    return JNI_TRUE;
}

static jboolean
MTLTR_DrawLCDGlyphNoCache(MTLContext *mtlc, MTLSDOps *dstOps,
                          GlyphInfo *ginfo, jint x, jint y,
                          jint rowBytesOffset,
                          jboolean rgbOrder, jint contrast,
                          jint dstTextureID)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLTR_DrawLCDGlyphNoCache");
    return JNI_TRUE;
}

static jboolean
MTLTR_DrawColorGlyphNoCache(MTLContext *mtlc, GlyphInfo *ginfo, jint x, jint y)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLTR_DrawColorGlyphNoCache");
    return JNI_TRUE;
}


// see DrawGlyphList.c for more on this macro...
#define FLOOR_ASSIGN(l, r) \
    if ((r)<0) (l) = ((int)floor(r)); else (l) = ((int)(r))

void
MTLTR_DrawGlyphList(JNIEnv *env, MTLContext *mtlc, MTLSDOps *dstOps,
                    jint totalGlyphs, jboolean usePositions,
                    jboolean subPixPos, jboolean rgbOrder, jint lcdContrast,
                    jfloat glyphListOrigX, jfloat glyphListOrigY,
                    unsigned char *images, unsigned char *positions)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLTR_DrawGlyphList");
}

JNIEXPORT void JNICALL
Java_sun_java2d_metal_MTLTextRenderer_drawGlyphList
    (JNIEnv *env, jobject self,
     jint numGlyphs, jboolean usePositions,
     jboolean subPixPos, jboolean rgbOrder, jint lcdContrast,
     jfloat glyphListOrigX, jfloat glyphListOrigY,
     jlongArray imgArray, jfloatArray posArray)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLTextRenderer_drawGlyphList");
}

#endif /* !HEADLESS */
