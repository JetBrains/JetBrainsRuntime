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

// The native side of sun.java2d.skia.SKSurfaceData: its ops (SKSurfaceDataOps, one per Java SKSurfaceData) and
// the Skia side of its platform texture (SKSurfaceState). The wrapper is made, viewed and released
// for the current destination by SKContext (getSurface, getImage, releaseSurface).

#include "SKPlatform.h"

#include "jni.h"
#include "SurfaceData.h"

#include "include/core/SkSurface.h"

class SKGraphicsConfig;

// The Skia side of a Skia surface, made with its ops (SKSurfaceData.initOps, under the entry lock) and
// deleted with its platform surface (DISPOSE_SURFACE): the wrapper of its platform texture, made on its
// first use (SKContext::getSurface), and the context whose recorder made it. Flusher thread only.
class SKSurfaceState {
public:
    SKSurfaceState() = default;
    SKSurfaceState(const SKSurfaceState &) = delete;
    SKSurfaceState &operator=(const SKSurfaceState &) = delete;

    // The wrapper of the platform texture; null: none.
    [[nodiscard]] const sk_sp<SkSurface> &surface() const { return m_surface; }
    // The identity of the platform texture the wrapper was made for (SKPlatformSurface::textureHandle).
    [[nodiscard]] void *wrappedTexture() const { return m_wrappedTexture; }
    // The context whose recorder made the wrapper: exactly while the state is one of its surfaces
    // (SKGraphicsConfig::wrapSurface, unwrapSurface, the only ones that set it). Null: none.
    [[nodiscard]] SKGraphicsConfig *graphicsConfig() const { return m_graphicsConfig; }
    // A wrapper of this platform texture exists.
    [[nodiscard]] bool wraps(const void *texture) const { return m_surface && m_wrappedTexture == texture; }

    // The platform replaced the texture: the wrapper goes; the context keeps the state until the next
    // wrap or unwrap.
    void discardSurface() { m_surface.reset(); }

private:
    friend class SKGraphicsConfig;   // wrapSurface, unwrapSurface

    // The wrapper of `texture`, made with the recorder of `graphicsConfig`. Asserts (a release trace) that the
    // state is not another context's surface.
    void wrap(sk_sp<SkSurface> surface, void *texture, SKGraphicsConfig *graphicsConfig);
    // The wrapper and its context go (the surface is released, or its context disposed).
    void unwrap();

    sk_sp<SkSurface> m_surface;
    void *m_wrappedTexture = nullptr;
    SKGraphicsConfig *m_graphicsConfig = nullptr;
};

// Native ops of a SKSurfaceData. Allocated by SurfaceData_InitOps (plain C
// memory, no constructor runs; freed by SurfaceData_DisposeOps), as every Java2D
// pipeline's ops: only pointers and scalars live here, SKSurfaceData.cpp sets every
// field, and what they point to is deleted at DISPOSE_SURFACE.
struct SKSurfaceDataOps {
    SurfaceDataOps     sdOps;
    SKPlatformSurface *platform;   // the wrapped platform surface (owns the texture); null once disposed
    jint               type;       // AccelSurface type
    jboolean           isOpaque;
    jboolean           countFrames;   // report presents to the window's awt.window.counters
    SKSurfaceState    *state;      // null once disposed

    // These ops as a Skia surface's; null if they are another pipeline's (a software surface) or null.
    [[nodiscard]] static SKSurfaceDataOps *from(SurfaceDataOps *ops);
};

#endif /* SKSurfaceData_h_Included */
