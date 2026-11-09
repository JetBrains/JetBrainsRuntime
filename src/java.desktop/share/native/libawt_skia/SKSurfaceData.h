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
#ifndef SKSurfaceData_h_Included
#define SKSurfaceData_h_Included

// The native side of sun.java2d.skia.SKSurfaceData: its ops (SKSDOps, one per Java SKSurfaceData) and
// the Skia side of its platform texture (SKSurfaceState). The wrapper is made, viewed and released
// for the current destination by SKContext (getSurface, getImage, releaseSurface).

#include "SKPlatform.h"

#include "jni.h"
#include "SurfaceData.h"

#include "include/core/SkSurface.h"

#include <cstdint>
#include <unordered_map>

class SKGraphicsConfig;

// The Skia side of a Skia surface, made on its first use (SKContext::getSurface): the wrapper of its
// platform texture and the context whose recorder made it, its identity and content version (the
// image atlas keys and dates its copies by them), and, for a window Graphite presents
// (SKPlatform::acquirePresentTarget), the wrappers of its swap chain images. Flusher thread only.
class SKSurfaceState {
public:
    SKSurfaceState();   // with an identity unique for the life of the process
    SKSurfaceState(const SKSurfaceState &) = delete;
    SKSurfaceState &operator=(const SKSurfaceState &) = delete;

    // The wrapper of the platform texture; null: none.
    [[nodiscard]] const sk_sp<SkSurface> &surface() const { return m_surface; }
    // The identity of the platform texture the wrapper was made for (SKPlatform::textureHandle).
    [[nodiscard]] void *wrappedTexture() const { return m_wrappedTexture; }
    // The context whose recorder made the wrapper: exactly while the state is one of its surfaces
    // (SKGraphicsConfig::wrapSurface, unwrapSurface, the only ones that set it). Null: none.
    [[nodiscard]] SKGraphicsConfig *graphicsConfig() const { return m_graphicsConfig; }
    // A wrapper of this platform texture exists.
    [[nodiscard]] bool wraps(void *texture) const { return m_surface && m_wrappedTexture == texture; }

    // The platform replaced the texture: the wrapper goes; the context keeps the state until the next
    // wrap or unwrap.
    void discardSurface() { m_surface.reset(); }

    // Unique for the life of the process (SKImageAtlas keys copies by it).
    [[nodiscard]] uint64_t uid() const { return m_uid; }
    // Advanced by every op drawing into the surface (touch, from SKContext::markTouched).
    [[nodiscard]] uint64_t contentVersion() const { return m_contentVersion; }
    void touch() { m_contentVersion++; }

    // The wrapper of the swap chain image of a window's present target, made with the recorder that made
    // the state's wrapper (the recorder of the state's context, graphicsConfig) once per image of a swap
    // chain generation (SKPlatformPresentTarget::imageKey); null without a wrapper, or if the image cannot
    // be wrapped (tried again on the next present). The wrappers go when the state is unwrapped, with the
    // context whose recorder made them.
    [[nodiscard]] sk_sp<SkSurface> presentTarget(const SKPlatformPresentTarget &target);

private:
    friend class SKGraphicsConfig;   // wrapSurface, unwrapSurface

    // The wrapper of `texture`, made with the recorder of `graphicsConfig`. Asserts (a release trace) that the
    // state is not another context's surface.
    void wrap(sk_sp<SkSurface> surface, void *texture, SKGraphicsConfig *graphicsConfig);
    // The wrappers and their context go: the texture's and the swap chain images' (the surface is released, or
    // its context disposed).
    void unwrap();

    sk_sp<SkSurface> m_surface;
    void *m_wrappedTexture = nullptr;
    SKGraphicsConfig *m_graphicsConfig = nullptr;
    uint64_t m_uid = 0;
    uint64_t m_contentVersion = 0;
    // the wrappers of the swap chain images, by imageKey, of the swap chain generation m_presentGeneration
    std::unordered_map<uint64_t, sk_sp<SkSurface>> m_presentTargets;
    uint32_t m_presentGeneration = 0;
};

// Native ops of a SKSurfaceData. Allocated by SurfaceData_InitOps (plain C
// memory, no constructor runs), so only pointers and scalars live here and
// SKSurfaceData.cpp sets every field.
struct SKSDOps {
    SurfaceDataOps     sdOps;
    SKPlatformSurface *platform;   // the wrapped platform surface (owns the texture)
    jint               type;       // AccelSurface type
    jboolean           isOpaque;
    jboolean           countFrames;   // report presents to the window's awt.window.counters
    SKSurfaceState    *state;
};

namespace SKSurfaceData {

// Whether these ops belong to a Skia surface (as opposed to a software raster).
[[nodiscard]] bool isSkiaOps(SurfaceDataOps *ops);
// The platform surface behind a Skia surface's ops (null if not Skia's or already disposed).
[[nodiscard]] SKPlatformSurface *platformOf(SurfaceDataOps *ops);

} // namespace SKSurfaceData

#endif /* SKSurfaceData_h_Included */
