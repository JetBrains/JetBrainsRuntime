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
// The Graphite contexts of the configurations (SKGraphicsConfig.h).
// All functions run on the Skia queue flusher thread unless noted.

#include "SKGraphicsConfig.h"
#include "SKConfiguration.h"
#include "SKNeverDestroyed.h"
#include "SKRectBatch.h"
#include "SKSurfaceData.h"

#include "Trace.h"

#include "include/gpu/graphite/ContextOptions.h"
#include "include/gpu/graphite/GraphiteTypes.h"
#include "include/gpu/graphite/Recording.h"
#include "include/gpu/graphite/Surface.h"

#include <algorithm>

using namespace skgpu::graphite;

namespace {

// Never destroyed (SKNeverDestroyed.h), as the devices are not: at process exit the table's destructor
// would release every configuration's context during DLL detach. A disposed configuration's context
// leaves it (remove).
using SKGraphicsConfigTable = std::unordered_map<SKPlatformConfig *, std::unique_ptr<SKGraphicsConfig>>;
SKNeverDestroyed<SKGraphicsConfigTable> gGraphicsConfigs;

// The configured side (SKConfiguration::glyphCacheTexels) is the 32-pixel bucket's; the 16-pixel
// bucket has half the side (the same cell count), the larger buckets the same side or whatever
// holds LARGE_BUCKET_MIN_CELLS_PER_ROW squared cells (a font's glyphs times their sub-pixel
// variants), whichever is more.
constexpr jint CONFIGURED_SIDE_CELL = 32;
constexpr jint LARGE_BUCKET_MIN_CELLS_PER_ROW = 16;   // 256 cells

// LCD coverage tables are kept per contrast (SKTextPipe.cpp); an application uses one or two. A miss costs
// about 1.6 ms (two powf for each of the 256x256 entries and a 64 KB upload; a frame of one LCD string with a
// new contrast over the same frame with a known one, RTX 3080 Ti, Vulkan and Dawn), so at the limit only the
// least recently used table goes: a cycle through more contrasts than this still rebuilds, one that fits not.
constexpr size_t MAX_LCD_TABLES = 8;

jint glyphCacheSide(const jint cellSize) {
    const jint configured = SKConfiguration::glyphCacheTexels();
    return cellSize < CONFIGURED_SIDE_CELL ? configured / 2 : std::max(configured, cellSize * LARGE_BUCKET_MIN_CELLS_PER_ROW);
}

void flushGlyphCacheSet(const SKGlyphCacheSet &set) {
    for (const std::unique_ptr<SKGlyphCache> &cache : set.caches) {
        if (cache != nullptr) {
            cache->flush();
        }
    }
}

void freeGlyphCaches(SKGlyphCacheSet &set) {
    for (std::unique_ptr<SKGlyphCache> &cache : set.caches) {
        cache.reset();
    }
}

} // namespace

SKGraphicsConfig *SKGraphicsConfig::of(SKPlatformConfig *platformConfig) {
    if (platformConfig == nullptr) {
        return nullptr;
    }
    if (const auto found = gGraphicsConfigs->find(platformConfig); found != gGraphicsConfigs->end()) {
        return found->second.get();
    }
    ContextOptions options;
    // No multisampled passes. Graphite renders a whole pass with MSAA as soon
    // as one draw in it needs it, which quantizes every antialiased edge to the
    // sample count; anti-aliased paths use the path atlas instead, with full
    // coverage precision like the Metal pipeline's shaders.
    options.fInternalMultisampleCount = SampleCount::k1;
    std::unique_ptr<Context> context = platformConfig->makeContext(options);
    if (!context) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "SKGraphicsConfig::of: cannot create Graphite context");
        return nullptr;
    }
    std::unique_ptr<Recorder> recorder = context->makeRecorder();
    if (!recorder) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "SKGraphicsConfig::of: cannot create a Graphite recorder");
        return nullptr;
    }
    std::unique_ptr<SKGraphicsConfig> graphicsConfig(new SKGraphicsConfig());
    graphicsConfig->m_platformConfig = platformConfig;
    graphicsConfig->m_recorder = std::move(recorder);
    graphicsConfig->m_context = std::move(context);
    SKGraphicsConfig *created = graphicsConfig.get();
    (*gGraphicsConfigs)[platformConfig] = std::move(graphicsConfig);
    J2dTraceLn(J2D_TRACE_INFO, "SKGraphicsConfig::of: created Graphite context for config %p", platformConfig);
    return created;
}

SKGraphicsConfig *SKGraphicsConfig::ofSurface(const SKSurfaceDataOps *ops) {
    return ops->platform != nullptr ? of(ops->platform->config()) : nullptr;
}

// ReSharper disable once CppParameterMayBeConstPtrOrRef - the table is keyed by non-const pointers
SKGraphicsConfig *SKGraphicsConfig::existing(SKPlatformConfig *platformConfig) {
    const auto found = gGraphicsConfigs->find(platformConfig);
    return found != gGraphicsConfigs->end() ? found->second.get() : nullptr;
}

// ReSharper disable once CppParameterMayBeConstPtrOrRef - the table is keyed by non-const pointers
std::unique_ptr<SKGraphicsConfig> SKGraphicsConfig::remove(SKPlatformConfig *platformConfig) {
    const auto found = gGraphicsConfigs->find(platformConfig);
    if (found == gGraphicsConfigs->end()) {
        return nullptr;
    }
    std::unique_ptr<SKGraphicsConfig> removed = std::move(found->second);
    gGraphicsConfigs->erase(found);
    return removed;
}

// Surfaces wrapped with this recorder cannot be drawn to anymore; they are
// re-wrapped on their next use (the platform texture is still there). The
// disposal has submitted, which drew every pending batch, and the holders of
// paints outside the context dropped them. Never at process exit
// (gGraphicsConfigs); the trace says when a context goes (SKExitWhilePaintingTest).
SKGraphicsConfig::~SKGraphicsConfig() {
    J2dRlsTraceLn(J2D_TRACE_INFO, "SKGraphicsConfig: releasing the Graphite context %p of config %p",
                  m_context.get(), m_platformConfig);
    while (!m_surfaces.empty()) {
        unwrapSurface(*m_surfaces.begin());
    }
    m_maskCache.reset();
    freeGlyphCaches(m_grayscaleGlyphCaches);
    freeGlyphCaches(m_lcdGlyphCaches);
    m_lcdTables.clear();
    // what the context's own surfaces and caches held is gone: a texture paint's image referenced elsewhere
    // would be released after its recorder (SKDisposedContextTest)
    const auto heldElsewhere = static_cast<size_t>(std::ranges::count_if(
            m_texturePaintImages, [](const auto &tracked) { return !tracked.second->unique(); }));
    if (heldElsewhere != 0) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "SKGraphicsConfig: %zu texture paint images of the disposed context are held outside it",
                      heldElsewhere);
    }
    m_texturePaintImages.clear();
    m_recorder.reset();
    m_context.reset();
}

void SKGraphicsConfig::endRecording() {
    if (m_recorder == nullptr) {
        return;
    }
    flushCaches();
    SKRectBatch::batch().flush();
    m_recordingGeneration++;
    releaseUnreferencedTexturePaintImages();
    const std::unique_ptr<Recording> recording = m_recorder->snap();
    if (!recording) {
        return;
    }
    InsertRecordingInfo insertInfo;
    insertInfo.fRecording = recording.get();
    if (!m_context->insertRecording(insertInfo)) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "SKGraphicsConfig::endRecording: insertRecording failed");
    }
}

SKGlyphCache *SKGraphicsConfig::glyphCache(const bool lcd, const int bucket) {
    SKGlyphCacheSet &set = lcd ? m_lcdGlyphCaches : m_grayscaleGlyphCaches;
    std::unique_ptr<SKGlyphCache> &slot = set.caches[bucket];
    if (bool &failed = set.failed[bucket]; slot == nullptr && !failed) {
        const jint cellSize = SK_GLYPH_BUCKET_CELLS[bucket];
        slot = SKGlyphCache::make(this, &set, lcd, cellSize, glyphCacheSide(cellSize));
        failed = slot == nullptr;
    }
    return slot.get();
}

SKMaskCache *SKGraphicsConfig::maskCache() {
    if (m_maskCache == nullptr && !m_maskCacheFailed) {
        m_maskCache = SKMaskCache::make(this);
        m_maskCacheFailed = m_maskCache == nullptr;
    }
    return m_maskCache.get();
}

void SKGraphicsConfig::flushCaches() {
    flushGlyphCaches();
    flushMaskCache();
}

// ReSharper disable once CppMemberFunctionMayBeConst - draws the glyph caches' batches
void SKGraphicsConfig::flushGlyphCaches() {
    flushGlyphCacheSet(m_grayscaleGlyphCaches);
    flushGlyphCacheSet(m_lcdGlyphCaches);
}

// ReSharper disable once CppMemberFunctionMayBeConst - draws the mask cache's batch
void SKGraphicsConfig::flushMaskCache() {
    if (m_maskCache != nullptr) {
        m_maskCache->flush();
    }
}

sk_sp<SkImage> SKGraphicsConfig::findLCDTable(jint contrast) {
    const auto found = std::ranges::find_if(m_lcdTables,
                                            [contrast](const LCDTable &table) { return table.contrast == contrast; });
    if (found == m_lcdTables.end()) {
        return nullptr;
    }
    std::rotate(found, found + 1, m_lcdTables.end());   // now the most recently used, at the back
    return m_lcdTables.back().image;
}

void SKGraphicsConfig::storeLCDTable(jint contrast, sk_sp<SkImage> table) {
    if (!table) {
        return;
    }
    const auto stored = std::ranges::find_if(m_lcdTables,
                                             [contrast](const LCDTable &entry) { return entry.contrast == contrast; });
    if (stored != m_lcdTables.end()) {
        m_lcdTables.erase(stored);
    } else if (m_lcdTables.size() >= MAX_LCD_TABLES) {
        m_lcdTables.erase(m_lcdTables.begin());   // a new table at the limit: the least recently used goes
    }
    m_lcdTables.push_back({.contrast = contrast, .image = std::move(table)});
}

void SKGraphicsConfig::trackTexturePaintImage(const sk_sp<SkImage> &image) {
    if (!SKConfiguration::errorTraces()) {
        return;   // the destructor's trace would not print
    }
    // once per image: a linked source's image is the same object every time (its surface keeps it)
    m_texturePaintImages.try_emplace(image.get(), image);
}

void SKGraphicsConfig::releaseUnreferencedTexturePaintImages() {
    if (!m_texturePaintImages.empty()) {
        std::erase_if(m_texturePaintImages, [](const auto &tracked) { return tracked.second->unique(); });
    }
}

void SKGraphicsConfig::wrapSurface(SKSurfaceState *surfaceState, sk_sp<SkSurface> surface, void *texture) {
    surfaceState->wrap(std::move(surface), texture, this);
    m_surfaces.insert(surfaceState);
}

void SKGraphicsConfig::unwrapSurface(SKSurfaceState *surfaceState) {
    m_surfaces.erase(surfaceState);
    surfaceState->unwrap();
}
