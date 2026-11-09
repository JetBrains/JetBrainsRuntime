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

#ifndef SKQuadBatch_h_Included
#define SKQuadBatch_h_Included

#include "include/core/SkCanvas.h"
#include "include/core/SkRect.h"
#include "include/core/SkColor.h"
#include "include/core/SkImage.h"
#include "include/core/SkPaint.h"
#include "include/core/SkSamplingOptions.h"
#include "include/core/SkShader.h"
#include "include/core/SkVertices.h"
#include "jni.h"

#include "SKConfiguration.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <vector>

// Quads of one paint drawn as a single mesh. Every Graphite draw costs about
// 2 us of CPU and has its own paint key, so spans, scanlines, Bresenham runs,
// rect fills and outlines, non-AA parallelograms, and the glyph and mask
// sprites are collected here instead. A mesh is rasterized with the
// pixel-center rule and no coverage, which is what Java2D's non-AA primitives
// specify (Graphite would anti-alias anything not pixel-aligned). Without an
// atlas the paint's shader is sampled in canvas coordinates; with an atlas the
// quads carry texture coordinates and the atlas is the paint's shader: an
// alpha-only atlas takes the paint color, an RGB atlas feeds the blender.
// Two draws of every mesh instead of one, for sources that blend per color
// channel without reading the destination (LCD text as DirectWrite composites
// it): each pass gets its blend mode and a shader built over the atlas shader
// for the batch's paint (its color). Implemented by what composites so
// (SKTextPipe's LCD passes), given to SKQuadBatch::begin.
enum class SKQuadBatchPass { First, Second };

class SKQuadBatchTwoPass {
public:
    // The shader of the pass over the atlas shader, for the batch's paint.
    [[nodiscard]] virtual sk_sp<SkShader> passShader(SKQuadBatchPass pass, const sk_sp<SkShader> &atlas,
                                                     const SkPaint &paint) const = 0;
    [[nodiscard]] virtual SkBlendMode passBlend(SKQuadBatchPass pass) const = 0;
    // Part of the batch key: what else the shaders depend on (the LCD contrast).
    [[nodiscard]] virtual int passKey() const = 0;

protected:
    ~SKQuadBatchTwoPass() = default;
};

// What the owner of a batch does around the batch's draws (SKQuadBatch::setObserver): the
// operations run, in this order, whenever a batch holding quads draws, whoever triggers the
// draw (a flush, a full batch); afterDraw only for an observer that takesDrawBounds. The caches
// whose atlases the batches sample implement it, and pass on what their users add (the LCD
// coverage accumulation, SKGlyphCache::observeDraws).
class SKQuadBatchObserver {
public:
    // First. The glyph caches upload their staged atlas rows here: an upload is only ordered
    // ahead of the destination's pass if a draw sampling the atlas is recorded after it.
    virtual void beforeUpload() {}
    // Right before the meshes are drawn, with their bounds if takesDrawBounds (else the bounds are
    // empty). The mask cache uploads its staged atlas cells here, the coverage accumulation clears
    // the bounds.
    virtual void beforeDraw(const SkRect &bounds) {}
    // Right after the meshes, with their bounds and the batch's paint; called only if takesDrawBounds
    // (the coverage accumulation composites the bounds onto the destination here).
    virtual void afterDraw(const SkRect &bounds, const SkPaint &paint) {}
    // Whether the draws' bounds and the paint are used: the batch computes the bounds then, and
    // does not merge colors (afterDraw reads the color from the paint).
    [[nodiscard]] virtual bool takesDrawBounds() const { return false; }

protected:
    ~SKQuadBatchObserver() = default;
};

class SKQuadBatch {
public:
    static constexpr size_t VERTICES_PER_QUAD = 4;
    static constexpr size_t INDICES_PER_QUAD = 6;   // two triangles
    // A frame's worth of glyphs or tiles without a reallocation.
    static constexpr size_t RESERVED_QUADS = 1024;
    // SkVertices indices are 16-bit: a mesh holds at most this many vertices.
    static constexpr size_t MAX_VERTICES = 0xffff;

    SKQuadBatch() {
        m_base.positions.reserve(VERTICES_PER_QUAD * RESERVED_QUADS);
        m_base.texCoords.reserve(VERTICES_PER_QUAD * RESERVED_QUADS);
        m_base.indices.reserve(INDICES_PER_QUAD * RESERVED_QUADS);
        m_colors.reserve(VERTICES_PER_QUAD * RESERVED_QUADS);
    }

    // Starts (or continues) a batch for canvas/paint/atlas; a different triple
    // draws the open batch first. A plain color paint (a blend mode, no
    // shader or runtime blender) does not end a batch when only its color
    // differs: from the second color on, the color travels per vertex and the
    // mesh paint is white, so tiles, glyphs and plain quads (spans, runs, rect
    // fills and outlines, lines) of many colors share one draw, and an atlas
    // batch one upload of the cells staged for them. A batch of one color
    // carries no color data. Blenders that take the color as a uniform (LCD,
    // XOR) keep a batch per color. For plain quads the trade depends on the
    // GPU: on the Parallels VM's paravirtual D3D11 a colored-vertex mesh was
    // slower than separate single-color draws; on an RTX 3080 Ti
    // the separate draws are the cost (about 0.56 us of recording each), and
    // merging makes small primitives of many colors 2.6 to 2.8x faster, with
    // span-heavy fills (dozens of wide spans per primitive, RenderPerfTest
    // FlatQuad) about 11 % slower for the colors they carry.
    void begin(SkCanvas *newCanvas, const SkPaint &newPaint, const sk_sp<SkImage> &atlas = nullptr,
               const SKQuadBatchTwoPass *passes = nullptr) {
        // A runtime blender (XOR, LCD) reads the destination. Backends without
        // framebuffer fetch (Dawn on D3D11) read it from a copy taken before the
        // draw, so overlapping quads of one draw would all see the state before
        // any of them (a crossing of two XOR strokes stays XORed once, the second
        // of two overlapping LCD glyphs loses the first's coverage). Two passes
        // over one mesh are not sequential either (both quads' second passes add
        // to the product of their first). Such quads go to mesh layers drawn
        // after the quads they overlap (push()).
        m_splitOnOverlap = (!newPaint.asBlendMode().has_value() && !SKConfiguration::framebufferFetch()) || passes != nullptr;
        // Colors are not merged for an observer of the draws' bounds either: its
        // afterDraw receives the batch's paint and reads the color from it.
        const bool mergeColors = passes == nullptr && !observesDrawBounds() && newPaint.getShader() == nullptr
                                 && newPaint.asBlendMode().has_value() && newPaint.getColorFilter() == nullptr
                                 && newPaint.getMaskFilter() == nullptr;
        // begin() runs per primitive (thousands per frame): no paint copy on the
        // plain path; with merged colors the key is the paint in white
        bool same = sameTarget(newCanvas, atlas, passes);
        if (same) {
            if (mergeColors) {
                SkPaint key = newPaint;
                key.setColor(SK_ColorWHITE);
                same = m_paint == key;
            } else {
                same = m_paint == newPaint;
            }
        }
        if (same) {
            if (mergeColors && newPaint.getColor() != m_color) {
                if (!m_colorPerVertex) {   // promote: the quads so far all have the first color
                    m_colors.assign(m_base.positions.size(), m_color);
                    m_colorPerVertex = true;
                }
                m_color = newPaint.getColor();
            }
            return;
        }
        flush();
        m_canvas = newCanvas;
        // what of a quad can be visible: the overlap extents are clipped to it (the clip does not change while a
        // batch is open, a clip change draws the batches first)
        m_clipBounds = m_splitOnOverlap ? newCanvas->getLocalClipBounds() : SkRect::MakeEmpty();
        m_paint = newPaint;
        if (mergeColors) {
            m_paint.setColor(SK_ColorWHITE);   // the color is not part of the key
        }
        m_color = newPaint.getColor();
        m_colorPerVertex = false;
        m_atlasImage = atlas;
        m_twoPass = passes;
        m_twoPassKey = passKeyOf(passes);
        m_meshPaint = newPaint;
        if (atlas) {
            // the atlas paints of the batch's last few (atlas, paint, passes), most recent first: a batch begun again
            // for a color it had (the LCD two-pass batches change color with every token) reuses the shaders built
            // for it instead of building the atlas shader and the two passes' runtime shaders again
            const uint32_t atlasID = atlas->uniqueID();
            for (size_t cached = 0; cached < m_paintCache.size(); cached++) {
                PaintCacheEntry &entry = m_paintCache[cached];
                if (entry.atlasID == atlasID && entry.passes == passes && entry.passKey == m_twoPassKey && entry.key == newPaint) {
                    m_meshPaint = entry.meshPaint;
                    m_secondPaint = entry.secondPaint;
                    std::rotate(m_paintCache.begin(), m_paintCache.begin() + (ptrdiff_t) cached,
                                m_paintCache.begin() + (ptrdiff_t) cached + 1);
                    return;
                }
            }
            sk_sp<SkShader> atlasShader = atlas->makeShader(SkTileMode::kClamp, SkTileMode::kClamp,
                                                            SkSamplingOptions(SkFilterMode::kNearest));
            if (passes != nullptr) {
                m_meshPaint.setShader(passes->passShader(SKQuadBatchPass::First, atlasShader, newPaint));
                m_meshPaint.setBlendMode(passes->passBlend(SKQuadBatchPass::First));
                m_secondPaint = newPaint;
                m_secondPaint.setShader(passes->passShader(SKQuadBatchPass::Second, atlasShader, newPaint));
                m_secondPaint.setBlendMode(passes->passBlend(SKQuadBatchPass::Second));
            } else {
                m_meshPaint.setShader(std::move(atlasShader));
            }
            if (m_paintCache.size() == PAINT_CACHE_SIZE) {
                m_paintCache.pop_back();
            }
            m_paintCache.insert(m_paintCache.begin(), PaintCacheEntry{atlasID, passes, m_twoPassKey, newPaint, m_meshPaint, m_secondPaint});
        }
    }

    [[nodiscard]] bool active() const { return m_canvas != nullptr; }
    // Begun and holding quads: flush() draws something.
    [[nodiscard]] bool pending() const { return m_canvas != nullptr && !m_base.indices.empty(); }

    // Begun on this canvas, atlas and passes (begin() for another paint of them would draw the batch).
    [[nodiscard]] bool sameTarget(SkCanvas *otherCanvas, const sk_sp<SkImage> &atlas, const SKQuadBatchTwoPass *passes) const {
        return m_canvas == otherCanvas && m_atlasImage.get() == atlas.get() && m_twoPass == passes
               && m_twoPassKey == passKeyOf(passes);
    }

    // The batch's paint is `otherPaint` (for batches that do not merge colors: two passes, blenders).
    [[nodiscard]] bool samePaint(const SkPaint &otherPaint) const { return m_paint == otherPaint; }

    // A pending quad's overlap extent intersects `rect` (tracked under splitOnOverlap only).
    [[nodiscard]] bool overlaps(const SkRect &rect) const {
        if (m_canvas == nullptr || rect.isEmpty()) {
            return false;
        }
        if (!SkRect::Intersects(m_tracked, rect)) {
            return false;   // nowhere near the batch's quads
        }
        if (m_base.overlaps(rect)) {
            return true;
        }
        for (size_t layerIndex = 0; layerIndex < m_layerCount; layerIndex++) {
            if (m_layers[layerIndex].overlaps(rect)) {
                return true;
            }
        }
        return false;
    }

    // Axis-aligned sprite from the atlas at (atlasX, atlasY). `ink`, if given, is the
    // part of the sprite that carries coverage, relative to its origin: the
    // extent the overlap test uses (glyph images are padded; neighbors whose
    // boxes touch rarely have overlapping ink).
    void addSprite(float x, float y, float width, float height, float atlasX, float atlasY, const SkIRect *ink = nullptr) {
        const Corners positions = {{{x, y}, {x + width, y}, {x + width, y + height}, {x, y + height}}};
        const Corners texCoords = {{{atlasX, atlasY}, {atlasX + width, atlasY},
                                    {atlasX + width, atlasY + height}, {atlasX, atlasY + height}}};
        if (ink != nullptr) {
            const SkRect extent = SkRect::MakeLTRB(x + ink->fLeft, y + ink->fTop, x + ink->fRight, y + ink->fBottom);
            push(positions.data(), texCoords.data(), &extent);
        } else {
            push(positions.data(), texCoords.data());
        }
    }

    // Integer rect [left, right) x [top, bottom), as spans and runs are given.
    void addRect(jint left, jint top, jint right, jint bottom) {
        if (right <= left || bottom <= top) {
            return;
        }
        const Corners positions = {{{(float) left, (float) top}, {(float) right, (float) top},
                                    {(float) right, (float) bottom}, {(float) left, (float) bottom}}};
        push(positions.data(), nullptr);
    }

    void addQuad(const SkPoint corners[4]) {
        push(corners, nullptr);
    }

    // Outer quad minus inner quad (same corner order): the four side quads.
    void addRing(const SkPoint outer[4], const SkPoint inner[4]) {
        for (size_t corner = 0; corner < VERTICES_PER_QUAD; corner++) {
            const size_t next = (corner + 1) % VERTICES_PER_QUAD;
            const Corners side = {{outer[corner], outer[next], inner[next], inner[corner]}};
            push(side.data(), nullptr);
        }
    }

    // The batch's owner, told about its draws (SKQuadBatchObserver); null: none. Set once, by the owner.
    void setObserver(SKQuadBatchObserver *observer) { m_observer = observer; }

    // Draws what is queued and closes the batch. A batch not begun (never, or not since its last flush)
    // holds nothing, since every add follows a begin() and a flush leaves the batch empty: there is
    // nothing to draw or clear (the backend flushes every batch of a context before each canvas draw).
    void flush() {
        if (m_canvas == nullptr) {
            return;
        }
        drawPending();
        m_canvas = nullptr;
        m_atlasImage.reset();
    }

    // Draws what is queued, and drops the paints the batch keeps until its next begin (its key, the mesh
    // paints, the atlas paints of the paint cache): they may hold images of a context being disposed (a
    // texture paint's), which must not outlive it.
    void forgetPaints() {
        flush();
        m_paint = SkPaint();
        m_meshPaint = SkPaint();
        m_secondPaint = SkPaint();
        m_paintCache.clear();
    }

private:
    using Corners = std::array<SkPoint, VERTICES_PER_QUAD>;

    static int passKeyOf(const SKQuadBatchTwoPass *passes) {
        return passes != nullptr ? passes->passKey() : 0;
    }

    [[nodiscard]] bool observesDrawBounds() const { return m_observer != nullptr && m_observer->takesDrawBounds(); }

    // The vertices of one draw. While splitOnOverlap, a quad that overlaps a
    // pending quad goes to the layer after the last one it overlaps instead of
    // ending the batch: within a layer no two quads overlap, so one draw (or
    // one pass pair) composes them correctly, and a quad is drawn after every
    // quad it overlaps, since the layers are drawn in order. A line of text is
    // one or two draws rather than one per glyph. The overlap extents are kept
    // in a hashed grid of 64-pixel cells, so a test touches the few quads
    // nearby.
    struct Mesh {
        static constexpr int CELL_SHIFT = 6;   // 64-pixel grid cells
        static constexpr int BUCKET_COUNT = 64;    // a 512x512 region of cells before the hash wraps
        // The cell hash: odd multipliers, so that BUCKET_COUNT cells of a row or column reach every bucket.
        static constexpr int HASH_MULTIPLIER_X = 7;
        static constexpr int HASH_MULTIPLIER_Y = 13;
        // Coordinates are clamped to these before the int conversion, which keeps it defined whatever arrives
        // (the extents are clipped to the batch's clip bounds in push()); +-2^30, in whole cells.
        static constexpr float MIN_COORDINATE = -1073741824.0f;
        static constexpr float MAX_COORDINATE = 1073741760.0f;

        std::vector<SkPoint> positions;
        std::vector<SkPoint> texCoords;
        std::vector<uint16_t> indices;
        std::vector<SkRect> bounds;
        std::vector<uint16_t> buckets[BUCKET_COUNT];   // indices into `bounds` by grid cell

        void clear() {
            positions.clear();
            texCoords.clear();
            indices.clear();
            bounds.clear();
            for (auto &bucket : buckets) {
                bucket.clear();
            }
        }

        // Since the hash wraps every BUCKET_COUNT cells in either direction, BUCKET_COUNT cells of a row or
        // column reach every bucket that row or column can.
        static int cellOf(float coordinate) {
            return (int) std::clamp(coordinate, MIN_COORDINATE, MAX_COORDINATE) >> CELL_SHIFT;
        }
        template <typename Visitor> static void cells(const SkRect &rect, Visitor &&visit) {
            const int firstColumn = cellOf(floorf(rect.fLeft)), firstRow = cellOf(floorf(rect.fTop));
            const int lastColumn = std::min(cellOf(ceilf(rect.fRight) - 1), firstColumn + BUCKET_COUNT - 1);
            const int lastRow = std::min(cellOf(ceilf(rect.fBottom) - 1), firstRow + BUCKET_COUNT - 1);
            for (int row = firstRow; row <= lastRow; row++) {
                for (int column = firstColumn; column <= lastColumn; column++) {
                    visit((column * HASH_MULTIPLIER_X + row * HASH_MULTIPLIER_Y) & (BUCKET_COUNT - 1));
                }
            }
        }

        void addBounds(const SkRect &rect) {
            const uint16_t index = (uint16_t) bounds.size();
            bounds.push_back(rect);
            cells(rect, [&](int bucket) { buckets[bucket].push_back(index); });
        }

        [[nodiscard]] bool overlaps(const SkRect &rect) const {
            bool hit = false;
            cells(rect, [&](int bucket) {
                for (uint16_t index : buckets[bucket]) {
                    hit |= SkRect::Intersects(bounds[index], rect);
                }
            });
            return hit;
        }
    };

    // Under splitOnOverlap the base layer is drawn once it holds this many
    // quads: bounds the grid's occupancy, and the 16-bit bound indices.
    static constexpr size_t MAX_OVERLAP_TESTED = 1024;

    void push(const SkPoint positions[4], const SkPoint *texCoords, const SkRect *extent = nullptr) {
        // (The quads draw under the canvas clip of the moment: a clip change draws the batches first.)
        Mesh *target = &m_base;
        if (m_splitOnOverlap) {
            // The inked extent suffices for two passes (a pixel without coverage
            // is multiplied by one and added nothing). A blender that reads a
            // copy of the destination writes the stale copy back wherever its
            // quad has no coverage, so there the whole quad counts.
            SkRect bounds;
            if (extent != nullptr && m_twoPass != nullptr) {
                bounds = *extent;
            } else {
                bounds.setBounds(SkSpan(positions, VERTICES_PER_QUAD));
            }
            // only the visible part can overlap anything (a fill of 10^6 px covers 10^8 grid cells unclipped);
            // a quad entirely outside the clip draws nothing and is not tracked
            if (!bounds.intersect(m_clipBounds)) {
                bounds.setEmpty();
            }
            if (m_base.bounds.size() >= MAX_OVERLAP_TESTED) {
                drawPending();
            }
            if (!bounds.isEmpty()) {
                // the layer after the last one holding a quad this one overlaps
                size_t depth = 0;
                if (m_base.overlaps(bounds)) {
                    depth = 1;
                    for (size_t layerIndex = 0; layerIndex < m_layerCount; layerIndex++) {
                        if (m_layers[layerIndex].overlaps(bounds)) {
                            depth = layerIndex + 2;
                        }
                    }
                }
                target = layer(depth);
                if (target->positions.size() + VERTICES_PER_QUAD > MAX_VERTICES) {
                    drawPending();
                    target = &m_base;
                }
                target->addBounds(bounds);
                m_tracked.join(bounds);
            }
        } else if (m_base.positions.size() + VERTICES_PER_QUAD > MAX_VERTICES) {
            drawPending();
        }
        const uint16_t first = (uint16_t) target->positions.size();
        target->positions.insert(target->positions.end(), positions, positions + VERTICES_PER_QUAD);
        if (texCoords != nullptr) {
            target->texCoords.insert(target->texCoords.end(), texCoords, texCoords + VERTICES_PER_QUAD);
        }
        if (m_colorPerVertex) {
            m_colors.insert(m_colors.end(), VERTICES_PER_QUAD, m_color);   // base only: colors are never merged under splitOnOverlap
        }
        const std::array<uint16_t, INDICES_PER_QUAD> quad = {
            first, (uint16_t) (first + 1), (uint16_t) (first + 2),
            first, (uint16_t) (first + 2), (uint16_t) (first + 3)
        };
        target->indices.insert(target->indices.end(), quad.begin(), quad.end());
    }

    void draw(const Mesh &mesh, bool withColors) {
        // kModulate: with per-vertex colors the mesh paint is white, so the
        // result is vertex color x atlas alpha (an alpha-only shader yields
        // the paint color times the coverage); without an atlas the vertex
        // color is the source.
        SkASSERT(mesh.texCoords.empty() || mesh.texCoords.size() == mesh.positions.size());   // a batch is textured or not
        sk_sp<SkVertices> vertices = SkVertices::MakeCopy(SkVertices::kTriangles_VertexMode,
                                                          (int) mesh.positions.size(), mesh.positions.data(),
                                                          mesh.texCoords.empty() ? nullptr : mesh.texCoords.data(),
                                                          withColors ? m_colors.data() : nullptr,
                                                          (int) mesh.indices.size(), mesh.indices.data());
        if (withColors) {
            SkPaint white = m_meshPaint;
            white.setColor(SK_ColorWHITE);
            m_canvas->drawVertices(vertices, SkBlendMode::kModulate, white);
        } else {
            m_canvas->drawVertices(vertices, SkBlendMode::kModulate, m_meshPaint);
            if (m_twoPass != nullptr) {
                m_canvas->drawVertices(vertices, SkBlendMode::kModulate, m_secondPaint);
            }
        }
    }

    // Layer `depth` (0 is the base); the vectors of a used layer are kept.
    Mesh *layer(size_t depth) {
        if (depth == 0) {
            return &m_base;
        }
        while (m_layerCount < depth) {
            if (m_layerCount == m_layers.size()) {
                m_layers.emplace_back();
            }
            m_layerCount++;
        }
        return &m_layers[depth - 1];
    }

    static void extend(SkRect &bounds, const Mesh &mesh) {
        for (const SkPoint &point : mesh.positions) {
            bounds.fLeft = std::min(bounds.fLeft, point.fX);
            bounds.fTop = std::min(bounds.fTop, point.fY);
            bounds.fRight = std::max(bounds.fRight, point.fX);
            bounds.fBottom = std::max(bounds.fBottom, point.fY);
        }
    }

    void drawPending() {
        if (m_canvas != nullptr && !m_base.indices.empty()) {
            // the bounds matter to the composite after the draw only; the mask
            // cache's beforeDraw ignores them
            SkRect bounds = SkRect::MakeLTRB(SK_ScalarMax, SK_ScalarMax, -SK_ScalarMax, -SK_ScalarMax);
            const bool withBounds = observesDrawBounds();
            if (withBounds) {
                extend(bounds, m_base);
                for (size_t layerIndex = 0; layerIndex < m_layerCount; layerIndex++) {
                    extend(bounds, m_layers[layerIndex]);
                }
            }
            if (m_observer != nullptr) {
                m_observer->beforeUpload();
                m_observer->beforeDraw(bounds);
            }
            draw(m_base, m_colorPerVertex);
            for (size_t layerIndex = 0; layerIndex < m_layerCount; layerIndex++) {
                draw(m_layers[layerIndex], false);
            }
            if (withBounds) {
                m_observer->afterDraw(bounds, m_paint);
            }
        }
        m_base.clear();
        for (size_t layerIndex = 0; layerIndex < m_layerCount; layerIndex++) {
            m_layers[layerIndex].clear();
        }
        m_layerCount = 0;
        m_colors.clear();
        m_tracked.setEmpty();
    }

    SKQuadBatchObserver *m_observer = nullptr;
    SkCanvas *m_canvas = nullptr;
    SkRect m_clipBounds = SkRect::MakeEmpty();   // the canvas clip in local coordinates, under m_splitOnOverlap
    SkPaint m_paint;          // the batch key: the paint, color white if colors are merged
    SkPaint m_meshPaint;      // the first paint of the batch, with the atlas as its shader
    SkPaint m_secondPaint;    // the second pass (SKQuadBatchTwoPass)
    struct PaintCacheEntry {
        uint32_t atlasID;
        const SKQuadBatchTwoPass *passes;
        int passKey;
        SkPaint key;        // the paint begin() was given
        SkPaint meshPaint;
        SkPaint secondPaint;
    };
    static constexpr size_t PAINT_CACHE_SIZE = 16;   // the last few (atlas, paint, passes) begun: a glyph list's colors
    std::vector<PaintCacheEntry> m_paintCache;   // begin()'s atlas paints, most recent first
    const SKQuadBatchTwoPass *m_twoPass = nullptr;
    int m_twoPassKey = 0;
    bool m_colorPerVertex = false;     // a second color arrived: `m_colors` is filled
    bool m_splitOnOverlap = false;     // a runtime blender or two passes: overlapping quads do not share a draw
    SkColor m_color = SK_ColorWHITE;   // of the quads being added
    sk_sp<SkImage> m_atlasImage;
    Mesh m_base;                       // the quads of the batch, or its first layer
    std::vector<Mesh> m_layers;        // quads overlapping an earlier layer's, drawn after it
    size_t m_layerCount = 0;           // layers in use; the rest keep their vectors for reuse
    std::vector<SkColor> m_colors;     // per vertex of `m_base` when m_colorPerVertex
    SkRect m_tracked = SkRect::MakeEmpty();   // the union of the tracked overlap extents (overlaps()'s quick test)
};

#endif /* SKQuadBatch_h_Included */
