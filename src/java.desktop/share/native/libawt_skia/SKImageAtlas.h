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

#ifndef SKImageAtlas_h_Included
#define SKImageAtlas_h_Included

#include "SKQuadBatch.h"

#include "jni.h"

#include "include/core/SkCanvas.h"
#include "include/core/SkImage.h"
#include "include/core/SkPaint.h"
#include "include/core/SkSurface.h"

#include <cstdint>
#include <memory>
#include <unordered_map>
#include <vector>

class SKRecordingOwner;
class SKSurfaceState;

// Diagnostics and tests (SKEnvironment.imageAtlasCounters): since the start, blits drawn from the atlas, copies
// into it, refills, and the atlas batch's mesh draws. (An unscoped enum: the values index the counter array and
// the Java side reads them by position.)
enum SKImageAtlasCounter { SK_IMAGE_ATLAS_BLITS, SK_IMAGE_ATLAS_COPIES, SK_IMAGE_ATLAS_REFILLS, SK_IMAGE_ATLAS_BATCH_DRAWS, SK_IMAGE_ATLAS_COUNTERS };

// Small Skia-surface sources (the images of a UI, cached images) blitted unscaled and pixel-aligned are copied once into
// an RGBA atlas of the context and drawn from it as sprites of one mesh (an SKQuadBatch), instead of
// one Graphite draw per blit (each with its own image paint key, and a flush check of the source's device).
// A copy is keyed by the source surface's identity (SKSurfaceState::uid) and its rect, and dated by the
// source's content version (SKSurfaceState::contentVersion): a source drawn into since is copied again.
// The atlas is refilled from the start when it is full, after the batch was drawn and the recording ended.
// The batch's observer: its draws are counted and date the cells (m_batchEpoch).
class SKImageAtlas : private SKQuadBatchObserver {
public:
    // Null if the atlas or its image cannot be made.
    [[nodiscard]] static std::unique_ptr<SKImageAtlas> make(SKRecordingOwner *recordingOwner);
    SKImageAtlas(const SKImageAtlas &) = delete;
    SKImageAtlas &operator=(const SKImageAtlas &) = delete;

    // The rect fits a cell (draw may take it).
    [[nodiscard]] static bool fits(jint width, jint height);

    // Draws the source rect (sourceX, sourceY, width, height) of `source` (an image of the surface `sourceState`) at
    // integer device position (destX, destY) with `paint` (SrcOver, the extra alpha in its alpha, no shader) as a
    // sprite of the atlas batch on `canvas`; false if the atlas cannot take it (the caller draws the blit itself).
    [[nodiscard]] bool draw(SkCanvas *canvas, const SkPaint &paint, const SKSurfaceState *sourceState, const sk_sp<SkImage> &source,
                            jint sourceX, jint sourceY, jint width, jint height, jint destX, jint destY);

    // Draws the queued mesh (before any other draw that may overlap it, a state or destination change, and the
    // end of a recording).
    void flush();

    [[nodiscard]] static jlong counter(SKImageAtlasCounter counter);

private:
    struct Key {
        uint64_t sourceUID;   // SKSurfaceState::uid of the source surface
        jint sourceX, sourceY, width, height;
        bool operator==(const Key &other) const {
            return sourceUID == other.sourceUID && sourceX == other.sourceX && sourceY == other.sourceY
                   && width == other.width && height == other.height;
        }
    };

    // Golden-ratio hashing (2^64 / phi for the identity, boost's hash_combine for the rects).
    struct KeyHash {
        static constexpr uint64_t GOLDEN_RATIO_64 = 0x9E3779B97F4A7C15ull;
        static constexpr uint64_t COMBINE_SEED_LOW = 0x7F4A7C15ull;    // the low half of GOLDEN_RATIO_64
        static constexpr uint64_t COMBINE_SEED_HIGH = 0x9E3779B9ull;   // the high half (hash_combine's 32-bit constant)

        static uint64_t combine(uint64_t hash, uint64_t value, uint64_t seed) {
            return hash ^ (value + seed + (hash << 6) + (hash >> 2));
        }
        static uint64_t pack(jint high, jint low) {
            return (uint64_t) (uint32_t) high << 32 | (uint32_t) low;
        }
        size_t operator()(const Key &key) const {
            uint64_t hash = key.sourceUID * GOLDEN_RATIO_64;
            hash = combine(hash, pack(key.sourceX, key.sourceY), COMBINE_SEED_LOW);
            hash = combine(hash, pack(key.width, key.height), COMBINE_SEED_HIGH);
            return (size_t) hash;
        }
    };

    struct Cell {
        jint x, y;             // in the atlas
        uint64_t version;      // the source's content version the copy shows
        uint64_t batchEpoch;   // the batch generation it last joined (SKImageAtlas::m_batchEpoch)
    };

    // Rows of cells of one rounded height, filled left to right.
    struct Shelf {
        jint top, height;
        jint nextX;   // where the next cell goes
    };

    SKImageAtlas() = default;

    // SKQuadBatchObserver: the pending sprites are drawn now.
    void beforeDraw(const SkRect &bounds) override;

    [[nodiscard]] bool allocateCell(jint width, jint height, jint *cellX, jint *cellY);
    void refill();

    SKRecordingOwner *m_recordingOwner = nullptr;
    sk_sp<SkSurface> m_atlas;
    sk_sp<SkImage> m_atlasImage;
    SKQuadBatch m_batch;
    std::unordered_map<Key, Cell, KeyHash> m_cells;
    std::vector<Shelf> m_shelves;
    jint m_nextShelfTop = 0;
    uint64_t m_batchEpoch = 1;   // advanced by every draw of the batch: a cell of the current epoch has pending sprites
};

#endif /* SKImageAtlas_h_Included */
