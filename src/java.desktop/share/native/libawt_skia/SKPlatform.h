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

// What the shared Skia backend needs from the GPU API and the window system.
// Implemented by Win32SKPlatform.cpp, over the graphics API Graphite renders
// through, Dawn by default, with devices and swap chains of its own; everything
// else in libawt_skia is platform-independent C++ against Graphite. One
// implementation per build, bound at link time.

#include "include/core/SkColorType.h"
#include "include/gpu/graphite/BackendTexture.h"
#include "include/gpu/graphite/BackendSemaphore.h"
#include "include/gpu/graphite/Context.h"
#include "include/gpu/graphite/ContextOptions.h"
#include "include/gpu/MutableTextureState.h"
#include "include/core/SkRect.h"

#include <memory>
#include "jni.h"

// The platform's graphics configuration a Graphite context is created for
// (Win32SKGraphicsConfig's native configuration), and the native ops of the
// platform surface a Skia surface wraps (Win32SKSurface). Both are owned by the
// platform pipeline and only passed through here.
struct SKPlatformConfig;
struct SKPlatformSurface;

// SKPlatformPresentTarget::imageKey holds the swap chain generation above the image index's 32 bits.
constexpr int SK_IMAGE_KEY_GENERATION_SHIFT = 32;

// Window surfaces whose swap chain images Graphite draws into itself (Windows over
// Vulkan), instead of the platform copying the surface texture (SKPlatform::requestRedraw):
// the next swap chain image, the part of the surface texture that goes into it, the
// semaphore the draw waits for (the image is acquired) and the one it signals (the
// present waits for it), and the layout the image must be in for presenting. The
// backend draws the surface into the image in a recording of its own, inserts it with
// these, submits, and presents with SKPlatform::presentTarget. imageKey identifies the
// image until the swap chain is recreated, so the backend can keep the image's wrapper.
struct SKPlatformPresentTarget {
    skgpu::graphite::BackendTexture texture;
    SkColorType colorType = kUnknown_SkColorType;
    uint64_t imageKey = 0;   // swap chain generation << SK_IMAGE_KEY_GENERATION_SHIFT | image index
    SkIRect source = SkIRect::MakeEmpty();
    skgpu::graphite::BackendSemaphore waitSemaphore;
    skgpu::graphite::BackendSemaphore signalSemaphore;
    skgpu::MutableTextureState presentState;
    skgpu::graphite::GpuFinishedProc finishedProc = nullptr;   // releases what the platform acquired for this present
    skgpu::graphite::GpuFinishedContext finishedContext = nullptr;
};

namespace SKPlatform {

// A Graphite context on the configuration's device, submitting on the queue the
// platform presents from so that presentation is ordered after rendering.
// Returns null if the configuration has no usable device.
std::unique_ptr<skgpu::graphite::Context> makeContext(SKPlatformConfig *config,
                                                      const skgpu::graphite::ContextOptions &options);

// The configuration a surface belongs to; null if the surface has none.
SKPlatformConfig *configOf(SKPlatformSurface *surface);

// Whether two configurations render on the same GPU device, so that a texture
// of one can be sampled by a context of the other (the contexts of two displays
// on one adapter share textures; two adapters do not).
bool sameDevice(SKPlatformConfig *first, SKPlatformConfig *second);

// Whether the configuration's device is lost (or the configuration has none).
bool isLost(SKPlatformConfig *config);

// The surface's current GPU texture as an opaque identity (it changes when the
// platform replaces the texture, e.g. on resize); null if there is none yet.
void *textureHandle(SKPlatformSurface *surface);

// The surface's current GPU texture as a Graphite backend texture together
// with its color type; false if the surface has no texture.
bool backendTexture(SKPlatformSurface *surface, skgpu::graphite::BackendTexture *texture,
                    SkColorType *colorType);

// The window surface's texture has new content: present it. Returns whether a
// present was issued (false: no swap chain image, a lost device, a replaced surface).
bool requestRedraw(SKPlatformSurface *surface);

// False where the platform presents itself (SKPlatform::requestRedraw); true with an
// invalid texture where it does not, but has no image now (minimized, a lost device).
bool acquirePresentTarget(SKPlatformSurface *surface, SKPlatformPresentTarget *target);
// Presents the image acquired last, once the recording that draws into it is submitted.
bool presentTarget(SKPlatformSurface *surface);

// The Skia wrapper of the surface is gone (DISPOSE_SURFACE): release what the
// platform half allocated for it: the texture and swap chain created for the
// Java surface.
void disposeSurface(SKPlatformSurface *surface);

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
