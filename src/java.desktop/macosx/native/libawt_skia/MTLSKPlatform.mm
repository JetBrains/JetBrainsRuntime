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
#include "include/core/SkMatrix.h"
#import <CoreGraphics/CoreGraphics.h>
#import <CoreText/CoreText.h>
#import <objc/runtime.h>
#include <mutex>
#include <vector>
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

// CFont's native font is libawt_lwawt's AWTFont wrapper; its NSFont (and the
// fallback base the system font carries) are toll-free bridged to the
// CTFontRefs Skia's CoreText port wraps. The ivars are read through the
// runtime because this library does not link against libawt_lwawt.
static CTFontRef awtFontIvar(jlong nativeFont, const char *name) {
    id awtFont = (id) jlong_to_ptr(nativeFont);
    if (awtFont == nil) {
        return nullptr;
    }
    Ivar ivar = class_getInstanceVariable([awtFont class], name);
    return ivar != NULL ? (CTFontRef) object_getIvar(awtFont, ivar) : nullptr;
}

sk_sp<SkTypeface> SKPlatform_TypefaceFromNativeFont(jlong nativeFont) {
    CTFontRef font = awtFontIvar(nativeFont, "fFont");
    return font != nullptr ? SkMakeTypefaceFromCTFont(font) : nullptr;   // a new reference; the CTFont is retained
}

// One SkTypeface per distinct fallback CTFont, so that the glyph caches key on
// a stable typeface (CTFontCreateForString returns a new object every time).
static sk_sp<SkTypeface> typefaceForCTFont(CTFontRef font) {
    static std::mutex lock;
    static std::vector<std::pair<CTFontRef, sk_sp<SkTypeface>>> cache;
    std::lock_guard<std::mutex> guard(lock);
    for (auto &entry : cache) {
        if (CFEqual(entry.first, font)) {
            return entry.second;
        }
    }
    sk_sp<SkTypeface> typeface = SkMakeTypefaceFromCTFont(font);
    if (typeface) {
        cache.emplace_back((CTFontRef) CFRetain(font), typeface);
    }
    return typeface;
}

// As CoreTextSupport.m's CTS_CopyCTFallbackFontAndGlyphForUnicode: the primary
// font if it has the character, else CoreText's fallback for the string
// (from the fallback base when the font has one), else the primary font.
sk_sp<SkTypeface> SKPlatform_FallbackTypeface(jlong nativeFont, int codePoint, uint16_t *glyph) {
    CTFontRef primary = awtFontIvar(nativeFont, "fFont");
    if (primary == nullptr || codePoint < 0 || codePoint > 0x10FFFF) {
        return nullptr;
    }
    CTFontRef base = awtFontIvar(nativeFont, "fFallbackBase");
    UniChar chars[2];
    CFIndex count = 1;
    if (codePoint >= 0x10000) {
        const int value = codePoint - 0x10000;
        chars[0] = (UniChar) (0xD800 | (value >> 10));
        chars[1] = (UniChar) (0xDC00 | (value & 0x3FF));
        count = 2;
    } else {
        chars[0] = (UniChar) codePoint;
    }
    CGGlyph glyphs[2] = { 0, 0 };
    if (base != nullptr && CTFontGetGlyphsForCharacters(primary, chars, glyphs, count) && glyphs[0] != 0) {
        *glyph = glyphs[0];
        return typefaceForCTFont(primary);
    }
    CFStringRef string = CFStringCreateWithCharacters(kCFAllocatorDefault, chars, count);
    CTFontRef fallback = string != nullptr
            ? CTFontCreateForString(base != nullptr ? base : primary, string, CFRangeMake(0, count)) : nullptr;
    if (string != nullptr) {
        CFRelease(string);
    }
    if (fallback == nullptr) {
        fallback = (CTFontRef) CFRetain(primary);
    }
    CTFontGetGlyphsForCharacters(fallback, chars, glyphs, count);
    *glyph = glyphs[0];
    sk_sp<SkTypeface> typeface = typefaceForCTFont(fallback);
    CFRelease(fallback);
    return typeface;
}

// CoreGraphics draws the glyph with font smoothing on in the gray of the text's
// luminance, on white for dark text and on black for light text, and the
// coverage is what the paper lost: CoreGraphics' smoothing depends on the text
// luminance only (not on the destination), so this is the platform's look for
// that luminance. Text space is y up: the device matrix (y down) gets its y
// flipped into the text matrix.
// macOS has no platform simulation to copy (measured 2026-10-26, probes synthprobe.swift and
// derivedprobe.swift): CTFontCreateCopyWithSymbolicTraits returns nil for a face the font lacks
// and NSFontManager hands the regular face back. The JDK's own convention is the reference, and
// on macOS every font is a CFont, bundled files included: its fake italic is a 0.2 shear of the
// strike's glyph transform (CFont.createStrike, so the scaler is never asked for one), and there
// is no fake bold. These values are what a route asking the scaler would get, for consistency;
// today none does here. SkiaSyntheticStyleMacTest measures both scalers.
void SKPlatform_SyntheticStyle(float *obliqueShear, float *boldEmFraction)
{
    *obliqueShear = 0.2f;
    *boldEmFraction = 0.0f;
}

int SKPlatform_GlyphPlacementGrid(const SkTypeface *, float, float displayScale, bool lcd)
{
    if (lcd) {
        return 0;
    }
    return displayScale >= 2.5f ? -1 : displayScale >= 1.5f ? -2 : -3;
}

bool SKPlatform_SmoothedGlyphMask(const SkTypeface *typeface, uint16_t glyph, const SkMatrix &glyphToDevice, float displayScale,
                                  int luminance, bool lcd, float strokeWidth, float originX, float originY, int width, int height,
                                  uint8_t *mask)
{
    // CoreGraphics emits no colour sub-pixels and its unsmoothed coverage is
    // Skia's own; the LCD and the linear-coverage requests stay with Skia here
    if (typeface == nullptr || mask == nullptr || width <= 0 || height <= 0 || lcd || luminance < 0) {
        return false;
    }
    CTFontRef base = SkTypeface_GetCTFontRef(typeface);
    if (base == nullptr) {
        return false;
    }
    // the size is in the matrix: a unit-size font, the matrix as the text matrix
    CTFontRef font = CTFontCreateCopyWithAttributes(base, 1.0, nullptr, nullptr);
    if (font == nullptr) {
        return false;
    }
    CGColorSpaceRef colorSpace = CGColorSpaceCreateDeviceRGB();
    std::vector<uint8_t> pixels((size_t) width * height * 4);
    CGContextRef cg = CGBitmapContextCreate(pixels.data(), width, height, 8, (size_t) width * 4, colorSpace,
                                            kCGImageAlphaNoneSkipLast);
    CGColorSpaceRelease(colorSpace);
    if (cg == nullptr) {
        CFRelease(font);
        return false;
    }
    const bool lightText = luminance >= 128;
    const CGFloat paper = lightText ? 0.0 : 1.0, ink = luminance / 255.0;
    CGContextSetGrayFillColor(cg, paper, 1.0);
    CGContextFillRect(cg, CGRectMake(0, 0, width, height));
    CGContextSetShouldAntialias(cg, true);
    CGContextSetAllowsFontSmoothing(cg, true);
    CGContextSetShouldSmoothFonts(cg, true);
    CGContextSetAllowsFontSubpixelPositioning(cg, true);
    CGContextSetShouldSubpixelPositionFonts(cg, true);
    // quantization on, as AppKit draws: the glyph origin arrives on the platform's grid
    // (SKPlatform_GlyphPlacementGrid), and CoreGraphics' quantized rendering differs from
    // its continuous one even at a grid position
    CGContextSetAllowsFontSubpixelQuantization(cg, true);
    CGContextSetShouldSubpixelQuantizeFonts(cg, true);
    CGContextSetGrayFillColor(cg, ink, 1.0);
    // y down like the mask, then the glyph's design space (y up) through the device matrix with y flipped
    CGContextTranslateCTM(cg, 0, height);
    CGContextScaleCTM(cg, 1, -1);
    CGContextSetTextMatrix(cg, CGAffineTransformMake(glyphToDevice.getScaleX(), glyphToDevice.getSkewY(),
                                                     -glyphToDevice.getSkewX(), -glyphToDevice.getScaleY(), 0, 0));
    // glyph positions go through the text matrix (the size!): the origin belongs in the text position
    CGContextSetTextPosition(cg, originX, originY);
    const CGGlyph cgGlyph = glyph;
    const CGPoint zero = CGPointZero;
    CTFontDrawGlyphs(font, &cgGlyph, &zero, 1, cg);
    if (strokeWidth > 0) {
        // synthetic bold: the glyph drawn a second time, shifted right by the emboldening, as WebKit
        // does on macOS — CoreGraphics does not smooth stroked glyphs, so a fill-and-stroke would
        // lose the smoothing and its luminance dependence
        CGContextSetTextPosition(cg, originX + strokeWidth, originY);
        CTFontDrawGlyphs(font, &cgGlyph, &zero, 1, cg);
    }
    CGContextRelease(cg);
    CFRelease(font);
    // coverage = what the paper lost, relative to full ink
    const int paperByte = lightText ? 0 : 255, inkByte = luminance;
    const int range = std::abs(paperByte - inkByte);   // >= 128: the paper is the far one
    for (int y = 0; y < height; y++) {
        const uint8_t *row = pixels.data() + (size_t) y * width * 4;
        uint8_t *dst = mask + (size_t) y * width;
        for (int x = 0; x < width; x++) {
            const int value = row[x * 4];   // the channels are equal: no colour sub-pixels
            const int coverage = std::abs(paperByte - value) * 255 / range;
            dst[x] = (uint8_t) std::min(255, coverage);
        }
    }
    return true;
}
