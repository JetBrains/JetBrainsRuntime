// The Windows platform's own text rendering as the reference for the text sheets: the same rows and
// sheets as TextReference.java and ctref.swift, drawn by DirectWrite (through Direct2D) and by GDI
// into 32-bit bitmaps, with the flags that map to Java's hints:
//   aa          gray = grayscale antialiasing, lcd = ClearType with the system's parameters, aliased = no AA
//   positioning fractional = DirectWrite's natural layout with sub-pixel positions (GDI: its own integer
//               advances, GDI has no fractional positioning); whole-pixel = glyphs placed like Java's
//               whole-pixel mode: every natural advance rounded (in device pixels for the file fonts, as
//               the FreeType strike does; in points for the system fonts, as the platform strike does)
//               and summed, drawn in GDI-classic measuring mode
//   scale       1x, 2x (render target DPI; the cell is W*scale x H*scale px)
//   paper       on-white: ink (30, 30, 40) on white; on-dark: (188, 190, 196) on (30, 31, 34)
// Rows: family x {regular, italic, bold, bold italic, regular underlined} for Inter 13 and JetBrains
// Mono 13 (font files from the JDK image) and Segoe UI 13, Consolas 12 (system faces). No kerning,
// no ligatures: Java's drawString applies neither.
// Usage: dwref <out-dir> <rows.txt> <fonts-dir>        writes <out-dir>/{directwrite,gdi}/<aa>/<positioning>/<scale>/<paper>.png
//        dwref --probe                                  the luminance probe (does the weight of the text depend
//                                                       on the text colour, on the background, or on neither?)
#define NOMINMAX
#define _CRT_SECURE_NO_WARNINGS
#include <windows.h>
#include <d2d1_1.h>
#include <dwrite_3.h>
#include <wincodec.h>
#include <wrl/client.h>
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>
#include <climits>
#pragma comment(lib, "d2d1.lib")
#pragma comment(lib, "dwrite.lib")
#pragma comment(lib, "windowscodecs.lib")
#pragma comment(lib, "gdi32.lib")
#pragma comment(lib, "user32.lib")
#pragma comment(lib, "ole32.lib")
using Microsoft::WRL::ComPtr;

static const int W = 460, H = 28, BASE = 19, X = 4;
static const wchar_t* TEXT = L"Quick fox jumps 1234567890 {}[]();:,. ∑∂Δ → ≠ ≤ ≥ äöü";
static const char* STYLES[] = {"regular", "italic", "bold", "bold italic", "regular underlined"};
static bool gNaturalWholePixel = false;
static bool gColours = false;
static bool gSystemFromFiles = false;   // --files: the system families from their files instead of the system collection   // --natural: whole-pixel rows in natural measuring mode instead of GDI classic

static void fail(const char* what, HRESULT hr) { fprintf(stderr, "%s failed: 0x%08lx\n", what, hr); exit(1); }
#define CHECK(x) do { HRESULT _hr = (x); if (FAILED(_hr)) fail(#x, _hr); } while (0)

struct Face {                      // one row's font
    std::wstring family;           // family name for the text format (DirectWrite) and the LOGFONT (GDI)
    ComPtr<IDWriteFontCollection> collection;   // the file's own collection, or null for the system collection
    DWRITE_FONT_WEIGHT weight; DWRITE_FONT_STYLE style;
    float size;                    // in points (DIPs at 96 dpi)
    bool fileFont;                 // Java rounds its whole-pixel advances in device pixels
    std::wstring file;             // for GDI's AddFontResourceEx
};
struct Row { std::string label; Face face; bool underlined; };

static ComPtr<IDWriteFactory3> dw;
static ComPtr<ID2D1Factory> d2d;
static ComPtr<IWICImagingFactory> wic;

// A collection with exactly the one font of a file: the family name and the face's weight and style
// come from the font itself, so no guess about "Inter SemiBold" versus "Inter" is needed.
static Face fileFace(const std::wstring& path, float size) {
    ComPtr<IDWriteFontFile> file; CHECK(dw->CreateFontFileReference(path.c_str(), nullptr, &file));
    ComPtr<IDWriteFactory5> dw5; CHECK(dw.As(&dw5));   // AddFontFile(IDWriteFontFile*) is on the builder of factory 5
    ComPtr<IDWriteFontSetBuilder1> builder; CHECK(dw5->CreateFontSetBuilder(&builder));
    CHECK(builder->AddFontFile(file.Get()));
    ComPtr<IDWriteFontSet> set; CHECK(builder->CreateFontSet(&set));
    ComPtr<IDWriteFontCollection1> coll1; CHECK(dw->CreateFontCollectionFromFontSet(set.Get(), &coll1));
    ComPtr<IDWriteFontFamily> family; CHECK(coll1->GetFontFamily(0, &family));
    ComPtr<IDWriteFont> font; CHECK(family->GetFont(0, &font));
    ComPtr<IDWriteLocalizedStrings> names; CHECK(family->GetFamilyNames(&names));
    wchar_t name[256]; CHECK(names->GetString(0, name, 256));
    Face f; f.family = name; f.collection = coll1; f.weight = font->GetWeight(); f.style = font->GetStyle(); f.size = size; f.fileFont = true; f.file = path;
    return f;
}
static Face systemFace(const wchar_t* family, DWRITE_FONT_WEIGHT weight, DWRITE_FONT_STYLE style, float size) {
    Face f; f.family = family; f.weight = weight; f.style = style; f.size = size; f.fileFont = false; return f;
}

// Draws the glyph runs of a layout with Java's whole-pixel advances (or passes them through), so the
// sheets stay comparable to Java's layout; underlines as rectangles like Java's Decoration.
struct Renderer : IDWriteTextRenderer {
    ID2D1RenderTarget* rt; ID2D1Brush* brush; float scale; bool fileFont; bool wholePixel; ULONG refs = 1;
    HRESULT __stdcall QueryInterface(REFIID riid, void** out) override {
        if (riid == __uuidof(IUnknown) || riid == __uuidof(IDWritePixelSnapping) || riid == __uuidof(IDWriteTextRenderer)) { *out = this; AddRef(); return S_OK; }
        *out = nullptr; return E_NOINTERFACE;
    }
    ULONG __stdcall AddRef() override { return ++refs; }
    ULONG __stdcall Release() override { return --refs; }
    HRESULT __stdcall IsPixelSnappingDisabled(void*, BOOL* disabled) override { *disabled = FALSE; return S_OK; }
    HRESULT __stdcall GetCurrentTransform(void*, DWRITE_MATRIX* m) override { rt->GetTransform(reinterpret_cast<D2D1_MATRIX_3X2_F*>(m)); return S_OK; }
    HRESULT __stdcall GetPixelsPerDip(void*, FLOAT* ppd) override { float dx, dy; rt->GetDpi(&dx, &dy); *ppd = dx / 96.0f; return S_OK; }
    HRESULT __stdcall DrawGlyphRun(void*, FLOAT x, FLOAT y, DWRITE_MEASURING_MODE, DWRITE_GLYPH_RUN const* run, DWRITE_GLYPH_RUN_DESCRIPTION const*, IUnknown*) override {
        if (!wholePixel) { rt->DrawGlyphRun(D2D1::Point2F(x, y), run, brush, DWRITE_MEASURING_MODE_NATURAL); return S_OK; }
        std::vector<FLOAT> adv(run->glyphCount);
        for (UINT32 i = 0; i < run->glyphCount; i++) {
            float a = run->glyphAdvances[i];
            adv[i] = fileFont ? std::round(a * scale) / scale : std::round(a);
        }
        DWRITE_GLYPH_RUN r = *run; r.glyphAdvances = adv.data(); r.glyphOffsets = nullptr;
        // GDI-classic measuring (full hinting, the hand-over's spec) or, with --natural, the natural
        // mode the DirectWrite mask routes use, so whole-pixel sheets compare like for like
        rt->DrawGlyphRun(D2D1::Point2F(x, y), &r, brush, gNaturalWholePixel ? DWRITE_MEASURING_MODE_NATURAL : DWRITE_MEASURING_MODE_GDI_CLASSIC);
        return S_OK;
    }
    HRESULT __stdcall DrawUnderline(void*, FLOAT x, FLOAT y, DWRITE_UNDERLINE const* u, IUnknown*) override {
        rt->FillRectangle(D2D1::RectF(x, y + u->offset, x + u->width, y + u->offset + u->thickness), brush); return S_OK;
    }
    HRESULT __stdcall DrawStrikethrough(void*, FLOAT, FLOAT, DWRITE_STRIKETHROUGH const*, IUnknown*) override { return S_OK; }
    HRESULT __stdcall DrawInlineObject(void*, FLOAT, FLOAT, IDWriteInlineObject*, BOOL, BOOL, IUnknown*) override { return S_OK; }
};

// The whole-pixel advance sum differs from the natural one, so the underline of a whole-pixel row is
// drawn after the glyphs over the rounded width; the layout's own underline is used for fractional rows.
static float wholePixelWidth(IDWriteTextLayout* layout, float scale, bool fileFont) {
    struct Measure : Renderer { float total = 0;
        HRESULT __stdcall DrawGlyphRun(void*, FLOAT, FLOAT, DWRITE_MEASURING_MODE, DWRITE_GLYPH_RUN const* run, DWRITE_GLYPH_RUN_DESCRIPTION const*, IUnknown*) override {
            for (UINT32 i = 0; i < run->glyphCount; i++) { float a = run->glyphAdvances[i]; total += fileFont ? std::round(a * scale) / scale : std::round(a); }
            return S_OK; }
        HRESULT __stdcall DrawUnderline(void*, FLOAT, FLOAT, DWRITE_UNDERLINE const*, IUnknown*) override { return S_OK; }
        HRESULT __stdcall GetCurrentTransform(void*, DWRITE_MATRIX* m) override { *m = {1, 0, 0, 1, 0, 0}; return S_OK; }   // no render target here
        HRESULT __stdcall GetPixelsPerDip(void*, FLOAT* ppd) override { *ppd = scale; return S_OK; }
    } m; m.scale = scale; m.fileFont = fileFont; m.wholePixel = true; m.rt = nullptr; m.brush = nullptr;
    layout->Draw(nullptr, &m, 0, 0);
    return m.total;
}

static ComPtr<IDWriteTextLayout> layoutFor(const Face& f, bool underline) {
    ComPtr<IDWriteTextFormat> format;
    CHECK(dw->CreateTextFormat(f.family.c_str(), f.collection.Get(), f.weight, f.style, DWRITE_FONT_STRETCH_NORMAL, f.size, L"en-us", &format));
    ComPtr<IDWriteTextLayout> layout;
    CHECK(dw->CreateTextLayout(TEXT, (UINT32) wcslen(TEXT), format.Get(), 4096, 64, &layout));
    ComPtr<IDWriteTypography> typo; CHECK(dw->CreateTypography(&typo));
    for (DWRITE_FONT_FEATURE_TAG tag : {DWRITE_FONT_FEATURE_TAG_KERNING, DWRITE_FONT_FEATURE_TAG_STANDARD_LIGATURES, DWRITE_FONT_FEATURE_TAG_CONTEXTUAL_LIGATURES, DWRITE_FONT_FEATURE_TAG_CONTEXTUAL_ALTERNATES}) {
        DWRITE_FONT_FEATURE ft = {tag, 0}; CHECK(typo->AddFontFeature(ft));
    }
    DWRITE_TEXT_RANGE all = {0, (UINT32) wcslen(TEXT)};
    CHECK(layout->SetTypography(typo.Get(), all));
    if (underline) CHECK(layout->SetUnderline(TRUE, all));
    return layout;
}

static void writePng(IWICBitmap* bmp, const std::wstring& path) {
    ComPtr<IWICStream> stream; CHECK(wic->CreateStream(&stream));
    CHECK(stream->InitializeFromFilename(path.c_str(), GENERIC_WRITE));
    ComPtr<IWICBitmapEncoder> enc; CHECK(wic->CreateEncoder(GUID_ContainerFormatPng, nullptr, &enc));
    CHECK(enc->Initialize(stream.Get(), WICBitmapEncoderNoCache));
    ComPtr<IWICBitmapFrameEncode> frame; CHECK(enc->CreateNewFrame(&frame, nullptr));
    CHECK(frame->Initialize(nullptr));
    ComPtr<IWICFormatConverter> conv; CHECK(wic->CreateFormatConverter(&conv));
    CHECK(conv->Initialize(bmp, GUID_WICPixelFormat24bppBGR, WICBitmapDitherTypeNone, nullptr, 0, WICBitmapPaletteTypeCustom));
    CHECK(frame->WriteSource(conv.Get(), nullptr));
    CHECK(frame->Commit()); CHECK(enc->Commit());
}
static void mkdirs(const std::wstring& path) {
    std::wstring p;
    for (size_t i = 0; i <= path.size(); i++) {
        if (i == path.size() || path[i] == L'/' || path[i] == L'\\') { if (!p.empty() && p.back() != L':') CreateDirectoryW(p.c_str(), nullptr); }
        if (i < path.size()) p += path[i];
    }
}
static std::string scaleName(float scale) { char b[16]; snprintf(b, sizeof b, "%g", (double) scale); return b; }
static std::wstring widen(const char* s) { int n = MultiByteToWideChar(CP_UTF8, 0, s, -1, nullptr, 0); std::wstring w(n - 1, 0); MultiByteToWideChar(CP_UTF8, 0, s, -1, &w[0], n); return w; }

// ---- DirectWrite sheets ----------------------------------------------------------------------------

static ComPtr<IWICBitmap> directWriteSheetColour(const std::vector<Row>& rows, const char* aa, bool fractional, float scale, D2D1_COLOR_F ink, D2D1_COLOR_F bg);

static ComPtr<IWICBitmap> directWriteSheet(const std::vector<Row>& rows, const char* aa, bool fractional, float scale, bool dark) {
    // plain black on white and white on black since 2026-10-26 (before: the IDE's editor colours), as on the Mac
    D2D1_COLOR_F ink = dark ? D2D1::ColorF(1, 1, 1) : D2D1::ColorF(0, 0, 0);
    D2D1_COLOR_F bg = dark ? D2D1::ColorF(0, 0, 0) : D2D1::ColorF(1, 1, 1);
    return directWriteSheetColour(rows, aa, fractional, scale, ink, bg);
}

// The sheet in any ink on any paper (the ClearType-in-colour comparison).
static ComPtr<IWICBitmap> directWriteSheetColour(const std::vector<Row>& rows, const char* aa, bool fractional, float scale, D2D1_COLOR_F ink, D2D1_COLOR_F bg) {
    int cellW = (int) std::lround(W * scale), cellH = (int) std::lround(H * scale), sheetW = cellW, sheetH = cellH * (int) rows.size();
    ComPtr<IWICBitmap> bmp; CHECK(wic->CreateBitmap(sheetW, sheetH, GUID_WICPixelFormat32bppBGR, WICBitmapCacheOnDemand, &bmp));
    D2D1_RENDER_TARGET_PROPERTIES props = D2D1::RenderTargetProperties(D2D1_RENDER_TARGET_TYPE_SOFTWARE,
        D2D1::PixelFormat(DXGI_FORMAT_B8G8R8A8_UNORM, D2D1_ALPHA_MODE_IGNORE), 96.0f * scale, 96.0f * scale);
    ComPtr<ID2D1RenderTarget> rt; CHECK(d2d->CreateWicBitmapRenderTarget(bmp.Get(), props, &rt));
    // the system's ClearType and gamma parameters for the primary monitor, as every application gets them
    ComPtr<IDWriteRenderingParams> params; CHECK(dw->CreateRenderingParams(&params));
    rt->SetTextRenderingParams(params.Get());
    rt->SetTextAntialiasMode(strcmp(aa, "aliased") == 0 ? D2D1_TEXT_ANTIALIAS_MODE_ALIASED : strcmp(aa, "lcd") == 0 ? D2D1_TEXT_ANTIALIAS_MODE_CLEARTYPE : D2D1_TEXT_ANTIALIAS_MODE_GRAYSCALE);
    ComPtr<ID2D1SolidColorBrush> brush; CHECK(rt->CreateSolidColorBrush(ink, &brush));
    rt->BeginDraw();
    rt->Clear(bg);
    for (size_t i = 0; i < rows.size(); i++) {
        const Row& r = rows[i];
        rt->SetTransform(D2D1::Matrix3x2F::Translation(0, (float) (i * H)));   // DIPs: the DPI scales to the cell
        ComPtr<IDWriteTextLayout> layout = layoutFor(r.face, r.underlined && fractional);
        Renderer rend; rend.rt = rt.Get(); rend.brush = brush.Get(); rend.scale = (float) scale; rend.fileFont = r.face.fileFont; rend.wholePixel = !fractional;
        // the layout's origin is the line's top; the baseline sits at BASE: shift by the first line's baseline
        DWRITE_LINE_METRICS lm; UINT32 n = 1; layout->GetLineMetrics(&lm, 1, &n);
        CHECK(layout->Draw(nullptr, &rend, (float) X, (float) BASE - lm.baseline));
        if (r.underlined && !fractional) {   // the font's underline metrics over the rounded width, like Java's Decoration
            ComPtr<IDWriteTextFormat> fmt; CHECK(dw->CreateTextFormat(r.face.family.c_str(), r.face.collection.Get(), r.face.weight, r.face.style, DWRITE_FONT_STRETCH_NORMAL, r.face.size, L"en-us", &fmt));
            ComPtr<IDWriteFontCollection> coll; fmt->GetFontCollection(&coll); if (!coll) dw->GetSystemFontCollection(&coll);
            UINT32 idx; BOOL exists; coll->FindFamilyName(r.face.family.c_str(), &idx, &exists);
            ComPtr<IDWriteFontFamily> fam; coll->GetFontFamily(idx, &fam);
            ComPtr<IDWriteFont> font; fam->GetFirstMatchingFont(r.face.weight, DWRITE_FONT_STRETCH_NORMAL, r.face.style, &font);
            DWRITE_FONT_METRICS fm; font->GetMetrics(&fm);
            float pos = -fm.underlinePosition * r.face.size / fm.designUnitsPerEm, thick = fm.underlineThickness * r.face.size / fm.designUnitsPerEm;
            float width = wholePixelWidth(layout.Get(), (float) scale, r.face.fileFont);
            rt->FillRectangle(D2D1::RectF((float) X, BASE + pos, X + width, BASE + pos + thick), brush.Get());
        }
    }
    CHECK(rt->EndDraw());
    return bmp;
}

// ---- GDI sheets --------------------------------------------------------------------------------------

static HFONT gdiFont(const Face& f, float scale, bool underline, DWORD quality) {
    LOGFONTW lf = {};
    lf.lfHeight = -(LONG) std::lround(f.size * scale);   // Java's pixel size at this scale (13 pt = 13 px at 96 dpi)
    lf.lfWeight = (LONG) f.weight; lf.lfItalic = f.style != DWRITE_FONT_STYLE_NORMAL; lf.lfUnderline = underline;
    lf.lfCharSet = DEFAULT_CHARSET; lf.lfOutPrecision = OUT_TT_ONLY_PRECIS; lf.lfQuality = (BYTE) quality;
    wcsncpy_s(lf.lfFaceName, f.family.c_str(), LF_FACESIZE - 1);
    return CreateFontIndirectW(&lf);
}

static ComPtr<IWICBitmap> gdiSheet(const std::vector<Row>& rows, const char* aa, bool fractional, float scale, bool dark) {
    int cellW = (int) std::lround(W * scale), cellH = (int) std::lround(H * scale), sheetW = cellW, sheetH = cellH * (int) rows.size();
    BITMAPINFO bi = {}; bi.bmiHeader.biSize = sizeof(bi.bmiHeader); bi.bmiHeader.biWidth = sheetW; bi.bmiHeader.biHeight = -sheetH; bi.bmiHeader.biPlanes = 1; bi.bmiHeader.biBitCount = 32; bi.bmiHeader.biCompression = BI_RGB;
    void* bits = nullptr;
    HDC dc = CreateCompatibleDC(nullptr);
    HBITMAP dib = CreateDIBSection(dc, &bi, DIB_RGB_COLORS, &bits, nullptr, 0);
    HGDIOBJ oldBmp = SelectObject(dc, dib);
    COLORREF ink = dark ? RGB(255, 255, 255) : RGB(0, 0, 0), bg = dark ? RGB(0, 0, 0) : RGB(255, 255, 255);
    RECT all = {0, 0, sheetW, sheetH}; HBRUSH bgBrush = CreateSolidBrush(bg); FillRect(dc, &all, bgBrush); DeleteObject(bgBrush);
    DWORD quality = strcmp(aa, "aliased") == 0 ? NONANTIALIASED_QUALITY : strcmp(aa, "lcd") == 0 ? CLEARTYPE_QUALITY : ANTIALIASED_QUALITY;
    SetBkMode(dc, TRANSPARENT); SetTextColor(dc, ink); SetTextAlign(dc, TA_LEFT | TA_BASELINE);
    size_t len = wcslen(TEXT);
    for (size_t i = 0; i < rows.size(); i++) {
        const Row& r = rows[i];
        HFONT font = gdiFont(r.face, scale, r.underlined, quality);
        HGDIOBJ old = SelectObject(dc, font);
        wchar_t face[64]; GetTextFaceW(dc, 64, face);
        if (_wcsicmp(face, r.face.family.c_str()) != 0) fprintf(stderr, "GDI substituted %ls for %ls\n", face, r.face.family.c_str());
        std::vector<INT> dx;
        if (!fractional) {
            // Java's whole-pixel layout: file fonts round the advance at the device size (GDI's integer
            // advances at this size already are that); system fonts round at the point size and scale
            if (r.face.fileFont) {
                dx.resize(len); for (size_t k = 0; k < len; k++) { SIZE sz; GetTextExtentPoint32W(dc, TEXT + k, 1, &sz); dx[k] = sz.cx; }
            } else {
                HFONT base = gdiFont(r.face, 1, false, quality); HGDIOBJ prev = SelectObject(dc, base);
                dx.resize(len); for (size_t k = 0; k < len; k++) { SIZE sz; GetTextExtentPoint32W(dc, TEXT + k, 1, &sz); dx[k] = (int) std::lround(sz.cx * scale); }
                SelectObject(dc, prev); DeleteObject(base);
            }
        }
        ExtTextOutW(dc, (int) std::lround(X * scale), (int) (i * cellH) + (int) std::lround(BASE * scale), 0, nullptr, TEXT, (UINT) len, dx.empty() ? nullptr : dx.data());
        SelectObject(dc, old); DeleteObject(font);
    }
    GdiFlush();
    ComPtr<IWICBitmap> bmp; CHECK(wic->CreateBitmapFromMemory(sheetW, sheetH, GUID_WICPixelFormat32bppBGR, sheetW * 4, sheetW * sheetH * 4, (BYTE*) bits, &bmp));
    SelectObject(dc, oldBmp); DeleteObject(dib); DeleteDC(dc);
    return bmp;
}

// ---- the luminance probe -------------------------------------------------------------------------------

// Sum of |pixel - paper| over a "Hamburgefonts" line in Segoe UI 13 for one renderer and mode.
static long long probeInk(const char* renderer, const char* mode, float fg, float bg, IDWriteRenderingParams* params) {
    const int w = 120, h = 24; int bgByte = (int) std::lround(bg * 255);
    std::vector<BYTE> px(w * h * 4);
    if (strcmp(renderer, "directwrite") == 0) {
        ComPtr<IWICBitmap> bmp; CHECK(wic->CreateBitmap(w, h, GUID_WICPixelFormat32bppBGR, WICBitmapCacheOnDemand, &bmp));
        D2D1_RENDER_TARGET_PROPERTIES props = D2D1::RenderTargetProperties(D2D1_RENDER_TARGET_TYPE_SOFTWARE, D2D1::PixelFormat(DXGI_FORMAT_B8G8R8A8_UNORM, D2D1_ALPHA_MODE_IGNORE), 96, 96);
        ComPtr<ID2D1RenderTarget> rt; CHECK(d2d->CreateWicBitmapRenderTarget(bmp.Get(), props, &rt));
        rt->SetTextRenderingParams(params);
        rt->SetTextAntialiasMode(strcmp(mode, "aliased") == 0 ? D2D1_TEXT_ANTIALIAS_MODE_ALIASED : strcmp(mode, "cleartype") == 0 ? D2D1_TEXT_ANTIALIAS_MODE_CLEARTYPE : D2D1_TEXT_ANTIALIAS_MODE_GRAYSCALE);
        ComPtr<IDWriteTextFormat> format; CHECK(dw->CreateTextFormat(L"Segoe UI", nullptr, DWRITE_FONT_WEIGHT_NORMAL, DWRITE_FONT_STYLE_NORMAL, DWRITE_FONT_STRETCH_NORMAL, 13, L"en-us", &format));
        ComPtr<IDWriteTextLayout> layout; CHECK(dw->CreateTextLayout(L"Hamburgefonts", 13, format.Get(), 200, 24, &layout));
        ComPtr<ID2D1SolidColorBrush> brush; CHECK(rt->CreateSolidColorBrush(D2D1::ColorF(fg, fg, fg), &brush));
        rt->BeginDraw(); rt->Clear(D2D1::ColorF(bg, bg, bg)); rt->DrawTextLayout(D2D1::Point2F(2, 2), layout.Get(), brush.Get()); CHECK(rt->EndDraw());
        CHECK(bmp->CopyPixels(nullptr, w * 4, (UINT) px.size(), px.data()));
    } else {
        BITMAPINFO bi = {}; bi.bmiHeader.biSize = sizeof(bi.bmiHeader); bi.bmiHeader.biWidth = w; bi.bmiHeader.biHeight = -h; bi.bmiHeader.biPlanes = 1; bi.bmiHeader.biBitCount = 32; bi.bmiHeader.biCompression = BI_RGB;
        void* bits = nullptr; HDC dc = CreateCompatibleDC(nullptr); HBITMAP dib = CreateDIBSection(dc, &bi, DIB_RGB_COLORS, &bits, nullptr, 0); HGDIOBJ oldBmp = SelectObject(dc, dib);
        RECT all = {0, 0, w, h}; HBRUSH b = CreateSolidBrush(RGB(bgByte, bgByte, bgByte)); FillRect(dc, &all, b); DeleteObject(b);
        LOGFONTW lf = {}; lf.lfHeight = -13; lf.lfWeight = FW_NORMAL; lf.lfCharSet = DEFAULT_CHARSET; lf.lfOutPrecision = OUT_TT_ONLY_PRECIS;
        lf.lfQuality = (BYTE) (strcmp(mode, "aliased") == 0 ? NONANTIALIASED_QUALITY : strcmp(mode, "cleartype") == 0 ? CLEARTYPE_QUALITY : ANTIALIASED_QUALITY);
        wcscpy_s(lf.lfFaceName, L"Segoe UI"); HFONT f = CreateFontIndirectW(&lf); HGDIOBJ old = SelectObject(dc, f);
        int fgByte = (int) std::lround(fg * 255); SetBkMode(dc, TRANSPARENT); SetTextColor(dc, RGB(fgByte, fgByte, fgByte)); SetTextAlign(dc, TA_LEFT | TA_BASELINE);
        ExtTextOutW(dc, 2, 16, 0, nullptr, L"Hamburgefonts", 13, nullptr); GdiFlush();
        memcpy(px.data(), bits, px.size());
        SelectObject(dc, old); DeleteObject(f); SelectObject(dc, oldBmp); DeleteObject(dib); DeleteDC(dc);
    }
    long long total = 0;
    for (size_t i = 0; i < px.size(); i += 4) total += std::llabs((long long) px[i] - bgByte) + std::llabs((long long) px[i + 1] - bgByte) + std::llabs((long long) px[i + 2] - bgByte);
    return total / 3;
}

static void probe() {
    // the system's parameters, and plain coverage (gamma 1, no enhanced contrast, no ClearType level)
    ComPtr<IDWriteRenderingParams> sys; CHECK(dw->CreateRenderingParams(&sys));
    ComPtr<IDWriteRenderingParams> plain; CHECK(dw->CreateCustomRenderingParams(1.0f, 0.0f, 0.0f, sys->GetPixelGeometry(), DWRITE_RENDERING_MODE_NATURAL, &plain));
    printf("system rendering params: gamma %.2f, enhanced contrast %.2f, cleartype level %.2f, pixel geometry %d, rendering mode %d\n",
           sys->GetGamma(), sys->GetEnhancedContrast(), sys->GetClearTypeLevel(), (int) sys->GetPixelGeometry(), (int) sys->GetRenderingMode());
    struct Case { const char* name; float fg, bg; } cases[] = {
        {"black on white", 0, 1}, {"white on black", 1, 0}, {"black on mid-gray", 0, 0.5f}, {"white on mid-gray", 1, 0.5f},
        {"dark gray (0.2) on light gray (0.8)", 0.2f, 0.8f}, {"light gray (0.8) on dark gray (0.2)", 0.8f, 0.2f}, {"mid-gray on white", 0.5f, 1}, {"mid-gray on black", 0.5f, 0}};
    printf("\nSum |pixel - paper| for 'Hamburgefonts' Segoe UI 13; gain of each mode over DirectWrite's plain coverage (gamma 1, contrast 0, grayscale)\n");
    printf("%-38s %9s | %9s %6s | %9s %6s | %9s %6s | %9s %6s | %9s\n", "", "dw-plain", "dw-gray", "gain", "dw-ctype", "gain", "gdi-gray", "gain", "gdi-ctype", "gain", "gdi-alias");
    for (const Case& c : cases) {
        long long plainInk = probeInk("directwrite", "gray", c.fg, c.bg, plain.Get());
        long long dwGray = probeInk("directwrite", "gray", c.fg, c.bg, sys.Get());
        long long dwCt = probeInk("directwrite", "cleartype", c.fg, c.bg, sys.Get());
        long long gdiGray = probeInk("gdi", "gray", c.fg, c.bg, nullptr);
        long long gdiCt = probeInk("gdi", "cleartype", c.fg, c.bg, nullptr);
        long long gdiAlias = probeInk("gdi", "aliased", c.fg, c.bg, nullptr);
        auto gain = [&](long long v) { return 100.0 * (double) (v - plainInk) / (double) plainInk; };
        printf("%-38s %9lld | %9lld %+5.0f%% | %9lld %+5.0f%% | %9lld %+5.0f%% | %9lld %+5.0f%% | %9lld\n", c.name, plainInk, dwGray, gain(dwGray), dwCt, gain(dwCt), gdiGray, gain(gdiGray), gdiCt, gain(gdiCt), gdiAlias);
    }
    // the same per unit of contrast |fg - bg|, so rows of different contrast compare directly: a renderer whose
    // weight depends on the text colour alone gives equal numbers for equal text colours whatever the paper; one
    // that depends on the polarity gives two groups (dark on light, light on dark); the aliased render is the control
    printf("\nPer unit contrast (ink / |fg - bg|):\n%-38s %9s %9s %9s %9s %9s %9s\n", "", "dw-plain", "dw-gray", "dw-ctype", "gdi-gray", "gdi-ctype", "gdi-alias");
    for (const Case& c : cases) {
        double k = 1.0 / std::fabs(c.fg - c.bg);
        printf("%-38s %9.0f %9.0f %9.0f %9.0f %9.0f %9.0f\n", c.name, k * probeInk("directwrite", "gray", c.fg, c.bg, plain.Get()), k * probeInk("directwrite", "gray", c.fg, c.bg, sys.Get()),
               k * probeInk("directwrite", "cleartype", c.fg, c.bg, sys.Get()), k * probeInk("gdi", "gray", c.fg, c.bg, nullptr), k * probeInk("gdi", "cleartype", c.fg, c.bg, nullptr), k * probeInk("gdi", "aliased", c.fg, c.bg, nullptr));
    }
}

// ---- the placement grid probe ---------------------------------------------------------------------------

// How finely does DirectWrite position a glyph horizontally? One glyph rasterized at 64 fractional
// x offsets across one pixel, through the glyph run analysis (the alpha texture) and through
// Direct2D's DrawGlyphRun (what the sheets use); the number of distinct images per pixel is the grid.
static size_t hashBytes(const std::vector<uint8_t>& v) { size_t h = 1469598103934665603ull; for (uint8_t b : v) { h ^= b; h *= 1099511628211ull; } return h; }

// At 2x the sheets draw at 192 dpi in DIPs: a second variant measures the grid per device pixel
// there (em 13 DIP on a 192-dpi target, offsets stepping through one device pixel = half a DIP).
static void gridProbe2x(const Face& f, const char* label, float size) {
    ComPtr<IDWriteTextFormat> format; CHECK(dw->CreateTextFormat(f.family.c_str(), f.collection.Get(), f.weight, f.style, DWRITE_FONT_STRETCH_NORMAL, size, L"en-us", &format));
    ComPtr<IDWriteFontCollection> coll; format->GetFontCollection(&coll); if (!coll) dw->GetSystemFontCollection(&coll);
    UINT32 idx; BOOL exists; coll->FindFamilyName(f.family.c_str(), &idx, &exists);
    ComPtr<IDWriteFontFamily> fam; coll->GetFontFamily(idx, &fam);
    ComPtr<IDWriteFont> font; fam->GetFirstMatchingFont(f.weight, DWRITE_FONT_STRETCH_NORMAL, f.style, &font);
    ComPtr<IDWriteFontFace> face; CHECK(font->CreateFontFace(&face));
    ComPtr<IDWriteRenderingParams> params; CHECK(dw->CreateRenderingParams(&params));
    DWRITE_RENDERING_MODE mode; face->GetRecommendedRenderingMode(size, 2.0f, DWRITE_MEASURING_MODE_NATURAL, params.Get(), &mode);
    UINT32 cp = 'l'; UINT16 glyph; face->GetGlyphIndices(&cp, 1, &glyph);
    FLOAT adv = 0; DWRITE_GLYPH_OFFSET off = {0, 0};
    DWRITE_GLYPH_RUN run = {}; run.fontFace = face.Get(); run.fontEmSize = size; run.glyphCount = 1; run.glyphIndices = &glyph; run.glyphAdvances = &adv; run.glyphOffsets = &off;
    std::vector<size_t> bHash; const int N = 64;
    for (int k = 0; k < N; k++) {
        float xDevice = 8 + (float) k / N;   // one device pixel
        ComPtr<IWICBitmap> bmp; CHECK(wic->CreateBitmap(48, 56, GUID_WICPixelFormat32bppBGR, WICBitmapCacheOnDemand, &bmp));
        D2D1_RENDER_TARGET_PROPERTIES props = D2D1::RenderTargetProperties(D2D1_RENDER_TARGET_TYPE_SOFTWARE, D2D1::PixelFormat(DXGI_FORMAT_B8G8R8A8_UNORM, D2D1_ALPHA_MODE_IGNORE), 192, 192);
        ComPtr<ID2D1RenderTarget> rt; CHECK(d2d->CreateWicBitmapRenderTarget(bmp.Get(), props, &rt));
        rt->SetTextRenderingParams(params.Get()); rt->SetTextAntialiasMode(D2D1_TEXT_ANTIALIAS_MODE_GRAYSCALE);
        ComPtr<ID2D1SolidColorBrush> brush; CHECK(rt->CreateSolidColorBrush(D2D1::ColorF(0, 0, 0), &brush));
        rt->BeginDraw(); rt->Clear(D2D1::ColorF(1, 1, 1)); rt->DrawGlyphRun(D2D1::Point2F(xDevice / 2, 20), &run, brush.Get(), DWRITE_MEASURING_MODE_NATURAL); CHECK(rt->EndDraw());
        std::vector<uint8_t> px((size_t) 48 * 56 * 4); CHECK(bmp->CopyPixels(nullptr, 48 * 4, (UINT) px.size(), px.data()));
        bHash.push_back(hashBytes(px));
    }
    int distinct = 1; std::string steps = "0";
    for (int k = 1; k < N; k++) if (bHash[k] != bHash[k - 1]) { distinct++; steps += " " + std::to_string(k); }
    printf("  %-10s direct2d at 192 dpi    mode %d: %2d distinct images across one device pixel; changes at (1/64 device px): %s\n", label, (int) mode, distinct, steps.c_str());
}

static void gridProbe(const Face& f, const char* label, float size) {
    ComPtr<IDWriteTextFormat> format; CHECK(dw->CreateTextFormat(f.family.c_str(), f.collection.Get(), f.weight, f.style, DWRITE_FONT_STRETCH_NORMAL, size, L"en-us", &format));
    ComPtr<IDWriteFontCollection> coll; format->GetFontCollection(&coll); if (!coll) dw->GetSystemFontCollection(&coll);
    UINT32 idx; BOOL exists; coll->FindFamilyName(f.family.c_str(), &idx, &exists);
    ComPtr<IDWriteFontFamily> fam; coll->GetFontFamily(idx, &fam);
    ComPtr<IDWriteFont> font; fam->GetFirstMatchingFont(f.weight, DWRITE_FONT_STRETCH_NORMAL, f.style, &font);
    ComPtr<IDWriteFontFace> face; CHECK(font->CreateFontFace(&face));
    ComPtr<IDWriteRenderingParams> params; CHECK(dw->CreateRenderingParams(&params));
    DWRITE_RENDERING_MODE mode; face->GetRecommendedRenderingMode(size, 1.0f, DWRITE_MEASURING_MODE_NATURAL, params.Get(), &mode);
    UINT32 cp = 'l'; UINT16 glyph; face->GetGlyphIndices(&cp, 1, &glyph);
    FLOAT adv = 0; DWRITE_GLYPH_OFFSET off = {0, 0};
    DWRITE_GLYPH_RUN run = {}; run.fontFace = face.Get(); run.fontEmSize = size; run.glyphCount = 1; run.glyphIndices = &glyph; run.glyphAdvances = &adv; run.glyphOffsets = &off;
    // (a) the analysis texture
    std::vector<size_t> aHash, bHash; const int N = 64;
    for (int k = 0; k < N; k++) {
        float x = 4 + (float) k / N;
        ComPtr<IDWriteGlyphRunAnalysis> an; ComPtr<IDWriteFactory2> dw2; CHECK(dw.As(&dw2));
        CHECK(dw2->CreateGlyphRunAnalysis(&run, nullptr, mode, DWRITE_MEASURING_MODE_NATURAL, DWRITE_GRID_FIT_MODE_DEFAULT, DWRITE_TEXT_ANTIALIAS_MODE_GRAYSCALE, x, 20, &an));
        RECT b; CHECK(an->GetAlphaTextureBounds(DWRITE_TEXTURE_ALIASED_1x1, &b));
        RECT fixedRect = {0, 0, 24, 28};   // a fixed window so position shifts count as differences
        std::vector<uint8_t> tex((size_t) 24 * 28); CHECK(an->CreateAlphaTexture(DWRITE_TEXTURE_ALIASED_1x1, &fixedRect, tex.data(), (UINT32) tex.size()));
        aHash.push_back(hashBytes(tex));
        // (b) Direct2D
        ComPtr<IWICBitmap> bmp; CHECK(wic->CreateBitmap(24, 28, GUID_WICPixelFormat32bppBGR, WICBitmapCacheOnDemand, &bmp));
        D2D1_RENDER_TARGET_PROPERTIES props = D2D1::RenderTargetProperties(D2D1_RENDER_TARGET_TYPE_SOFTWARE, D2D1::PixelFormat(DXGI_FORMAT_B8G8R8A8_UNORM, D2D1_ALPHA_MODE_IGNORE), 96, 96);
        ComPtr<ID2D1RenderTarget> rt; CHECK(d2d->CreateWicBitmapRenderTarget(bmp.Get(), props, &rt));
        rt->SetTextRenderingParams(params.Get()); rt->SetTextAntialiasMode(D2D1_TEXT_ANTIALIAS_MODE_GRAYSCALE);
        ComPtr<ID2D1SolidColorBrush> brush; CHECK(rt->CreateSolidColorBrush(D2D1::ColorF(0, 0, 0), &brush));
        rt->BeginDraw(); rt->Clear(D2D1::ColorF(1, 1, 1)); rt->DrawGlyphRun(D2D1::Point2F(x, 20), &run, brush.Get(), DWRITE_MEASURING_MODE_NATURAL); CHECK(rt->EndDraw());
        std::vector<uint8_t> px((size_t) 24 * 28 * 4); CHECK(bmp->CopyPixels(nullptr, 24 * 4, (UINT) px.size(), px.data()));
        bHash.push_back(hashBytes(px));
    }
    auto report = [&](const char* what, const std::vector<size_t>& h) {
        int distinct = 1; std::string steps = "0";
        for (int k = 1; k < N; k++) if (h[k] != h[k - 1]) { distinct++; steps += " " + std::to_string(k); }
        printf("  %-10s %-22s mode %d: %2d distinct images across one pixel; changes at offsets (in 1/64 px): %s\n", label, what, (int) mode, distinct, steps.c_str());
    };
    report("analysis", aHash); report("direct2d", bHash);
}

// --dpi: is DirectWrite's raster per device pixel the same whether the 2x sheet is drawn at 192 dpi
// with em 13 DIP (the sheets, every Windows application on a 200 % display) or at 96 dpi with em 26
// (the Skia scaler's mask route: pixelsPerDip 1, the em in device pixels)? The sheet's text through
// the layout, grayscale, black on white, fractional positions; per-device-pixel statistics.
static void dpiProbe(const Face& f, const char* label) {
    const int w = W * 2, h = H * 2;
    auto render = [&](float dpi, float emScale) {
        ComPtr<IWICBitmap> bmp; CHECK(wic->CreateBitmap(w, h, GUID_WICPixelFormat32bppBGR, WICBitmapCacheOnDemand, &bmp));
        D2D1_RENDER_TARGET_PROPERTIES props = D2D1::RenderTargetProperties(D2D1_RENDER_TARGET_TYPE_SOFTWARE, D2D1::PixelFormat(DXGI_FORMAT_B8G8R8A8_UNORM, D2D1_ALPHA_MODE_IGNORE), dpi, dpi);
        ComPtr<ID2D1RenderTarget> rt; CHECK(d2d->CreateWicBitmapRenderTarget(bmp.Get(), props, &rt));
        ComPtr<IDWriteRenderingParams> params; CHECK(dw->CreateRenderingParams(&params));
        rt->SetTextRenderingParams(params.Get()); rt->SetTextAntialiasMode(D2D1_TEXT_ANTIALIAS_MODE_GRAYSCALE);
        ComPtr<ID2D1SolidColorBrush> brush; CHECK(rt->CreateSolidColorBrush(D2D1::ColorF(0, 0, 0), &brush));
        Face g = f; g.size = f.size * emScale;
        ComPtr<IDWriteTextLayout> layout = layoutFor(g, false);
        Renderer rend; rend.rt = rt.Get(); rend.brush = brush.Get(); rend.scale = dpi / 96.0f; rend.fileFont = f.fileFont; rend.wholePixel = false;
        DWRITE_LINE_METRICS lm; UINT32 n = 1; layout->GetLineMetrics(&lm, 1, &n);
        rt->BeginDraw(); rt->Clear(D2D1::ColorF(1, 1, 1));
        CHECK(layout->Draw(nullptr, &rend, X * emScale, BASE * emScale - lm.baseline));
        CHECK(rt->EndDraw());
        std::vector<uint8_t> px((size_t) w * h * 4); CHECK(bmp->CopyPixels(nullptr, w * 4, (UINT) px.size(), px.data()));
        return px;
    };
    std::vector<uint8_t> a = render(192, 1), b = render(96, 2);
    int n = 0, b32 = 0, max = 0; long sum = 0; int firstX = -1;
    for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
        int m = 0; for (int c = 0; c < 3; c++) m = std::max(m, std::abs((int) a[((size_t) y * w + x) * 4 + c] - (int) b[((size_t) y * w + x) * 4 + c]));
        if (m > 0) { n++; sum += m; if (firstX < 0 || x < firstX) firstX = x; }
        if (m > 32) b32++;
        max = std::max(max, m);
    }
    printf("  %-10s 192 dpi em 13 vs 96 dpi em 26: differing %5d  >32: %4d  max %3d  mean %5.1f  first differing column %d\n", label, n, b32, max, n == 0 ? 0.0 : (double) sum / n, firstX);
}

static ComPtr<IDWriteFontFace> faceOf(const Face& f) {
    ComPtr<IDWriteTextFormat> format; CHECK(dw->CreateTextFormat(f.family.c_str(), f.collection.Get(), f.weight, f.style, DWRITE_FONT_STRETCH_NORMAL, f.size, L"en-us", &format));
    ComPtr<IDWriteFontCollection> coll; format->GetFontCollection(&coll); if (!coll) dw->GetSystemFontCollection(&coll);
    UINT32 idx; BOOL exists; coll->FindFamilyName(f.family.c_str(), &idx, &exists);
    ComPtr<IDWriteFontFamily> fam; coll->GetFontFamily(idx, &fam);
    ComPtr<IDWriteFont> font; fam->GetFirstMatchingFont(f.weight, DWRITE_FONT_STRETCH_NORMAL, f.style, &font);
    ComPtr<IDWriteFontFace> face; CHECK(font->CreateFontFace(&face)); return face;
}

static void diffStats(const char* what, const std::vector<uint8_t>& a, const std::vector<uint8_t>& b) {
    int n = 0, b32 = 0, max = 0; long sum = 0;
    for (size_t i = 0; i < a.size(); i++) { int m = std::abs((int) a[i] - (int) b[i]); if (m > 0) { n++; sum += m; } if (m > 32) b32++; max = std::max(max, m); }
    printf("    %-62s differing %5d  >32: %4d  max %3d  mean %5.1f\n", what, n, b32, max, n == 0 ? 0.0 : (double) sum / n);
}

// The same glyphs at the same device positions (origins snapped to half device pixels, the 192-dpi
// grid), one glyph run each, so the placement grid is out of the comparison: Direct2D at 192 dpi with
// em 13 against Direct2D at 96 dpi with em 26, and DirectWrite's own analysis textures with em 13 at
// 2 pixels per DIP against em 26 at 1. What is left is the rasterizer's own DPI dependence.
static void dpiProbeSnapped(const Face& f, const char* label) {
    const int w = W * 2, h = H * 2;
    ComPtr<IDWriteFontFace> face = faceOf(f);
    size_t len = wcslen(TEXT); std::vector<UINT32> cps(len); for (size_t i = 0; i < len; i++) cps[i] = TEXT[i];
    std::vector<UINT16> glyphs(len); CHECK(face->GetGlyphIndices(cps.data(), (UINT32) len, glyphs.data()));
    DWRITE_FONT_METRICS fm; face->GetMetrics(&fm);
    std::vector<DWRITE_GLYPH_METRICS> gm(len); CHECK(face->GetDesignGlyphMetrics(glyphs.data(), (UINT32) len, gm.data(), FALSE));
    std::vector<float> xs(len); float pen = X * 2;
    for (size_t i = 0; i < len; i++) { xs[i] = std::round(pen * 2) / 2; pen += gm[i].advanceWidth * (f.size * 2) / fm.designUnitsPerEm; }
    const float yDev = BASE * 2;
    ComPtr<IDWriteRenderingParams> params; CHECK(dw->CreateRenderingParams(&params));
    auto runFor = [&](size_t i, float ppd, FLOAT* adv, DWRITE_GLYPH_OFFSET* off) {
        DWRITE_GLYPH_RUN run = {}; run.fontFace = face.Get(); run.fontEmSize = f.size * 2 / ppd; run.glyphCount = 1; run.glyphIndices = &glyphs[i]; run.glyphAdvances = adv; run.glyphOffsets = off; return run;
    };
    auto d2dRender = [&](float dpi, IDWriteRenderingParams* prm = nullptr, float shiftX = 0, float shiftY = 0) {
        float ppd = dpi / 96.0f; if (prm == nullptr) prm = params.Get();
        ComPtr<IWICBitmap> bmp; CHECK(wic->CreateBitmap(w, h, GUID_WICPixelFormat32bppBGR, WICBitmapCacheOnDemand, &bmp));
        D2D1_RENDER_TARGET_PROPERTIES props = D2D1::RenderTargetProperties(D2D1_RENDER_TARGET_TYPE_SOFTWARE, D2D1::PixelFormat(DXGI_FORMAT_B8G8R8A8_UNORM, D2D1_ALPHA_MODE_IGNORE), dpi, dpi);
        ComPtr<ID2D1RenderTarget> rt; CHECK(d2d->CreateWicBitmapRenderTarget(bmp.Get(), props, &rt));
        rt->SetTextRenderingParams(prm); rt->SetTextAntialiasMode(D2D1_TEXT_ANTIALIAS_MODE_GRAYSCALE);
        ComPtr<ID2D1SolidColorBrush> brush; CHECK(rt->CreateSolidColorBrush(D2D1::ColorF(0, 0, 0), &brush));
        rt->BeginDraw(); rt->Clear(D2D1::ColorF(1, 1, 1));
        for (size_t i = 0; i < len; i++) { FLOAT adv = 0; DWRITE_GLYPH_OFFSET off = {0, 0}; DWRITE_GLYPH_RUN run = runFor(i, ppd, &adv, &off); rt->DrawGlyphRun(D2D1::Point2F((xs[i] + shiftX) / ppd, (yDev + shiftY) / ppd), &run, brush.Get(), DWRITE_MEASURING_MODE_NATURAL); }
        CHECK(rt->EndDraw());
        std::vector<uint8_t> px((size_t) w * h * 4); CHECK(bmp->CopyPixels(nullptr, w * 4, (UINT) px.size(), px.data()));
        std::vector<uint8_t> gray((size_t) w * h); for (size_t i = 0; i < gray.size(); i++) gray[i] = px[i * 4 + 1];
        return gray;
    };
    // the newer analysis (what Direct2D uses): no pixels-per-DIP parameter, the DPI goes into the
    // transform; em 13 with a scale-2 transform against em 26 with the identity
    auto analysisRender = [&](float ppd, DWRITE_RENDERING_MODE* modeOut, float* gamma, float* contrast, float* level) {
        std::vector<uint8_t> cov((size_t) w * h, 0);
        DWRITE_RENDERING_MODE mode; face->GetRecommendedRenderingMode(f.size * 2 / ppd, ppd, DWRITE_MEASURING_MODE_NATURAL, params.Get(), &mode); *modeOut = mode;
        DWRITE_MATRIX tx = {ppd, 0, 0, ppd, 0, 0};
        ComPtr<IDWriteFactory2> dw2; CHECK(dw.As(&dw2));
        for (size_t i = 0; i < len; i++) {
            FLOAT adv = 0; DWRITE_GLYPH_OFFSET off = {0, 0}; DWRITE_GLYPH_RUN run = runFor(i, ppd, &adv, &off);
            ComPtr<IDWriteGlyphRunAnalysis> an; CHECK(dw2->CreateGlyphRunAnalysis(&run, &tx, mode, DWRITE_MEASURING_MODE_NATURAL, DWRITE_GRID_FIT_MODE_DEFAULT, DWRITE_TEXT_ANTIALIAS_MODE_GRAYSCALE, xs[i] / ppd, yDev / ppd, &an));
            if (i == 0) CHECK(an->GetAlphaBlendParams(params.Get(), gamma, contrast, level));
            RECT b; CHECK(an->GetAlphaTextureBounds(DWRITE_TEXTURE_ALIASED_1x1, &b)); if (b.right <= b.left || b.bottom <= b.top) continue;
            int bw = b.right - b.left, bh = b.bottom - b.top; std::vector<uint8_t> tex((size_t) bw * bh); CHECK(an->CreateAlphaTexture(DWRITE_TEXTURE_ALIASED_1x1, &b, tex.data(), (UINT32) tex.size()));
            for (int y = 0; y < bh; y++) for (int x = 0; x < bw; x++) { int px = b.left + x, py = b.top + y; if (px >= 0 && px < w && py >= 0 && py < h) cov[(size_t) py * w + px] = std::max(cov[(size_t) py * w + px], tex[(size_t) y * bw + x]); }
        }
        return cov;
    };
    printf("  %s\n", label);
    diffStats("direct2d 192 dpi em 13 vs 96 dpi em 26", d2dRender(192), d2dRender(96));
    DWRITE_RENDERING_MODE m2, m1; float g2, c2, l2, g1, c1, l1;
    std::vector<uint8_t> a2 = analysisRender(2, &m2, &g2, &c2, &l2), a1 = analysisRender(1, &m1, &g1, &c1, &l1);
    char what[160]; snprintf(what, sizeof what, "analysis em 13 x2 transform (mode %d) vs em 26 identity (mode %d)", (int) m2, (int) m1);
    diffStats(what, a2, a1);
    printf("    alpha blend params (gamma, enhanced contrast, cleartype level): em 13 x2 %.3f %.3f %.3f, em 26 %.3f %.3f %.3f\n", g2, c2, l2, g1, c1, l1);
    auto ink = [&](const std::vector<uint8_t>& gray) { long t = 0; for (uint8_t g : gray) t += 255 - g; return t; };
    long cov = 0; for (uint8_t c : a2) cov += c;
    long i192 = ink(d2dRender(192)), i96 = ink(d2dRender(96));
    // which setting does Direct2D change with the DPI? the same drawing with the rendering mode and the
    // grid-fit mode forced through custom rendering parameters (gamma, contrast, level as the system's)
    {
        ComPtr<IDWriteRenderingParams1> p1; params.As(&p1); float gec = p1 ? p1->GetGrayscaleEnhancedContrast() : 1.0f;
        ComPtr<IDWriteFactory2> dw2; CHECK(dw.As(&dw2));
        auto custom = [&](DWRITE_RENDERING_MODE rm, DWRITE_GRID_FIT_MODE gf) {
            ComPtr<IDWriteRenderingParams2> c; CHECK(dw2->CreateCustomRenderingParams(params->GetGamma(), params->GetEnhancedContrast(), gec, params->GetClearTypeLevel(), params->GetPixelGeometry(), rm, gf, &c));
            ComPtr<IDWriteRenderingParams> r; c.As(&r); return r;
        };
        std::vector<uint8_t> ref = d2dRender(192);
        struct V { const char* name; DWRITE_RENDERING_MODE rm; DWRITE_GRID_FIT_MODE gf; } vs[] = {
            {"symmetric, grid fit enabled", DWRITE_RENDERING_MODE_NATURAL_SYMMETRIC, DWRITE_GRID_FIT_MODE_ENABLED},
            {"symmetric, grid fit disabled", DWRITE_RENDERING_MODE_NATURAL_SYMMETRIC, DWRITE_GRID_FIT_MODE_DISABLED},
            {"natural, grid fit enabled", DWRITE_RENDERING_MODE_NATURAL, DWRITE_GRID_FIT_MODE_ENABLED},
            {"natural, grid fit disabled", DWRITE_RENDERING_MODE_NATURAL, DWRITE_GRID_FIT_MODE_DISABLED},
        };
        for (const V& v : vs) {
            char what[160];
            snprintf(what, sizeof what, "96 dpi em 26 forced %s vs 192 default", v.name); diffStats(what, ref, d2dRender(96, custom(v.rm, v.gf).Get()));
            snprintf(what, sizeof what, "192 dpi em 13 forced %s vs 192 default", v.name); diffStats(what, ref, d2dRender(192, custom(v.rm, v.gf).Get()));
        }
        // a pure shift? the 96 dpi image moved by whole device pixels against the 192 dpi one
        std::vector<uint8_t> b = d2dRender(96); long best = LONG_MAX; int bdx = 0, bdy = 0;
        for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++) { long sum = 0; for (int y = 2; y < h - 2; y++) for (int x = 2; x < w - 2; x++) sum += std::abs((int) ref[(size_t) y * w + x] - (int) b[(size_t) (y + dy) * w + x + dx]); if (sum < best) { best = sum; bdx = dx; bdy = dy; } }
        // a sub-pixel shift? the 96 dpi drawing with every origin moved by a fraction of a device pixel
        for (float sy : {-0.5f, -0.25f, 0.25f, 0.5f}) { char what[160]; snprintf(what, sizeof what, "96 dpi em 26 with origins moved by dy %+.2f px vs 192 default", sy); diffStats(what, ref, d2dRender(96, nullptr, 0, sy)); }
        for (float sx : {-0.25f, 0.25f}) { char what[160]; snprintf(what, sizeof what, "96 dpi em 26 with origins moved by dx %+.2f px vs 192 default", sx); diffStats(what, ref, d2dRender(96, nullptr, sx, 0)); }
        // the first glyph's pixels, rows around the x-height and baseline, both renderings side by side
        printf("    first glyph, gray values (192 dpi | 96 dpi), rows 18..40, columns 8..27:\n");
        for (int y = 18; y <= 40; y++) { printf("    %2d ", y); for (int x = 8; x < 28; x++) printf("%4d", ref[(size_t) y * w + x]); printf("  |"); for (int x = 8; x < 28; x++) printf("%4d", b[(size_t) y * w + x]); printf("\n"); }
        printf("    best whole-pixel shift of the 96 dpi image: dx %d dy %d (sum of differences %ld; unshifted %ld)\n", bdx, bdy, best, [&] { long sum = 0; for (int y = 2; y < h - 2; y++) for (int x = 2; x < w - 2; x++) sum += std::abs((int) ref[(size_t) y * w + x] - (int) b[(size_t) y * w + x]); return sum; }());
    }
    printf("    ink over linear coverage: direct2d 192 dpi em 13 %.3f, direct2d 96 dpi em 26 %.3f\n", (double) i192 / cov, (double) i96 / cov);
}

// --scale: DirectWrite at the non-integer display scales (120 and 144 dpi, i.e. 125 % and 150 %):
// where the glyph origin changes across five device pixels (the placement grid is per DIP, so it is
// not periodic per device pixel there), the rendering mode, and the gray levels of the sheet text.
static void scaleProbe(const Face& f, const char* label, float dpi, bool clearType = false) {
    const float ppd = dpi / 96.0f;
    ComPtr<IDWriteFontFace> face = faceOf(f);
    ComPtr<IDWriteRenderingParams> params; CHECK(dw->CreateRenderingParams(&params));
    DWRITE_RENDERING_MODE mode; face->GetRecommendedRenderingMode(f.size, ppd, DWRITE_MEASURING_MODE_NATURAL, params.Get(), &mode);
    UINT32 cp = 'l'; UINT16 glyph; face->GetGlyphIndices(&cp, 1, &glyph);
    FLOAT adv = 0; DWRITE_GLYPH_OFFSET off = {0, 0};
    DWRITE_GLYPH_RUN run = {}; run.fontFace = face.Get(); run.fontEmSize = f.size; run.glyphCount = 1; run.glyphIndices = &glyph; run.glyphAdvances = &adv; run.glyphOffsets = &off;
    const int N = 32, P = 5, w = 48, h = 56;
    std::vector<size_t> hashes;
    for (int k = 0; k < N * P; k++) {
        float xDevice = 8 + (float) k / N;
        ComPtr<IWICBitmap> bmp; CHECK(wic->CreateBitmap(w, h, GUID_WICPixelFormat32bppBGR, WICBitmapCacheOnDemand, &bmp));
        D2D1_RENDER_TARGET_PROPERTIES props = D2D1::RenderTargetProperties(D2D1_RENDER_TARGET_TYPE_SOFTWARE, D2D1::PixelFormat(DXGI_FORMAT_B8G8R8A8_UNORM, D2D1_ALPHA_MODE_IGNORE), dpi, dpi);
        ComPtr<ID2D1RenderTarget> rt; CHECK(d2d->CreateWicBitmapRenderTarget(bmp.Get(), props, &rt));
        rt->SetTextRenderingParams(params.Get()); rt->SetTextAntialiasMode(clearType ? D2D1_TEXT_ANTIALIAS_MODE_CLEARTYPE : D2D1_TEXT_ANTIALIAS_MODE_GRAYSCALE);
        ComPtr<ID2D1SolidColorBrush> brush; CHECK(rt->CreateSolidColorBrush(D2D1::ColorF(0, 0, 0), &brush));
        rt->BeginDraw(); rt->Clear(D2D1::ColorF(1, 1, 1)); rt->DrawGlyphRun(D2D1::Point2F(xDevice / ppd, 40 / ppd), &run, brush.Get(), DWRITE_MEASURING_MODE_NATURAL); CHECK(rt->EndDraw());
        std::vector<uint8_t> px((size_t) w * h * 4); CHECK(bmp->CopyPixels(nullptr, w * 4, (UINT) px.size(), px.data()));
        hashes.push_back(hashBytes(px));
    }
    int distinct = 1; std::string steps;
    for (int k = 1; k < N * P; k++) if (hashes[k] != hashes[k - 1]) { distinct++; char b[16]; snprintf(b, sizeof b, " %.3f", (double) k / N); steps += b; }
    printf("  %-10s %3.0f dpi (em %.2f px) mode %d%s: %d distinct origins over %d device pixels; changes at (device px):%s\n", label, dpi, f.size * ppd, (int) mode, clearType ? " ClearType" : "", distinct, P, steps.c_str());
}

static void scaleLevels(const Face& f, const char* label, float dpi, bool clearType = false) {
    const float ppd = dpi / 96.0f;
    const int w = (int) (W * ppd), h = (int) (H * ppd);
    ComPtr<IWICBitmap> bmp; CHECK(wic->CreateBitmap(w, h, GUID_WICPixelFormat32bppBGR, WICBitmapCacheOnDemand, &bmp));
    D2D1_RENDER_TARGET_PROPERTIES props = D2D1::RenderTargetProperties(D2D1_RENDER_TARGET_TYPE_SOFTWARE, D2D1::PixelFormat(DXGI_FORMAT_B8G8R8A8_UNORM, D2D1_ALPHA_MODE_IGNORE), dpi, dpi);
    ComPtr<ID2D1RenderTarget> rt; CHECK(d2d->CreateWicBitmapRenderTarget(bmp.Get(), props, &rt));
    ComPtr<IDWriteRenderingParams> params; CHECK(dw->CreateRenderingParams(&params));
    rt->SetTextRenderingParams(params.Get()); rt->SetTextAntialiasMode(clearType ? D2D1_TEXT_ANTIALIAS_MODE_CLEARTYPE : D2D1_TEXT_ANTIALIAS_MODE_GRAYSCALE);
    ComPtr<ID2D1SolidColorBrush> brush; CHECK(rt->CreateSolidColorBrush(D2D1::ColorF(0, 0, 0), &brush));
    ComPtr<IDWriteTextLayout> layout = layoutFor(f, false);
    Renderer rend; rend.rt = rt.Get(); rend.brush = brush.Get(); rend.scale = ppd; rend.fileFont = f.fileFont; rend.wholePixel = false;
    DWRITE_LINE_METRICS lm; UINT32 n = 1; layout->GetLineMetrics(&lm, 1, &n);
    rt->BeginDraw(); rt->Clear(D2D1::ColorF(1, 1, 1)); CHECK(layout->Draw(nullptr, &rend, X, BASE - lm.baseline)); CHECK(rt->EndDraw());
    std::vector<uint8_t> px((size_t) w * h * 4); CHECK(bmp->CopyPixels(nullptr, w * 4, (UINT) px.size(), px.data()));
    std::vector<bool> seen(256, false); int count = 0; std::string list;
    for (size_t i = 0; i < (size_t) w * h; i++) for (int c = 0; c < (clearType ? 3 : 1); c++) { uint8_t g = px[i * 4 + (clearType ? c : 1)]; if (!seen[g]) { seen[g] = true; count++; } }
    for (int g = 0; g < 256; g++) if (seen[g]) { char b[8]; snprintf(b, sizeof b, " %d", g); list += b; }
    printf("  %-10s %3.0f dpi%s: %d %s levels:%s\n", label, dpi, clearType ? " ClearType" : "", count, clearType ? "channel" : "gray", list.c_str());
}

// --positions: the device x of every glyph origin of the sheet text as DirectWrite's layout places
// them at a DPI (to compare with Java's accumulated advances), and the distance to the nearest
// rounding boundary of the per-DIP placement grid (a glyph near one may land a step apart).
static void positionsProbe(const Face& f, const char* label, float dpi) {
    const float ppd = dpi / 96.0f;
    struct Capture : Renderer { std::vector<float> xs; float ppd = 1; std::string runs;
        HRESULT __stdcall DrawGlyphRun(void*, FLOAT x, FLOAT y, DWRITE_MEASURING_MODE mm, DWRITE_GLYPH_RUN const* run, DWRITE_GLYPH_RUN_DESCRIPTION const*, IUnknown*) override {
            char b[160]; bool offsets = false; if (run->glyphOffsets) for (UINT32 i = 0; i < run->glyphCount; i++) if (run->glyphOffsets[i].advanceOffset != 0 || run->glyphOffsets[i].ascenderOffset != 0) offsets = true;
            snprintf(b, sizeof b, " [run at x %.4f y %.4f DIP, %u glyphs, em %.2f, measuring %d, sideways %d, bidi %u, offsets %s]", x, y, run->glyphCount, run->fontEmSize, (int) mm, (int) run->isSideways, run->bidiLevel, offsets ? "yes" : "none"); runs += b;
            float pen = x; for (UINT32 i = 0; i < run->glyphCount; i++) { xs.push_back(pen * ppd); pen += run->glyphAdvances[i]; } return S_OK; }
        HRESULT __stdcall DrawUnderline(void*, FLOAT, FLOAT, DWRITE_UNDERLINE const*, IUnknown*) override { return S_OK; }
        HRESULT __stdcall GetCurrentTransform(void*, DWRITE_MATRIX* m) override { *m = {1, 0, 0, 1, 0, 0}; return S_OK; }
        HRESULT __stdcall GetPixelsPerDip(void*, FLOAT* p) override { *p = ppd; return S_OK; }
    } c; c.ppd = ppd; c.scale = ppd; c.fileFont = f.fileFont; c.wholePixel = false; c.rt = nullptr; c.brush = nullptr;
    ComPtr<IDWriteTextLayout> layout = layoutFor(f, false);
    layout->Draw(nullptr, &c, X, 0);
    printf("  %s at %.0f dpi, runs:%s\n  device x of each glyph origin (grid step %.4f px):\n   ", label, dpi, c.runs.c_str(), 0.25f * ppd);
    for (size_t i = 0; i < c.xs.size(); i++) printf(" %.4f", c.xs[i]);
    printf("\n");
}

// --vgrid: does Direct2D position a glyph vertically at sub-pixel offsets, at device pixels, or at
// whole DIPs? The glyph 'o' with the baseline stepping 1/32 device px over four device pixels.
static void verticalProbe(const Face& f, const char* label, float dpi) {
    const float ppd = dpi / 96.0f;
    ComPtr<IDWriteFontFace> face = faceOf(f);
    ComPtr<IDWriteRenderingParams> params; CHECK(dw->CreateRenderingParams(&params));
    UINT32 cp = 'o'; UINT16 glyph; face->GetGlyphIndices(&cp, 1, &glyph);
    FLOAT adv = 0; DWRITE_GLYPH_OFFSET off = {0, 0};
    DWRITE_GLYPH_RUN run = {}; run.fontFace = face.Get(); run.fontEmSize = f.size; run.glyphCount = 1; run.glyphIndices = &glyph; run.glyphAdvances = &adv; run.glyphOffsets = &off;
    const int N = 32, P = 4, w = 48, h = 64;
    std::vector<size_t> hashes;
    for (int k = 0; k < N * P; k++) {
        float yDevice = 30 + (float) k / N;
        ComPtr<IWICBitmap> bmp; CHECK(wic->CreateBitmap(w, h, GUID_WICPixelFormat32bppBGR, WICBitmapCacheOnDemand, &bmp));
        D2D1_RENDER_TARGET_PROPERTIES props = D2D1::RenderTargetProperties(D2D1_RENDER_TARGET_TYPE_SOFTWARE, D2D1::PixelFormat(DXGI_FORMAT_B8G8R8A8_UNORM, D2D1_ALPHA_MODE_IGNORE), dpi, dpi);
        ComPtr<ID2D1RenderTarget> rt; CHECK(d2d->CreateWicBitmapRenderTarget(bmp.Get(), props, &rt));
        rt->SetTextRenderingParams(params.Get()); rt->SetTextAntialiasMode(D2D1_TEXT_ANTIALIAS_MODE_GRAYSCALE);
        ComPtr<ID2D1SolidColorBrush> brush; CHECK(rt->CreateSolidColorBrush(D2D1::ColorF(0, 0, 0), &brush));
        rt->BeginDraw(); rt->Clear(D2D1::ColorF(1, 1, 1)); rt->DrawGlyphRun(D2D1::Point2F(8 / ppd, yDevice / ppd), &run, brush.Get(), DWRITE_MEASURING_MODE_NATURAL); CHECK(rt->EndDraw());
        std::vector<uint8_t> px((size_t) w * h * 4); CHECK(bmp->CopyPixels(nullptr, w * 4, (UINT) px.size(), px.data()));
        hashes.push_back(hashBytes(px));
    }
    int distinct = 1; std::string steps;
    for (int k = 1; k < N * P; k++) if (hashes[k] != hashes[k - 1]) { distinct++; char b[16]; snprintf(b, sizeof b, " %.3f", 30 + (double) k / N); steps += b; }
    printf("  %-10s %3.0f dpi: %d distinct images over %d device pixels of baseline y; changes at device y:%s\n", label, dpi, distinct, P, steps.c_str());
}

// --glyph <font file> <char> <device x> <dpi>: one glyph through Direct2D at that DPI, the baseline
// at device y 40, printed as gray values (rows 8..47, columns 0..31) to compare with Java's mask.
static void glyphDump(const Face& f, wchar_t ch, float xDevice, float dpi) {
    const float ppd = dpi / 96.0f; const int w = 32, h = 48;
    ComPtr<IDWriteFontFace> face = faceOf(f);
    ComPtr<IDWriteRenderingParams> params; CHECK(dw->CreateRenderingParams(&params));
    UINT32 cp = ch; UINT16 glyph; face->GetGlyphIndices(&cp, 1, &glyph);
    FLOAT adv = 0; DWRITE_GLYPH_OFFSET off = {0, 0};
    DWRITE_GLYPH_RUN run = {}; run.fontFace = face.Get(); run.fontEmSize = f.size; run.glyphCount = 1; run.glyphIndices = &glyph; run.glyphAdvances = &adv; run.glyphOffsets = &off;
    ComPtr<IWICBitmap> bmp; CHECK(wic->CreateBitmap(w, h, GUID_WICPixelFormat32bppBGR, WICBitmapCacheOnDemand, &bmp));
    D2D1_RENDER_TARGET_PROPERTIES props = D2D1::RenderTargetProperties(D2D1_RENDER_TARGET_TYPE_SOFTWARE, D2D1::PixelFormat(DXGI_FORMAT_B8G8R8A8_UNORM, D2D1_ALPHA_MODE_IGNORE), dpi, dpi);
    ComPtr<ID2D1RenderTarget> rt; CHECK(d2d->CreateWicBitmapRenderTarget(bmp.Get(), props, &rt));
    rt->SetTextRenderingParams(params.Get()); rt->SetTextAntialiasMode(D2D1_TEXT_ANTIALIAS_MODE_GRAYSCALE);
    ComPtr<ID2D1SolidColorBrush> brush; CHECK(rt->CreateSolidColorBrush(D2D1::ColorF(0, 0, 0), &brush));
    rt->BeginDraw(); rt->Clear(D2D1::ColorF(1, 1, 1)); rt->DrawGlyphRun(D2D1::Point2F(xDevice / ppd, 40 / ppd), &run, brush.Get(), DWRITE_MEASURING_MODE_NATURAL); CHECK(rt->EndDraw());
    std::vector<uint8_t> px((size_t) w * h * 4); CHECK(bmp->CopyPixels(nullptr, w * 4, (UINT) px.size(), px.data()));
    printf("direct2d %.0f dpi, glyph at device x %.4f, baseline y 40:\n", dpi, xDevice);
    for (int y = 8; y < h; y++) { printf("%3d ", y); for (int x = 0; x < w; x++) printf("%4d", px[((size_t) y * w + x) * 4 + 1]); printf("\n"); }
}

// --runs <fonts-dir> <dpi>: the sheet text drawn as the layout's run (the native sheets) against the
// same glyphs drawn one run each at the layout's positions (how Java places glyph images): does
// Direct2D position glyphs inside a run the way it positions single glyphs? Per glyph: the largest
// difference in the glyph's column band.
static void runsProbe(const Face& f, const char* label, float dpi) {
    const float ppd = dpi / 96.0f; const int w = (int) (W * ppd), h = (int) (H * ppd);
    struct Capture : Renderer { std::vector<float> xs; std::vector<UINT16> glyphs; std::vector<float> advs; IDWriteFontFace* face = nullptr; float ppd = 1; float em = 0;
        HRESULT __stdcall DrawGlyphRun(void*, FLOAT x, FLOAT, DWRITE_MEASURING_MODE, DWRITE_GLYPH_RUN const* run, DWRITE_GLYPH_RUN_DESCRIPTION const*, IUnknown*) override {
            float pen = x; face = run->fontFace; em = run->fontEmSize; for (UINT32 i = 0; i < run->glyphCount; i++) { xs.push_back(pen); glyphs.push_back(run->glyphIndices[i]); advs.push_back(run->glyphAdvances[i]); pen += run->glyphAdvances[i]; } return S_OK; }
        HRESULT __stdcall DrawUnderline(void*, FLOAT, FLOAT, DWRITE_UNDERLINE const*, IUnknown*) override { return S_OK; }
        HRESULT __stdcall GetCurrentTransform(void*, DWRITE_MATRIX* m) override { *m = {1, 0, 0, 1, 0, 0}; return S_OK; }
        HRESULT __stdcall GetPixelsPerDip(void*, FLOAT* p) override { *p = ppd; return S_OK; }
    } c; c.ppd = ppd; c.scale = ppd; c.fileFont = f.fileFont; c.wholePixel = false; c.rt = nullptr; c.brush = nullptr;
    ComPtr<IDWriteTextLayout> layout = layoutFor(f, false);
    DWRITE_LINE_METRICS lm; UINT32 n = 1; layout->GetLineMetrics(&lm, 1, &n);
    layout->Draw(nullptr, &c, X, BASE - lm.baseline);
    ComPtr<IDWriteRenderingParams> params; CHECK(dw->CreateRenderingParams(&params));
    auto target = [&](ComPtr<IWICBitmap>& bmp, ComPtr<ID2D1RenderTarget>& rt, ComPtr<ID2D1SolidColorBrush>& brush) {
        CHECK(wic->CreateBitmap(w, h, GUID_WICPixelFormat32bppBGR, WICBitmapCacheOnDemand, &bmp));
        D2D1_RENDER_TARGET_PROPERTIES props = D2D1::RenderTargetProperties(D2D1_RENDER_TARGET_TYPE_SOFTWARE, D2D1::PixelFormat(DXGI_FORMAT_B8G8R8A8_UNORM, D2D1_ALPHA_MODE_IGNORE), dpi, dpi);
        CHECK(d2d->CreateWicBitmapRenderTarget(bmp.Get(), props, &rt));
        rt->SetTextRenderingParams(params.Get()); rt->SetTextAntialiasMode(D2D1_TEXT_ANTIALIAS_MODE_GRAYSCALE);
        CHECK(rt->CreateSolidColorBrush(D2D1::ColorF(0, 0, 0), &brush));
        rt->BeginDraw(); rt->Clear(D2D1::ColorF(1, 1, 1));
    };
    auto pixels = [&](ComPtr<IWICBitmap>& bmp) { std::vector<uint8_t> px((size_t) w * h * 4); CHECK(bmp->CopyPixels(nullptr, w * 4, (UINT) px.size(), px.data())); std::vector<uint8_t> g((size_t) w * h); for (size_t i = 0; i < g.size(); i++) g[i] = px[i * 4 + 1]; return g; };
    // (a) the layout's run, as the sheets draw it
    ComPtr<IWICBitmap> bmpA; ComPtr<ID2D1RenderTarget> rtA; ComPtr<ID2D1SolidColorBrush> brA; target(bmpA, rtA, brA);
    Renderer rend; rend.rt = rtA.Get(); rend.brush = brA.Get(); rend.scale = ppd; rend.fileFont = f.fileFont; rend.wholePixel = false;
    CHECK(layout->Draw(nullptr, &rend, X, BASE - lm.baseline)); CHECK(rtA->EndDraw());
    std::vector<uint8_t> a = pixels(bmpA);
    // (b) one run per glyph at the layout's positions
    ComPtr<IWICBitmap> bmpB; ComPtr<ID2D1RenderTarget> rtB; ComPtr<ID2D1SolidColorBrush> brB; target(bmpB, rtB, brB);
    for (size_t i = 0; i < c.xs.size(); i++) {
        FLOAT adv = 0; DWRITE_GLYPH_OFFSET off = {0, 0}; DWRITE_GLYPH_RUN run = {}; run.fontFace = c.face; run.fontEmSize = c.em; run.glyphCount = 1; run.glyphIndices = &c.glyphs[i]; run.glyphAdvances = &adv; run.glyphOffsets = &off;
        rtB->DrawGlyphRun(D2D1::Point2F(c.xs[i], (float) BASE), &run, brB.Get(), DWRITE_MEASURING_MODE_NATURAL);
    }
    CHECK(rtB->EndDraw());
    std::vector<uint8_t> b = pixels(bmpB);
    diffStats(label, a, b);
    // which position did the run's glyph actually take? the glyph alone at the nearest grid point and
    // one step either side, matched within its band against the run's rendering
    {
        const float step = ppd >= 1.8f ? 0.5f : 0.25f;
        std::string taken;
        for (size_t i = 0; i < c.xs.size(); i++) {
            float xd = c.xs[i] * ppd; float nearest = std::round(xd / step) * step;
            int x0 = (int) xd - 1, x1 = (int) ((c.xs[i] + c.advs[i]) * ppd) - 1; if (x0 < 0) x0 = 0;
            const char* which = "?";
            for (int d = -2; d <= 2 && which[0] == '?'; d++) {
                float xt = nearest + d * step;
                ComPtr<IWICBitmap> bmpC; ComPtr<ID2D1RenderTarget> rtC; ComPtr<ID2D1SolidColorBrush> brC; target(bmpC, rtC, brC);
                FLOAT adv = 0; DWRITE_GLYPH_OFFSET off = {0, 0}; DWRITE_GLYPH_RUN run = {}; run.fontFace = c.face; run.fontEmSize = c.em; run.glyphCount = 1; run.glyphIndices = &c.glyphs[i]; run.glyphAdvances = &adv; run.glyphOffsets = &off;
                rtC->DrawGlyphRun(D2D1::Point2F(xt / ppd, (float) BASE), &run, brC.Get(), DWRITE_MEASURING_MODE_NATURAL); CHECK(rtC->EndDraw());
                std::vector<uint8_t> cpx = pixels(bmpC);
                int m = 0; for (int y = 0; y < h; y++) for (int x = x0; x < x1 && x < w; x++) m = std::max(m, std::abs((int) a[(size_t) y * w + x] - (int) cpx[(size_t) y * w + x]));
                if (m <= 2) { static char buf[8]; snprintf(buf, sizeof buf, "%+d", d); which = buf; }
            }
            char b2[48]; snprintf(b2, sizeof b2, " %.4f:%s", xd, which); taken += b2;
        }
        printf("    in-run position as grid steps from the nearest grid point (? = none of -2..+2 matches):%s\n", taken.c_str());
    }
    printf("    per glyph (device x of origin: max difference in its band):");
    for (size_t i = 0; i < c.xs.size(); i++) {
        int x0 = (int) (c.xs[i] * ppd), x1 = (int) ((c.xs[i] + c.advs[i]) * ppd); int m = 0;
        for (int y = 0; y < h; y++) for (int x = x0; x < x1 && x < w; x++) m = std::max(m, std::abs((int) a[(size_t) y * w + x] - (int) b[(size_t) y * w + x]));
        printf(" %.3f:%d", c.xs[i] * ppd, m);
    }
    printf("\n");
}

// --runrule <fonts-dir> <dpi>: how Direct2D positions the second glyph of a two-glyph run. 'l' at
// the run origin, a second 'l' at an advance stepping across two device pixels; the second glyph's
// columns are matched against the glyph alone at grid points, and the position it took is printed
// as its offset from the exact position (in device pixels).
static void runRuleProbe(const Face& f, const char* label, float dpi, float originDevice) {
    const float ppd = dpi / 96.0f; const int w = 64, h = 56; const float step = ppd >= 1.8f ? 0.5f : 0.25f;
    ComPtr<IDWriteFontFace> face = faceOf(f);
    ComPtr<IDWriteRenderingParams> params; CHECK(dw->CreateRenderingParams(&params));
    UINT32 cp = 'l'; UINT16 glyph; face->GetGlyphIndices(&cp, 1, &glyph);
    auto render = [&](float x0Device, float advanceDevice, bool two) {
        ComPtr<IWICBitmap> bmp; CHECK(wic->CreateBitmap(w, h, GUID_WICPixelFormat32bppBGR, WICBitmapCacheOnDemand, &bmp));
        D2D1_RENDER_TARGET_PROPERTIES props = D2D1::RenderTargetProperties(D2D1_RENDER_TARGET_TYPE_SOFTWARE, D2D1::PixelFormat(DXGI_FORMAT_B8G8R8A8_UNORM, D2D1_ALPHA_MODE_IGNORE), dpi, dpi);
        ComPtr<ID2D1RenderTarget> rt; CHECK(d2d->CreateWicBitmapRenderTarget(bmp.Get(), props, &rt));
        rt->SetTextRenderingParams(params.Get()); rt->SetTextAntialiasMode(D2D1_TEXT_ANTIALIAS_MODE_GRAYSCALE);
        ComPtr<ID2D1SolidColorBrush> brush; CHECK(rt->CreateSolidColorBrush(D2D1::ColorF(0, 0, 0), &brush));
        UINT16 glyphs[2] = {glyph, glyph}; FLOAT advs[2] = {advanceDevice / ppd, 0}; DWRITE_GLYPH_OFFSET offs[2] = {{0, 0}, {0, 0}};
        DWRITE_GLYPH_RUN run = {}; run.fontFace = face.Get(); run.fontEmSize = f.size; run.glyphCount = two ? 2 : 1; run.glyphIndices = glyphs; run.glyphAdvances = advs; run.glyphOffsets = offs;
        rt->BeginDraw(); rt->Clear(D2D1::ColorF(1, 1, 1)); rt->DrawGlyphRun(D2D1::Point2F(x0Device / ppd, 40 / ppd), &run, brush.Get(), DWRITE_MEASURING_MODE_NATURAL); CHECK(rt->EndDraw());
        std::vector<uint8_t> px((size_t) w * h * 4); CHECK(bmp->CopyPixels(nullptr, w * 4, (UINT) px.size(), px.data()));
        std::vector<uint8_t> g((size_t) w * h); for (size_t i = 0; i < g.size(); i++) g[i] = px[i * 4 + 1]; return g;
    };
    printf("  %s %.0f dpi, run origin at device x %.3f; second glyph's position minus its exact position, advance 24 + k/32 px:\n   ", label, dpi, originDevice);
    for (int k = 0; k < 64; k++) {
        float advance = 24 + (float) k / 32; float exact = originDevice + advance;
        std::vector<uint8_t> run = render(originDevice, advance, true);
        float nearest = std::round(exact / step) * step; const char* taken = "   ?  ";
        static char buf[16];
        for (int d = -2; d <= 2 && taken[3] == '?'; d++) {
            float xt = nearest + d * step;
            std::vector<uint8_t> one = render(xt, 0, false);
            int m = 0; for (int y = 0; y < h; y++) for (int x = 28; x < w; x++) m = std::max(m, std::abs((int) run[(size_t) y * w + x] - (int) one[(size_t) y * w + x]));
            if (m <= 2) { snprintf(buf, sizeof buf, "%+.3f", xt - exact); taken = buf; }
        }
        printf(" %s", taken);
        if (k % 16 == 15) printf("\n   ");
    }
    printf("\n");
}

// --prefix <font file> <text> <dpi>: the text as one glyph run (design advances, origin at device x 8)
// against one run per glyph at the same positions; for every glyph the largest difference in its
// advance band and, where it differs, the phase the run's glyph took (steps from nearest), measured
// by re-rendering the whole run with that glyph alone moved. Finds the glyphs Direct2D places
// differently inside a run.
static void prefixProbe(const Face& f, const wchar_t* text, float dpi) {
    const float ppd = dpi / 96.0f; const int w = 900, h = 56; const float step = ppd >= 1.8f ? 0.5f : 0.25f;
    ComPtr<IDWriteFontFace> face = faceOf(f);
    ComPtr<IDWriteRenderingParams> params; CHECK(dw->CreateRenderingParams(&params));
    size_t len = wcslen(text); std::vector<UINT32> cps(len); for (size_t i = 0; i < len; i++) cps[i] = text[i];
    std::vector<UINT16> glyphs(len); CHECK(face->GetGlyphIndices(cps.data(), (UINT32) len, glyphs.data()));
    DWRITE_FONT_METRICS fm; face->GetMetrics(&fm);
    std::vector<DWRITE_GLYPH_METRICS> gm(len); CHECK(face->GetDesignGlyphMetrics(glyphs.data(), (UINT32) len, gm.data(), FALSE));
    std::vector<FLOAT> advDip(len); std::vector<float> xDev(len); float pen = 8;
    for (size_t i = 0; i < len; i++) { advDip[i] = gm[i].advanceWidth * f.size / fm.designUnitsPerEm; xDev[i] = pen; pen += advDip[i] * ppd; }
    auto render = [&](const std::vector<float>& originsDev, bool oneRun, float dyDev = 0) {
        ComPtr<IWICBitmap> bmp; CHECK(wic->CreateBitmap(w, h, GUID_WICPixelFormat32bppBGR, WICBitmapCacheOnDemand, &bmp));
        D2D1_RENDER_TARGET_PROPERTIES props = D2D1::RenderTargetProperties(D2D1_RENDER_TARGET_TYPE_SOFTWARE, D2D1::PixelFormat(DXGI_FORMAT_B8G8R8A8_UNORM, D2D1_ALPHA_MODE_IGNORE), dpi, dpi);
        ComPtr<ID2D1RenderTarget> rt; CHECK(d2d->CreateWicBitmapRenderTarget(bmp.Get(), props, &rt));
        rt->SetTextRenderingParams(params.Get()); rt->SetTextAntialiasMode(D2D1_TEXT_ANTIALIAS_MODE_GRAYSCALE);
        ComPtr<ID2D1SolidColorBrush> brush; CHECK(rt->CreateSolidColorBrush(D2D1::ColorF(0, 0, 0), &brush));
        rt->BeginDraw(); rt->Clear(D2D1::ColorF(1, 1, 1));
        if (oneRun) {
            std::vector<DWRITE_GLYPH_OFFSET> offs(len, DWRITE_GLYPH_OFFSET{0, 0});
            DWRITE_GLYPH_RUN run = {}; run.fontFace = face.Get(); run.fontEmSize = f.size; run.glyphCount = (UINT32) len; run.glyphIndices = glyphs.data(); run.glyphAdvances = advDip.data(); run.glyphOffsets = offs.data();
            rt->DrawGlyphRun(D2D1::Point2F(8 / ppd, 40 / ppd), &run, brush.Get(), DWRITE_MEASURING_MODE_NATURAL);
        } else {
            for (size_t i = 0; i < len; i++) { FLOAT a0 = 0; DWRITE_GLYPH_OFFSET o0 = {0, 0}; DWRITE_GLYPH_RUN run = {}; run.fontFace = face.Get(); run.fontEmSize = f.size; run.glyphCount = 1; run.glyphIndices = &glyphs[i]; run.glyphAdvances = &a0; run.glyphOffsets = &o0;
                rt->DrawGlyphRun(D2D1::Point2F(originsDev[i] / ppd, (40 + dyDev) / ppd), &run, brush.Get(), DWRITE_MEASURING_MODE_NATURAL); }
        }
        CHECK(rt->EndDraw());
        std::vector<uint8_t> px((size_t) w * h * 4); CHECK(bmp->CopyPixels(nullptr, w * 4, (UINT) px.size(), px.data()));
        std::vector<uint8_t> g((size_t) w * h); for (size_t i = 0; i < g.size(); i++) g[i] = px[i * 4 + 1]; return g;
    };
    std::vector<uint8_t> a = render(xDev, true), b = render(xDev, false);
    auto bandMax = [&](const std::vector<uint8_t>& p, const std::vector<uint8_t>& q, size_t i) { int x0 = (int) xDev[i] - 1, x1 = (int) (xDev[i] + advDip[i] * ppd) + 1; int m = 0; for (int y = 0; y < h; y++) for (int x = std::max(0, x0); x < x1 && x < w; x++) m = std::max(m, std::abs((int) p[(size_t) y * w + x] - (int) q[(size_t) y * w + x])); return m; };
    printf("  %ls at %.0f dpi (char: device x, nearest-phase fraction, band max; run phase if different):\n   ", f.file.c_str() + f.file.find_last_of(L'/') + 1, dpi);
    for (size_t i = 0; i < len; i++) {
        int m = bandMax(a, b, i); float nearest = std::round(xDev[i] / step) * step;
        printf(" %lc:%.3f/%.2f:%d", text[i] == L' ' ? L'_' : text[i], xDev[i], nearest - std::floor(nearest), m);
        if (m > 8) {
            // the position the run's glyph took, searched in eighths of a pixel around the exact one
            int bestM = 999; float bestOff = 0, bestDy = 0;
            for (int dy = -4; dy <= 4; dy++) for (int d = -8; d <= 8; d++) { std::vector<float> moved = xDev; moved[i] = xDev[i] + d * 0.125f; std::vector<uint8_t> c = render(moved, false, dy * 0.125f); int mm = bandMax(a, c, i); if (mm < bestM) { bestM = mm; bestOff = d * 0.125f; bestDy = dy * 0.125f; } }
            float lsbPx = gm[i].leftSideBearing * f.size * ppd / fm.designUnitsPerEm;
            // are the differing pixels where a neighbouring glyph's own ink overlaps this glyph's?
            std::vector<float> none(len, -10000.0f);
            auto alone = [&](size_t g) { std::vector<float> o = none; o[g] = xDev[g]; return render(o, false); };
            std::vector<uint8_t> me = alone(i), prev = i > 0 ? alone(i - 1) : std::vector<uint8_t>((size_t) w * h, 255), next = i + 1 < len ? alone(i + 1) : std::vector<uint8_t>((size_t) w * h, 255);
            int inOverlap = 0, outside = 0; int x0 = (int) xDev[i] - 1, x1 = (int) (xDev[i] + advDip[i] * ppd) + 1;
            for (int y = 0; y < h; y++) for (int x = std::max(0, x0); x < x1 && x < w; x++) { size_t k = (size_t) y * w + x; if (std::abs((int) a[k] - (int) b[k]) > 8) { bool neighbourInk = prev[k] < 255 || next[k] < 255; bool myInk = me[k] < 255; if (neighbourInk && myInk) inOverlap++; else outside++; } }
            printf("->single at exact%+.3f dy%+.3f (max %d; lsb %.2f px); differing px: %d where own and neighbour ink overlap, %d elsewhere", bestOff, bestDy, bestM, lsbPx, inOverlap, outside);
        }
    }
    printf("\n");
}

// --runanalysis <font file> <text> <dpi>: DirectWrite alone, no Direct2D. The text as one glyph run
// through one glyph run analysis (DPI as a transform) against one analysis per glyph at the same
// positions: per glyph the largest coverage difference in its band. Says whether the run-internal
// difference is DirectWrite's rasterizer or Direct2D's.
static void runAnalysisProbe(const Face& f, const wchar_t* text, float dpi) {
    const float ppd = dpi / 96.0f; const int w = 900, h = 56;
    ComPtr<IDWriteFontFace> face = faceOf(f);
    ComPtr<IDWriteRenderingParams> params; CHECK(dw->CreateRenderingParams(&params));
    ComPtr<IDWriteFactory2> dw2; CHECK(dw.As(&dw2));
    size_t len = wcslen(text); std::vector<UINT32> cps(len); for (size_t i = 0; i < len; i++) cps[i] = text[i];
    std::vector<UINT16> glyphs(len); CHECK(face->GetGlyphIndices(cps.data(), (UINT32) len, glyphs.data()));
    DWRITE_FONT_METRICS fm; face->GetMetrics(&fm);
    std::vector<DWRITE_GLYPH_METRICS> gm(len); CHECK(face->GetDesignGlyphMetrics(glyphs.data(), (UINT32) len, gm.data(), FALSE));
    std::vector<FLOAT> advDip(len); std::vector<float> xDev(len); float pen = 8;
    for (size_t i = 0; i < len; i++) { advDip[i] = gm[i].advanceWidth * f.size / fm.designUnitsPerEm; xDev[i] = pen; pen += advDip[i] * ppd; }
    DWRITE_RENDERING_MODE mode; face->GetRecommendedRenderingMode(f.size, ppd, DWRITE_MEASURING_MODE_NATURAL, params.Get(), &mode);
    DWRITE_MATRIX tx = {ppd, 0, 0, ppd, 0, 0};
    auto analyse = [&](const DWRITE_GLYPH_RUN& run, float originXDev, std::vector<uint8_t>& cov) {
        ComPtr<IDWriteGlyphRunAnalysis> an; CHECK(dw2->CreateGlyphRunAnalysis(&run, &tx, mode, DWRITE_MEASURING_MODE_NATURAL, DWRITE_GRID_FIT_MODE_DEFAULT, DWRITE_TEXT_ANTIALIAS_MODE_GRAYSCALE, originXDev / ppd, 40 / ppd, &an));
        RECT b; CHECK(an->GetAlphaTextureBounds(DWRITE_TEXTURE_ALIASED_1x1, &b)); if (b.right <= b.left || b.bottom <= b.top) return;
        int bw = b.right - b.left, bh = b.bottom - b.top; std::vector<uint8_t> tex((size_t) bw * bh); CHECK(an->CreateAlphaTexture(DWRITE_TEXTURE_ALIASED_1x1, &b, tex.data(), (UINT32) tex.size()));
        for (int y = 0; y < bh; y++) for (int x = 0; x < bw; x++) { int px = b.left + x, py = b.top + y; if (px >= 0 && px < w && py >= 0 && py < h) cov[(size_t) py * w + px] = std::max(cov[(size_t) py * w + px], tex[(size_t) y * bw + x]); }
    };
    std::vector<uint8_t> a((size_t) w * h, 0), b((size_t) w * h, 0);
    std::vector<DWRITE_GLYPH_OFFSET> offs(len, DWRITE_GLYPH_OFFSET{0, 0});
    DWRITE_GLYPH_RUN run = {}; run.fontFace = face.Get(); run.fontEmSize = f.size; run.glyphCount = (UINT32) len; run.glyphIndices = glyphs.data(); run.glyphAdvances = advDip.data(); run.glyphOffsets = offs.data();
    analyse(run, 8, a);
    for (size_t i = 0; i < len; i++) { FLOAT a0 = 0; DWRITE_GLYPH_OFFSET o0 = {0, 0}; DWRITE_GLYPH_RUN one = {}; one.fontFace = face.Get(); one.fontEmSize = f.size; one.glyphCount = 1; one.glyphIndices = &glyphs[i]; one.glyphAdvances = &a0; one.glyphOffsets = &o0; analyse(one, xDev[i], b); }
    printf("  %ls at %.0f dpi, mode %d, DirectWrite analysis: whole run vs one per glyph (char: band max):\n   ", f.file.c_str() + f.file.find_last_of(L'/') + 1, dpi, (int) mode);
    int total = 0;
    for (size_t i = 0; i < len; i++) {
        int x0 = (int) xDev[i] - 1, x1 = (int) (xDev[i] + advDip[i] * ppd) + 1; int m = 0;
        for (int y = 0; y < h; y++) for (int x = std::max(0, x0); x < x1 && x < w; x++) m = std::max(m, std::abs((int) a[(size_t) y * w + x] - (int) b[(size_t) y * w + x]));
        printf(" %lc:%d", text[i] == L' ' ? L'_' : text[i], m); if (m > 8) total++;
    }
    printf("\n    glyphs differing by more than 8: %d\n", total);
}

// --pairgap <font file> <two chars> <dpi>: a two-glyph run with the second glyph at increasing
// advances against the two glyphs drawn one run each, through Direct2D: does the run-internal
// difference vanish once the glyphs' boxes no longer overlap?
static void pairGapProbe(const Face& f, const wchar_t* pair, float dpi) {
    const float ppd = dpi / 96.0f; const int w = 96, h = 56;
    ComPtr<IDWriteFontFace> face = faceOf(f);
    ComPtr<IDWriteRenderingParams> params; CHECK(dw->CreateRenderingParams(&params));
    UINT32 cps[2] = {(UINT32) pair[0], (UINT32) pair[1]}; UINT16 glyphs[2]; CHECK(face->GetGlyphIndices(cps, 2, glyphs));
    DWRITE_FONT_METRICS fm; face->GetMetrics(&fm);
    DWRITE_GLYPH_METRICS gm[2]; CHECK(face->GetDesignGlyphMetrics(glyphs, 2, gm, FALSE));
    float adv0 = gm[0].advanceWidth * f.size * ppd / fm.designUnitsPerEm;
    float right0 = (gm[0].leftSideBearing + (float) gm[0].advanceWidth - gm[0].leftSideBearing - gm[0].rightSideBearing) * f.size * ppd / fm.designUnitsPerEm;   // ink right edge of the first glyph from its origin
    float lsb1 = gm[1].leftSideBearing * f.size * ppd / fm.designUnitsPerEm;
    auto render = [&](float advanceDev, bool oneRun) {
        ComPtr<IWICBitmap> bmp; CHECK(wic->CreateBitmap(w, h, GUID_WICPixelFormat32bppBGR, WICBitmapCacheOnDemand, &bmp));
        D2D1_RENDER_TARGET_PROPERTIES props = D2D1::RenderTargetProperties(D2D1_RENDER_TARGET_TYPE_SOFTWARE, D2D1::PixelFormat(DXGI_FORMAT_B8G8R8A8_UNORM, D2D1_ALPHA_MODE_IGNORE), dpi, dpi);
        ComPtr<ID2D1RenderTarget> rt; CHECK(d2d->CreateWicBitmapRenderTarget(bmp.Get(), props, &rt));
        rt->SetTextRenderingParams(params.Get()); rt->SetTextAntialiasMode(D2D1_TEXT_ANTIALIAS_MODE_GRAYSCALE);
        ComPtr<ID2D1SolidColorBrush> brush; CHECK(rt->CreateSolidColorBrush(D2D1::ColorF(0, 0, 0), &brush));
        rt->BeginDraw(); rt->Clear(D2D1::ColorF(1, 1, 1));
        if (oneRun) {
            FLOAT advs[2] = {advanceDev / ppd, 0}; DWRITE_GLYPH_OFFSET offs[2] = {{0, 0}, {0, 0}};
            DWRITE_GLYPH_RUN run = {}; run.fontFace = face.Get(); run.fontEmSize = f.size; run.glyphCount = 2; run.glyphIndices = glyphs; run.glyphAdvances = advs; run.glyphOffsets = offs;
            rt->DrawGlyphRun(D2D1::Point2F(8 / ppd, 40 / ppd), &run, brush.Get(), DWRITE_MEASURING_MODE_NATURAL);
        } else {
            for (int i = 0; i < 2; i++) { FLOAT a0 = 0; DWRITE_GLYPH_OFFSET o0 = {0, 0}; DWRITE_GLYPH_RUN run = {}; run.fontFace = face.Get(); run.fontEmSize = f.size; run.glyphCount = 1; run.glyphIndices = &glyphs[i]; run.glyphAdvances = &a0; run.glyphOffsets = &o0;
                rt->DrawGlyphRun(D2D1::Point2F((8 + (i == 1 ? advanceDev : 0)) / ppd, 40 / ppd), &run, brush.Get(), DWRITE_MEASURING_MODE_NATURAL); }
        }
        CHECK(rt->EndDraw());
        std::vector<uint8_t> px((size_t) w * h * 4); CHECK(bmp->CopyPixels(nullptr, w * 4, (UINT) px.size(), px.data()));
        std::vector<uint8_t> g((size_t) w * h); for (size_t i = 0; i < g.size(); i++) g[i] = px[i * 4 + 1]; return g;
    };
    printf("  %ls '%lc%lc' at %.0f dpi: design advance %.2f px, first glyph's ink ends %.2f px right of its origin, second glyph's ink starts %.2f px right of its origin\n", f.file.c_str() + f.file.find_last_of(L'/') + 1, pair[0], pair[1], dpi, adv0, right0, lsb1);
    printf("    advance of the first glyph (px) : max difference run vs singles\n   ");
    for (float adv = adv0 - 4; adv <= adv0 + 12; adv += 0.5f) {
        std::vector<uint8_t> a = render(adv, true), b = render(adv, false); int m = 0;
        for (size_t i = 0; i < a.size(); i++) m = std::max(m, std::abs((int) a[i] - (int) b[i]));
        printf(" %.1f:%d", adv, m);
    }
    printf("\n");
}

// --simulations <font file>: DirectWrite's synthetic styles. A face of the file created with
// DWRITE_FONT_SIMULATIONS_OBLIQUE and with _BOLD against the plain face: the oblique shear (ink
// shift between the top and bottom quarters of a tall glyph over their distance), the advance
// growth of the bold simulation (design advances per glyph, in em) and the ink widening of a stem.
static void simulationsProbe(const wchar_t* path, float size, float dpi) {
    const float ppd = dpi / 96.0f;
    ComPtr<IDWriteFontFile> file; CHECK(dw->CreateFontFileReference(path, nullptr, &file));
    BOOL supported; DWRITE_FONT_FILE_TYPE fileType; DWRITE_FONT_FACE_TYPE faceType; UINT32 faces;
    CHECK(file->Analyze(&supported, &fileType, &faceType, &faces));
    IDWriteFontFile* files[1] = {file.Get()};
    ComPtr<IDWriteFontFace> plain, oblique, bold;
    CHECK(dw->CreateFontFace(faceType, 1, files, 0, DWRITE_FONT_SIMULATIONS_NONE, &plain));
    CHECK(dw->CreateFontFace(faceType, 1, files, 0, DWRITE_FONT_SIMULATIONS_OBLIQUE, &oblique));
    CHECK(dw->CreateFontFace(faceType, 1, files, 0, DWRITE_FONT_SIMULATIONS_BOLD, &bold));
    DWRITE_FONT_METRICS fm; plain->GetMetrics(&fm);
    // advances: design metrics of the plain and the bold-simulated face
    const wchar_t* text = L"Appearance & Behavior Hamburgefonts";
    size_t len = wcslen(text); std::vector<UINT32> cps(len); for (size_t i = 0; i < len; i++) cps[i] = text[i];
    std::vector<UINT16> glyphs(len); CHECK(plain->GetGlyphIndices(cps.data(), (UINT32) len, glyphs.data()));
    std::vector<DWRITE_GLYPH_METRICS> gmPlain(len), gmBold(len);
    CHECK(plain->GetDesignGlyphMetrics(glyphs.data(), (UINT32) len, gmPlain.data(), FALSE));
    CHECK(bold->GetDesignGlyphMetrics(glyphs.data(), (UINT32) len, gmBold.data(), FALSE));
    printf("  %ls, %u design units per em\n", path + std::wstring(path).find_last_of(L'\\') + 1, fm.designUnitsPerEm);
    printf("  bold simulation, advance growth per glyph in em (design metrics):");
    double minG = 1e9, maxG = -1e9;
    for (size_t i = 0; i < len; i++) { double g = (double) ((int) gmBold[i].advanceWidth - (int) gmPlain[i].advanceWidth) / fm.designUnitsPerEm; if (text[i] != L' ') { minG = std::min(minG, g); maxG = std::max(maxG, g); } if (i < 8) printf(" %lc:%.4f", text[i], g); }
    printf("  ... min %.4f max %.4f (space: %.4f) = 1/%.1f .. 1/%.1f em\n", minG, maxG, (double) ((int) gmBold[6].advanceWidth - (int) gmPlain[6].advanceWidth) / fm.designUnitsPerEm, 1 / maxG, 1 / minG);
    // GDI-compatible (hinted, pixel) metrics of the bold simulation at the size
    std::vector<DWRITE_GLYPH_METRICS> gdiPlain(len), gdiBold(len);
    CHECK(plain->GetGdiCompatibleGlyphMetrics(size, ppd, nullptr, FALSE, glyphs.data(), (UINT32) len, gdiPlain.data(), FALSE));
    CHECK(bold->GetGdiCompatibleGlyphMetrics(size, ppd, nullptr, FALSE, glyphs.data(), (UINT32) len, gdiBold.data(), FALSE));
    double sumPlain = 0, sumBold = 0; for (size_t i = 0; i < len; i++) { sumPlain += gdiPlain[i].advanceWidth; sumBold += gdiBold[i].advanceWidth; }
    printf("  bold simulation, GDI-compatible advances at %.0f px: line %.2f px plain, %.2f px bold (design-unit sums scaled)\n", size * ppd, sumPlain * size * ppd / fm.designUnitsPerEm, sumBold * size * ppd / fm.designUnitsPerEm);
    // rasters: shear of 'H' oblique, stem width of 'l' bold, through Direct2D grayscale at the size
    auto render = [&](IDWriteFontFace* face, wchar_t ch, int w, int h, float x, float baseline) {
        UINT32 cp = ch; UINT16 glyph; face->GetGlyphIndices(&cp, 1, &glyph);
        FLOAT adv = 0; DWRITE_GLYPH_OFFSET off = {0, 0}; DWRITE_GLYPH_RUN run = {}; run.fontFace = face; run.fontEmSize = size; run.glyphCount = 1; run.glyphIndices = &glyph; run.glyphAdvances = &adv; run.glyphOffsets = &off;
        ComPtr<IWICBitmap> bmp; CHECK(wic->CreateBitmap(w, h, GUID_WICPixelFormat32bppBGR, WICBitmapCacheOnDemand, &bmp));
        D2D1_RENDER_TARGET_PROPERTIES props = D2D1::RenderTargetProperties(D2D1_RENDER_TARGET_TYPE_SOFTWARE, D2D1::PixelFormat(DXGI_FORMAT_B8G8R8A8_UNORM, D2D1_ALPHA_MODE_IGNORE), dpi, dpi);
        ComPtr<ID2D1RenderTarget> rt; CHECK(d2d->CreateWicBitmapRenderTarget(bmp.Get(), props, &rt));
        ComPtr<IDWriteRenderingParams> params; CHECK(dw->CreateRenderingParams(&params));
        rt->SetTextRenderingParams(params.Get()); rt->SetTextAntialiasMode(D2D1_TEXT_ANTIALIAS_MODE_GRAYSCALE);
        ComPtr<ID2D1SolidColorBrush> brush; CHECK(rt->CreateSolidColorBrush(D2D1::ColorF(0, 0, 0), &brush));
        rt->BeginDraw(); rt->Clear(D2D1::ColorF(1, 1, 1)); rt->DrawGlyphRun(D2D1::Point2F(x / ppd, baseline / ppd), &run, brush.Get(), DWRITE_MEASURING_MODE_NATURAL); CHECK(rt->EndDraw());
        std::vector<uint8_t> px((size_t) w * h * 4); CHECK(bmp->CopyPixels(nullptr, w * 4, (UINT) px.size(), px.data()));
        std::vector<uint8_t> g((size_t) w * h); for (size_t i = 0; i < g.size(); i++) g[i] = px[i * 4 + 1]; return g;
    };
    const int w = 128, h = 128;
    auto slant = [&](const std::vector<uint8_t>& g) {
        int top = -1, bottom = -1; for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) if (g[(size_t) y * w + x] < 128) { if (top < 0) top = y; bottom = y; }
        int q = std::max(1, (bottom - top + 1) / 4);
        auto centroid = [&](int y0, int y1) { double sum = 0, wt = 0; for (int y = y0; y < y1; y++) for (int x = 0; x < w; x++) { double ink = 255 - g[(size_t) y * w + x]; sum += ink * (x + 0.5); wt += ink; } return wt == 0 ? 0 : sum / wt; };
        double dist = (bottom - q + 1 + bottom + 1) / 2.0 - (top + top + q) / 2.0;
        return (centroid(top, top + q) - centroid(bottom - q + 1, bottom + 1)) / dist;
    };
    auto ink = [&](const std::vector<uint8_t>& g) { double t = 0; for (uint8_t v : g) t += 255 - v; return t / 255; };
    std::vector<uint8_t> hPlain = render(plain.Get(), L'H', w, h, 24, 96), hOblique = render(oblique.Get(), L'H', w, h, 24, 96);
    std::vector<uint8_t> lPlain = render(plain.Get(), L'l', w, h, 24, 96), lBold = render(bold.Get(), L'l', w, h, 24, 96);
    printf("  oblique simulation: shear %.4f (plain %.4f) at %.0f px; tan 12 deg = 0.2126, tan 20 deg = 0.3640, 1/3 = 0.3333\n", slant(hOblique), slant(hPlain), size * ppd);
    printf("  bold simulation: ink of 'l' %.1f px^2 plain, %.1f bold: +%.2f px per row over %.0f rows = +%.4f em of stem width\n", ink(lPlain), ink(lBold), (ink(lBold) - ink(lPlain)) / (0.73 * size * ppd), 0.73 * size * ppd, (ink(lBold) - ink(lPlain)) / (0.73 * size * ppd) / (size * ppd));
}

// Collects the outline points of a glyph run (GetGlyphRunOutline): the exact geometry of a face,
// simulated or not, without rasterization.
struct PointSink : ID2D1SimplifiedGeometrySink {
    std::vector<D2D1_POINT_2F> points; ULONG refs = 1;
    HRESULT __stdcall QueryInterface(REFIID riid, void** out) override { if (riid == __uuidof(IUnknown) || riid == __uuidof(ID2D1SimplifiedGeometrySink)) { *out = this; AddRef(); return S_OK; } *out = nullptr; return E_NOINTERFACE; }
    ULONG __stdcall AddRef() override { return ++refs; }
    ULONG __stdcall Release() override { return --refs; }
    void __stdcall SetFillMode(D2D1_FILL_MODE) override {}
    void __stdcall SetSegmentFlags(D2D1_PATH_SEGMENT) override {}
    void __stdcall BeginFigure(D2D1_POINT_2F p, D2D1_FIGURE_BEGIN) override { points.push_back(p); }
    void __stdcall AddLines(const D2D1_POINT_2F* p, UINT32 n) override { for (UINT32 i = 0; i < n; i++) points.push_back(p[i]); }
    void __stdcall AddBeziers(const D2D1_BEZIER_SEGMENT* b, UINT32 n) override { for (UINT32 i = 0; i < n; i++) points.push_back(b[i].point3); }
    void __stdcall EndFigure(D2D1_FIGURE_END) override {}
    HRESULT __stdcall Close() override { return S_OK; }
};

// The simulations' exact geometry: the oblique shear from the outline of 'H' (x shift of the top
// points against the bottom points over their height), the bold outline's widening of 'l' (left and
// right stem edges) and its advance, in em.
static void simulationGeometry(const wchar_t* path) {
    ComPtr<IDWriteFontFile> file; CHECK(dw->CreateFontFileReference(path, nullptr, &file));
    BOOL supported; DWRITE_FONT_FILE_TYPE fileType; DWRITE_FONT_FACE_TYPE faceType; UINT32 faces;
    CHECK(file->Analyze(&supported, &fileType, &faceType, &faces));
    IDWriteFontFile* files[1] = {file.Get()};
    ComPtr<IDWriteFontFace> plain, oblique, bold;
    CHECK(dw->CreateFontFace(faceType, 1, files, 0, DWRITE_FONT_SIMULATIONS_NONE, &plain));
    CHECK(dw->CreateFontFace(faceType, 1, files, 0, DWRITE_FONT_SIMULATIONS_OBLIQUE, &oblique));
    CHECK(dw->CreateFontFace(faceType, 1, files, 0, DWRITE_FONT_SIMULATIONS_BOLD, &bold));
    const float em = 1000;   // em size 1000: coordinates in thousandths of an em
    auto outline = [&](IDWriteFontFace* face, wchar_t ch) {
        UINT32 cp = ch; UINT16 glyph; face->GetGlyphIndices(&cp, 1, &glyph);
        FLOAT adv = 0; PointSink sink; CHECK(face->GetGlyphRunOutline(em, &glyph, &adv, nullptr, 1, FALSE, FALSE, &sink));
        return sink.points;
    };
    std::vector<D2D1_POINT_2F> hPlain = outline(plain.Get(), L'H'), hOblique = outline(oblique.Get(), L'H');
    // the shear: every point moves by shear * (-y) (y up is negative in this space)
    double shear = 0; int n = 0;
    for (size_t i = 0; i < hPlain.size() && i < hOblique.size(); i++) { if (std::fabs(hPlain[i].y) > 100) { shear += (hOblique[i].x - hPlain[i].x) / -hPlain[i].y; n++; } }
    std::vector<D2D1_POINT_2F> lPlain = outline(plain.Get(), L'l'), lBold = outline(bold.Get(), L'l');
    auto extent = [](const std::vector<D2D1_POINT_2F>& pts) { float lo = 1e9, hi = -1e9; for (auto& p : pts) { lo = std::min(lo, p.x); hi = std::max(hi, p.x); } return std::make_pair(lo, hi); };
    auto ep = extent(lPlain), eb = extent(lBold);
    UINT32 cp = 'l'; UINT16 glyph; plain->GetGlyphIndices(&cp, 1, &glyph);
    DWRITE_GLYPH_METRICS gp, gb; plain->GetDesignGlyphMetrics(&glyph, 1, &gp, FALSE); bold->GetDesignGlyphMetrics(&glyph, 1, &gb, FALSE);
    DWRITE_FONT_METRICS fm; plain->GetMetrics(&fm);
    printf("  %ls: oblique shear %.5f (%d points; 1/3 = 0.33333, tan 20 = 0.36397); bold 'l' stem %.1f -> %.1f thousandths em (left %.1f -> %.1f, right %.1f -> %.1f), advance +%.4f em\n",
           path + std::wstring(path).find_last_of(L'\\') + 1, n ? shear / n : 0, n, eb.first == 1e9 ? 0 : ep.second - ep.first, eb.second - eb.first, ep.first, eb.first, ep.second, eb.second,
           (double) ((int) gb.advanceWidth - (int) gp.advanceWidth) / fm.designUnitsPerEm);
}

// --------------------------------------------------------------------------------------------------------

int wmain(int argc, wchar_t** argv) {
    CHECK(CoInitializeEx(nullptr, COINIT_MULTITHREADED));
    CHECK(DWriteCreateFactory(DWRITE_FACTORY_TYPE_SHARED, __uuidof(IDWriteFactory3), (IUnknown**) dw.GetAddressOf()));
    CHECK(D2D1CreateFactory(D2D1_FACTORY_TYPE_SINGLE_THREADED, d2d.GetAddressOf()));
    CHECK(CoCreateInstance(CLSID_WICImagingFactory, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&wic)));
    if (argc == 2 && wcscmp(argv[1], L"--probe") == 0) { probe(); return 0; }
    if (argc == 3 && wcscmp(argv[1], L"--grid") == 0) {   // dwref --grid <fonts-dir>
        std::wstring fonts = argv[2];
        printf("Horizontal placement grid, glyph 'l' at 13 px (12 for Consolas), 64 offsets across one pixel:\n");
        gridProbe(fileFace(fonts + L"/Inter-Regular.otf", 13), "Inter", 13);
        gridProbe(fileFace(fonts + L"/JetBrainsMono-Regular.ttf", 13), "JBMono", 13);
        gridProbe(systemFace(L"Segoe UI", DWRITE_FONT_WEIGHT_NORMAL, DWRITE_FONT_STYLE_NORMAL, 13), "Segoe UI", 13);
        gridProbe(systemFace(L"Consolas", DWRITE_FONT_WEIGHT_NORMAL, DWRITE_FONT_STYLE_NORMAL, 12), "Consolas", 12);
        printf("At 2x (192 dpi, em 13 DIP = 26 px):\n");
        gridProbe2x(fileFace(fonts + L"/Inter-Regular.otf", 13), "Inter", 13);
        gridProbe2x(systemFace(L"Segoe UI", DWRITE_FONT_WEIGHT_NORMAL, DWRITE_FONT_STYLE_NORMAL, 13), "Segoe UI", 13);
        gridProbe2x(systemFace(L"Consolas", DWRITE_FONT_WEIGHT_NORMAL, DWRITE_FONT_STYLE_NORMAL, 12), "Consolas", 12);
        return 0;
    }
    if (argc == 3 && wcscmp(argv[1], L"--scale-lcd") == 0) {   // dwref --scale-lcd <fonts-dir>
        std::wstring fonts = argv[2];
        printf("ClearType: glyph 'l', origin stepping 1/32 device px over five device pixels, and channel levels of the sheet text\n");
        for (float dpi : {96.0f, 120.0f, 144.0f, 172.0f, 174.0f, 192.0f}) {
            scaleProbe(fileFace(fonts + L"/Inter-Regular.otf", 13), "Inter", dpi, true);
            scaleProbe(systemFace(L"Segoe UI", DWRITE_FONT_WEIGHT_NORMAL, DWRITE_FONT_STYLE_NORMAL, 13), "Segoe UI", dpi, true);
        }
        for (float dpi : {96.0f, 144.0f, 192.0f}) {
            scaleLevels(fileFace(fonts + L"/Inter-Regular.otf", 13), "Inter", dpi, true);
            scaleLevels(systemFace(L"Segoe UI", DWRITE_FONT_WEIGHT_NORMAL, DWRITE_FONT_STYLE_NORMAL, 13), "Segoe UI", dpi, true);
        }
        return 0;
    }
    if (argc == 3 && wcscmp(argv[1], L"--scale") == 0) {   // dwref --scale <fonts-dir>
        std::wstring fonts = argv[2];
        printf("DirectWrite at non-integer display scales: glyph 'l', origin stepping 1/32 device px over five device pixels\n");
        for (float dpi : {96.0f, 120.0f, 144.0f, 168.0f, 192.0f, 240.0f, 288.0f, 384.0f}) {
            scaleProbe(fileFace(fonts + L"/Inter-Regular.otf", 13), "Inter", dpi);
            scaleProbe(systemFace(L"Segoe UI", DWRITE_FONT_WEIGHT_NORMAL, DWRITE_FONT_STYLE_NORMAL, 13), "Segoe UI", dpi);
        }
        printf("Small text at 2x and above (the natural mode at a high display scale):\n");
        scaleProbe(systemFace(L"Segoe UI", DWRITE_FONT_WEIGHT_NORMAL, DWRITE_FONT_STYLE_NORMAL, 8), "Segoe UI 8", 192);
        scaleProbe(systemFace(L"Segoe UI", DWRITE_FONT_WEIGHT_NORMAL, DWRITE_FONT_STYLE_NORMAL, 6), "Segoe UI 6", 192);
        scaleProbe(systemFace(L"Consolas", DWRITE_FONT_WEIGHT_NORMAL, DWRITE_FONT_STYLE_NORMAL, 6), "Consolas 6", 192);
        scaleProbe(systemFace(L"Segoe UI", DWRITE_FONT_WEIGHT_NORMAL, DWRITE_FONT_STYLE_NORMAL, 5), "Segoe UI 5", 288);
        scaleLevels(systemFace(L"Segoe UI", DWRITE_FONT_WEIGHT_NORMAL, DWRITE_FONT_STYLE_NORMAL, 6), "Segoe UI 6", 192);
        printf("Where does the symmetric mode switch to the coarse grid? Inter 13 at 168..180 dpi, and small Inter at 192 dpi:\n");
        for (float dpi : {168.0f, 170.0f, 172.0f, 174.0f, 176.0f, 178.0f, 180.0f}) scaleProbe(fileFace(fonts + L"/Inter-Regular.otf", 13), "Inter", dpi);
        for (float size : {6.0f, 8.0f, 10.0f, 12.0f}) { char lbl[32]; snprintf(lbl, sizeof lbl, "Inter %.0f", size); scaleProbe(fileFace(fonts + L"/Inter-Regular.otf", size), lbl, 192); scaleLevels(fileFace(fonts + L"/Inter-Regular.otf", size), lbl, 192); }
        for (float size : {6.0f, 8.0f}) { char lbl[32]; snprintf(lbl, sizeof lbl, "Inter %.0f", size); scaleProbe(fileFace(fonts + L"/Inter-Regular.otf", size), lbl, 288); }
        printf("Just below and at 2x:\n");
        scaleProbe(fileFace(fonts + L"/Inter-Regular.otf", 13), "Inter", 180);
        scaleProbe(fileFace(fonts + L"/Inter-Regular.otf", 13), "Inter", 191);
        scaleLevels(fileFace(fonts + L"/Inter-Regular.otf", 13), "Inter", 191);
        printf("The same pixel size at 96 dpi (is the grid a matter of the DPI or of the pixel size?):\n");
        scaleProbe(fileFace(fonts + L"/Inter-Regular.otf", 26), "Inter 26", 96);
        scaleProbe(fileFace(fonts + L"/Inter-Regular.otf", 19.5f), "Inter 19.5", 96);
        scaleProbe(fileFace(fonts + L"/Inter-Regular.otf", 39), "Inter 39", 96);
        scaleProbe(fileFace(fonts + L"/Inter-Regular.otf", 52), "Inter 52", 96);
        scaleLevels(fileFace(fonts + L"/Inter-Regular.otf", 26), "Inter 26", 96);
        scaleLevels(fileFace(fonts + L"/Inter-Regular.otf", 39), "Inter 39", 96);
        scaleLevels(fileFace(fonts + L"/Inter-Regular.otf", 52), "Inter 52", 96);
        printf("Gray levels of the sheet text (grayscale, fractional, black on white):\n");
        for (float dpi : {96.0f, 120.0f, 144.0f, 168.0f, 192.0f, 240.0f, 288.0f, 384.0f}) {
            scaleLevels(fileFace(fonts + L"/Inter-Regular.otf", 13), "Inter", dpi);
            scaleLevels(systemFace(L"Segoe UI", DWRITE_FONT_WEIGHT_NORMAL, DWRITE_FONT_STYLE_NORMAL, 13), "Segoe UI", dpi);
        }
        return 0;
    }
    if (argc == 3 && wcscmp(argv[1], L"--simulations") == 0) {   // dwref --simulations <fonts-dir>
        std::wstring fonts = argv[2];
        printf("DirectWrite font simulations (oblique, bold) against the plain face:\n");
        for (float dpi : {96.0f, 192.0f}) { printf(" at %.0f dpi, em 13:\n", dpi); simulationsProbe((fonts + L"\\Inter-Regular.otf").c_str(), 13, dpi); simulationsProbe((fonts + L"\\JetBrainsMono-Regular.ttf").c_str(), 13, dpi); }
        printf(" at 96 dpi, em 48:\n"); simulationsProbe((fonts + L"\\Inter-Regular.otf").c_str(), 48, 96);
        printf(" exact geometry (outlines):\n");
        simulationGeometry((fonts + L"\\Inter-Regular.otf").c_str());
        simulationGeometry((fonts + L"\\JetBrainsMono-Regular.ttf").c_str());
        simulationGeometry((fonts + L"\\Inter-SemiBold.otf").c_str());
        return 0;
    }
    if (argc == 5 && wcscmp(argv[1], L"--pairgap") == 0) {   // dwref --pairgap <font file> <two chars> <dpi>
        pairGapProbe(fileFace(argv[2], 13), argv[3], (float) _wtof(argv[4]));
        return 0;
    }
    if (argc == 5 && wcscmp(argv[1], L"--runanalysis") == 0) {   // dwref --runanalysis <font file> <text> <dpi>
        runAnalysisProbe(fileFace(argv[2], 13), argv[3], (float) _wtof(argv[4]));
        return 0;
    }
    if (argc == 5 && wcscmp(argv[1], L"--prefix") == 0) {   // dwref --prefix <font file> <text> <dpi>
        prefixProbe(fileFace(argv[2], 13), argv[3], (float) _wtof(argv[4]));
        return 0;
    }
    if (argc == 4 && wcscmp(argv[1], L"--runrule") == 0) {   // dwref --runrule <fonts-dir> <dpi>
        std::wstring fonts = argv[2]; float dpi = (float) _wtof(argv[3]);
        for (float origin : {8.0f, 8.25f, 8.375f, 8.5f}) runRuleProbe(fileFace(fonts + L"/Inter-Regular.otf", 13), "Inter", dpi, origin);
        return 0;
    }
    if (argc == 4 && wcscmp(argv[1], L"--runs") == 0) {   // dwref --runs <fonts-dir> <dpi>
        std::wstring fonts = argv[2]; float dpi = (float) _wtof(argv[3]);
        printf("The layout's run against one run per glyph at the layout's positions, %.0f dpi:\n", dpi);
        runsProbe(fileFace(fonts + L"/Inter-Italic.otf", 13), "Inter Italic", dpi);
        runsProbe(fileFace(fonts + L"/Inter-Regular.otf", 13), "Inter Regular", dpi);
        runsProbe(fileFace(fonts + L"/Inter-SemiBold.otf", 13), "Inter SemiBold", dpi);
        runsProbe(fileFace(fonts + L"/JetBrainsMono-BoldItalic.ttf", 13), "JetBrains Mono Bold Italic", dpi);
        runsProbe(fileFace(fonts + L"/JetBrainsMono-Regular.ttf", 13), "JetBrains Mono Regular", dpi);
        return 0;
    }
    if (argc == 6 && wcscmp(argv[1], L"--glyph") == 0) {   // dwref --glyph <font file> <char> <device x> <dpi>
        glyphDump(fileFace(argv[2], 13), argv[3][0], (float) _wtof(argv[4]), (float) _wtof(argv[5]));
        return 0;
    }
    if (argc == 3 && wcscmp(argv[1], L"--vgrid") == 0) {   // dwref --vgrid <fonts-dir>
        std::wstring fonts = argv[2];
        printf("Vertical placement: glyph 'o', baseline stepping 1/32 device px over four device pixels (from device y 30)\n");
        for (float dpi : {96.0f, 120.0f, 144.0f, 192.0f}) {
            verticalProbe(fileFace(fonts + L"/Inter-Regular.otf", 13), "Inter", dpi);
            verticalProbe(systemFace(L"Segoe UI", DWRITE_FONT_WEIGHT_NORMAL, DWRITE_FONT_STYLE_NORMAL, 13), "Segoe UI", dpi);
        }
        return 0;
    }
    if (argc == 3 && wcscmp(argv[1], L"--positions") == 0) {   // dwref --positions <fonts-dir>
        std::wstring fonts = argv[2];
        positionsProbe(fileFace(fonts + L"/Inter-Italic.otf", 13), "Inter Italic", 192);
        positionsProbe(fileFace(fonts + L"/Inter-SemiBold.otf", 13), "Inter SemiBold", 192);
        positionsProbe(fileFace(fonts + L"/JetBrainsMono-BoldItalic.ttf", 13), "JetBrains Mono Bold Italic", 192);
        positionsProbe(fileFace(fonts + L"/Inter-Regular.otf", 13), "Inter Regular", 192);
        return 0;
    }
    if (argc == 3 && wcscmp(argv[1], L"--dpi") == 0) {   // dwref --dpi <fonts-dir>
        std::wstring fonts = argv[2];
        printf("The sheet text, grayscale, fractional: the 2x raster at 192 dpi (em 13 DIP) against 96 dpi with the em doubled (26 px):\n");
        dpiProbe(fileFace(fonts + L"/Inter-Regular.otf", 13), "Inter");
        dpiProbe(fileFace(fonts + L"/JetBrainsMono-Regular.ttf", 13), "JBMono");
        dpiProbe(systemFace(L"Segoe UI", DWRITE_FONT_WEIGHT_NORMAL, DWRITE_FONT_STYLE_NORMAL, 13), "Segoe UI");
        dpiProbe(systemFace(L"Consolas", DWRITE_FONT_WEIGHT_NORMAL, DWRITE_FONT_STYLE_NORMAL, 12), "Consolas");
        printf("With the glyph origins fixed on half device pixels (one run per glyph), so only the rasterizer differs:\n");
        dpiProbeSnapped(fileFace(fonts + L"/Inter-Regular.otf", 13), "Inter");
        dpiProbeSnapped(fileFace(fonts + L"/JetBrainsMono-Regular.ttf", 13), "JBMono");
        dpiProbeSnapped(systemFace(L"Segoe UI", DWRITE_FONT_WEIGHT_NORMAL, DWRITE_FONT_STYLE_NORMAL, 13), "Segoe UI");
        dpiProbeSnapped(systemFace(L"Consolas", DWRITE_FONT_WEIGHT_NORMAL, DWRITE_FONT_STYLE_NORMAL, 12), "Consolas");
        return 0;
    }
    for (int i = argc - 1; i >= 4; i--) {
        if (wcscmp(argv[i], L"--natural") == 0) { gNaturalWholePixel = true; argc--; }
        else if (wcscmp(argv[i], L"--files") == 0) { gSystemFromFiles = true; argc--; }
        else if (wcscmp(argv[i], L"--colours") == 0) { gColours = true; argc--; }
    }
    if (argc != 4) { fprintf(stderr, "usage: dwref <out-dir> <rows.txt> <fonts-dir> [--natural] | dwref --probe\n"); return 2; }
    std::wstring out = argv[1], rowsFile = argv[2], fonts = argv[3];

    std::vector<Row> rows;
    auto add = [&](const char* family, std::vector<Face> faces) {
        for (int i = 0; i < 4; i++) rows.push_back({std::string(family) + " " + STYLES[i], faces[i], false});
        rows.push_back({std::string(family) + " " + STYLES[4], faces[0], true});
    };
    add("Inter 13", {fileFace(fonts + L"/Inter-Regular.otf", 13), fileFace(fonts + L"/Inter-Italic.otf", 13), fileFace(fonts + L"/Inter-SemiBold.otf", 13), fileFace(fonts + L"/Inter-SemiBoldItalic.otf", 13)});
    add("JetBrains Mono 13", {fileFace(fonts + L"/JetBrainsMono-Regular.ttf", 13), fileFace(fonts + L"/JetBrainsMono-Italic.ttf", 13), fileFace(fonts + L"/JetBrainsMono-Bold.ttf", 13), fileFace(fonts + L"/JetBrainsMono-BoldItalic.ttf", 13)});
    if (gSystemFromFiles) {
        // --files: the system families from their font files (what the JDK's scalers open), not the
        // system font collection's family resolution, to tell face resolution from rendering
        std::wstring win = L"C:/Windows/Fonts/";
        add("Segoe UI 13", {fileFace(win + L"segoeui.ttf", 13), fileFace(win + L"segoeuii.ttf", 13), fileFace(win + L"segoeuib.ttf", 13), fileFace(win + L"segoeuiz.ttf", 13)});
        add("Consolas 12", {fileFace(win + L"consola.ttf", 12), fileFace(win + L"consolai.ttf", 12), fileFace(win + L"consolab.ttf", 12), fileFace(win + L"consolaz.ttf", 12)});
    } else {
    add("Segoe UI 13", {systemFace(L"Segoe UI", DWRITE_FONT_WEIGHT_NORMAL, DWRITE_FONT_STYLE_NORMAL, 13), systemFace(L"Segoe UI", DWRITE_FONT_WEIGHT_NORMAL, DWRITE_FONT_STYLE_ITALIC, 13),
                        systemFace(L"Segoe UI", DWRITE_FONT_WEIGHT_BOLD, DWRITE_FONT_STYLE_NORMAL, 13), systemFace(L"Segoe UI", DWRITE_FONT_WEIGHT_BOLD, DWRITE_FONT_STYLE_ITALIC, 13)});
    add("Consolas 12", {systemFace(L"Consolas", DWRITE_FONT_WEIGHT_NORMAL, DWRITE_FONT_STYLE_NORMAL, 12), systemFace(L"Consolas", DWRITE_FONT_WEIGHT_NORMAL, DWRITE_FONT_STYLE_ITALIC, 12),
                        systemFace(L"Consolas", DWRITE_FONT_WEIGHT_BOLD, DWRITE_FONT_STYLE_NORMAL, 12), systemFace(L"Consolas", DWRITE_FONT_WEIGHT_BOLD, DWRITE_FONT_STYLE_ITALIC, 12)});
    }
    // GDI sees the file fonts as private resources under their own family names
    for (const Row& r : rows) if (r.face.fileFont) AddFontResourceExW(r.face.file.c_str(), FR_PRIVATE, nullptr);

    int count = 0;
    if (gColours) {   // dwref <out-dir> <rows.txt> <fonts-dir> --colours: the ClearType rows in saturated colours
        // filed into the sheet tree as directwrite/lcd/fractional/<scale>/color-on-<paper>/<colour>.png (hand-over 2026-10-26c)
        struct C { const wchar_t* name; int r, g, b; } colours[] = {{L"red", 247, 84, 100}, {L"blue", 86, 168, 245}, {L"orange", 207, 142, 109}, {L"green", 106, 171, 115}, {L"purple", 199, 125, 186}};
        for (const C& c : colours) for (float scale : {1.0f, 1.25f, 1.5f, 2.0f}) for (const char* paper : {"black", "white"}) {
            bool dark = strcmp(paper, "black") == 0;
            D2D1_COLOR_F ink = D2D1::ColorF(c.r / 255.f, c.g / 255.f, c.b / 255.f), bg = dark ? D2D1::ColorF(0, 0, 0) : D2D1::ColorF(1, 1, 1);
            std::wstring dir = out + L"/directwrite/lcd/fractional/" + widen(scaleName(scale).c_str()) + L"x/color-on-" + widen(paper);
            mkdirs(dir);
            ComPtr<IWICBitmap> bmp = directWriteSheetColour(rows, "lcd", true, scale, ink, bg);
            writePng(bmp.Get(), dir + L"/" + c.name + L".png");
            count++;
        }
        FILE* f = _wfopen(rowsFile.c_str(), L"wb"); for (const Row& r : rows) fprintf(f, "%s\n", r.label.c_str()); fclose(f);
        printf("wrote %d colour sheets of %zu rows under %ls\n", count, rows.size(), out.c_str());
        return 0;
    }
    for (const char* aa : {"gray", "lcd", "aliased"}) for (const char* positioning : {"whole-pixel", "fractional"}) for (float scale : {1.0f, 1.25f, 1.5f, 2.0f}) for (const char* paper : {"black-on-white", "white-on-black"}) {
        bool fractional = strcmp(positioning, "fractional") == 0, dark = strcmp(paper, "white-on-black") == 0;
        for (const char* renderer : {"directwrite", "gdi"}) {
            std::wstring dir = out + L"/" + widen(renderer) + L"/" + widen(aa) + L"/" + widen(positioning) + L"/" + widen(scaleName(scale).c_str()) + L"x";
            mkdirs(dir);
            ComPtr<IWICBitmap> bmp = strcmp(renderer, "directwrite") == 0 ? directWriteSheet(rows, aa, fractional, scale, dark) : gdiSheet(rows, aa, fractional, scale, dark);
            writePng(bmp.Get(), dir + L"/" + widen(paper) + L".png");
            count++;
        }
    }
    FILE* f = _wfopen(rowsFile.c_str(), L"wb");
    for (const Row& r : rows) fprintf(f, "%s\n", r.label.c_str());
    fclose(f);
    printf("wrote %d sheets of %zu rows under %ls\n", count, rows.size(), out.c_str());
    return 0;
}
