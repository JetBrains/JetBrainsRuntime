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

#include "SKStats.h"

#include "include/core/SkCanvas.h"
#include "include/core/SkRect.h"
#include "include/core/SkColor.h"
#include "include/core/SkImage.h"
#include "include/core/SkPaint.h"
#include "include/core/SkSamplingOptions.h"
#include "include/core/SkShader.h"
#include "include/core/SkVertices.h"
#include "jni.h"
#include <algorithm>

// A runtime blender's quads split on overlap unless the blender reads the live
// framebuffer (SKBackend.h; declared here to keep this header free of it).
bool SKBackend_FramebufferFetch();
#include <cmath>
#include <functional>
#include <vector>

// Quads of one paint drawn as a single mesh. Every Graphite draw costs about
// 2 us of CPU and has its own paint key, so spans, scanlines, Bresenham runs,
// rect fills and outlines, non-AA parallelograms, and the glyph and mask
// sprites are collected here instead. A mesh is rasterized with the
// pixel-centre rule and no coverage, which is what Java2D's non-AA primitives
// specify (Graphite would anti-alias anything not pixel-aligned). Without an
// atlas the paint's shader is sampled in canvas coordinates; with an atlas the
// quads carry texture coordinates and the atlas is the paint's shader: an
// alpha-only atlas takes the paint colour, an RGB atlas feeds the blender.
// Two draws of every mesh instead of one, for sources that blend per colour
// channel without reading the destination (LCD text as DirectWrite composites
// it): each pass gets its blend mode and a shader built over the atlas shader
// for the batch's paint (its colour).
struct SKQuadBatchTwoPass {
    std::function<sk_sp<SkShader>(const sk_sp<SkShader> &atlas, const SkPaint &paint)> firstShader;
    std::function<sk_sp<SkShader>(const sk_sp<SkShader> &atlas, const SkPaint &paint)> secondShader;
    SkBlendMode firstBlend = SkBlendMode::kModulate;
    SkBlendMode secondBlend = SkBlendMode::kPlus;
    int key = 0;   // part of the batch key: what else the shaders depend on (the LCD contrast)
};

class SKQuadBatch {
public:
    // The rect clip every batch clips its quads to on arrival (push), null for none; set by the
    // backend's render state for the destination being drawn (one flusher, one state).
    static inline const SkIRect *gClip = nullptr;
    // An axis-aligned quad in the order addRect and addSprite give (top-left, top-right, bottom-right,
    // bottom-left): the quads push() clips to gClip itself.
    static bool clippable(const SkPoint pos[4]) {
        return pos[0].fY == pos[1].fY && pos[1].fX == pos[2].fX && pos[2].fY == pos[3].fY && pos[3].fX == pos[0].fX
               && pos[0].fX < pos[1].fX && pos[0].fY < pos[3].fY;
    }

    // The kind of primitive a batch holds, set by its owner (SKBackend: rects and lines 0, glyphs 1,
    // coverage tiles 2). Under gCrossFamily a quad joining a batch first draws the pending batches
    // of the other families it touches (drawn before it in Java's order, possibly beneath it), so
    // pending batches of different families never overlap and draw in any order: a batched op
    // of one kind need not draw the other kinds' batches (SKBackend's REQUIRE_CANVAS_* macros).
    // Batches of one family keep their own rules (the glyph caches' colour batches, drawOverlapped).
    int family = 0;
    static inline bool gCrossFamily = false;
    // The batches holding quads (one flusher thread); never destroyed, so a static batch that
    // outlives it at exit (SKBackend's rect batch) still finds it.
    static std::vector<SKQuadBatch *> &pendingBatches() {
        static auto *batches = new std::vector<SKQuadBatch *>();
        return *batches;
    }

    ~SKQuadBatch() {
        unregisterPending();
    }

    SKQuadBatch() {
        // 1024 quads: a frame's worth of glyphs or tiles without a reallocation
        base.positions.reserve(4 * 1024);
        base.texCoords.reserve(4 * 1024);
        base.indices.reserve(6 * 1024);
        colors.reserve(4 * 1024);
    }

    // Starts (or continues) a batch for canvas/paint/atlas; a different triple
    // draws the open batch first. A plain colour paint (a blend mode, no
    // shader or runtime blender) does not end a batch when only its colour
    // differs: from the second colour on, the colour travels per vertex and the
    // mesh paint is white, so tiles, glyphs and plain quads (spans, runs, rect
    // fills and outlines, lines) of many colours share one draw, and an atlas
    // batch one upload of the cells staged for them. A batch of one colour
    // carries no colour data. Blenders that take the colour as a uniform (LCD,
    // XOR) keep a batch per colour. For plain quads the trade depends on the
    // GPU: on the Parallels VM's paravirtual D3D11 a coloured-vertex mesh was
    // slower than separate single-colour draws (2026-10-15); on an RTX 3080 Ti
    // the separate draws are the cost (about 0.56 us of recording each), and
    // merging makes small primitives of many colours 2.6 to 2.8x faster, with
    // span-heavy fills (dozens of wide spans per primitive, RenderPerfTest
    // FlatQuad) about 11 % slower for the colours they carry (2026-10-23).
    void begin(SkCanvas *c, const SkPaint &p, const sk_sp<SkImage> &atlas = nullptr, const SKQuadBatchTwoPass *passes = nullptr) {
        // A runtime blender (XOR, LCD) reads the destination. Backends without
        // framebuffer fetch (Dawn on D3D11) read it from a copy taken before the
        // draw, so overlapping quads of one draw would all see the state before
        // any of them (a crossing of two XOR strokes stays XORed once, the second
        // of two overlapping LCD glyphs loses the first's coverage). Two passes
        // over one mesh are not sequential either (both quads' second passes add
        // to the product of their first). Such quads go to mesh layers drawn
        // after the quads they overlap (push()).
        splitOnOverlap = (!p.asBlendMode().has_value() && !SKBackend_FramebufferFetch()) || passes != nullptr;
        // Colours are not merged with an afterDraw hook either: it receives the
        // batch's paint and reads the colour from it.
        const bool mergeColors = passes == nullptr && !afterDraw && p.getShader() == nullptr
                                 && p.asBlendMode().has_value() && p.getColorFilter() == nullptr
                                 && p.getMaskFilter() == nullptr;
        // begin() runs per primitive (thousands per frame): no paint copy on the
        // plain path; with merged colours the key is the paint in white
        bool same = canvas == c && atlasImage.get() == atlas.get() && twoPass == passes
                    && twoPassKey == (passes != nullptr ? passes->key : 0);
        if (same) {
            if (mergeColors) {
                SkPaint key = p;
                key.setColor(SK_ColorWHITE);
                same = paint == key;
            } else {
                same = paint == p;
            }
        }
        if (same) {
            if (mergeColors && p.getColor() != color) {
                if (!colorPerVertex) {   // promote: the quads so far all have the first colour
                    colors.assign(base.positions.size(), color);
                    colorPerVertex = true;
                }
                color = p.getColor();
            }
            return;
        }
        flush();
        canvas = c;
        paint = p;
        if (mergeColors) {
            paint.setColor(SK_ColorWHITE);   // the colour is not part of the key
        }
        color = p.getColor();
        colorPerVertex = false;
        atlasImage = atlas;
        twoPass = passes;
        twoPassKey = passes != nullptr ? passes->key : 0;
        meshPaint = p;
        if (atlas) {
            sk_sp<SkShader> atlasShader = atlas->makeShader(SkTileMode::kClamp, SkTileMode::kClamp,
                                                            SkSamplingOptions(SkFilterMode::kNearest));
            if (passes != nullptr) {
                meshPaint.setShader(passes->firstShader(atlasShader, p));
                meshPaint.setBlendMode(passes->firstBlend);
                secondPaint = p;
                secondPaint.setShader(passes->secondShader(atlasShader, p));
                secondPaint.setBlendMode(passes->secondBlend);
            } else {
                meshPaint.setShader(std::move(atlasShader));
            }
        }
    }

    bool active() const { return canvas != nullptr; }
    // Begun and holding quads: flush() draws something.
    bool pending() const { return canvas != nullptr && !base.indices.empty(); }

    // Begun on this canvas, atlas and passes (begin() for another paint of them would draw the batch).
    bool sameTarget(SkCanvas *c, const sk_sp<SkImage> &atlas, const SKQuadBatchTwoPass *passes) const {
        return canvas == c && atlasImage.get() == atlas.get() && twoPass == passes
               && twoPassKey == (passes != nullptr ? passes->key : 0);
    }

    // The batch's paint is p (for batches that do not merge colours: two passes, blenders).
    bool samePaint(const SkPaint &p) const { return paint == p; }

    // Pending and r meets the bounds of its quads (all of them, as drawn): a draw outside them
    // commutes with the batch, so it need not draw the batch first (SKBackend_Blit).
    bool touches(const SkRect &r) const {
        return pending() && SkRect::Intersects(drawnBounds, r);
    }

    // A pending quad's overlap extent intersects r (tracked under splitOnOverlap only).
    bool overlaps(const SkRect &r) const {
        if (canvas == nullptr || r.isEmpty()) {
            return false;
        }
        if (!SkRect::Intersects(tracked, r)) {
            return false;   // nowhere near the batch's quads
        }
        if (base.overlaps(r)) {
            return true;
        }
        for (size_t i = 0; i < layerCount; i++) {
            if (layers[i].overlaps(r)) {
                return true;
            }
        }
        return false;
    }

    // Axis-aligned sprite from the atlas at (tx, ty). `ink`, if given, is the
    // part of the sprite that carries coverage, relative to its origin: the
    // extent the overlap test uses (glyph images are padded; neighbours whose
    // boxes touch rarely have overlapping ink).
    void addSprite(float x, float y, float w, float h, float tx, float ty, const SkIRect *ink = nullptr) {
        const SkPoint pos[4] = {{x, y}, {x + w, y}, {x + w, y + h}, {x, y + h}};
        const SkPoint tex[4] = {{tx, ty}, {tx + w, ty}, {tx + w, ty + h}, {tx, ty + h}};
        if (ink != nullptr) {
            const SkRect extent = SkRect::MakeLTRB(x + ink->fLeft, y + ink->fTop, x + ink->fRight, y + ink->fBottom);
            push(pos, tex, &extent);
        } else {
            push(pos, tex);
        }
    }

    // Integer rect [l, r) x [t, b), as spans and runs are given.
    void addRect(jint l, jint t, jint r, jint b) {
        if (r <= l || b <= t) {
            return;
        }
        const SkPoint pos[4] = {{(float) l, (float) t}, {(float) r, (float) t}, {(float) r, (float) b}, {(float) l, (float) b}};
        push(pos, nullptr);
    }

    void addQuad(const SkPoint pts[4]) {
        push(pts, nullptr);
    }

    // Outer quad minus inner quad (same corner order): the four side quads.
    void addRing(const SkPoint outer[4], const SkPoint inner[4]) {
        for (int i = 0; i < 4; i++) {
            const int j = (i + 1) % 4;
            const SkPoint side[4] = {outer[i], outer[j], inner[j], inner[i]};
            push(side, nullptr);
        }
    }

    // Runs first when the batch draws, whoever triggers the draw. The glyph
    // caches upload their staged atlas rows here: an upload is only ordered
    // ahead of the destination's pass if a draw sampling the atlas is recorded
    // after it.
    std::function<void()> beforeUpload;

    // Runs right before a mesh is drawn, after beforeUpload, with the bounds of
    // the draw if an afterDraw hook wants them. The mask cache uploads its
    // staged atlas cells here, the coverage accumulation clears the bounds.
    std::function<void(const SkRect &bounds)> beforeDraw;

    // Runs right after the meshes of a draw, with their bounds (the coverage
    // accumulation composites them onto the destination here).
    std::function<void(const SkRect &bounds, const SkPaint &paint)> afterDraw;

    // Draws what is queued and closes the batch.
    void flush() {
        drawPending();
        canvas = nullptr;
        atlasImage.reset();
    }

private:
    // The vertices of one draw. While splitOnOverlap, a quad that overlaps a
    // pending quad goes to the layer after the last one it overlaps instead of
    // ending the batch: within a layer no two quads overlap, so one draw (or
    // one pass pair) composes them correctly, and a quad is drawn after every
    // quad it overlaps, since the layers are drawn in order. A line of text is
    // one or two draws rather than one per glyph. The overlap extents are kept
    // in a hashed grid of 64-pixel cells, so a test touches the few quads
    // nearby.
    struct Mesh {
        static constexpr int kCellShift = 6;   // 64-pixel grid cells
        static constexpr int kBuckets = 64;    // a 512x512 region of cells before the hash wraps

        std::vector<SkPoint> positions;
        std::vector<SkPoint> texCoords;
        std::vector<uint16_t> indices;
        std::vector<SkRect> bounds;
        std::vector<uint16_t> buckets[kBuckets];   // indices into `bounds` by grid cell

        void clear() {
            positions.clear();
            texCoords.clear();
            indices.clear();
            bounds.clear();
            for (auto &bucket : buckets) {
                bucket.clear();
            }
        }

        template <typename F> static void cells(const SkRect &r, F &&f) {
            const int x0 = (int) floorf(r.fLeft) >> kCellShift, x1 = ((int) ceilf(r.fRight) - 1) >> kCellShift;
            const int y0 = (int) floorf(r.fTop) >> kCellShift, y1 = ((int) ceilf(r.fBottom) - 1) >> kCellShift;
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    f((x * 7 + y * 13) & (kBuckets - 1));
                }
            }
        }

        void addBounds(const SkRect &r) {
            const uint16_t index = (uint16_t) bounds.size();
            bounds.push_back(r);
            cells(r, [&](int bucket) { buckets[bucket].push_back(index); });
        }

        bool overlaps(const SkRect &r) const {
            bool hit = false;
            cells(r, [&](int bucket) {
                for (uint16_t index : buckets[bucket]) {
                    hit |= SkRect::Intersects(bounds[index], r);
                }
            });
            return hit;
        }
    };

    // Under splitOnOverlap the base layer is drawn once it holds this many
    // quads: bounds the grid's occupancy, and the 16-bit bound indices.
    static constexpr size_t kMaxOverlapTested = 1024;

    void push(const SkPoint pos[4], const SkPoint *tex, const SkRect *extent = nullptr) {
        // The device-space rect clip batched quads are clipped to as they arrive (SKBackend's lazy
        // canvas clip): an axis-aligned quad in the order addRect and addSprite give, with its
        // texture coordinates mapped along, exactly; one outside the clip is dropped. Any other
        // quad goes in as it is (the backend draws it under the canvas clip).
        SkPoint clippedPos[4], clippedTex[4];
        SkRect clippedExtent;
        // Not for a shader paint without an atlas (gradients, textures): such a quad cut to exactly the
        // surface was drawn differently by Graphite (2026-11-03, RenderMatrix xf:SCALE_2/TEXTURE/FILL_RECT;
        // the same with an exact-fit rect and the canvas clip), so those keep the canvas clip.
        if (gClip != nullptr && clippable(pos) && !(atlasImage == nullptr && paint.getShader() != nullptr)) {
            const float l = pos[0].fX, t = pos[0].fY, r = pos[1].fX, b = pos[2].fY;
            const float cl = std::max(l, (float) gClip->fLeft), ct = std::max(t, (float) gClip->fTop);
            const float cr = std::min(r, (float) gClip->fRight), cb = std::min(b, (float) gClip->fBottom);
            if (cl >= cr || ct >= cb) {
                return;   // nothing of it inside the clip
            }
            if (cl != l || ct != t || cr != r || cb != b) {
                clippedPos[0] = {cl, ct}; clippedPos[1] = {cr, ct}; clippedPos[2] = {cr, cb}; clippedPos[3] = {cl, cb};
                if (tex != nullptr) {
                    const float su = (tex[1].fX - tex[0].fX) / (r - l), sv = (tex[3].fY - tex[0].fY) / (b - t);
                    const float ul = tex[0].fX + (cl - l) * su, ur = tex[0].fX + (cr - l) * su;
                    const float vt = tex[0].fY + (ct - t) * sv, vb = tex[0].fY + (cb - t) * sv;
                    clippedTex[0] = {ul, vt}; clippedTex[1] = {ur, vt}; clippedTex[2] = {ur, vb}; clippedTex[3] = {ul, vb};
                    tex = clippedTex;
                }
                pos = clippedPos;
                if (extent != nullptr) {
                    clippedExtent = *extent;
                    if (!clippedExtent.intersect(SkRect::MakeLTRB(cl, ct, cr, cb))) {
                        clippedExtent.setEmpty();
                    }
                    extent = &clippedExtent;
                }
            }
        }
        SkRect quadBounds;
        quadBounds.setBounds(SkSpan(pos, 4));
        if (gCrossFamily && !pendingBatches().empty()) {
            drawTouchedFamilies(quadBounds);
        }
        Mesh *target = &base;
        if (splitOnOverlap) {
            // The inked extent suffices for two passes (a pixel without coverage
            // is multiplied by one and added nothing). A blender that reads a
            // copy of the destination writes the stale copy back wherever its
            // quad has no coverage, so there the whole quad counts.
            SkRect bounds;
            if (extent != nullptr && twoPass != nullptr) {
                bounds = *extent;
            } else {
                bounds.setBounds(SkSpan(pos, 4));
            }
            if (base.bounds.size() >= kMaxOverlapTested) {
                drawPending();
            }
            if (!bounds.isEmpty()) {
                // the layer after the last one holding a quad this one overlaps
                size_t depth = 0;
                if (base.overlaps(bounds)) {
                    depth = 1;
                    for (size_t i = 0; i < layerCount; i++) {
                        if (layers[i].overlaps(bounds)) {
                            depth = i + 2;
                        }
                    }
                }
                target = layer(depth);
                if (target->positions.size() + 4 > 0xffff) {   // 16-bit indices
                    drawPending();
                    target = &base;
                }
                target->addBounds(bounds);
                tracked.join(bounds);
            }
        } else if (base.positions.size() + 4 > 0xffff) {
            drawPending();   // 16-bit indices
        }
        drawnBounds.join(quadBounds);
        if (base.indices.empty() && target == &base) {
            pendingBatches().push_back(this);   // pending from this quad on (drawPending removes it)
        }
        uint16_t first = (uint16_t) target->positions.size();
        target->positions.insert(target->positions.end(), pos, pos + 4);
        if (tex != nullptr) {
            target->texCoords.insert(target->texCoords.end(), tex, tex + 4);
        }
        if (colorPerVertex) {
            colors.insert(colors.end(), 4, color);   // base only: colours are never merged under splitOnOverlap
        }
        const uint16_t quad[6] = { first, (uint16_t) (first + 1), (uint16_t) (first + 2),
                                   first, (uint16_t) (first + 2), (uint16_t) (first + 3) };
        target->indices.insert(target->indices.end(), quad, quad + 6);
    }

    void draw(const Mesh &m, bool withColors) {
        // kModulate: with per-vertex colours the mesh paint is white, so the
        // result is vertex colour x atlas alpha (an alpha-only shader yields
        // the paint colour times the coverage); without an atlas the vertex
        // colour is the source.
        SkASSERT(m.texCoords.empty() || m.texCoords.size() == m.positions.size());   // a batch is textured or not
        sk_sp<SkVertices> mesh = SkVertices::MakeCopy(SkVertices::kTriangles_VertexMode,
                                                      (int) m.positions.size(), m.positions.data(),
                                                      m.texCoords.empty() ? nullptr : m.texCoords.data(),
                                                      withColors ? colors.data() : nullptr,
                                                      (int) m.indices.size(), m.indices.data());
        if (withColors) {
            SkPaint white = meshPaint;
            white.setColor(SK_ColorWHITE);
            SKStats_Count(SK_COUNT_MESH_DRAWS);
            canvas->drawVertices(mesh, SkBlendMode::kModulate, white);
        } else {
            SKStats_Count(SK_COUNT_MESH_DRAWS);
            canvas->drawVertices(mesh, SkBlendMode::kModulate, meshPaint);
            if (twoPass != nullptr) {
                SKStats_Count(SK_COUNT_MESH_DRAWS);
                canvas->drawVertices(mesh, SkBlendMode::kModulate, secondPaint);
            }
        }
    }

    // Layer `depth` (0 is the base); the vectors of a used layer are kept.
    Mesh *layer(size_t depth) {
        if (depth == 0) {
            return &base;
        }
        while (layerCount < depth) {
            if (layerCount == layers.size()) {
                layers.emplace_back();
            }
            layerCount++;
        }
        return &layers[depth - 1];
    }

    static void extend(SkRect &bounds, const Mesh &m) {
        for (const SkPoint &pt : m.positions) {
            bounds.fLeft = std::min(bounds.fLeft, pt.fX);
            bounds.fTop = std::min(bounds.fTop, pt.fY);
            bounds.fRight = std::max(bounds.fRight, pt.fX);
            bounds.fBottom = std::max(bounds.fBottom, pt.fY);
        }
    }

    void unregisterPending() {
        auto it = std::find(pendingBatches().begin(), pendingBatches().end(), this);
        if (it != pendingBatches().end()) {
            pendingBatches().erase(it);
        }
    }

    // gCrossFamily: before a quad within r joins this batch, the pending batches of other families
    // on its canvas that touch r are drawn (see `family`).
    void drawTouchedFamilies(const SkRect &r) {
        for (size_t i = 0; i < pendingBatches().size(); ) {
            SKQuadBatch *b = pendingBatches()[i];
            if (b != this && b->family != family && b->canvas == canvas && b->touches(r)) {
                SKStats_SetDrawCause(SK_COUNT_MESH_DRAWS_AT_KIND);
                b->drawPending();   // leaves pendingBatches()
                SKStats_SetDrawCause(SK_COUNT_COUNT);
                i = 0;
            } else {
                i++;
            }
        }
    }

    void drawPending() {
        if (canvas != nullptr && !base.indices.empty()) {
            // the bounds matter to the composite after the draw only; the mask
            // cache's beforeDraw ignores them
            SkRect bounds = SkRect::MakeLTRB(SK_ScalarMax, SK_ScalarMax, -SK_ScalarMax, -SK_ScalarMax);
            if (afterDraw) {
                extend(bounds, base);
                for (size_t i = 0; i < layerCount; i++) {
                    extend(bounds, layers[i]);
                }
            }
            if (beforeUpload) {
                beforeUpload();
            }
            if (beforeDraw) {
                beforeDraw(bounds);
            }
            draw(base, colorPerVertex);
            for (size_t i = 0; i < layerCount; i++) {
                draw(layers[i], false);
            }
            if (afterDraw) {
                afterDraw(bounds, paint);
            }
        }
        unregisterPending();
        base.clear();
        for (size_t i = 0; i < layerCount; i++) {
            layers[i].clear();
        }
        layerCount = 0;
        colors.clear();
        tracked.setEmpty();
        drawnBounds.setEmpty();
    }

    SkCanvas *canvas = nullptr;
    SkPaint paint;          // the batch key: the paint, colour white if colours are merged
    SkPaint meshPaint;      // the first paint of the batch, with the atlas as its shader
    SkPaint secondPaint;    // the second pass (SKQuadBatchTwoPass)
    const SKQuadBatchTwoPass *twoPass = nullptr;
    int twoPassKey = 0;
    bool colorPerVertex = false;     // a second colour arrived: `colors` is filled
    bool splitOnOverlap = false;     // a runtime blender or two passes: overlapping quads do not share a draw
    SkColor color = SK_ColorWHITE;   // of the quads being added
    sk_sp<SkImage> atlasImage;
    Mesh base;                       // the quads of the batch, or its first layer
    std::vector<Mesh> layers;        // quads overlapping an earlier layer's, drawn after it
    size_t layerCount = 0;           // layers in use; the rest keep their vectors for reuse
    std::vector<SkColor> colors;     // per vertex of `base` when colorPerVertex
    SkRect tracked = SkRect::MakeEmpty();   // the union of the tracked overlap extents (overlaps()'s quick test)
    SkRect drawnBounds = SkRect::MakeEmpty();   // the union of the pending quads' bounds (touches())
};

#endif /* SKQuadBatch_h_Included */
