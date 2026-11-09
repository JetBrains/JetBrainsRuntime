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
// and LCD glyphs, and the LCD compositing (blend, twoPass, accumulate).
// All functions run on the Skia queue flusher thread.

#include "SKTextPipe.h"
#include "SKConfiguration.h"
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

#ifndef SUBPIXEL_TIE_NUDGE
// A glyph's sub-pixel variant: the nearest of res steps per pixel, a half-step tie rounding up; the
// nudge of 1/4096 px keeps float rounding noise from deciding a tie either way. The rule belongs in
// fontscalerdefs.h for every pipe (a separate change for the loops, Metal and OpenGL); until
// fontscalerdefs.h defines it, the backend carries its own copy.
#define SUBPIXEL_TIE_NUDGE 0.000244140625f
#endif
#include "sun_font_StrikeCache.h"
#include "sun_java2d_skia_SKTextPipe.h"

#include "include/core/SkBlender.h"
#include "include/core/SkData.h"
#include "include/effects/SkRuntimeEffect.h"
#include "include/core/SkMatrix.h"
#include "include/core/SkPixmap.h"
#include "include/core/SkRect.h"
#include "include/core/SkSamplingOptions.h"
#include "include/core/SkSpan.h"
#include "include/core/SkTileMode.h"
#include "include/gpu/graphite/Surface.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <optional>
#include <vector>

namespace {

// The LCD contrast arrives as the text gamma times 100 (KEY_TEXT_LCD_CONTRAST, 100 .. 250).
constexpr float LCD_CONTRAST_SCALE = 100.0f;
// A color channel as the shaders' uniforms and the coverage tables take it: 0..255 scaled to 0..1.
constexpr float CHANNEL_MAX = 255.0f;
// The coverage correction table has one entry per 8-bit coverage value.
constexpr int LCD_TABLE_ENTRIES = 256;
// A texel's center in a texture coordinate (an integer index plus this), for the table's nearest sampling.
constexpr float TEXEL_CENTER = 0.5f;
// The JDK's LCD sub-pixel positioning shifts a glyph's coverage by thirds of a pixel (channels).
constexpr int LCD_SUBPIXEL_THIRDS = 3;
// A glyph image entry that ends the list, besides null (MTLTextRenderer checks the same two).
constexpr jlong NO_GLYPH_IMAGE = -1;

// Glyph images come from Java's font rasterizer (sun.font); Skia only composites them.
// LCD text, after MTLTextRenderer's lcd_color shader: per-channel coverage blends
// the gamma-adjusted text color into the gamma-adjusted destination,
//   Cr = (Ag * Cs^g + (1 - Ag) * Cd^g) ^ (1/g),   g = contrast / 100.
sk_sp<SkBlender> lcdBlender(jint contrast) {
    static const SkRuntimeEffect *effect = [] {
        auto result = SkRuntimeEffect::MakeForBlender(SkString(R"(
            uniform half3 srcAdj;
            uniform half3 gamma;
            uniform half3 invGamma;
            half4 main(half4 src, half4 dst) {
                half3 coverage = src.rgb;
                if (coverage.r == 0 && coverage.g == 0 && coverage.b == 0) {
                    return dst;
                }
                half3 destination = pow(dst.rgb, gamma);
                half3 blended = mix(destination, srcAdj, coverage);
                return half4(pow(blended, invGamma), 1);
            }
        )"));
        if (!result.effect) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "SKTextPipe: LCD blender does not compile: %s", result.errorText.c_str());
        }
        return result.effect.release();
    }();
    if (effect == nullptr) {
        return nullptr;
    }
    // The blender of the last (contrast, text color): a glyph list's glyphs share it.
    static jint lastContrast = -1;
    static SkColor lastColor = 0;
    static sk_sp<SkBlender> last;
    if (!last || contrast != lastContrast || SKContext::current().color() != lastColor) {
        const float gamma = contrast / LCD_CONTRAST_SCALE, invGamma = 1.0f / gamma;
        const SkColor4f textColor = SkColor4f::FromColor(SKContext::current().color());
        // the uniforms in declaration order: srcAdj, gamma, invGamma (three channels each)
        const std::array<float, 9> uniforms = {
            powf(textColor.fR, gamma), powf(textColor.fG, gamma), powf(textColor.fB, gamma),
            gamma, gamma, gamma,
            invGamma, invGamma, invGamma
        };
        last = effect->makeBlender(SkData::MakeWithCopy(uniforms.data(), uniforms.size() * sizeof(float)));
        lastContrast = contrast;
        lastColor = SKContext::current().color();
    }
    return last;
}

// LCD text without a destination read (the twoPass and accumulate compositing):
// the blend  Cr = Cd * (1 - cov) + Cs * cov  per channel as two
// fixed-function passes, kModulate with (1 - cov) and an addition of Cs * cov,
// which is how DirectWrite composites ClearType (dual-source blending does both
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
float lcdCorrectedCoverage(float coverage, float textValue, float textGamma, float inverseGamma) {
    const float onWhite = 1.0f - powf(1.0f + (textGamma - 1.0f) * coverage, inverseGamma);
    const float onBlack = powf(textGamma * coverage, inverseGamma);
    const float weight = ((1.0f - textValue) * onWhite + textValue * onBlack)
                         / ((1.0f - textValue) * (1.0f - textValue) + textValue * textValue);
    return std::clamp(weight, 0.0f, 1.0f);
}

// The table of a contrast (a row per channel value, a column per coverage), uploaded to the current context.
sk_sp<SkImage> makeLCDTable(jint contrast) {
    const float gamma = contrast / LCD_CONTRAST_SCALE;
    const float inverseGamma = 1.0f / gamma;
    std::vector<uint8_t> pixels((size_t) LCD_TABLE_ENTRIES * LCD_TABLE_ENTRIES);
    for (int row = 0; row < LCD_TABLE_ENTRIES; row++) {
        // the channel as the text color's SkColor4f holds it
        const float textValue = SkColor4f::FromColor(SkColorSetRGB(row, row, row)).fR;
        const float textGamma = powf(textValue, gamma);
        uint8_t *rowPixels = pixels.data() + (size_t) row * LCD_TABLE_ENTRIES;
        for (int entry = 0; entry < LCD_TABLE_ENTRIES; entry++) {
            rowPixels[entry] = (uint8_t) lroundf(lcdCorrectedCoverage(entry / CHANNEL_MAX, textValue, textGamma, inverseGamma)
                                                 * CHANNEL_MAX);
        }
    }
    return SKContext::current().uploadPixmap(SkPixmap(SkImageInfo::Make(LCD_TABLE_ENTRIES, LCD_TABLE_ENTRIES, kGray_8_SkColorType,
                                                                        kOpaque_SkAlphaType),
                                                      pixels.data(), LCD_TABLE_ENTRIES)).image;
}

// The context's table of a contrast, made on first use (a failed upload is retried by the next glyph list).
sk_sp<SkImage> lcdTable(jint contrast) {
    SKGraphicsConfig *graphicsConfig = SKContext::current().graphicsConfig();
    sk_sp<SkImage> table = graphicsConfig->findLCDTable(contrast);
    if (!table) {
        table = makeLCDTable(contrast);
        graphicsConfig->storeLCDTable(contrast, table);
    }
    return table;
}

sk_sp<SkShader> lcdPassShader(const sk_sp<SkShader> &coverage, const SkPaint &paint, jint contrast,
                              SKQuadBatchPass pass) {
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
        const std::array<const char *, 2> mains = {
            "half4 main(float2 xy) { return half4(1 - coverage(xy), 1); }",
            "half4 main(float2 xy) { return half4(src * coverage(xy), 0); }",   // alpha 0: kSrcOver adds
        };
        for (size_t passIndex = 0; passIndex < effects.size(); passIndex++) {
            auto result = SkRuntimeEffect::MakeForShader(SkStringPrintf("%s%s", common.c_str(), mains[passIndex]));
            if (!result.effect) {
                J2dRlsTraceLn(J2D_TRACE_ERROR, "SKTextPipe: LCD two-pass shader does not compile: %s", result.errorText.c_str());
                break;
            }
            effects[passIndex] = result.effect.release();
        }
    }
    if (effects[1] == nullptr) {
        return nullptr;
    }
    sk_sp<SkImage> table = lcdTable(contrast);
    if (!table) {
        return nullptr;
    }
    const SkColor color = paint.getColor();
    const SkColor4f textColor = SkColor4f::FromColor(color);
    // the shader's `src` and `rows`, in declaration order
    const std::array<float, 6> uniforms = {
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

// The two passes of the LCD glyph batches (sun.java2d.skia.text.lcdCompositing=twoPass): the
// batch's paint keys the color, the key the contrast of the glyph list being drawn (setContrast).
class SKLCDTwoPass final : public SKQuadBatchTwoPass {
public:
    sk_sp<SkShader> passShader(SKQuadBatchPass pass, const sk_sp<SkShader> &atlas, const SkPaint &paint) const override {
        return lcdPassShader(atlas, paint, m_contrast, pass);
    }
    // kModulate, then kSrcOver: the second shader's alpha is 0, an addition (above)
    SkBlendMode passBlend(SKQuadBatchPass pass) const override {
        return pass == SKQuadBatchPass::Second ? SkBlendMode::kSrcOver : SkBlendMode::kModulate;
    }
    int passKey() const override { return m_contrast; }

    [[nodiscard]] jint contrast() const { return m_contrast; }
    void setContrast(jint contrast) { m_contrast = contrast; }

private:
    jint m_contrast = 0;
};

SKLCDTwoPass gLCDTwoPass;

// The two passes over a coverage image (device coordinates) within `bounds`,
// with the batch's blend modes.
void drawLCDPasses(SkCanvas *destination, const sk_sp<SkShader> &coverage, const SkRect &bounds, const SkPaint &paint,
                   jint contrast) {
    SkPaint passPaint = paint;
    passPaint.setShader(lcdPassShader(coverage, paint, contrast, SKQuadBatchPass::First));
    passPaint.setBlendMode(gLCDTwoPass.passBlend(SKQuadBatchPass::First));
    destination->drawRect(bounds, passPaint);
    passPaint.setShader(lcdPassShader(coverage, paint, contrast, SKQuadBatchPass::Second));
    passPaint.setBlendMode(gLCDTwoPass.passBlend(SKQuadBatchPass::Second));
    destination->drawRect(bounds, passPaint);
}

// sun.java2d.skia.text.lcdCompositing=accumulate: the glyphs of a batch are drawn into a
// destination-sized coverage texture with kScreen (1 - (1 - a)(1 - b), the
// union of coverages, which is what compositing same-colored glyphs in
// sequence leaves; fixed-function and ordered, so overlapping glyphs need no
// splitting), and the batch's bounds are then composited onto the destination
// in the two passes. Two draws per batch and a clear, whatever the glyph
// count or overlap depth; a scratch texture per context in return.
// The accumulation observes the LCD caches' batch draws (SKGlyphCache::observeDraws),
// which may come after the destination has left the render state (a surface
// disposed behind queued text), so it keeps its own destination and context.
class SKLCDAccumulationObserver final : public SKQuadBatchObserver {
public:
    // Clears the bounds of the coverage surface, composites them afterwards.
    void beforeDraw(const SkRect &bounds) override;
    void afterDraw(const SkRect &bounds, const SkPaint &paint) override;
    bool takesDrawBounds() const override { return true; }

    // The batches accumulate for this destination, context and contrast.
    [[nodiscard]] bool targets(SkCanvas *destination, SKGraphicsConfig *graphicsConfig, jint contrast) const {
        return m_destination == destination && m_graphicsConfig == graphicsConfig && m_contrast == contrast;
    }
    // After the batches pending for the previous target are drawn.
    void retarget(SkCanvas *destination, SKGraphicsConfig *graphicsConfig, jint contrast) {
        m_destination = destination;
        m_graphicsConfig = graphicsConfig;
        m_contrast = contrast;
    }
    // The context is disposed (its batches were drawn).
    void forgetGraphicsConfig(SKGraphicsConfig *graphicsConfig) {
        if (m_graphicsConfig == graphicsConfig) {
            retarget(nullptr, nullptr, 0);
        }
    }

private:
    SkCanvas *m_destination = nullptr;
    SKGraphicsConfig *m_graphicsConfig = nullptr;
    jint m_contrast = 0;   // set with the destination, per glyph list
};

SKLCDAccumulationObserver gLCDAccumulation;

// The context's coverage surface, at least the destination's size; null if it cannot be made.
SkCanvas *lcdCoverageCanvas() {
    const SKContext &context = SKContext::current();
    SkSurface *scratch = context.graphicsConfig()->lcdCoverageSurface(context.canvas()->imageInfo().width(),
                                                                      context.canvas()->imageInfo().height());
    return scratch != nullptr ? scratch->getCanvas() : nullptr;
}

void clearLCDCoverage(SKGraphicsConfig *graphicsConfig, const SkRect &bounds) {
    SkPaint clear;
    clear.setColor(SK_ColorBLACK);
    clear.setBlendMode(SkBlendMode::kSrc);
    graphicsConfig->lcdCoverage()->getCanvas()->drawRect(bounds, clear);
}

sk_sp<SkShader> lcdCoverageShader(SKGraphicsConfig *graphicsConfig) {
    sk_sp<SkImage> image = SkSurfaces::AsImage(sk_ref_sp(graphicsConfig->lcdCoverage()));
    return image ? image->makeShader(SkTileMode::kClamp, SkTileMode::kClamp, SkSamplingOptions(SkFilterMode::kNearest))
                 : nullptr;
}

void SKLCDAccumulationObserver::beforeDraw(const SkRect &bounds) {
    clearLCDCoverage(m_graphicsConfig, bounds);
}

void SKLCDAccumulationObserver::afterDraw(const SkRect &bounds, const SkPaint &paint) {
    drawLCDPasses(m_destination, lcdCoverageShader(m_graphicsConfig), bounds, paint, m_contrast);
}

// A glyph list accumulates: the LCD caches' batches draw into the coverage
// surface, clear its bounds first and composite them afterwards
// (gLCDAccumulation observes each bucket's batch draws).
SkCanvas *beginLCDAccumulation(SkPaint &lcdPaint, jint lcdContrast) {
    const SKContext &context = SKContext::current();
    SkCanvas *scratch = lcdCoverageCanvas();
    if (scratch == nullptr) {
        return nullptr;
    }
    if (!gLCDAccumulation.targets(context.canvas(), context.graphicsConfig(), lcdContrast)) {
        context.graphicsConfig()->flushGlyphCaches(false, true);   // neither is part of the batch key
        gLCDAccumulation.retarget(context.canvas(), context.graphicsConfig(), lcdContrast);
    }
    lcdPaint.setColor(context.color());   // the key; the atlas shader ignores it
    lcdPaint.setBlendMode(SkBlendMode::kScreen);
    return scratch;
}

// An uncached LCD glyph image without a destination read: in two passes with
// the image shader standing in for the atlas, positioned at (x, y), or through
// the coverage surface.
void drawLCDImageNoDstRead(const sk_sp<SkImage> &coverage, float x, float y, const SkPaint &paint, jint contrast) {
    SkRect rect = SkRect::MakeXYWH(x, y, (float) coverage->width(), (float) coverage->height());
    if (SKConfiguration::lcdCompositing() == SKLCDCompositing::Accumulate) {
        SkCanvas *scratch = lcdCoverageCanvas();
        if (scratch == nullptr) {
            return;
        }
        clearLCDCoverage(SKContext::current().graphicsConfig(), rect);
        SkPaint screen;
        screen.setBlendMode(SkBlendMode::kScreen);
        scratch->drawImage(coverage, x, y, SkSamplingOptions(SkFilterMode::kNearest), &screen);
        drawLCDPasses(SKContext::current().canvas(), lcdCoverageShader(SKContext::current().graphicsConfig()), rect, paint, contrast);
        return;
    }
    SkMatrix at = SkMatrix::Translate(x, y);
    sk_sp<SkShader> image = coverage->makeShader(SkTileMode::kClamp, SkTileMode::kClamp, SkSamplingOptions(SkFilterMode::kNearest), &at);
    drawLCDPasses(SKContext::current().canvas(), image, rect, paint, contrast);
}

// The bucket's cache for a glyph image (SKGlyphCache::bucketFor), created on first use;
// nullptr if the image exceeds the largest cell or the creation failed.
SKGlyphCache *glyphCache(bool lcd, const GlyphInfo *glyph) {
    const int bucket = SKGlyphCache::bucketFor(glyph);
    SKGraphicsConfig *graphicsConfig = SKContext::current().graphicsConfig();
    if (bucket == SK_GLYPH_NO_BUCKET || graphicsConfig == nullptr) {
        return nullptr;
    }
    return graphicsConfig->glyphCache(lcd, bucket);
}

// The glyph (sub)image's first byte: the sub-pixel variants follow each other, one image each.
const unsigned char *glyphImageOf(const GlyphInfo *glyph, jint subimage) {
    return glyph->image + (size_t) glyph->rowBytes * glyph->height * subimage;
}

// Glyphs too large for a cache cell, or LCD glyphs shifted by a third of a
// pixel, are uploaded and drawn individually (MTLTextRenderer's NoCache paths).
void drawGlyphNoCache(const GlyphInfo *glyph, jint subimage, float x, float y, const SkPaint &paint) {
    SKContext::current().flushAllBatches();    // drawn through the canvas, not batched: every pending batch first, of any kind
    SKGlyphCache::count(SK_GLYPHS_UNCACHED);
    SkPixmap pixmap(SkImageInfo::MakeA8(glyph->width, glyph->height), glyphImageOf(glyph, subimage), glyph->rowBytes);
    if (sk_sp<SkImage> coverage = SKContext::current().uploadPixmap(pixmap).image) {
        SKContext::current().canvas()->drawImage(coverage, x, y, SkSamplingOptions(SkFilterMode::kNearest), &paint);
    }
}

void drawLCDGlyphNoCache(const GlyphInfo *glyph, jint subimage, jint rowBytesOffset, jboolean rgbOrder,
                         float x, float y, const SkPaint &paint) {
    SKContext::current().flushAllBatches();    // drawn through the canvas, not batched: every pending batch first, of any kind
    SKGlyphCache::count(SK_GLYPHS_UNCACHED);
    const int width = glyph->width, height = glyph->height;
    std::vector<unsigned char> rgba((size_t) width * height * SK_RGBA_BYTES_PER_PIXEL);
    const unsigned char *image = glyphImageOf(glyph, subimage);
    for (int row = 0; row < height; row++) {
        SKGlyphCache::expandLCDRow(image + (size_t) row * glyph->rowBytes + rowBytesOffset,
                                   rgba.data() + (size_t) row * width * SK_RGBA_BYTES_PER_PIXEL, width, rgbOrder);
    }
    SkPixmap pixmap(SkImageInfo::Make(width, height, kRGBA_8888_SkColorType, kOpaque_SkAlphaType), rgba.data(),
                    (size_t) width * SK_RGBA_BYTES_PER_PIXEL);
    if (sk_sp<SkImage> coverage = SKContext::current().uploadPixmap(pixmap).image) {
        if (SKConfiguration::lcdCompositing() != SKLCDCompositing::Blend) {
            drawLCDImageNoDstRead(coverage, x, y, paint, gLCDTwoPass.contrast());
        } else {
            SKContext::current().canvas()->drawImage(coverage, x, y, SkSamplingOptions(SkFilterMode::kNearest), &paint);
        }
    }
}

void drawColorGlyph(const GlyphInfo *glyph, float x, float y) {
    SKContext::current().flushAllBatches();    // drawn through the canvas, not batched: every pending batch first, of any kind
    SkPixmap pixmap(SkImageInfo::Make(glyph->width, glyph->height, kBGRA_8888_SkColorType, kPremul_SkAlphaType),
                    glyph->image, glyph->rowBytes);
    if (sk_sp<SkImage> image = SKContext::current().uploadPixmap(pixmap).image) {
        const SkPaint paint = SKPaints::imageSourcePaint();
        SKContext::current().canvas()->drawImage(image, x, y, SkSamplingOptions(SkFilterMode::kNearest), &paint);
    }
}

// Sub-pixel strikes render rx*ry variants of each glyph; pick the one for the
// fractional position (MTLTextRenderer's ADJUST_SUBPIXEL_GLYPH_POSITION).
// The pipes' shared variant rule (fontscalerdefs.h), as an expression.
float adjustSubpixel(float coordinate, int resolution) {
    return resolution > 1 ? coordinate + 0.5f / (float) resolution - 0.5f + SUBPIXEL_TIE_NUDGE : coordinate;
}

// The two kinds of cached glyphs; a switch between them draws the pending batches first.
enum class GlyphKind { Grayscale, LCD };

// The previous glyph list's paint, LCD contrast and the kind of the bucket begun last,
// see SKTextPipe::drawGlyphList. The destructor traces, as it never runs (gLastGlyphList;
// SkiaExitWhilePaintingTest).
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

// The paint may hold a shader of the context's recorder (a texture paint's image).
void SKTextPipe::forgetGraphicsConfig(SKGraphicsConfig *graphicsConfig) {
    gLastGlyphList->paint = SkPaint();
    gLastGlyphList->contrast = -1;
    gLastGlyphList->kind.reset();
    gLCDAccumulation.forgetGraphicsConfig(graphicsConfig);
}

void SKTextPipe::drawGlyphList(jint numGlyphs, jboolean usePositions, jboolean subPixPos,
                               jboolean rgbOrder, jint lcdContrast,
                               jfloat glyphListOrigX, jfloat glyphListOrigY,
                               const jlong *images, const jfloat *positions) {
    if (!SKContext::current().beginDraw(SKBatchKind::Glyphs)) {
        return;
    }
    SKGraphicsConfig *graphicsConfig = SKContext::current().graphicsConfig();
    const SKLCDCompositing lcdCompositing = SKConfiguration::lcdCompositing();
    SkPaint paint = SKPaints::makePaint(false);
    // The buckets' caches begun for this list, grayscale and LCD. The buckets' batches
    // stay open side by side (glyphs of one paint commute under the blends used), but a
    // switch between grayscale and LCD glyphs draws the pending batches first.
    // Across lists the buckets' batches commute only for the same paint (and contrast,
    // and kind): the bucket order (16 ... 128) is not the lists' order, so a list of
    // another paint or kind draws the earlier lists' batches of the other buckets before
    // its first glyph is batched (one compare per list; a bucket's own batch keeps its
    // quads in order, colors merged or not).
    SkPaint &lastPaint = gLastGlyphList->paint;
    jint &lastContrast = gLastGlyphList->contrast;
    std::optional<GlyphKind> &lastKind = gLastGlyphList->kind;
    bool separate = !(paint == lastPaint) || lcdContrast != lastContrast;
    if (separate) {
        lastPaint = paint;
        lastContrast = lcdContrast;
    }
    // Before the list's first glyph of a kind is batched in `bucket`: the other batches
    // pending from earlier lists, if those may not commute with this one.
    auto separateFromEarlierLists = [&](GlyphKind kind, int bucket) {
        if (separate || lastKind != kind) {
            if (graphicsConfig != nullptr) {
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
            }
            separate = false;
            lastKind = kind;
        }
    };
    std::array<bool, SK_GLYPH_BUCKETS> grayscaleBegun = {}, lcdBegun = {};
    bool anyGrayscale = false, anyLCD = false;
    SkPaint lcdPaint;
    bool lcdPaintReady = false;
    SkCanvas *lcdCanvas = SKContext::current().canvas();   // the coverage surface when accumulating

    // Glyphs of one list are drawn in order: an immediate draw (uncached
    // glyph, color glyph) or a switch between grayscale and LCD glyphs first
    // draws whatever is batched.
    auto flushBatches = [&] {
        SKContext::current().flushGlyphBatches();
        grayscaleBegun.fill(false);
        lcdBegun.fill(false);
        anyGrayscale = false;
        anyLCD = false;
    };

    for (jint index = 0; index < numGlyphs; index++) {
        if (images[index] == 0 || images[index] == NO_GLYPH_IMAGE) {
            break;
        }
        const GlyphInfo *glyph = (const GlyphInfo *) jlong_to_ptr(images[index]);
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
            subimage = (int) ((glyphX - floorX) * (float) resolutionX) + (int) ((glyphY - floorY) * (float) resolutionY) * resolutionX;
        }
        if (glyph->image == nullptr || glyph->width == 0 || glyph->height == 0) {
            continue;
        }

        if (glyph->format == sun_font_StrikeCache_PIXEL_FORMAT_GREYSCALE) {
            if (anyLCD) {
                flushBatches();
            }
            SKGlyphCache *grayscaleCache = glyphCache(false, glyph);
            if (grayscaleCache != nullptr) {
                const int bucket = SKGlyphCache::bucketFor(glyph);
                if (!grayscaleBegun[bucket]) {
                    separateFromEarlierLists(GlyphKind::Grayscale, bucket);
                    grayscaleCache->begin(SKContext::current().canvas(), paint);
                    grayscaleBegun[bucket] = true;
                    anyGrayscale = true;
                }
                if (grayscaleCache->draw((GlyphInfo *) glyph, subimage, false, floorX, floorY)) {
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
            // the destination, in the destination's gamma (blender), in two
            // passes without reading it, or accumulated in a coverage texture
            // first; in the latter two the paint carries the color, which keys
            // the batch.
            if (!lcdPaintReady) {
                gLCDTwoPass.setContrast(lcdContrast);
                switch (lcdCompositing) {
                    case SKLCDCompositing::TwoPass:
                        lcdPaint.setColor(SKContext::current().color());
                        break;
                    case SKLCDCompositing::Accumulate:
                        lcdCanvas = beginLCDAccumulation(lcdPaint, lcdContrast);
                        if (lcdCanvas == nullptr) {
                            continue;   // no coverage surface: the glyph is skipped
                        }
                        break;
                    case SKLCDCompositing::Blend:
                        lcdPaint.setColor(SK_ColorWHITE);
                        lcdPaint.setBlender(lcdBlender(lcdContrast));
                        break;
                }
                lcdPaintReady = true;
            }
            // the scaler's own variants (the platform's placement grid) take precedence over
            // the JDK's channel shift by thirds
            jint rowBytesOffset = 0;
            if (subPixPos && resolutionX <= 1) {
                const jint thirds = (jint) ((glyphX - floorX) * LCD_SUBPIXEL_THIRDS);
                if (thirds != 0) {
                    rowBytesOffset = LCD_SUBPIXEL_THIRDS - thirds;
                    floorX += 1;
                }
            }
            SKGlyphCache *lcdCache = rowBytesOffset == 0 ? glyphCache(true, glyph) : nullptr;
            if (lcdCache != nullptr) {
                const int bucket = SKGlyphCache::bucketFor(glyph);
                if (!lcdBegun[bucket]) {
                    separateFromEarlierLists(GlyphKind::LCD, bucket);
                    if (lcdCompositing == SKLCDCompositing::Accumulate) {
                        lcdCache->observeDraws(&gLCDAccumulation);
                    }
                    lcdCache->begin(lcdCanvas, lcdPaint,
                                    lcdCompositing == SKLCDCompositing::TwoPass ? &gLCDTwoPass : nullptr);
                    lcdBegun[bucket] = true;
                    anyLCD = true;
                }
                if (lcdCache->draw((GlyphInfo *) glyph, subimage, rgbOrder, floorX, floorY)) {
                    continue;
                }
            }
            flushBatches();
            drawLCDGlyphNoCache(glyph, subimage, rowBytesOffset, rgbOrder, floorX, floorY, lcdPaint);
        }
    }
    // batches stay open for the next glyph list (separateFromEarlierLists)
}

// The direct entry (SKRenderQueue.flushAndInvokeNow): an operation larger than the render buffer.

extern "C" {

JNIEXPORT jboolean JNICALL
Java_sun_java2d_skia_SKTextPipe_drawGlyphListNative(JNIEnv *env, jobject self,
                                                    jint numGlyphs, jboolean usePositions,
                                                    jboolean subPixPos, jboolean rgbOrder,
                                                    jint lcdContrast,
                                                    jfloat glyphListOrigX, jfloat glyphListOrigY,
                                                    jlongArray imageArray, jfloatArray positionArray)
{
    SKDirectEntry entry(env);
    jlong *images = (jlong *) env->GetPrimitiveArrayCritical(imageArray, nullptr);
    if (images != nullptr) {
        jfloat *positions = nullptr;
        if (usePositions && positionArray != nullptr) {
            positions = (jfloat *) env->GetPrimitiveArrayCritical(positionArray, nullptr);
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
