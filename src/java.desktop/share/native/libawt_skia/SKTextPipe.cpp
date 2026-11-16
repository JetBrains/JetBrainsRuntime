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
// Glyph lists (SKTextPipe.h): through the glyph caches (SKGlyphCache.h), grayscale, color
// and LCD glyphs, and the LCD compositing in two passes.
// All functions run on the Skia queue flusher thread.

#include "SKTextPipe.h"
#include "SKContext.h"
#include "SKDirectEntry.h"
#include "SKGlyphCache.h"
#include "SKGraphicsConfig.h"
#include "SKNeverDestroyed.h"
#include "SKPaints.h"
#include "SKPendingBatches.h"
#include "SKQuadBatch.h"

#include "Trace.h"

#include "jlong.h"
#include "fontscalerdefs.h"
// ReSharper disable once CppUnusedIncludeDirective - the sun_font_StrikeCache_PIXEL_FORMAT_* constants are used here
#include "sun_font_StrikeCache.h"
#include "sun_java2d_skia_SKTextPipe.h"

#include "include/core/SkColorFilter.h"
#include "include/core/SkData.h"
#include "include/effects/SkRuntimeEffect.h"
#include "include/core/SkMatrix.h"
#include "include/core/SkPixmap.h"
#include "include/core/SkRect.h"
#include "include/core/SkSamplingOptions.h"
#include "include/core/SkSpan.h"
#include "include/core/SkTileMode.h"

#include <algorithm>
#include <array>
// ReSharper disable once CppUnusedIncludeDirective - powf, lroundf and floorf are used here
#include <cmath>
// ReSharper disable once CppUnusedIncludeDirective - memcpy is used here
#include <cstring>
#include <optional>
#include <vector>

#ifndef SUBPIXEL_TIE_NUDGE
// A glyph's sub-pixel variant: the nearest of res steps per pixel, a half-step tie rounding up; the
// nudge of 1/4096 px keeps float rounding noise from deciding a tie either way. The rule belongs in
// fontscalerdefs.h for every pipe (a separate change for the loops, Metal and OpenGL); until
// fontscalerdefs.h defines it, the backend carries its own copy.
#define SUBPIXEL_TIE_NUDGE 0.000244140625f
#endif

namespace {

// The LCD contrast arrives as the text gamma times 100 (KEY_TEXT_LCD_CONTRAST, 100 to 250).
constexpr float LCD_CONTRAST_SCALE = 100.0f;
// A color channel as the shaders' uniforms and the coverage tables take it: 0..255 scaled to 0..1.
constexpr float CHANNEL_MAX = 255.0f;
// The coverage correction table has one entry per 8-bit coverage value.
constexpr int LCD_TABLE_ENTRIES = 256;
// A texel's center in a texture coordinate (an integer index plus this), for the table's nearest sampling.
constexpr float TEXEL_CENTER = 0.5f;
// The JDK's LCD sub-pixel positioning shifts a glyph's coverage by thirds of a pixel (channels).
constexpr int LCD_SUBPIXEL_THIRDS = 3;
// The software loops round a sub-pixel positioned LCD glyph to the nearest third of a pixel: from the glyph
// list's position, which is BufferedTextPipe's origin too (GlyphList.gposx, half a pixel past the text's), they
// take px + 0.1666667 - 0.5 (setupLCDBlitVector, DrawGlyphList.c).
constexpr float LCD_SUBPIXEL_ORIGIN = 0.5f - 0.1666667f;
// A glyph image entry that ends the list, besides null (MTLTextRenderer checks the same two).
constexpr jlong NO_GLYPH_IMAGE = -1;

// Glyph images come from Java's font rasterizer (sun.font); Skia only composites them.
// LCD text without a destination read: the blend  Cr = Cd * (1 - cov) + Cs * cov
// per channel as two fixed-function passes, kModulate with (1 - cov) and an
// addition of Cs * cov, which is how DirectWrite composites ClearType (dual-source blending does both
// at once; Graphite uses it for its own LCD text only, no mesh or runtime effect has a second
// output). The destination's gamma cannot be
// applied without reading it; instead the coverage is corrected so that the
// linear blend reproduces the gamma blend on a white and on a black
// background, mixed by the channel's text value (dark text is mostly read on
// light backgrounds): exact for black on white and white on black, up to about
// fifteen levels off at glyph edges for light text on dark mid-tones. The
// correction depends on the coverage and the channel's text value only, so it is
// a 256x256 table, a row per 8-bit channel value and a column per 8-bit coverage,
// built once per contrast and sampled as a texture (lcdTable): each channel reads
// its row, which the text color gives as a uniform, so a new text color costs a
// uniform, not a table (a table per color cost about 40 us each, mostly powf, and
// a frame of many colors such as syntax highlighting paid it per color beyond the
// tables kept). The coverage arrives in the atlas' RGB
// channels. The addition is kSrcOver with a source alpha of 0 (Cs * cov + Cd), not kPlus: Graphite
// blends kPlus in the shader wherever the destination is cheap to read (Vulkan's input attachment,
// Metal's framebuffer fetch; ContextUtils.cpp, CanUseHardwareBlending), which costs a destination read
// per draw and loses the second pass of overlap layers on Vulkan; kSrcOver without
// coverage is fixed-function everywhere, and the unorm destination clamps the sum as kPlus does.
// Both passes leave the destination's alpha (1) as it is over the whole quad and ignore the
// paint's alpha, which holds because LCD text is only requested for an opaque
// color on an opaque destination (SKSurfaceData.canRenderLCDText).

// The linear weight that reproduces the gamma blend on white and on black (the
// result's distance from the background over the text color's), mixed by the
// channel's text value; textGamma is textValue to the power gamma, inverseGamma 1 / gamma.
float lcdCorrectedCoverage(const float coverage, const float textValue, const float textGamma,
                           const float inverseGamma) {
    const float onWhite = 1.0f - powf(1.0f + (textGamma - 1.0f) * coverage, inverseGamma);
    const float onBlack = powf(textGamma * coverage, inverseGamma);
    const float weight = ((1.0f - textValue) * onWhite + textValue * onBlack)
                         / ((1.0f - textValue) * (1.0f - textValue) + textValue * textValue);
    return std::clamp(weight, 0.0f, 1.0f);
}

// The table of a contrast (a row per channel value, a column per coverage), uploaded to the current context.
sk_sp<SkImage> makeLCDTable(const jint contrast) {
    const float gamma = static_cast<float>(contrast) / LCD_CONTRAST_SCALE;
    const float inverseGamma = 1.0f / gamma;
    std::vector<uint8_t> pixels(static_cast<size_t>(LCD_TABLE_ENTRIES) * LCD_TABLE_ENTRIES);
    for (int row = 0; row < LCD_TABLE_ENTRIES; row++) {
        // the channel as the text color's SkColor4f holds it
        const float textValue = SkColor4f::FromColor(SkColorSetRGB(row, row, row)).fR;
        const float textGamma = powf(textValue, gamma);
        uint8_t *rowPixels = pixels.data() + static_cast<size_t>(row) * LCD_TABLE_ENTRIES;
        for (int entry = 0; entry < LCD_TABLE_ENTRIES; entry++) {
            const float coverage = static_cast<float>(entry) / CHANNEL_MAX;
            rowPixels[entry] = static_cast<uint8_t>(
                    lroundf(lcdCorrectedCoverage(coverage, textValue, textGamma, inverseGamma) * CHANNEL_MAX));
        }
    }
    return SKContext::current().uploadPixmap(SkPixmap(SkImageInfo::Make(LCD_TABLE_ENTRIES, LCD_TABLE_ENTRIES, kGray_8_SkColorType,
                                                                        kOpaque_SkAlphaType),
                                                      pixels.data(), LCD_TABLE_ENTRIES)).image;
}

// The context's table of a contrast, made on first use (a failed upload is retried by the next glyph list).
sk_sp<SkImage> lcdTable(const jint contrast) {
    SKGraphicsConfig *graphicsConfig = SKContext::current().graphicsConfig();
    sk_sp<SkImage> table = graphicsConfig->findLCDTable(contrast);
    if (!table) {
        table = makeLCDTable(contrast);
        if (!table) {
            static bool traced = false;   // once; every glyph list tries again
            if (!traced) {
                traced = true;
                J2dRlsTraceLn(J2D_TRACE_WARNING,
                              "SKTextPipe: the LCD coverage table cannot be uploaded; LCD glyphs are drawn in grayscale meanwhile");
            }
        }
        graphicsConfig->storeLCDTable(contrast, table);
    }
    return table;
}

// The LCD passes without their shader (the SkSL did not compile, or the table's upload failed; each traced once):
// the glyphs are drawn in grayscale from the atlas' green channel, the coverage of the pixel's middle sub-pixel,
// rather than as the solid boxes the color paint would leave under the passes' blend modes. The first pass keeps
// the destination (white under kModulate); the second covers it with the text color at that coverage.
sk_sp<SkShader> lcdFallbackPassShader(const sk_sp<SkShader> &coverage, const SkPaint &paint, const SKQuadBatchPass pass) {
    if (pass == SKQuadBatchPass::First) {
        return SkShaders::Color(SK_ColorWHITE);
    }
    const SkColor4f text = SkColor4f::FromColor(paint.getColor());
    // unpremultiplied RGBA, row-major: the text color's channels as constants, the green coverage as the alpha
    const std::array<float, 20> textAtGreenCoverage = {
        0, 0, 0, 0, text.fR,
        0, 0, 0, 0, text.fG,
        0, 0, 0, 0, text.fB,
        0, 1, 0, 0, 0,
    };
    return coverage->makeWithColorFilter(SkColorFilters::Matrix(textAtGreenCoverage.data()));
}

sk_sp<SkShader> lcdPassShader(const sk_sp<SkShader> &coverage, const SkPaint &paint, const jint contrast,
                              const SKQuadBatchPass pass) {
    static std::array<const SkRuntimeEffect *, 2> effects = { nullptr, nullptr };   // the first and the second pass
    static bool compiled = false;
    if (!compiled) {
        compiled = true;
        // rows: the table's row of each channel of the text color (the channel value, at the texel's center);
        // the column: the channel's coverage as an 8-bit value, at the texel's center (CHANNEL_MAX, TEXEL_CENTER)
        const SkString common = SkStringPrintf(R"(
            const half CHANNEL_MAX = %f;
            const half TEXEL_CENTER = %f;
            uniform shader atlas;
            uniform shader table;
            uniform half3 src;
            uniform float3 rows;
            half3 coverage(float2 xy) {
                half3 atlasCoverage = atlas.eval(xy).rgb;
                return half3(table.eval(float2(atlasCoverage.r * CHANNEL_MAX + TEXEL_CENTER, rows.r)).r,
                             table.eval(float2(atlasCoverage.g * CHANNEL_MAX + TEXEL_CENTER, rows.g)).r,
                             table.eval(float2(atlasCoverage.b * CHANNEL_MAX + TEXEL_CENTER, rows.b)).r);
            }
        )", CHANNEL_MAX, TEXEL_CENTER);
        for (size_t passIndex = 0; passIndex < effects.size(); passIndex++) {
            constexpr std::array<const char *, 2> mains = {
                "half4 main(float2 xy) { return half4(1 - coverage(xy), 1); }",
                "half4 main(float2 xy) { return half4(src * coverage(xy), 0); }",   // alpha 0: kSrcOver adds
            };
            auto [effect, errorText] = SkRuntimeEffect::MakeForShader(SkStringPrintf("%s%s", common.c_str(),
                                                                                     mains[passIndex]));
            if (!effect) {
                J2dRlsTraceLn(J2D_TRACE_ERROR, "SKTextPipe: LCD two-pass shader does not compile, LCD glyphs are drawn in grayscale: %s",
                              errorText.c_str());
                break;
            }
            effects[passIndex] = effect.release();
        }
    }
    if (effects[1] == nullptr) {
        return lcdFallbackPassShader(coverage, paint, pass);
    }
    const sk_sp<SkImage> table = lcdTable(contrast);
    if (!table) {
        return lcdFallbackPassShader(coverage, paint, pass);
    }
    const SkColor color = paint.getColor();
    const SkColor4f textColor = SkColor4f::FromColor(color);
    // the shader's `src` and `rows`, in declaration order
    const std::array uniforms = {
        textColor.fR, textColor.fG, textColor.fB,
        SkColorGetR(color) + TEXEL_CENTER, SkColorGetG(color) + TEXEL_CENTER, SkColorGetB(color) + TEXEL_CENTER,
    };
    SkRuntimeEffect::ChildPtr children[2] = {
        SkRuntimeEffect::ChildPtr(coverage),
        SkRuntimeEffect::ChildPtr(table->makeShader(SkTileMode::kClamp, SkTileMode::kClamp,
                                                    SkSamplingOptions(SkFilterMode::kNearest))),
    };
    return effects[pass == SKQuadBatchPass::Second ? 1 : 0]->makeShader(
            SkData::MakeWithCopy(uniforms.data(), uniforms.size() * sizeof(float)), SkSpan(children, 2));
}

// The two passes of the LCD glyph batches: the
// batch's paint keys the color, the key the contrast of the glyph list being drawn (setContrast).
class SKLCDTwoPass final : public SKQuadBatchTwoPass {
public:
    [[nodiscard]] sk_sp<SkShader> passShader(const SKQuadBatchPass pass, const sk_sp<SkShader> &atlas,
                                             const SkPaint &paint) const override {
        return lcdPassShader(atlas, paint, m_contrast, pass);
    }
    // kModulate, then kSrcOver: the second shader's alpha is 0, an addition (above)
    [[nodiscard]] SkBlendMode passBlend(const SKQuadBatchPass pass) const override {
        return pass == SKQuadBatchPass::Second ? SkBlendMode::kSrcOver : SkBlendMode::kModulate;
    }
    [[nodiscard]] int passKey() const override { return m_contrast; }

    [[nodiscard]] jint contrast() const { return m_contrast; }
    void setContrast(const jint contrast) { m_contrast = contrast; }

private:
    jint m_contrast = 0;
};

SKLCDTwoPass gLCDTwoPass;

// The two passes of grayscale glyphs under a rule that copies the color (Src, Clear) on a translucent
// destination: the result is the color by the coverage, dst * (1 - coverage) + color * coverage, as Java2D's
// general loops apply coverage to every rule (AlphaMacros.h) and as Skia does for its own antialiasing. The
// coverage is the source's alpha here, so the rule itself would overwrite the glyph's whole cell; kDstOut by the
// coverage, then kPlus of the color times the coverage, gives that exactly for any coverage. The batch's paint
// is the opaque color, so that the first pass's alpha is the coverage; the key is the color's alpha, which the
// second pass applies.
class SKCoverageCopyTwoPass final : public SKQuadBatchTwoPass {
public:
    [[nodiscard]] sk_sp<SkShader> passShader(const SKQuadBatchPass pass, const sk_sp<SkShader> &atlas,
                                             const SkPaint &paint) const override {
        if (pass == SKQuadBatchPass::First) {
            return atlas;
        }
        const auto [red, green, blue, alpha] = SkColor4f::FromColor(SkColorSetA(paint.getColor(), m_alpha));
        // unpremultiplied RGBA, row-major: the color's channels as constants, its alpha times the coverage
        const std::array<float, 20> colorAtCoverage = {
            0, 0, 0, 0, red,
            0, 0, 0, 0, green,
            0, 0, 0, 0, blue,
            0, 0, 0, alpha, 0,
        };
        return atlas->makeWithColorFilter(SkColorFilters::Matrix(colorAtCoverage.data()));
    }
    [[nodiscard]] SkBlendMode passBlend(const SKQuadBatchPass pass) const override {
        return pass == SKQuadBatchPass::Second ? SkBlendMode::kPlus : SkBlendMode::kDstOut;
    }
    [[nodiscard]] int passKey() const override { return static_cast<int>(m_alpha); }

    void setAlpha(const U8CPU alpha) { m_alpha = alpha; }

private:
    U8CPU m_alpha = UINT8_MAX;
};

SKCoverageCopyTwoPass gCoverageCopyTwoPass;

// Whether `paint` (SKPaints::coveragePaint) copies the color, which it does on translucent destinations only
// (SKCoverageCopyTwoPass).
bool copiesColor(const SkPaint &paint) {
    const std::optional<SkBlendMode> blendMode = paint.asBlendMode();
    return blendMode == SkBlendMode::kSrc || blendMode == SkBlendMode::kClear;
}

// The two passes over a coverage image (device coordinates) within `bounds`,
// with the batch's blend modes.
void drawLCDPasses(SkCanvas *destination, const sk_sp<SkShader> &coverage, const SkRect &bounds, const SkPaint &paint,
                   const jint contrast) {
    SkPaint passPaint = paint;
    passPaint.setShader(lcdPassShader(coverage, paint, contrast, SKQuadBatchPass::First));
    passPaint.setBlendMode(gLCDTwoPass.passBlend(SKQuadBatchPass::First));
    destination->drawRect(bounds, passPaint);
    passPaint.setShader(lcdPassShader(coverage, paint, contrast, SKQuadBatchPass::Second));
    passPaint.setBlendMode(gLCDTwoPass.passBlend(SKQuadBatchPass::Second));
    destination->drawRect(bounds, passPaint);
}

// An uncached LCD glyph image: in two passes with the image shader standing in
// for the atlas, positioned at (x, y).
void drawLCDImage(const sk_sp<SkImage> &coverage, const float x, const float y, const SkPaint &paint,
                  const jint contrast) {
    const SkRect rect = SkRect::MakeXYWH(x, y, static_cast<float>(coverage->width()),
                                         static_cast<float>(coverage->height()));
    const SkMatrix at = SkMatrix::Translate(x, y);
    const sk_sp<SkShader> image = coverage->makeShader(SkTileMode::kClamp, SkTileMode::kClamp,
                                                       SkSamplingOptions(SkFilterMode::kNearest), &at);
    drawLCDPasses(SKContext::current().canvas(), image, rect, paint, contrast);
}

// The bucket's cache for a glyph image (SKGlyphCache::bucketFor), created on first use;
// nullptr if the image exceeds the largest cell or the creation failed.
SKGlyphCache *glyphCache(const bool lcd, const GlyphInfo *glyph) {
    const int bucket = SKGlyphCache::bucketFor(glyph);
    if (bucket == SK_GLYPH_NO_BUCKET) {
        return nullptr;
    }
    SKGraphicsConfig *graphicsConfig = SKContext::current().graphicsConfig();
    if (graphicsConfig == nullptr) {
        // never: a destination with a canvas (beginDraw) has its context
        J2dRlsTraceLn(J2D_TRACE_ERROR, "SKTextPipe: never: a glyph list without a context");
        return nullptr;
    }
    return graphicsConfig->glyphCache(lcd, bucket);
}

// The glyph (sub)image's first byte: the sub-pixel variants follow each other, one image each.
const unsigned char *glyphImageOf(const GlyphInfo *glyph, const jint subimage) {
    return glyph->image + static_cast<size_t>(glyph->rowBytes) * glyph->height * subimage;
}

// Glyphs too large for a cache cell, or LCD glyphs shifted by a third of a
// pixel, are uploaded and drawn individually (MTLTextRenderer's NoCache paths).
void drawGlyphNoCache(const GlyphInfo *glyph, const jint subimage, const float x, const float y, const SkPaint &paint) {
    SKContext::current().flushAllBatches();    // drawn through the canvas, not batched: every pending batch first, of any kind
    SKGlyphCache::count(SK_GLYPHS_UNCACHED);
    const SkPixmap pixmap(SkImageInfo::MakeA8(glyph->width, glyph->height), glyphImageOf(glyph, subimage),
                          glyph->rowBytes);
    const sk_sp<SkImage> coverage = SKContext::current().uploadPixmap(pixmap).image;
    if (!coverage) {
        return;
    }
    SkCanvas *canvas = SKContext::current().canvas();
    constexpr SkSamplingOptions nearest(SkFilterMode::kNearest);
    if (copiesColor(paint)) {
        // SKCoverageCopyTwoPass's passes, with the image as the coverage
        SkPaint clearPaint;
        clearPaint.setBlendMode(gCoverageCopyTwoPass.passBlend(SKQuadBatchPass::First));
        canvas->drawImage(coverage, x, y, nearest, &clearPaint);
        SkPaint colorPaint = paint;
        colorPaint.setBlendMode(gCoverageCopyTwoPass.passBlend(SKQuadBatchPass::Second));
        canvas->drawImage(coverage, x, y, nearest, &colorPaint);
        return;
    }
    canvas->drawImage(coverage, x, y, nearest, &paint);
}

void drawLCDGlyphNoCache(const GlyphInfo *glyph, const jint subimage, const jint rowBytesOffset,
                         const jboolean rgbOrder, const float x, const float y, const SkPaint &paint) {
    SKContext::current().flushAllBatches();    // drawn through the canvas, not batched: every pending batch first, of any kind
    SKGlyphCache::count(SK_GLYPHS_UNCACHED);
    const int width = glyph->width, height = glyph->height;
    std::vector<unsigned char> rgba(static_cast<size_t>(width) * height * SK_RGBA_BYTES_PER_PIXEL);
    const unsigned char *image = glyphImageOf(glyph, subimage);
    // The shift reads rowBytesOffset bytes beyond a row's coverage, into the next row's leading padding (no
    // coverage); the last row has no next one and is expanded from a copy padded with zeros instead.
    const size_t rowCoverageBytes = static_cast<size_t>(width) * SK_LCD_COVERAGE_BYTES_PER_PIXEL;
    std::vector<unsigned char> lastRow(rowCoverageBytes, 0);
    for (int row = 0; row < height; row++) {
        const unsigned char *coverage = image + static_cast<size_t>(row) * glyph->rowBytes + rowBytesOffset;
        if (row == height - 1) {
            memcpy(lastRow.data(), coverage, rowCoverageBytes - static_cast<size_t>(rowBytesOffset));
            coverage = lastRow.data();
        }
        SKGlyphCache::expandLCDRow(coverage, rgba.data() + static_cast<size_t>(row) * width * SK_RGBA_BYTES_PER_PIXEL,
                                   width, rgbOrder);
    }
    const SkPixmap pixmap(SkImageInfo::Make(width, height, kRGBA_8888_SkColorType, kOpaque_SkAlphaType), rgba.data(),
                          static_cast<size_t>(width) * SK_RGBA_BYTES_PER_PIXEL);
    if (const sk_sp<SkImage> coverage = SKContext::current().uploadPixmap(pixmap).image) {
        drawLCDImage(coverage, x, y, paint, gLCDTwoPass.contrast());
    }
}

// With the glyph's alpha as its coverage (SKPaints::colorGlyphPaints).
void drawColorGlyph(const GlyphInfo *glyph, const float x, const float y) {
    SKContext::current().flushAllBatches();    // drawn through the canvas, not batched: every pending batch first, of any kind
    const SkPixmap pixmap(SkImageInfo::Make(glyph->width, glyph->height, kBGRA_8888_SkColorType, kPremul_SkAlphaType),
                          glyph->image, glyph->rowBytes);
    if (const sk_sp<SkImage> image = SKContext::current().uploadPixmap(pixmap).image) {
        const auto [first, second] = SKPaints::colorGlyphPaints();
        constexpr SkSamplingOptions nearest(SkFilterMode::kNearest);
        SKContext::current().canvas()->drawImage(image, x, y, nearest, &first);
        if (second) {
            SKContext::current().canvas()->drawImage(image, x, y, nearest, &*second);
        }
    }
}

// Sub-pixel strikes render rx*ry variants of each glyph; pick the one for the
// fractional position (MTLTextRenderer's ADJUST_SUBPIXEL_GLYPH_POSITION).
// The pipes' shared variant rule (fontscalerdefs.h), as an expression.
float adjustSubpixel(const float coordinate, const int resolution) {
    return resolution > 1 ? coordinate + 0.5f / static_cast<float>(resolution) - 0.5f + SUBPIXEL_TIE_NUDGE : coordinate;
}

// The two kinds of cached glyphs; a switch between them draws the pending batches first.
enum class GlyphKind { Grayscale, LCD };

// The previous glyph list's paint, LCD contrast and the kind of the bucket begun last,
// see SKTextPipe::drawGlyphList. The destructor traces, as it never runs (gLastGlyphList;
// SKExitWhilePaintingTest).
struct SKLastGlyphList {
    SkPaint paint;
    jint contrast = -1;
    std::optional<GlyphKind> kind;

    ~SKLastGlyphList() { J2dRlsTraceLn(J2D_TRACE_INFO, "SKTextPipe: releasing the last glyph list"); }
};

// Never destroyed (SKNeverDestroyed.h): the paint may hold an image of a Graphite context, which is not
// released at process exit either.
SKNeverDestroyed<SKLastGlyphList> gLastGlyphList;

} // namespace

// The paint may hold a shader of the context's recorder (a texture paint's image). Java sends only color
// paints to the text pipe today (SKSurfaceData.validatePipe), so none does; the reset keeps the disposal's
// guarantee independent of that gating.
void SKTextPipe::forgetPaints() {
    gLastGlyphList->paint = SkPaint();
    gLastGlyphList->contrast = -1;
    gLastGlyphList->kind.reset();
}

void SKTextPipe::drawGlyphList(const jint numGlyphs, const jboolean usePositions, const jboolean subPixPos,
                               const jboolean rgbOrder, const jint lcdContrast,
                               jfloat glyphListOrigX, jfloat glyphListOrigY,
                               const jlong *images, const jfloat *positions) {
    if (!SKContext::current().beginDraw(SKBatchKind::Glyphs)) {
        return;
    }
    SKGraphicsConfig *graphicsConfig = SKContext::current().graphicsConfig();
    SkPaint paint = SKPaints::coveragePaint();
    // The buckets' caches begun for this list, grayscale and LCD. The buckets' batches
    // stay open side by side (glyphs of one paint commute under the blends used), but a
    // switch between grayscale and LCD glyphs draws the pending batches first.
    // Across lists the buckets' batches commute only for the same paint (and contrast,
    // and kind): the bucket order (16 ... 128) is not the lists' order, so a list of
    // another paint or kind draws the earlier lists' batches of the other buckets before
    // its first glyph is batched (one compare per list; a bucket's own batch keeps its
    // quads in order, colors merged or not). The own bucket's batch is kept, and a grayscale
    // list merges into it (SKQuadBatch merges colors): another bucket the list begins afterward
    // would be drawn before it, in bucket order, under the earlier lists' glyphs it holds, so
    // the merged batch is drawn first then (one more draw per list that spans buckets right
    // after a color change).
    SkPaint &lastPaint = gLastGlyphList->paint;
    jint &lastContrast = gLastGlyphList->contrast;
    std::optional<GlyphKind> &lastKind = gLastGlyphList->kind;
    bool separate = paint != lastPaint || lcdContrast != lastContrast;
    if (separate) {
        lastPaint = paint;
        lastContrast = lcdContrast;
    }
    // The bucket, and kind, whose kept batch this list merged into; none yet.
    int mergedBucket = -1;
    auto mergedKind = GlyphKind::Grayscale;
    // The list's first glyph of a kind in `bucket`: the earlier lists' batches that may not
    // commute with this list are drawn first, then the bucket's batch begins for the list's paint.
    auto beginBucket = [&](SKGlyphCache *cache, const GlyphKind kind, const int bucket, const SkPaint &bucketPaint,
                           const SKQuadBatchTwoPass *passes) {
        if (mergedBucket != -1 && (bucket != mergedBucket || kind != mergedKind)) {
            if (SKGlyphCache *merged = graphicsConfig->glyphCaches(mergedKind == GlyphKind::LCD).caches[mergedBucket].get()) {
                merged->flush();
            }
            mergedBucket = -1;
        }
        const bool fromEarlierLists = separate || lastKind != kind;
        if (fromEarlierLists) {
            for (int other = 0; other < SK_GLYPH_BUCKETS; other++) {
                if (other != bucket || kind != GlyphKind::Grayscale) {
                    if (SKGlyphCache *grayscaleCache = graphicsConfig->glyphCaches(false).caches[other].get()) {
                        grayscaleCache->flush();
                    }
                }
                if (other != bucket || kind != GlyphKind::LCD) {
                    if (SKGlyphCache *lcdCache = graphicsConfig->glyphCaches(true).caches[other].get()) {
                        lcdCache->flush();
                    }
                }
            }
            separate = false;
            lastKind = kind;
        }
        cache->begin(SKContext::current().canvas(), bucketPaint, passes);
        if (fromEarlierLists && cache->pending()) {   // the earlier lists' sprites stayed: this list merged into them
            mergedBucket = bucket;
            mergedKind = kind;
        }
    };
    // the grayscale glyphs' batch paint and passes (SKCoverageCopyTwoPass)
    SkPaint grayscalePaint = paint;
    const SKQuadBatchTwoPass *grayscalePasses = nullptr;
    if (copiesColor(paint)) {
        grayscalePaint = SkPaint();
        grayscalePaint.setColor(SkColorSetA(paint.getColor(), UINT8_MAX));
        gCoverageCopyTwoPass.setAlpha(SkColorGetA(paint.getColor()));
        grayscalePasses = &gCoverageCopyTwoPass;
    }
    std::array<bool, SK_GLYPH_BUCKETS> grayscaleBegun = {}, lcdBegun = {};
    bool anyGrayscale = false, anyLCD = false;
    SkPaint lcdPaint;
    bool lcdPaintReady = false;

    // Glyphs of one list are drawn in order: an immediate draw (uncached
    // glyph, color glyph) or a switch between grayscale and LCD glyphs first
    // draws whatever is batched.
    auto flushBatches = [&] {
        SKContext::current().flushGlyphBatches();
        grayscaleBegun.fill(false);
        lcdBegun.fill(false);
        anyGrayscale = false;
        anyLCD = false;
        mergedBucket = -1;
    };

    for (jint index = 0; index < numGlyphs; index++) {
        if (images[index] == 0 || images[index] == NO_GLYPH_IMAGE) {
            break;
        }
        // not pointer to const: a cache records the glyph's cell in it (SKGlyphCache::draw)
        auto *glyph = static_cast<GlyphInfo *>(jlong_to_ptr(images[index]));
        float glyphX, glyphY;
        if (usePositions) {
            glyphX = glyphListOrigX + positions[2 * index] + glyph->topLeftX;
            glyphY = glyphListOrigY + positions[2 * index + 1] + glyph->topLeftY;
        } else {
            glyphX = glyphListOrigX + glyph->topLeftX;
            glyphY = glyphListOrigY + glyph->topLeftY;
            glyphListOrigX += glyph->advanceX;
            glyphListOrigY += glyph->advanceY;
        }
        const int resolutionX = glyph->subpixelResolutionX, resolutionY = glyph->subpixelResolutionY;
        glyphX = adjustSubpixel(glyphX, resolutionX);
        glyphY = adjustSubpixel(glyphY, resolutionY);
        float floorX = floorf(glyphX);
        const float floorY = floorf(glyphY);
        jint subimage = 0;
        if (!((resolutionX == 1 && resolutionY == 1) || resolutionX <= 0 || resolutionY <= 0)) {
            subimage = static_cast<int>((glyphX - floorX) * static_cast<float>(resolutionX))
                       + static_cast<int>((glyphY - floorY) * static_cast<float>(resolutionY)) * resolutionX;
        }
        if (glyph->image == nullptr || glyph->width == 0 || glyph->height == 0) {
            continue;
        }

        if (glyph->format == sun_font_StrikeCache_PIXEL_FORMAT_GREYSCALE) {
            if (anyLCD) {
                flushBatches();
            }
            if (SKGlyphCache *grayscaleCache = glyphCache(false, glyph); grayscaleCache != nullptr) {
                if (const int bucket = SKGlyphCache::bucketFor(glyph); !grayscaleBegun[bucket]) {
                    beginBucket(grayscaleCache, GlyphKind::Grayscale, bucket, grayscalePaint, grayscalePasses);
                    grayscaleBegun[bucket] = true;
                    anyGrayscale = true;
                }
                if (grayscaleCache->draw(glyph, subimage, false, floorX, floorY)) {
                    continue;
                }
            }
            flushBatches();
            drawGlyphNoCache(glyph, subimage, floorX, floorY, paint);
        } else if (glyph->format == sun_font_StrikeCache_PIXEL_FORMAT_BGRA) {
            flushBatches();
            drawColorGlyph(glyph, floorX, floorY);
        } else {
            if (anyGrayscale) {
                flushBatches();
            }
            // LCD: the three coverage channels are blended per channel against
            // the destination in two passes without reading it; the paint
            // carries the color, which keys the batch.
            if (!lcdPaintReady) {
                gLCDTwoPass.setContrast(lcdContrast);
                lcdPaint.setColor(paint.getColor());   // the list's color as coveragePaint sets it (opaque under Clear)
                lcdPaintReady = true;
            }
            // the scaler's own variants (the platform's placement grid) take precedence over
            // the JDK's channel shift by thirds
            jint rowBytesOffset = 0;
            float lcdY = floorY;
            if (subPixPos && resolutionX <= 1) {
                // the software loops' rounding, in both directions (LCD_SUBPIXEL_ORIGIN)
                const float positionX = glyphX - LCD_SUBPIXEL_ORIGIN;
                floorX = floorf(positionX);
                if (resolutionY <= 1) {
                    lcdY = floorf(glyphY - LCD_SUBPIXEL_ORIGIN);
                }
                if (const jint thirds = static_cast<jint>((positionX - floorX) * LCD_SUBPIXEL_THIRDS); thirds != 0) {
                    rowBytesOffset = LCD_SUBPIXEL_THIRDS - thirds;
                    floorX += 1;
                }
            }
            if (SKGlyphCache *lcdCache = rowBytesOffset == 0 ? glyphCache(true, glyph) : nullptr; lcdCache != nullptr) {
                if (const int bucket = SKGlyphCache::bucketFor(glyph); !lcdBegun[bucket]) {
                    beginBucket(lcdCache, GlyphKind::LCD, bucket, lcdPaint, &gLCDTwoPass);
                    lcdBegun[bucket] = true;
                    anyLCD = true;
                }
                if (lcdCache->draw(glyph, subimage, rgbOrder, floorX, lcdY)) {
                    continue;
                }
            }
            flushBatches();
            drawLCDGlyphNoCache(glyph, subimage, rowBytesOffset, rgbOrder, floorX, lcdY, lcdPaint);
        }
    }
    // batches stay open for the next glyph list (separateFromEarlierLists)
}

// The direct entry (SKRenderQueue.flushAndInvokeNow): an operation larger than the render buffer.

extern "C" {

JNIEXPORT jboolean JNICALL
Java_sun_java2d_skia_SKTextPipe_drawGlyphListNative(JNIEnv *env, jobject,
                                                    const jint numGlyphs, const jboolean usePositions,
                                                    const jboolean subPixPos, const jboolean rgbOrder,
                                                    const jint lcdContrast,
                                                    const jfloat glyphListOrigX, const jfloat glyphListOrigY,
                                                    // NOLINTNEXTLINE(misc-misplaced-const): a JNI handle passed by value is const as every by-value parameter (the object it names is not)
                                                    const jlongArray imageArray, const jfloatArray positionArray)
{
    SKDirectEntry entry(env);
    if (auto *images = static_cast<jlong *>(env->GetPrimitiveArrayCritical(imageArray, nullptr)); images != nullptr) {
        jfloat *positions = nullptr;
        if (usePositions && positionArray != nullptr) {
            positions = static_cast<jfloat *>(env->GetPrimitiveArrayCritical(positionArray, nullptr));
        }
        if (!usePositions || positions != nullptr) {
            SKTextPipe::drawGlyphList(numGlyphs, usePositions, subPixPos, rgbOrder, lcdContrast,
                                    glyphListOrigX, glyphListOrigY, images, positions);
        }
        if (positions != nullptr) {
            env->ReleasePrimitiveArrayCritical(positionArray, positions, JNI_ABORT);
        }
        env->ReleasePrimitiveArrayCritical(imageArray, images, JNI_ABORT);
    }
    return entry.end();
}

} // extern "C"
