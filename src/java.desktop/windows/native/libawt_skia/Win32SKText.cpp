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

// Text of the Windows platform half (SKPlatform.h), whatever graphics API Graphite renders
// through: Skia's DirectWrite font host, font fallback, and glyph masks rasterized by
// DirectWrite and Direct2D as the platform's own text stack rasterizes them.

#include "SKPlatform.h"

#include "Trace.h"
#include "include/core/SkFontMgr.h"
#include "include/core/SkMatrix.h"
#include "include/core/SkStream.h"
#include "include/core/SkTypeface.h"
#include "include/ports/SkTypeface_win.h"
#include <d2d1.h>
#include <dwrite_2.h>   // the JDK builds for Windows 8 (_WIN32_WINNT 0x0602): no dwrite_3 interfaces
#include <wrl/client.h>
#include <algorithm>
#include <cmath>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <vector>

// ---- text ------------------------------------------------------------------

// The DirectWrite host: the font manager the platform's own text stack uses,
// over DirectWrite's own factory (no COM initialization needed). The JDK's
// Windows fonts are all file fonts, so this is the only way a typeface is
// made here; the manager is shared and lives as long as the library.
sk_sp<SkFontMgr> SKPlatform_FontManager() {
    static sk_sp<SkFontMgr> manager = SkFontMgr_New_DirectWrite();
    return manager;
}

sk_sp<SkTypeface> SKPlatform_TypefaceFromNativeFont(jlong) {
    return nullptr;
}

sk_sp<SkTypeface> SKPlatform_FallbackTypeface(jlong, int, uint16_t *) {
    return nullptr;   // Windows resolves fallback through composite fonts, with non-negative codes
}

// ---- DirectWrite glyph masks ------------------------------------------------
//
// The platform's own rasterizer for the Skia scaler's glyph images, in the two
// forms the pipes can use. Linear coverage (luminance -1): the alpha texture of
// a glyph run analysis, which DirectWrite produces before any gamma or contrast
// (those belong to its blend, IDWriteGlyphRunAnalysis::GetAlphaBlendParams), so
// the pipes apply their own gamma to it as they do to FreeType's masks. The
// native look (luminance 0..255): the glyph drawn by Direct2D with the system's
// rendering parameters, text of that luminance on the opposite paper, and the
// coverage recovered from what the paper lost. DirectWrite's weight depends on
// the text luminance alone (measured on this project: equal for black text on
// white and on mid-gray, and so on), so the mask is exact on any background.
// Rendering mode and pixel geometry are DirectWrite's recommendation for the
// size and the system parameters, i.e. vertical hinting at text sizes, which
// is what snaps x-heights the way Windows 11 text has them.
//
// The typeface's font face comes from its font data through an in-memory
// loader: the Skia host keeps its own face private, and this way a font the JDK
// created from a file or stream at run time renders exactly like an installed
// one, which is what the DirectWrite font model is designed for (no registry).

namespace {

using Microsoft::WRL::ComPtr;

// A font file loader over the typefaces' font data (DirectWrite 1, so it builds
// against the JDK's Windows 8 headers; IDWriteInMemoryFontFileLoader needs
// Windows 10). The key of a font file reference is the typeface's id.
struct MemoryFontStream : IDWriteFontFileStream {
    std::shared_ptr<std::vector<uint8_t>> data;
    ULONG refs = 1;
    explicit MemoryFontStream(std::shared_ptr<std::vector<uint8_t>> d) : data(std::move(d)) {}
    HRESULT __stdcall QueryInterface(REFIID riid, void **out) override {
        if (riid == __uuidof(IUnknown) || riid == __uuidof(IDWriteFontFileStream)) { *out = this; AddRef(); return S_OK; }
        *out = nullptr; return E_NOINTERFACE;
    }
    ULONG __stdcall AddRef() override { return ++refs; }
    ULONG __stdcall Release() override { ULONG n = --refs; if (n == 0) delete this; return n; }
    HRESULT __stdcall ReadFileFragment(const void **start, UINT64 offset, UINT64 size, void **context) override {
        if (offset + size > data->size()) { *start = nullptr; *context = nullptr; return E_INVALIDARG; }
        *start = data->data() + offset; *context = nullptr; return S_OK;
    }
    void __stdcall ReleaseFileFragment(void *) override {}
    HRESULT __stdcall GetFileSize(UINT64 *size) override { *size = data->size(); return S_OK; }
    HRESULT __stdcall GetLastWriteTime(UINT64 *time) override { *time = 0; return S_OK; }
};

struct MemoryFontLoader : IDWriteFontFileLoader {
    std::unordered_map<SkTypefaceID, std::shared_ptr<std::vector<uint8_t>>> fonts;
    ULONG refs = 1;
    HRESULT __stdcall QueryInterface(REFIID riid, void **out) override {
        if (riid == __uuidof(IUnknown) || riid == __uuidof(IDWriteFontFileLoader)) { *out = this; AddRef(); return S_OK; }
        *out = nullptr; return E_NOINTERFACE;
    }
    ULONG __stdcall AddRef() override { return ++refs; }
    ULONG __stdcall Release() override { return --refs; }   // owned by DWriteText for the process lifetime
    HRESULT __stdcall CreateStreamFromKey(const void *key, UINT32 keySize, IDWriteFontFileStream **stream) override {
        if (key == nullptr || keySize != sizeof(SkTypefaceID)) { return E_INVALIDARG; }
        auto found = fonts.find(*(const SkTypefaceID *) key);
        if (found == fonts.end()) { return E_INVALIDARG; }
        *stream = new MemoryFontStream(found->second);
        return S_OK;
    }
};

struct DWriteText {
    std::mutex lock;
    ComPtr<IDWriteFactory2> factory;
    MemoryFontLoader loader;
    ComPtr<IDWriteRenderingParams> params;   // the primary monitor's system parameters
    ComPtr<ID2D1Factory> d2d;
    ComPtr<ID2D1DCRenderTarget> target;
    std::unordered_map<SkTypefaceID, ComPtr<IDWriteFontFace>> faces;
    bool initialized = false, failed = false;

    bool init() {
        if (initialized) {
            return !failed;
        }
        initialized = true;
        failed = true;
        if (FAILED(DWriteCreateFactory(DWRITE_FACTORY_TYPE_SHARED, __uuidof(IDWriteFactory2), (IUnknown **) factory.GetAddressOf()))
            || FAILED(factory->RegisterFontFileLoader(&loader))
            || FAILED(factory->CreateRenderingParams(&params))
            || FAILED(D2D1CreateFactory(D2D1_FACTORY_TYPE_MULTI_THREADED, d2d.GetAddressOf()))) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "SKPlatform: DirectWrite is unavailable for glyph masks");
            return false;
        }
        const D2D1_RENDER_TARGET_PROPERTIES properties = D2D1::RenderTargetProperties(
            D2D1_RENDER_TARGET_TYPE_SOFTWARE, D2D1::PixelFormat(DXGI_FORMAT_B8G8R8A8_UNORM, D2D1_ALPHA_MODE_IGNORE), 96, 96);
        if (FAILED(d2d->CreateDCRenderTarget(&properties, &target))) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "SKPlatform: Direct2D is unavailable for glyph masks");
            return false;
        }
        target->SetTextRenderingParams(params.Get());
        failed = false;
        return true;
    }

    // The typeface's face, made once from its font data.
    ComPtr<IDWriteFontFace> faceFor(const SkTypeface *typeface) {
        auto found = faces.find(typeface->uniqueID());
        if (found != faces.end()) {
            return found->second;
        }
        ComPtr<IDWriteFontFace> face;
        int index = 0;
        std::unique_ptr<SkStreamAsset> stream = typeface->openStream(&index);
        if (stream && stream->getLength() > 0) {
            auto data = std::make_shared<std::vector<uint8_t>>(stream->getLength());
            if (stream->read(data->data(), data->size()) == data->size()) {
                const SkTypefaceID key = typeface->uniqueID();
                loader.fonts[key] = data;
                ComPtr<IDWriteFontFile> file;
                BOOL supported = FALSE;
                DWRITE_FONT_FILE_TYPE fileType;
                DWRITE_FONT_FACE_TYPE faceType = DWRITE_FONT_FACE_TYPE_UNKNOWN;
                UINT32 faceCount = 0;
                if (SUCCEEDED(factory->CreateCustomFontFileReference(&key, sizeof key, &loader, &file))
                    && SUCCEEDED(file->Analyze(&supported, &fileType, &faceType, &faceCount)) && supported) {
                    IDWriteFontFile *files[] = {file.Get()};
                    if (FAILED(factory->CreateFontFace(faceType, 1, files, (UINT32) index, DWRITE_FONT_SIMULATIONS_NONE, &face))) {
                        face.Reset();
                    }
                }
            }
        }
        if (!face) {
            J2dRlsTraceLn(J2D_TRACE_WARNING, "SKPlatform: DirectWrite cannot load the font data of typeface %u", typeface->uniqueID());
        }
        faces[typeface->uniqueID()] = face;   // a null entry remembers the failure
        return face;
    }
};

DWriteText &dwriteText() {
    static DWriteText text;
    return text;
}

// The run and its transform: a unit-em glyph through glyphToDevice, split into
// the pixel size (for DirectWrite's rendering-mode and hinting decisions) and
// the remaining shape; the mask origin goes into the transform's translation.
// With pixelsPerDip above 1 the em size and the origin are in DIPs of a target
// at that DPI (Direct2D's DPI for the smoothed look on a scaled display); the
// pixel size stays the device one.
struct GlyphRunSetup {
    UINT16 index;
    FLOAT advance = 0;
    DWRITE_GLYPH_OFFSET offset = {0, 0};
    DWRITE_GLYPH_RUN run = {};
    DWRITE_MATRIX matrix = {};
    float pixelSize = 0;

    GlyphRunSetup(IDWriteFontFace *face, uint16_t glyph, const SkMatrix &glyphToDevice, float originX, float originY,
                  float pixelsPerDip = 1) : index(glyph) {
        pixelSize = std::sqrt(std::fabs(glyphToDevice.getScaleX() * glyphToDevice.getScaleY()
                                        - glyphToDevice.getSkewX() * glyphToDevice.getSkewY()));
        if (pixelSize <= 0) {
            pixelSize = 1;
        }
        if (!(pixelsPerDip > 0)) {
            pixelsPerDip = 1;
        }
        run.fontFace = face;
        run.fontEmSize = pixelSize / pixelsPerDip;
        run.glyphCount = 1;
        run.glyphIndices = &index;
        run.glyphAdvances = &advance;
        run.glyphOffsets = &offset;
        run.isSideways = FALSE;
        run.bidiLevel = 0;
        matrix.m11 = glyphToDevice.getScaleX() / pixelSize;
        matrix.m12 = glyphToDevice.getSkewY() / pixelSize;
        matrix.m21 = glyphToDevice.getSkewX() / pixelSize;
        matrix.m22 = glyphToDevice.getScaleY() / pixelSize;
        matrix.dx = originX / pixelsPerDip;
        matrix.dy = originY / pixelsPerDip;
    }
};

DWRITE_RENDERING_MODE recommendedMode(DWriteText &text, IDWriteFontFace *face, float pixelSize) {
    DWRITE_RENDERING_MODE mode = DWRITE_RENDERING_MODE_NATURAL;
    if (FAILED(face->GetRecommendedRenderingMode(pixelSize, 1.0f, DWRITE_MEASURING_MODE_NATURAL, text.params.Get(), &mode))
        || mode == DWRITE_RENDERING_MODE_DEFAULT || mode == DWRITE_RENDERING_MODE_OUTLINE) {
        mode = DWRITE_RENDERING_MODE_NATURAL;
    }
    return mode;
}

// Linear coverage: the analysis' alpha texture, grayscale or ClearType 3x1.
bool coverageMask(DWriteText &text, IDWriteFontFace *face, uint16_t glyph, const SkMatrix &glyphToDevice, bool lcd,
                  float strokeWidth, float originX, float originY, int width, int height, uint8_t *mask)
{
    const int channels = lcd ? 3 : 1;
    memset(mask, 0, (size_t) width * height * channels);
    const int passes = strokeWidth > 0 ? 2 : 1;   // synthetic bold: the glyph again, shifted by the emboldening
    for (int pass = 0; pass < passes; pass++) {
        GlyphRunSetup setup(face, glyph, glyphToDevice, originX + (pass == 1 ? strokeWidth : 0), originY);
        ComPtr<IDWriteGlyphRunAnalysis> analysis;
        if (FAILED(text.factory->CreateGlyphRunAnalysis(&setup.run, &setup.matrix, recommendedMode(text, face, setup.pixelSize),
                                                       DWRITE_MEASURING_MODE_NATURAL, DWRITE_GRID_FIT_MODE_DEFAULT,
                                                       lcd ? DWRITE_TEXT_ANTIALIAS_MODE_CLEARTYPE : DWRITE_TEXT_ANTIALIAS_MODE_GRAYSCALE,
                                                       0, 0, &analysis))) {
            return false;
        }
        const DWRITE_TEXTURE_TYPE type = lcd ? DWRITE_TEXTURE_CLEARTYPE_3x1 : DWRITE_TEXTURE_ALIASED_1x1;
        RECT bounds = {};
        if (FAILED(analysis->GetAlphaTextureBounds(type, &bounds)) || bounds.right <= bounds.left || bounds.bottom <= bounds.top) {
            continue;   // nothing inked (a space)
        }
        const int tw = bounds.right - bounds.left, th = bounds.bottom - bounds.top;
        std::vector<uint8_t> texture((size_t) tw * th * channels);
        if (FAILED(analysis->CreateAlphaTexture(type, &bounds, texture.data(), (UINT32) texture.size()))) {
            return false;
        }
        for (int y = 0; y < th; y++) {
            const int my = bounds.top + y;
            if (my < 0 || my >= height) {
                continue;
            }
            for (int x = 0; x < tw; x++) {
                const int mx = bounds.left + x;
                if (mx < 0 || mx >= width) {
                    continue;
                }
                for (int c = 0; c < channels; c++) {
                    uint8_t &dst = mask[((size_t) my * width + mx) * channels + c];
                    const uint8_t src = texture[((size_t) y * tw + x) * channels + c];
                    dst = (uint8_t) std::max<int>(dst, src);   // the union of the two bold passes
                }
            }
        }
    }
    return true;
}

// The native look: Direct2D with the system's parameters, text of the given
// luminance on the opposite paper, coverage = what the paper lost. The target
// runs at the display's DPI and the glyph is drawn in DIPs there, as every
// Windows application draws its text: from a display scale of 1.8 on, Direct2D
// samples natural-symmetric coverage on a 2 x 2 grid per device pixel instead
// of 4 x 4, so a 13 DIP glyph at 192 dpi has 4 coverage steps per pixel where a
// 26 px glyph at 96 dpi has 16 (dwref --dpi, --scale: same total ink, same
// blend parameters, the natural mode and DirectWrite's own analysis textures
// are DPI-invariant).
bool smoothedMask(DWriteText &text, IDWriteFontFace *face, uint16_t glyph, const SkMatrix &glyphToDevice, float displayScale,
                  int luminance, bool lcd, float strokeWidth, float originX, float originY, int width, int height, uint8_t *mask)
{
    if (!(displayScale > 0)) {
        displayScale = 1;
    }
    // the ink per channel: a gray of the luminance, or the colour class's colour (0x1RRGGBB) for
    // an lcd mask; the paper is the opposite extreme per channel, so every channel's coverage
    // is recovered from what that channel of the paper lost
    int ink[3], paper[3], range[3];
    const bool colour = luminance >= 0x1000000;
    for (int c = 0; c < 3; c++) {
        ink[c] = colour ? (luminance >> (16 - 8 * c)) & 255 : luminance;
    }
    if (colour && !lcd) {
        ink[0] = ink[1] = ink[2] = (ink[0] * 299 + ink[1] * 587 + ink[2] * 114) / 1000;   // a grayscale mask takes the luminance
    }
    for (int c = 0; c < 3; c++) {
        paper[c] = ink[c] >= 128 ? 0 : 255;
        range[c] = std::abs(paper[c] - ink[c]);
        if (range[c] == 0) {
            return false;
        }
    }
    BITMAPINFO info = {};
    info.bmiHeader.biSize = sizeof(info.bmiHeader);
    info.bmiHeader.biWidth = width;
    info.bmiHeader.biHeight = -height;
    info.bmiHeader.biPlanes = 1;
    info.bmiHeader.biBitCount = 32;
    info.bmiHeader.biCompression = BI_RGB;
    void *bits = nullptr;
    HDC dc = CreateCompatibleDC(nullptr);
    HBITMAP dib = CreateDIBSection(dc, &info, DIB_RGB_COLORS, &bits, nullptr, 0);
    if (dib == nullptr || bits == nullptr) {
        DeleteDC(dc);
        return false;
    }
    HGDIOBJ previous = SelectObject(dc, dib);
    const RECT rect = {0, 0, width, height};
    bool ok = false;
    text.target->SetDpi(96 * displayScale, 96 * displayScale);
    if (SUCCEEDED(text.target->BindDC(dc, &rect))) {
        ComPtr<ID2D1SolidColorBrush> brush;
        if (SUCCEEDED(text.target->CreateSolidColorBrush(D2D1::ColorF(ink[0] / 255.0f, ink[1] / 255.0f, ink[2] / 255.0f), &brush))) {
            text.target->SetTextAntialiasMode(lcd ? D2D1_TEXT_ANTIALIAS_MODE_CLEARTYPE : D2D1_TEXT_ANTIALIAS_MODE_GRAYSCALE);
            text.target->BeginDraw();
            text.target->SetTransform(D2D1::Matrix3x2F::Identity());
            text.target->Clear(D2D1::ColorF(paper[0] / 255.0f, paper[1] / 255.0f, paper[2] / 255.0f));
            const int passes = strokeWidth > 0 ? 2 : 1;
            for (int pass = 0; pass < passes; pass++) {
                GlyphRunSetup setup(face, glyph, glyphToDevice, originX + (pass == 1 ? strokeWidth : 0), originY, displayScale);
                text.target->SetTransform(D2D1::Matrix3x2F(setup.matrix.m11, setup.matrix.m12, setup.matrix.m21, setup.matrix.m22,
                                                           setup.matrix.dx, setup.matrix.dy));
                text.target->DrawGlyphRun(D2D1::Point2F(0, 0), &setup.run, brush.Get(), DWRITE_MEASURING_MODE_NATURAL);
            }
            ok = SUCCEEDED(text.target->EndDraw());
        }
    }
    GdiFlush();
    if (ok) {
        const int channels = lcd ? 3 : 1;
        for (int y = 0; y < height; y++) {
            const uint8_t *row = (const uint8_t *) bits + (size_t) y * width * 4;   // B, G, R, x
            uint8_t *dst = mask + (size_t) y * width * channels;
            for (int x = 0; x < width; x++) {
                if (lcd) {
                    for (int c = 0; c < 3; c++) {
                        const int value = row[x * 4 + (2 - c)];   // R, G, B order in the mask
                        dst[x * 3 + c] = (uint8_t) std::min(255, std::abs(value - paper[c]) * 255 / range[c]);
                    }
                } else {
                    dst[x] = (uint8_t) std::min(255, std::abs((int) row[x * 4 + 1] - paper[1]) * 255 / range[1]);
                }
            }
        }
    }
    SelectObject(dc, previous);
    DeleteObject(dib);
    DeleteDC(dc);
    return ok;
}

} // namespace

// DirectWrite's font simulations (DWRITE_FONT_SIMULATIONS_OBLIQUE / _BOLD),
// read from the simulated faces' outlines with dwref --simulations: the oblique
// shears every point by 0.3399 of its height (Inter, JetBrains Mono alike), the
// bold moves the right side of every contour out by 20/1000 em and adds the
// same 0.02 em to every glyph's advance, spaces included.
void SKPlatform_SyntheticStyle(float *obliqueShear, float *boldEmFraction)
{
    *obliqueShear = 0.34f;
    *boldEmFraction = 0.02f;
}

#ifndef SPI_GETFONTSMOOTHINGORIENTATION
#define SPI_GETFONTSMOOTHINGORIENTATION 0x2012
#endif
#ifndef FE_FONTSMOOTHINGORIENTATIONBGR
#define FE_FONTSMOOTHINGORIENTATIONBGR 0x0000
#define FE_FONTSMOOTHINGORIENTATIONRGB 0x0001
#endif

// The user's text preferences (SKPlatform_TextDefaults): kNormal lets DirectWrite decide per
// font and size whether and how to grid-fit (the gasp table, GetRecommendedRenderingMode);
// darkening is ClearType's enhanced contrast, DirectWrite's own business under its rendering
// parameters (always on here; the desktop hints carry SPI_GETFONTSMOOTHING); the sub-pixel
// order is the ClearType tuner's orientation (SPI_GETFONTSMOOTHINGORIENTATION), as the JDK's
// lcdglyph.c and the desktop hints read it, RGB when the query fails.
const SKTextDefaults &SKPlatform_TextDefaults()
{
    static const SKTextDefaults defaults = [] {
        UINT orientation = FE_FONTSMOOTHINGORIENTATIONRGB;
        const bool bgr = SystemParametersInfo(SPI_GETFONTSMOOTHINGORIENTATION, 0, &orientation, 0)
                         && orientation == FE_FONTSMOOTHINGORIENTATIONBGR;
        return SKTextDefaults{SkFontHinting::kNormal, true, bgr, false};
    }();
    return defaults;
}

// Skia's DirectWrite host asks DirectWrite under kNormal and forces grid fitting off under
// kNone or kSlight; the request is taken as given, -1 being the platform's kNormal.
SkFontHinting SKPlatform_FontHinting(int requested, bool darkening)
{
    return requested < 0 ? SkFontHinting::kNormal : (SkFontHinting) requested;
}

// The display scale from which DirectWrite's symmetric mode halves its placement
// and coverage sampling (measured between 1.792 and 1.8125; 173 dpi).
const float HIGH_DPI_SCALE = 1.8f;

// Measured with dwref --grid (one glyph at 64 offsets across a pixel, through the
// glyph run analysis and Direct2D alike): the natural-symmetric mode positions
// glyphs on quarter pixels, the natural mode on eighths, both to the nearest;
// aliased and GDI-compatible modes on whole pixels. The grid is per device
// pixel at every display scale, but the symmetric mode has two regimes by the
// display scale (dwref --scale, Direct2D targets from 96 to 384 dpi): quarter
// pixels up to a scale of 1.79, half pixels from 1.81 on (and 2 x 2 coverage
// samples per pixel instead of 4 x 4), whatever the pixel size; the natural
// mode keeps its eighths at every scale. ClearType (dwref --scale-lcd) positions
// on sixths of a pixel - half a colour sub-pixel - in both modes at every scale.
int SKPlatform_GlyphPlacementGrid(const SkTypeface *typeface, float pixelSize, float displayScale, bool lcd)
{
    if (typeface == nullptr || pixelSize <= 0) {
        return 0;
    }
    if (!(displayScale > 0)) {
        displayScale = 1;
    }
    DWriteText &text = dwriteText();
    std::lock_guard<std::mutex> guard(text.lock);
    if (!text.init()) {
        return 0;
    }
    ComPtr<IDWriteFontFace> face = text.faceFor(typeface);
    if (!face) {
        return 0;
    }
    switch (recommendedMode(text, face.Get(), pixelSize)) {
        case DWRITE_RENDERING_MODE_NATURAL: return lcd ? 6 : 8;
        case DWRITE_RENDERING_MODE_NATURAL_SYMMETRIC: return lcd ? 6 : displayScale >= HIGH_DPI_SCALE ? 2 : 4;
        default: return 1;
    }
}

bool SKPlatform_SmoothedGlyphMask(const SkTypeface *typeface, uint16_t glyph, const SkMatrix &glyphToDevice, float displayScale,
                                  int luminance, bool lcd, bool darkening, float strokeWidth, float originX, float originY, int width, int height,
                                  uint8_t *mask)
{
    if (typeface == nullptr || mask == nullptr || width <= 0 || height <= 0) {
        return false;
    }
    DWriteText &text = dwriteText();
    std::lock_guard<std::mutex> guard(text.lock);
    if (!text.init()) {
        return false;
    }
    ComPtr<IDWriteFontFace> face = text.faceFor(typeface);
    if (!face) {
        return false;
    }
    return luminance < 0 ? coverageMask(text, face.Get(), glyph, glyphToDevice, lcd, strokeWidth, originX, originY, width, height, mask)
                         : smoothedMask(text, face.Get(), glyph, glyphToDevice, displayScale, luminance, lcd, strokeWidth, originX, originY,
                                        width, height, mask);
}
