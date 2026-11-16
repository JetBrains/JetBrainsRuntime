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
#ifndef SKGraphicsConfig_h_Included
#define SKGraphicsConfig_h_Included

// The native side of a sun.java2d.skia.SKGraphicsConfig: the Graphite context and recorder created for
// the platform configuration it names (getNativeConfigInfo, an SKPlatformConfig), on the configuration's
// device and queue (SKPlatform.h), the caches of that context, and the end of its recordings. One per
// configuration that rendered, created on first use and disposed with the configuration (DISPOSE_CONFIG).
// Flusher thread only.

#include "SKGlyphCache.h"
#include "SKMaskCache.h"
#include "SKPlatform.h"
#include "SKRecordingOwner.h"

#include "include/core/SkImage.h"
#include "include/core/SkSurface.h"
#include "include/gpu/graphite/Context.h"
#include "include/gpu/graphite/Recorder.h"

// ReSharper disable once CppUnusedIncludeDirective - uint32_t is used here
#include <cstdint>
#include <memory>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include "jni.h"

struct SKSurfaceDataOps;
class SKSurfaceState;

// The owner of the caches' recordings (SKRecordingOwner).
class SKGraphicsConfig : public SKRecordingOwner {
public:
    // The context of the configuration, created on first use; null for a null configuration and when
    // Graphite cannot make a context or recorder on its device.
    [[nodiscard]] static SKGraphicsConfig *of(SKPlatformConfig *platformConfig);
    // The context of the configuration a Skia surface belongs to; null if it has none.
    [[nodiscard]] static SKGraphicsConfig *ofSurface(const SKSurfaceDataOps *ops);
    // The context of the configuration if it has one already (none is created).
    [[nodiscard]] static SKGraphicsConfig *existing(SKPlatformConfig *platformConfig);
    // The configuration is disposed: its context leaves the configurations' table and goes when the caller
    // drops it (null if the configuration has none).
    [[nodiscard]] static std::unique_ptr<SKGraphicsConfig> remove(SKPlatformConfig *platformConfig);

    ~SKGraphicsConfig() override;
    SKGraphicsConfig(const SKGraphicsConfig &) = delete;
    SKGraphicsConfig &operator=(const SKGraphicsConfig &) = delete;

    [[nodiscard]] SKPlatformConfig *platformConfig() const { return m_platformConfig; }
    [[nodiscard]] skgpu::graphite::Context *context() const { return m_context.get(); }
    [[nodiscard]] skgpu::graphite::Recorder *recorder() const override { return m_recorder.get(); }

    // Ends the recording (no submit): the pending batches of the caches and the rect batch are drawn into it,
    // the recording generation advances, and the recording is snapped and inserted into the context.
    void endRecording() override;
    // Advanced by every recording ended (endRecording); the glyph caches date cells by it.
    [[nodiscard]] uint32_t recordingGeneration() const override { return m_recordingGeneration; }

    // The glyph caches of one kind: grayscale (alpha-only atlases, taking the paint color) or LCD (RGB coverage atlases).
    [[nodiscard]] const SKGlyphCacheSet &glyphCaches(const bool lcd) const {
        return lcd ? m_lcdGlyphCaches : m_grayscaleGlyphCaches;
    }
    // The bucket's glyph cache of a kind, created on first use; null if its creation failed.
    [[nodiscard]] SKGlyphCache *glyphCache(bool lcd, int bucket);
    // Marlin's coverage tiles (SKMaskCache.h), created on first use; null if its creation failed.
    [[nodiscard]] SKMaskCache *maskCache();

    // The pending batches of the caches made so far (none is created): all of them (the glyph caches', then the
    // mask cache's); every bucket's batch of the glyph caches, grayscale and LCD; the mask cache's.
    void flushCaches();
    void flushGlyphCaches();
    void flushMaskCache();

    // LCD text without a destination read (SKTextPipe): the coverage correction table of an LCD contrast,
    // now the most recently used; null if none is stored.
    [[nodiscard]] sk_sp<SkImage> findLCDTable(jint contrast);
    // The table made for `contrast` after findLCDTable missed; a new contrast with MAX_LCD_TABLES stored drops
    // the least recently used first. A failed make (null) is not stored and drops nothing (the next glyph list
    // tries again).
    void storeLCDTable(jint contrast, sk_sp<SkImage> table);

    // The surfaces wrapped with this recorder (SKContext::getSurface). A surface state names this context
    // (SKSurfaceState::graphicsConfig) exactly while it is one of these surfaces: the one operation wraps its
    // platform texture `texture` in `surface` and adds it, the other removes it and releases every wrapper of
    // it made with this recorder.
    // The context's destructor unwraps the surfaces left.
    void wrapSurface(SKSurfaceState *surfaceState, sk_sp<SkSurface> surface, void *texture);
    void unwrapSurface(SKSurfaceState *surfaceState);
    [[nodiscard]] size_t surfaceCount() const { return m_surfaces.size(); }

    // An image made with this recorder for a texture paint (SKPaints::setTexturePaint, given the context an
    // SKOwnedImage names). It is kept once (a linked source's image is the same object each time, kept by its
    // surface), until it is the only reference left at the end of a recording or after the release of a surface
    // (releaseUnreferencedTexturePaintImages). The disposal's check: a paint's holder that keeps the image
    // past the context (the render state, the rect batch, the last glyph list, any other) keeps a reference
    // the context's own surfaces and caches do not. The destructor reports such references (a release
    // trace), counting the images' references whoever holds them. Only while that trace would print
    // (SKConfiguration::errorTraces): otherwise nothing is tracked and nothing kept.
    void trackTexturePaintImage(const sk_sp<SkImage> &image);
    // The tracked images no one else references anymore go: at the end of a recording, and once a
    // released surface (SKContext::releaseSurface) and the paint of it no longer hold its linked image.
    void releaseUnreferencedTexturePaintImages();

private:
    SKGraphicsConfig() = default;

    SKPlatformConfig *m_platformConfig = nullptr;
    std::unique_ptr<skgpu::graphite::Context> m_context;
    std::unique_ptr<skgpu::graphite::Recorder> m_recorder;
    SKGlyphCacheSet m_grayscaleGlyphCaches;
    SKGlyphCacheSet m_lcdGlyphCaches;
    std::unique_ptr<SKMaskCache> m_maskCache;
    bool m_maskCacheFailed = false;
    std::unordered_set<SKSurfaceState *> m_surfaces;
    struct LCDTable {
        jint contrast;
        sk_sp<SkImage> image;
    };
    std::vector<LCDTable> m_lcdTables;   // by LCD contrast, the least recently used first
    // trackTexturePaintImage, keyed by the image (a linked source's is tracked once)
    std::unordered_map<const SkImage *, sk_sp<SkImage>> m_texturePaintImages;
    uint32_t m_recordingGeneration = 1;
};

#endif /* SKGraphicsConfig_h_Included */
