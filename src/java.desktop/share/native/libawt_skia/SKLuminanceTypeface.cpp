/*
 * Copyright 2026 JetBrains s.r.o.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
 */

#include "SKLuminanceTypeface.h"
#include "SKPlatform.h"

#include <algorithm>
#include <cstring>
#include <map>
#include <memory>
#include <utility>
#include <vector>

#include "include/core/SkFontArguments.h"
#include "include/core/SkFontParameters.h"
#include "include/core/SkFontStyle.h"
#include "include/core/SkStream.h"
#include "include/core/SkString.h"
#include "src/core/SkArenaAlloc.h"
#include "src/core/SkAdvancedTypefaceMetrics.h"
#include "src/core/SkFontDescriptor.h"
#include "src/core/SkGlyph.h"
#include "src/core/SkScalerContext.h"

namespace {

// Delegates to the wrapped typeface's scaler context for metrics, paths, drawables
// and every mask format but A8; A8 masks are CoreGraphics' smoothed rendering for
// the proxy's luminance class, at the glyph's sub-pixel offset.
class SKLuminanceScalerContext final : public SkScalerContext {
public:
    SKLuminanceScalerContext(SkTypeface &typeface, const SkScalerContextEffects &effects,
                             const SkDescriptor *desc, std::unique_ptr<SkScalerContext> inner, int luminance)
        : SkScalerContext(typeface, effects, desc), fInner(std::move(inner)), fLuminance(luminance) {}

protected:
    GlyphMetrics generateMetrics(const SkGlyph &glyph, SkArenaAlloc *alloc) override {
        const SkGlyph innerGlyph = fInner->makeGlyph(glyph.getPackedID(), alloc);
        GlyphMetrics metrics(innerGlyph.maskFormat());
        metrics.advance = innerGlyph.advanceVector();
        metrics.bounds = innerGlyph.rect();
        if (innerGlyph.maskFormat() == SkMask::kA8_Format && !innerGlyph.isEmpty()) {
            metrics.bounds.outset(1, 1);   // room for the smoothing's dilation
        }
        return metrics;
    }

    void generateImage(const SkGlyph &glyph, void *imageBuffer) override {
        if (glyph.maskFormat() == SkMask::kA8_Format && smoothedMask(glyph, imageBuffer)) {
            return;
        }
        fInner->getImage(glyph);   // writes into glyph.fImage, which is imageBuffer
    }

    std::optional<GeneratedPath> generatePath(const SkGlyph &glyph) override {
        SkArenaAlloc alloc(1024);
        SkGlyph copy = glyph;
        fInner->getPath(copy, &alloc);
        if (const SkPath *path = copy.path()) {
            return GeneratedPath{*path, false};
        }
        return std::nullopt;
    }

    sk_sp<SkDrawable> generateDrawable(const SkGlyph &glyph) override {
        SkGlyph copy = glyph;
        return fInner->getDrawable(copy);
    }

    void generateFontMetrics(SkFontMetrics *metrics) override {
        fInner->getFontMetrics(metrics);
    }

private:
    bool smoothedMask(const SkGlyph &glyph, void *imageBuffer) {
        const int width = glyph.width(), height = glyph.height();
        if (width <= 0 || height <= 0) {
            return true;
        }
        // the glyph's design space in device pixels: size, synthetic skew and the 2x2 remainder
        const SkMatrix glyphToDevice = fRec.getSingleMatrix();
        const float stroke = (fRec.fFlags & kEmbolden_Flag) ? fRec.fTextSize / 24 : 0;   // as Java's synthetic bold
        const float originX = (float) -glyph.left() + SkFixedToFloat(glyph.getSubXFixed());
        const float originY = (float) -glyph.top() + SkFixedToFloat(glyph.getSubYFixed());
        std::vector<uint8_t> mask((size_t) width * height);
        if (!SKPlatform_SmoothedGlyphMask(fInner->getTypeface(), glyph.getGlyphID(), glyphToDevice, fLuminance, stroke,
                                          originX, originY, width, height, mask.data())) {
            return false;
        }
        const size_t rowBytes = glyph.rowBytes();
        for (int y = 0; y < height; y++) {
            memcpy((uint8_t *) imageBuffer + (size_t) y * rowBytes, mask.data() + (size_t) y * width, (size_t) width);
        }
        return true;
    }

    std::unique_ptr<SkScalerContext> fInner;
    const int fLuminance;
};

class SKLuminanceTypeface final : public SkTypeface {
public:
    SKLuminanceTypeface(sk_sp<SkTypeface> inner, int luminance)
        : SkTypeface(inner->fontStyle(), inner->isFixedPitch()), fInner(std::move(inner)), fLuminance(luminance) {}

protected:
    std::unique_ptr<SkScalerContext> onCreateScalerContext(const SkScalerContextEffects &effects,
                                                           const SkDescriptor *desc) const override {
        return std::make_unique<SKLuminanceScalerContext>(*const_cast<SKLuminanceTypeface *>(this), effects, desc,
                                                          fInner->createScalerContext(effects, desc), fLuminance);
    }
    void onFilterRec(SkScalerContextRec *rec) const override {
        fInner->filterRec(rec);
        rec->ignorePreBlend();   // the masks are final coverage: no gamma or contrast on top
    }
    sk_sp<SkTypeface> onMakeClone(const SkFontArguments &args) const override {
        return SKLuminanceTypeface_For(fInner->makeClone(args), fLuminance);
    }
    std::unique_ptr<SkAdvancedTypefaceMetrics> onGetAdvancedMetrics() const override {
        return nullptr;   // PDF only; the wrapped typeface's are private to it
    }
    void getPostScriptGlyphNames(SkString *) const override {}
    void getGlyphToUnicodeMap(SkSpan<SkUnichar> dst) const override {
        std::fill(dst.begin(), dst.end(), 0);
    }
    std::unique_ptr<SkStreamAsset> onOpenStream(int *ttcIndex) const override {
        return fInner->openStream(ttcIndex);
    }
    std::unique_ptr<SkStreamAsset> onOpenExistingStream(int *ttcIndex) const override {
        return fInner->openExistingStream(ttcIndex);
    }
    bool onGlyphMaskNeedsCurrentColor() const override {
        return false;   // colour-font palettes (COLR) are not re-tinted here; the wrapped typeface's answer is private
    }
    int onGetVariationDesignPosition(SkSpan<SkFontArguments::VariationPosition::Coordinate> coordinates) const override {
        return fInner->getVariationDesignPosition(coordinates);
    }
    int onGetVariationDesignParameters(SkSpan<SkFontParameters::Variation::Axis> parameters) const override {
        return fInner->getVariationDesignParameters(parameters);
    }
    bool onIsSyntheticBold() const override {
        return fInner->isSyntheticBold();
    }
    void onGetFontDescriptor(SkFontDescriptor *desc, bool *isLocal) const override {
        fInner->getFontDescriptor(desc, isLocal);
    }
    void onCharsToGlyphs(SkSpan<const SkUnichar> chars, SkSpan<SkGlyphID> glyphs) const override {
        fInner->unicharsToGlyphs(chars, glyphs);
    }
    int onCountGlyphs() const override {
        return fInner->countGlyphs();
    }
    int onGetUPEM() const override {
        return fInner->getUnitsPerEm();
    }
    bool onGetKerningPairAdjustments(SkSpan<const SkGlyphID> glyphs, SkSpan<int32_t> adjustments) const override {
        return fInner->getKerningPairAdjustments(glyphs, adjustments);
    }
    void onGetFamilyName(SkString *familyName) const override {
        fInner->getFamilyName(familyName);
    }
    bool onGetPostScriptName(SkString *name) const override {
        return fInner->getPostScriptName(name);
    }
    int onGetResourceName(SkString *name) const override {
        return fInner->getResourceName(name);
    }
    LocalizedStrings *onCreateFamilyNameIterator() const override {
        return fInner->createFamilyNameIterator();
    }
    int onGetTableTags(SkSpan<SkFontTableTag> tags) const override {
        return fInner->readTableTags(tags);
    }
    size_t onGetTableData(SkFontTableTag tag, size_t offset, size_t length, void *data) const override {
        return fInner->getTableData(tag, offset, length, data);
    }
    sk_sp<SkData> onCopyTableData(SkFontTableTag tag) const override {
        return fInner->copyTableData(tag);
    }
    bool onComputeBounds(SkRect *bounds) const override {
        *bounds = fInner->getBounds();
        return true;
    }
    void *onGetCTFontRef() const override {
        return fInner->internal_private_getCTFontRef();
    }

private:
    sk_sp<SkTypeface> fInner;
    const int fLuminance;
};

class SKLuminanceFontMgr final : public SkFontMgr {
public:
    SKLuminanceFontMgr(sk_sp<SkFontMgr> inner, int luminance) : fInner(std::move(inner)), fLuminance(luminance) {}

protected:
    int onCountFamilies() const override { return fInner->countFamilies(); }
    void onGetFamilyName(int index, SkString *familyName) const override { fInner->getFamilyName(index, familyName); }
    sk_sp<SkFontStyleSet> onCreateStyleSet(int index) const override { return fInner->createStyleSet(index); }
    sk_sp<SkFontStyleSet> onMatchFamily(const char familyName[]) const override { return fInner->matchFamily(familyName); }
    sk_sp<SkTypeface> onMatchFamilyStyle(const char familyName[], const SkFontStyle &style) const override {
        return wrap(fInner->matchFamilyStyle(familyName, style));
    }
    sk_sp<SkTypeface> onMatchFamilyStyleCharacter(const char familyName[], const SkFontStyle &style, const char *bcp47[],
                                                  int bcp47Count, SkUnichar character) const override {
        return wrap(fInner->matchFamilyStyleCharacter(familyName, style, bcp47, bcp47Count, character));
    }
    sk_sp<SkTypeface> onMakeFromData(sk_sp<SkData> data, int ttcIndex) const override {
        return fInner->makeFromData(std::move(data), ttcIndex);
    }
    sk_sp<SkTypeface> onMakeFromStreamIndex(std::unique_ptr<SkStreamAsset> stream, int ttcIndex) const override {
        return fInner->makeFromStream(std::move(stream), ttcIndex);
    }
    sk_sp<SkTypeface> onMakeFromStreamArgs(std::unique_ptr<SkStreamAsset> stream, const SkFontArguments &args) const override {
        return fInner->makeFromStream(std::move(stream), args);
    }
    sk_sp<SkTypeface> onMakeFromFile(const char path[], int ttcIndex) const override {
        return fInner->makeFromFile(path, ttcIndex);
    }
    sk_sp<SkTypeface> onLegacyMakeTypeface(const char familyName[], SkFontStyle style) const override {
        return wrap(fInner->legacyMakeTypeface(familyName, style));
    }

private:
    sk_sp<SkTypeface> wrap(sk_sp<SkTypeface> typeface) const {
        return typeface ? SKLuminanceTypeface_For(std::move(typeface), fLuminance) : nullptr;
    }

    sk_sp<SkFontMgr> fInner;
    const int fLuminance;
};

} // namespace

sk_sp<SkFontMgr> SKLuminanceFontMgr_For(sk_sp<SkFontMgr> manager, int luminance) {
    static std::map<std::pair<const SkFontMgr *, int>, sk_sp<SkFontMgr>> managers;   // render thread only
    if (!manager) {
        return nullptr;
    }
    sk_sp<SkFontMgr> &proxy = managers[{manager.get(), luminance}];
    if (!proxy) {
        proxy = sk_make_sp<SKLuminanceFontMgr>(std::move(manager), luminance);
    }
    return proxy;
}

sk_sp<SkTypeface> SKLuminanceTypeface_For(sk_sp<SkTypeface> typeface, int luminance) {
    static std::map<std::pair<SkTypefaceID, int>, sk_sp<SkTypeface>> proxies;   // render thread only
    if (!typeface) {
        return nullptr;
    }
    sk_sp<SkTypeface> &proxy = proxies[{typeface->uniqueID(), luminance}];
    if (!proxy) {
        proxy = sk_make_sp<SKLuminanceTypeface>(std::move(typeface), luminance);
    }
    return proxy;
}
