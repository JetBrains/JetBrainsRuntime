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
#ifndef SKPendingBatches_h_Included
#define SKPendingBatches_h_Included

// The order of the pending batches: the rect batch (SKRectBatch.h) and the caches' batches of a
// context (glyph sprites, Marlin's tiles, image atlas sprites; SKGlyphCache.h, SKMaskCache.h,
// SKImageAtlas.h). Pending batches are drawn before anything else touches the canvas, so draw order
// is preserved; a batched primitive draws the other kinds only (drawOtherKinds), so that the batches
// pending at any time are of one kind and draw in Java's order.
// All functions run on the Skia queue flusher thread.

class SKGraphicsConfig;

// The kinds of batched primitives; the batches pending at any time are of one kind
// (drawOtherKinds), so that they draw in Java's order.
enum class SKBatchKind {
    Rects,          // rects, lines, spans, non-AA parallelograms (SKRectBatch)
    Glyphs,         // the glyph caches' sprites
    MaskTiles,      // Marlin's coverage tiles (SKMaskCache)
    ImageSprites    // small image sources (SKImageAtlas)
};

namespace SKPendingBatches {

// The glyph, Marlin tile and image atlas batches of a context (null: none), then the rect batch.
void flushOf(SKGraphicsConfig *graphicsConfig);

// Before a batched op of one kind: the other kinds' pending batches (of the context, null: none) are drawn first.
void drawOtherKinds(SKGraphicsConfig *graphicsConfig, SKBatchKind kind);

} // namespace SKPendingBatches

#endif /* SKPendingBatches_h_Included */
