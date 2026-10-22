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

// SKPlatform on the Metal pipeline: Graphite/Metal contexts on the
// MTLGraphicsConfig's device, Skia surfaces over the MTLSurfaceData textures,
// presentation through the MTLLayer.

#include "SKPlatform.h"
#include "MTLSKInterop.h"

#include "jlong.h"
#include "include/core/SkFontMgr.h"
#include "include/core/SkTypeface.h"
#include "include/ports/SkFontMgr_mac_ct.h"
#include "include/ports/SkTypeface_mac.h"
#import <objc/runtime.h>
#include "include/gpu/graphite/mtl/MtlBackendContext.h"
#include "include/gpu/graphite/mtl/MtlGraphiteTypes.h"
#include "include/gpu/graphite/mtl/MtlGraphiteTypes_cpp.h"

#import <Foundation/Foundation.h>

using namespace skgpu::graphite;

static inline MTLGraphicsConfigInfo *configInfo(SKPlatformConfig *config) {
    return reinterpret_cast<MTLGraphicsConfigInfo *>(config);
}

static inline BMTLSDOps *mtlOps(SKPlatformSurface *surface) {
    return reinterpret_cast<BMTLSDOps *>(surface);
}

std::unique_ptr<Context> SKPlatform_MakeContext(SKPlatformConfig *config, const ContextOptions &options) {
    MTLGraphicsConfigInfo *info = configInfo(config);
    if (info == nullptr || info->context == nil) {
        return nullptr;
    }
    id<MTLSKContextLike> mtlc = (id<MTLSKContextLike>) (id) info->context;
    id<MTLDevice> device = [mtlc device];
    id<MTLCommandQueue> queue = [mtlc blitCommandQueue];
    if (device == nil || queue == nil) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "SKPlatform_MakeContext: MTLContext has no device/queue");
        return nullptr;
    }
    // MTLLayer.blitTexture copies pTexture to the drawable on blitCommandQueue.
    // Submitting on the same queue makes Metal order the presentation blit after
    // our rendering without a CPU wait (commands on one queue execute in order).
    MtlBackendContext backend;
    backend.fDevice.retain((CFTypeRef) device);
    backend.fQueue.retain((CFTypeRef) queue);
    // ContextFactory::MakeMetal is the working entry point in m156;
    // SkContexts::MakeGraphite is announced as its successor but not functional yet.
    return ContextFactory::MakeMetal(backend, options);
}

SKPlatformConfig *SKPlatform_ConfigOf(SKPlatformSurface *surface) {
    BMTLSDOps *ops = mtlOps(surface);
    return ops != nullptr ? reinterpret_cast<SKPlatformConfig *>(MTLSK_ConfigInfo(ops)) : nullptr;
}

static id<MTLDevice> deviceOf(SKPlatformConfig *config) {
    MTLGraphicsConfigInfo *info = configInfo(config);
    if (info == nullptr || info->context == nil) {
        return nil;
    }
    return [(id<MTLSKContextLike>) (id) info->context device];
}

bool SKPlatform_SameDevice(SKPlatformConfig *a, SKPlatformConfig *b) {
    id<MTLDevice> da = deviceOf(a);
    return da != nil && da == deviceOf(b);
}

void *SKPlatform_TextureHandle(SKPlatformSurface *surface) {
    BMTLSDOps *ops = mtlOps(surface);
    return ops != nullptr ? ops->pTexture : nullptr;
}

bool SKPlatform_BackendTexture(SKPlatformSurface *surface, BackendTexture *texture, SkColorType *colorType) {
    BMTLSDOps *ops = mtlOps(surface);
    if (ops == nullptr || ops->pTexture == nullptr) {
        return false;
    }
    id<MTLTexture> tex = (id<MTLTexture>) ops->pTexture;
    switch (tex.pixelFormat) {
        case MTLPixelFormatBGRA8Unorm: *colorType = kBGRA_8888_SkColorType; break;
        case MTLPixelFormatRGBA8Unorm: *colorType = kRGBA_8888_SkColorType; break;
        default:
            J2dRlsTraceLn(J2D_TRACE_ERROR, "SKPlatform_BackendTexture: unsupported pixel format %lu", (unsigned long) tex.pixelFormat);
            return false;
    }
    *texture = BackendTextures::MakeMetal(SkISize::Make((int) tex.width, (int) tex.height), (CFTypeRef) tex);
    return true;
}

bool SKPlatform_IsWindow(SKPlatformSurface *surface) {
    BMTLSDOps *ops = mtlOps(surface);
    return ops != nullptr && MTLSK_PrivOps(ops) != nullptr && MTLSK_PrivOps(ops)->layer != nil;
}

void SKPlatform_RequestRedraw(SKPlatformSurface *surface) {
    BMTLSDOps *ops = mtlOps(surface);
    MTLSDOps *priv = ops != nullptr ? MTLSK_PrivOps(ops) : nullptr;
    if (priv == nullptr || priv->layer == nil) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "SKPlatform_RequestRedraw: window surface %p has no layer", surface);
        return;
    }
    J2dTraceLn(J2D_TRACE_VERBOSE, "SKPlatform_RequestRedraw: startRedraw layer=%p", (void *) priv->layer);
    [(id<MTLSKLayerLike>) (id) priv->layer startRedraw];
}

void SKPlatform_DisposeSurface(SKPlatformSurface *surface) {
    // the Metal surface owns the texture and has its own disposer
}

SKPlatformScope::SKPlatformScope() : handle([[NSAutoreleasePool alloc] init]) {}

SKPlatformScope::~SKPlatformScope() {
    [(NSAutoreleasePool *) handle drain];
}

// ---- text ------------------------------------------------------------------

sk_sp<SkFontMgr> SKPlatform_FontManager() {
    static sk_sp<SkFontMgr> manager = SkFontMgr_New_CoreText(nullptr);
    return manager;
}

// CFont's native font is libawt_lwawt's AWTFont wrapper; its NSFont is
// toll-free bridged to the CTFontRef Skia's CoreText port wraps, and the
// typeface retains it. The ivar is read through the runtime because this
// library does not link against libawt_lwawt.
sk_sp<SkTypeface> SKPlatform_TypefaceFromNativeFont(jlong nativeFont) {
    id awtFont = (id) jlong_to_ptr(nativeFont);
    if (awtFont == nil) {
        return nullptr;
    }
    Ivar ivar = class_getInstanceVariable([awtFont class], "fFont");
    id font = ivar != NULL ? object_getIvar(awtFont, ivar) : nil;
    return font != nil ? SkMakeTypefaceFromCTFont((CTFontRef) font) : nullptr;
}
