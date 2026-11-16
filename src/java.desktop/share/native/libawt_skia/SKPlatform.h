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
#ifndef SKPlatform_h_Included
#define SKPlatform_h_Included

// What the shared Skia backend needs from the GPU API and the window system: the
// platform's configurations and surfaces, as abstract classes a window system's
// platform layer implements once per graphics API Graphite renders through
// (Win32SKPlatform.h: Dawn, with devices and swap chains of its own), and what holds
// for the whole platform (SKPlatform). Everything else in libawt_skia is
// platform-independent C++ against Graphite and these classes; nothing here depends
// on a platform layer.

#include "include/core/SkColorType.h"
#include "include/gpu/graphite/BackendTexture.h"
#include "include/gpu/graphite/Context.h"

#include <memory>

// The platform's graphics configuration a Graphite context is created for: the
// native side of a Java configuration (Win32SKGraphicsConfig on Windows), which holds
// its address as an SKPlatformConfig (getNativeConfigInfo). Made and deleted by the
// platform layer; the shared backend keys its Graphite context by it (SKGraphicsConfig).
class SKPlatformConfig {
public:
    virtual ~SKPlatformConfig() = default;
    SKPlatformConfig(const SKPlatformConfig &) = delete;
    SKPlatformConfig &operator=(const SKPlatformConfig &) = delete;

    // A Graphite context on the configuration's device, submitting on the queue the
    // platform presents from so that presentation is ordered after rendering.
    // Returns null if the configuration has no usable device.
    [[nodiscard]] virtual std::unique_ptr<skgpu::graphite::Context> makeContext(
            const skgpu::graphite::ContextOptions &options) = 0;

    // Whether the configuration's device is lost; nothing renders or presents on it anymore.
    [[nodiscard]] virtual bool isLost() const = 0;

    // Whether this configuration and `other` render on the same GPU device, so that a
    // texture of one can be sampled by a context of the other (the contexts of two
    // displays on one adapter share textures; two adapters do not).
    [[nodiscard]] bool sameDevice(const SKPlatformConfig &other) const {
        return device() != nullptr && device() == other.device();
    }

protected:
    SKPlatformConfig() = default;

    // The identity of the configuration's GPU device (sameDevice); null if it has none.
    [[nodiscard]] virtual const void *device() const = 0;
};

// The platform surface a Skia surface wraps: its GPU texture and, for a window, what
// presents it. Made by the platform layer for a Java surface, which hands its address
// as an SKPlatformSurface to the surface's ops (SKSurfaceDataOps::platform); deleted with the
// Skia wrapper (DISPOSE_SURFACE), which releases the texture and the swap chain.
class SKPlatformSurface {
public:
    virtual ~SKPlatformSurface() = default;
    SKPlatformSurface(const SKPlatformSurface &) = delete;
    SKPlatformSurface &operator=(const SKPlatformSurface &) = delete;

    // The configuration the surface belongs to: the key of the context that wraps it.
    [[nodiscard]] virtual SKPlatformConfig *config() const = 0;

    // The surface's current GPU texture as an opaque identity (it changes when the
    // platform replaces the texture, e.g. on resize); null if there is none yet.
    [[nodiscard]] virtual void *textureHandle() const = 0;

    // The surface's current GPU texture as a Graphite backend texture together
    // with its color type; false if the surface has no texture.
    [[nodiscard]] virtual bool backendTexture(skgpu::graphite::BackendTexture *texture,
                                              SkColorType *colorType) const = 0;

    // The window surface's texture has new content: present it. Returns whether a
    // present was issued (false: no swap chain image, a lost device, a replaced surface).
    [[nodiscard]] virtual bool requestRedraw() = 0;

protected:
    SKPlatformSurface() = default;
};

namespace SKPlatform {

// The display's sub-pixel order for LCD text: true where the platform reports BGR
// (ClearType's orientation).
bool lcdOrderBGR();

} // namespace SKPlatform

// Scope for the platform objects a flush creates (nothing on Windows; the hook
// where a platform needs one, such as an autorelease pool). Instantiate at the
// top of every JNI entry that renders.
class SKPlatformScope {
public:
    SKPlatformScope();
    ~SKPlatformScope();
    SKPlatformScope(const SKPlatformScope &) = delete;
    SKPlatformScope &operator=(const SKPlatformScope &) = delete;
private:
    void *m_handle;
};

#endif /* SKPlatform_h_Included */
