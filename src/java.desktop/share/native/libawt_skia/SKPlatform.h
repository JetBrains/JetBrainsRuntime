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
// One implementation per platform (macOS: MTLSKPlatform.mm on the Metal
// pipeline's contexts, textures and layers; Windows: DawnSKPlatform.cpp on a
// Dawn device and swap chains); everything else in libawt_skia is
// platform-independent C++ against Graphite.

#include "include/core/SkColorType.h"
#include "include/gpu/graphite/BackendTexture.h"
#include "include/gpu/graphite/Context.h"
#include "include/gpu/graphite/ContextOptions.h"

#include <memory>
#include "jni.h"

class SkFontMgr;
class SkTypeface;

// The platform's graphics configuration a Graphite context is created for
// (macOS: MTLGraphicsConfigInfo; Windows: Win32SKGraphicsConfig's native
// configuration), and the native ops of the platform surface a Skia surface
// wraps (macOS: BMTLSDOps; Windows: DawnSKSurface). Both are owned by the
// platform pipeline and only passed through here.
struct SKPlatformConfig;
struct SKPlatformSurface;

// A Graphite context on the configuration's device, submitting on the queue the
// platform presents from so that presentation is ordered after rendering.
// Returns null if the configuration has no usable device.
std::unique_ptr<skgpu::graphite::Context> SKPlatform_MakeContext(SKPlatformConfig *config,
                                                                 const skgpu::graphite::ContextOptions &options);

// The configuration a surface belongs to; null if the surface has none.
SKPlatformConfig *SKPlatform_ConfigOf(SKPlatformSurface *surface);

// Whether two configurations render on the same GPU device, so that a texture
// of one can be sampled by a context of the other (the contexts of two displays
// on one adapter share textures; two adapters do not).
bool SKPlatform_SameDevice(SKPlatformConfig *a, SKPlatformConfig *b);

// The surface's current GPU texture as an opaque identity (it changes when the
// platform replaces the texture, e.g. on resize); null if there is none yet.
void *SKPlatform_TextureHandle(SKPlatformSurface *surface);

// The surface's current GPU texture as a Graphite backend texture together
// with its colour type; false if the surface has no texture.
bool SKPlatform_BackendTexture(SKPlatformSurface *surface, skgpu::graphite::BackendTexture *texture,
                               SkColorType *colorType);

// Whether the surface is an on-screen window surface the platform presents.
bool SKPlatform_IsWindow(SKPlatformSurface *surface);

// The window surface's texture has new content: present it.
void SKPlatform_RequestRedraw(SKPlatformSurface *surface);

// The Skia wrapper of the surface is gone (DISPOSE_SURFACE): release what the
// platform half allocated for it. Nothing on macOS, where the Metal surface
// owns its texture and has its own disposer; on Windows the Dawn texture and
// swap chain created for the Java surface.
void SKPlatform_DisposeSurface(SKPlatformSurface *surface);

// Scope for the platform objects a flush creates (an autorelease pool on macOS,
// nothing elsewhere). Instantiate at the top of every JNI entry that renders.
class SKPlatformScope {
public:
    SKPlatformScope();
    ~SKPlatformScope();
    SKPlatformScope(const SKPlatformScope &) = delete;
    SKPlatformScope &operator=(const SKPlatformScope &) = delete;
private:
    void *handle;
};

// Text (SKFontScaler): the platform's font host, which is also what the
// platform's own text stack uses (macOS: CoreText; Windows: DirectWrite), and
// a typeface over the font object the platform's Font2D already holds (macOS:
// the CTFontRef behind CFont). Null if the platform has no font host yet.
sk_sp<SkFontMgr> SKPlatform_FontManager();
sk_sp<SkTypeface> SKPlatform_TypefaceFromNativeFont(jlong nativeFont);
// The platform's font fallback for a code point the font lacks (macOS: the
// mapper hands such characters to CoreText as negative glyph codes): the
// typeface that has it and the glyph in it; null if the platform has none.
sk_sp<SkTypeface> SKPlatform_FallbackTypeface(jlong nativeFont, int codePoint, uint16_t *glyph);

#endif /* SKPlatform_h_Included */
