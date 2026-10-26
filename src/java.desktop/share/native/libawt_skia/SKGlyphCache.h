/*
 * Copyright 2026 JetBrains s.r.o.
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

#ifndef SKGlyphCache_h_Included
#define SKGlyphCache_h_Included

#include "jni.h"
#include "fontscalerdefs.h"

#include "include/core/SkCanvas.h"
#include "include/core/SkPaint.h"

struct SKConfigContext;
struct SKGlyphCache;

// Glyph cache of one Graphite context: the shared Java2D AccelGlyphCache for
// cell bookkeeping and invalidation (sunFont.c evicts glyphs from it when they
// are freed), an SkSurface as the atlas texture, and a sprite batch drawn as
// one mesh (SKQuadBatch). Grayscale caches are alpha-only and take the paint
// colour; LCD caches hold RGB coverage and are drawn through the paint's
// blender, in two passes, or accumulated first (see SKBackend_DrawGlyphList).
SKGlyphCache *SKGlyphCache_Create(SKConfigContext *cc, bool lcd);
void SKGlyphCache_Free(SKGlyphCache *gc);

// Fits the cells of this cache?
bool SKGlyphCache_Fits(const SKGlyphCache *gc, const GlyphInfo *glyph);

// A glyph list starts: the canvas and paint its glyphs are drawn with. Sprites
// of consecutive glyph lists with the same canvas and paint accumulate in one
// batch; a different paint draws the previous batch first. A flush inside the
// list (an uncached glyph, or an eviction with no cell free of this recording)
// closes the batch; the next cached glyph of the list reopens it.
void SKGlyphCache_Begin(SKGlyphCache *gc, SkCanvas *canvas, const SkPaint &paint,
                        const struct SKQuadBatchTwoPass *passes = nullptr);

// Queues one glyph (sub)image at device position x, y; writes it into a cell
// of the shadow bitmap first if it is not cached yet (uploaded with the next
// batch draw). Returns false if no cell could be allocated.
bool SKGlyphCache_Draw(SKGlyphCache *gc, GlyphInfo *glyph, jint subimage, jboolean rgbOrder, float x, float y);

// Draws the queued sprites as one mesh. The backend calls this before any
// other draw, state or destination change, and before ending a recording.
void SKGlyphCache_Flush(SKGlyphCache *gc);

// The cache's sprite batch, for its draw hooks (SKQuadBatch::beforeDraw,
// afterDraw).
class SKQuadBatch &SKGlyphCache_Batch(SKGlyphCache *gc);

// The caches' work so far, for diagnostics and tests (SKEnvironment.glyphCacheStats):
// glyphs drawn from a cell, cells staged (first draw of a glyph image), cells evicted
// (a staged glyph replaced a cached one), atlas row uploads, glyphs drawn uncached by
// the backend (did not fit a cell, or no cell free). Written by the flusher, read anywhere.
enum SKGlyphCacheStat { SK_GLYPHS_CACHED = 0, SK_CELLS_STAGED, SK_CELLS_EVICTED, SK_ATLAS_UPLOADS, SK_GLYPHS_UNCACHED, SK_GLYPH_STAT_COUNT };
void SKGlyphCache_CountStat(SKGlyphCacheStat stat, jlong n = 1);
jlong SKGlyphCache_Stat(SKGlyphCacheStat stat);

#endif /* SKGlyphCache_h_Included */
