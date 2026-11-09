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
// The order of the pending batches (SKPendingBatches.h).
// All functions run on the Skia queue flusher thread.

#include "SKPendingBatches.h"
#include "SKGraphicsConfig.h"
#include "SKRectBatch.h"

void SKPendingBatches::flushOf(SKGraphicsConfig *graphicsConfig) {
    if (graphicsConfig != nullptr) {
        graphicsConfig->flushCaches();
    }
    SKRectBatch::batch().flush();
}

// Before a batched op of one kind: the other kinds' pending batches are drawn first, so the batches
// pending at any time are of one kind and draw in Java's order, as in any in-order batching renderer.
void SKPendingBatches::drawOtherKinds(SKGraphicsConfig *graphicsConfig, SKBatchKind kind) {
    if (kind != SKBatchKind::Rects) {
        SKRectBatch::batch().flush();
    }
    if (kind != SKBatchKind::Glyphs && graphicsConfig != nullptr) {
        graphicsConfig->flushGlyphCaches();
    }
    if (kind != SKBatchKind::MaskTiles && graphicsConfig != nullptr) {
        graphicsConfig->flushMaskCache();
    }
    if (kind != SKBatchKind::ImageSprites && graphicsConfig != nullptr) {
        graphicsConfig->flushImageAtlas();
    }
}
