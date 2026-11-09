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

#ifndef SKMaskCache_h_Included
#define SKMaskCache_h_Included

#include "SKQuadBatch.h"

#include "jni.h"

#include "include/core/SkCanvas.h"
#include "include/core/SkImage.h"
#include "include/core/SkPaint.h"
#include "include/core/SkSurface.h"

#include <memory>
#include <vector>

class SKRecordingOwner;

// Marlin's coverage tiles (MASK_FILL, at most 32x32 pixels each) go into an
// alpha-only atlas with round-robin cells, like MTLVertexCache's mask cache:
// tiles are not addressable, so the atlas is simply refilled from the start
// when it is full, after the recording that sampled it has been ended. The
// batch's observer: the staged span is uploaded before the batch draws.
class SKMaskCache : private SKQuadBatchObserver {
public:
    // Null if the atlas or its image cannot be made.
    [[nodiscard]] static std::unique_ptr<SKMaskCache> make(SKRecordingOwner *recordingOwner);
    SKMaskCache(const SKMaskCache &) = delete;
    SKMaskCache &operator=(const SKMaskCache &) = delete;

    [[nodiscard]] static bool fits(jint width, jint height);

    // Draws one tile at device position x, y with the given paint; `mask` is the tile's alpha rows,
    // `maskScan` bytes apart. A color paint is batched into one mesh; a shader paint (gradient,
    // texture) draws the tile from the atlas individually, so the shader is sampled in device space.
    void draw(SkCanvas *canvas, const SkPaint &paint, jint x, jint y, jint width, jint height,
              const unsigned char *mask, jint maskScan);
    // A fully covered tile (MASK_FILL without a mask) of a color paint: a sprite
    // of the atlas' reserved opaque cell, so it joins the mesh instead of ending it.
    void drawSolid(SkCanvas *canvas, const SkPaint &paint, jint x, jint y, jint width, jint height);

    // Draws the queued mesh. Called before any other draw, state or destination
    // change, and before ending a recording.
    void flush();

private:
    static constexpr jint CACHE_SIZE = 2048;   // the atlas' side: 4096 cells, about one wrap per heavy frame
    static constexpr jint CELL_SIZE  = 32;     // Marlin's tile size (MarlinConst.TILE_W / TILE_H)
    static constexpr jint CELLS_PER_ROW = CACHE_SIZE / CELL_SIZE;
    static constexpr jint CELL_COUNT = CELLS_PER_ROW * CELLS_PER_ROW;
    static constexpr jint OPAQUE_CELL = 0;     // reserved: fully covered, for drawSolid
    static constexpr jint FIRST_CELL = OPAQUE_CELL + 1;

    SKMaskCache() = default;

    // SKQuadBatchObserver: the staged span goes up before the batch draws.
    void beforeDraw(const SkRect &bounds) override { uploadStaging(); }

    void uploadStaging();
    void allocateCell(jint *cellX, jint *cellY);

    SKRecordingOwner *m_recordingOwner = nullptr;
    sk_sp<SkSurface> m_atlas;
    sk_sp<SkImage> m_atlasImage;
    jint m_nextCell = 0;
    SKQuadBatch m_batch;
    // Tiles are copied into one atlas row on the CPU and uploaded per row: a
    // writePixels per 32x32 tile costs more than the tile itself. Only the
    // span of the row staged since the last upload goes up: a batch is drawn
    // (and the row uploaded first) whenever the paint changes, which with
    // per-shape colors is every few tiles, and a full 64 KB row each time
    // made uploads the frame's cost where upload bandwidth is scarce (VM).
    std::vector<unsigned char> m_staging;
    jint m_stagingRow = -1;            // the atlas y of the row staged; -1 before the first tile
    jint m_stagingStart = CACHE_SIZE;     // dirty span [m_stagingStart, m_stagingEnd), empty when start >= end
    jint m_stagingEnd = 0;
};

#endif /* SKMaskCache_h_Included */
