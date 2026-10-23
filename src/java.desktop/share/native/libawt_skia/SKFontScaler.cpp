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

// Native side of sun.font.SKFontScaler: a typeface from the platform's font
// host (SKPlatform) and, per strike, an SkFont that answers the scaler's
// questions (metrics, advances, outlines; glyph images follow).

#include "SKPlatform.h"
#include "SKFontScaler.h"

#include <cmath>
#include <mutex>
#include <unordered_map>
#include <optional>
#include <vector>
#include "jlong.h"
#include "Trace.h"
#include "sun_awt_SunHints.h"
#include "sun_font_SKFontScaler.h"

#include "include/core/SkFont.h"
#include "include/core/SkFontMetrics.h"
#include "include/core/SkFontMgr.h"
#include "include/core/SkFontTypes.h"
#include "include/core/SkPath.h"
#include "include/core/SkStream.h"
#include "include/core/SkTypeface.h"
#include "include/core/SkBitmap.h"
#include "include/core/SkUnPreMultiply.h"
#include <cstring>
#include "include/core/SkCanvas.h"
#include "include/core/SkSurfaceProps.h"
#include "fontscalerdefs.h"
#include "sun_font_StrikeCache.h"

namespace {

struct Fallback {
    sk_sp<SkTypeface> typeface;
    SkGlyphID glyph = 0;
};

struct SKScaler {
    sk_sp<SkTypeface> typeface;
    jlong nativeFont = 0;   // for the platform's fallback (0 for file fonts)
    std::mutex fallbackLock;
    std::unordered_map<int, Fallback> fallbacks;   // code point -> the platform's fallback glyph
};

// One per FontStrikeDesc: the glyph transform split into the SkFont's size and
// the remaining matrix (device transform, skew, non-uniform scale), which the
// glyph images and outlines are mapped through.
struct SKScalerContext {
    SKScaler *scaler = nullptr;
    SkFont font;
    SkMatrix remainder;   // glyphTx / size
    bool fractionalMetrics = false;
    bool identityRemainder = true;
    double deviceScale = 1.0;   // whole-pixel advances are rounded in user space when it is not 1
    double displayScale = 1.0;  // scale of the strike's device transform (2 on a 200 % display); the
                                // platform's placement grid is per device-independent pixel
    jint grayMode = 0;          // SKFontScaler.GRAY_*
    float grayDarkening = 0;    // fraction of the em added to the outline's width in the darkened mode
    jint grayLuminance = 0;     // the text luminance the cg mode rasterizes for
};

// The font and glyph to use for a Java glyph code: the strike's own font for
// a glyph of it, the platform's fallback font for a negative code (CFont's
// mapper encodes a character the font lacks as -codePoint). False if there
// is nothing to draw.
bool resolveGlyph(SKScalerContext *context, jint glyphCode, SkFont *font, SkGlyphID *glyph) {
    if (context == nullptr) {
        return false;
    }
    if (glyphCode >= 0) {
        if (glyphCode > 0xffff) {
            return false;
        }
        *font = context->font;
        *glyph = (SkGlyphID) glyphCode;
        return true;
    }
    SKScaler *scaler = context->scaler;
    if (scaler == nullptr || scaler->nativeFont == 0) {
        return false;
    }
    const int codePoint = -glyphCode;
    Fallback fallback;
    {
        std::lock_guard<std::mutex> guard(scaler->fallbackLock);
        auto it = scaler->fallbacks.find(codePoint);
        if (it != scaler->fallbacks.end()) {
            fallback = it->second;
        } else {
            uint16_t id = 0;
            fallback.typeface = SKPlatform_FallbackTypeface(scaler->nativeFont, codePoint, &id);
            fallback.glyph = id;
            scaler->fallbacks[codePoint] = fallback;
        }
    }
    if (!fallback.typeface || fallback.glyph == 0) {
        return false;
    }
    *font = context->font;
    font->setTypeface(fallback.typeface);
    *glyph = fallback.glyph;
    return true;
}

jobject strikeMetrics(JNIEnv *env, float ay, float dy, float ly, float mx) {
    jclass cls = env->FindClass("sun/font/StrikeMetrics");
    if (cls == nullptr) {
        return nullptr;
    }
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(FFFFFFFFFF)V");
    if (ctor == nullptr) {
        return nullptr;
    }
    // Same convention as the CoreText and FreeType scalers: ascent negative (up),
    // descent positive, baseline 1, x components zero.
    return env->NewObject(cls, ctor, 0.0f, ay, 0.0f, dy, 1.0f, 0.0f, 0.0f, ly, mx, 0.0f);
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_sun_font_SKFontScaler_createNative(JNIEnv *env, jclass clazz, jlong nativeFont, jstring path, jint index)
{
    sk_sp<SkTypeface> typeface;
    if (nativeFont != 0) {
        typeface = SKPlatform_TypefaceFromNativeFont(nativeFont);
    } else if (path != nullptr) {
        sk_sp<SkFontMgr> manager = SKPlatform_FontManager();
        const char *chars = env->GetStringUTFChars(path, nullptr);
        if (manager && chars != nullptr) {
            typeface = manager->makeFromFile(chars, index);
        }
        if (chars != nullptr) {
            env->ReleaseStringUTFChars(path, chars);
        }
    }
    if (!typeface) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "SKFontScaler: no typeface for native font %p / file", jlong_to_ptr(nativeFont));
        return 0;
    }
    auto *scaler = new SKScaler();
    scaler->typeface = std::move(typeface);
    scaler->nativeFont = nativeFont;
    return ptr_to_jlong(scaler);
}

JNIEXPORT void JNICALL
Java_sun_font_SKFontScaler_disposeNative(JNIEnv *env, jclass clazz, jlong pScaler)
{
    delete (SKScaler *) jlong_to_ptr(pScaler);
}

JNIEXPORT jint JNICALL
Java_sun_font_SKFontScaler_getNumGlyphsNative(JNIEnv *env, jclass clazz, jlong pScaler)
{
    auto *scaler = (SKScaler *) jlong_to_ptr(pScaler);
    return scaler != nullptr ? scaler->typeface->countGlyphs() : 0;
}

JNIEXPORT jint JNICALL
Java_sun_font_SKFontScaler_getGlyphCodeNative(JNIEnv *env, jclass clazz, jlong pScaler, jchar charCode)
{
    auto *scaler = (SKScaler *) jlong_to_ptr(pScaler);
    return scaler != nullptr ? scaler->typeface->unicharToGlyph(charCode) : 0;
}

JNIEXPORT jlong JNICALL
Java_sun_font_SKFontScaler_getUnitsPerEmNative(JNIEnv *env, jclass clazz, jlong pScaler)
{
    auto *scaler = (SKScaler *) jlong_to_ptr(pScaler);
    return scaler != nullptr ? scaler->typeface->getUnitsPerEm() : 0;
}

// matrix: the strike's glyph transform (m00 m10 m01 m11), point size included.
JNIEXPORT jlong JNICALL
Java_sun_font_SKFontScaler_createContextNative(JNIEnv *env, jclass clazz, jlong pScaler, jdoubleArray matrix,
                                               jint aa, jint fm, jfloat boldness, jfloat italic, jdouble deviceScale,
                                               jdouble displayScale, jint grayMode, jfloat grayDarkening, jint grayLuminance)
{
    auto *scaler = (SKScaler *) jlong_to_ptr(pScaler);
    if (scaler == nullptr || matrix == nullptr || env->GetArrayLength(matrix) < 4) {
        return 0;
    }
    double m[4];
    env->GetDoubleArrayRegion(matrix, 0, 4, m);
    const double size = std::sqrt(std::fabs(m[0] * m[3] - m[1] * m[2]));
    if (!(size > 0)) {
        return 0;
    }
    auto *context = new SKScalerContext();
    context->scaler = scaler;
    context->font = SkFont(scaler->typeface, (SkScalar) size);
    // AffineTransform order: m00 m10 m01 m11
    context->remainder = SkMatrix::MakeAll((SkScalar) (m[0] / size), (SkScalar) (m[2] / size), 0,
                                           (SkScalar) (m[1] / size), (SkScalar) (m[3] / size), 0,
                                           0, 0, 1);
    context->identityRemainder = context->remainder.isIdentity();
    context->fractionalMetrics = fm == sun_awt_SunHints_INTVAL_FRACTIONALMETRICS_ON;
    context->deviceScale = deviceScale > 0 ? deviceScale : 1.0;
    context->displayScale = displayScale > 0 ? displayScale : 1.0;
    context->grayMode = grayMode;
    context->grayDarkening = grayDarkening;
    context->grayLuminance = grayLuminance;
    // Like the CoreText scaler behind CStrike: design metrics scaled linearly,
    // no hinting; sub-pixel positions only with fractional metrics.
    context->font.setHinting(SkFontHinting::kNone);
    context->font.setLinearMetrics(true);
    context->font.setSubpixel(context->fractionalMetrics);
    // aa: SunHints.INTVAL_TEXT_ANTIALIAS_*; the LCD values become sub-pixel edging
    context->font.setEdging(aa == sun_awt_SunHints_INTVAL_TEXT_ANTIALIAS_OFF ? SkFont::Edging::kAlias
                          : aa == sun_awt_SunHints_INTVAL_TEXT_ANTIALIAS_ON ? SkFont::Edging::kAntiAlias
                                                                            : SkFont::Edging::kSubpixelAntiAlias);
    if (boldness != 1.0f) {
        context->font.setEmbolden(true);
    }
    if (italic != 0.0f) {
        context->font.setSkewX(-italic);
    }
    return ptr_to_jlong(context);
}

JNIEXPORT void JNICALL
Java_sun_font_SKFontScaler_disposeContextNative(JNIEnv *env, jclass clazz, jlong pContext)
{
    delete (SKScalerContext *) jlong_to_ptr(pContext);
}

JNIEXPORT jobject JNICALL
Java_sun_font_SKFontScaler_getFontMetricsNative(JNIEnv *env, jclass clazz, jlong pContext)
{
    auto *context = (SKScalerContext *) jlong_to_ptr(pContext);
    if (context == nullptr) {
        return strikeMetrics(env, 0, 0, 0, 0);
    }
    SkFontMetrics metrics;
    context->font.getMetrics(&metrics);
#if defined(_WIN32)
    // The Windows strike rule (FreeType scaler, freetypeScaler.c): ascent and
    // descent are the OS/2 table's usWinAscent and usWinDescent, and the line
    // height is their sum, so the leading is zero. GDI's convention, which the
    // layouts of every Windows Java application were measured with; the font
    // host reports the hhea or typo values and a line gap instead.
    uint8_t usWin[4];
    const SkTypeface *typeface = context->font.getTypeface();
    if (typeface != nullptr && typeface->getUnitsPerEm() > 0
        && typeface->getTableData(SkSetFourByteTag('O', 'S', '/', '2'), 74, 4, usWin) == 4) {
        const float scale = context->font.getSize() / (float) typeface->getUnitsPerEm();
        const float winAscent = (float) ((usWin[0] << 8) | usWin[1]), winDescent = (float) ((usWin[2] << 8) | usWin[3]);
        metrics.fAscent = -winAscent * scale;
        metrics.fDescent = winDescent * scale;
        metrics.fLeading = 0;
    }
#endif
    // Skia has no maximum advance; the widest glyph box is the nearest value
    // (FontDesignMetrics.getMaxAdvance).
    return strikeMetrics(env, metrics.fAscent, metrics.fDescent, metrics.fLeading, metrics.fMaxCharWidth);
}

// The advance in device space. Without fractional metrics it is a whole
// number of pixels: of device pixels for the scaler contract (FreeType's rule
// behind FileFontStrike), of user-space pixels for a strike that passed its
// device scale (CoreText's rule behind CStrike, measured 2026-10-22: the two
// disagree on a third of the glyphs at fractional scales).
JNIEXPORT jfloat JNICALL
Java_sun_font_SKFontScaler_getGlyphAdvanceNative(JNIEnv *env, jclass clazz, jlong pContext, jint glyphCode)
{
    auto *context = (SKScalerContext *) jlong_to_ptr(pContext);
    SkFont font;
    SkGlyphID glyph;
    if (!resolveGlyph(context, glyphCode, &font, &glyph)) {
        return 0;
    }
    SkScalar width = 0;
    font.getWidths({&glyph, 1}, {&width, 1});
    if (!context->identityRemainder) {
        width = context->remainder.mapVector({width, 0}).fX;
    }
    if (context->fractionalMetrics) {
        return width;
    }
    const double scale = context->deviceScale;
    return (jfloat) (std::round(width / scale) * scale);
}

// GeneralPath(rule, types, numTypes, coords, numCoords) over the outline in
// device space, at x, y.
JNIEXPORT jobject JNICALL
Java_sun_font_SKFontScaler_getGlyphOutlineNative(JNIEnv *env, jclass clazz, jlong pContext, jint glyphCode,
                                                 jfloat x, jfloat y)
{
    auto *context = (SKScalerContext *) jlong_to_ptr(pContext);
    jclass gpClass = env->FindClass("java/awt/geom/GeneralPath");
    if (gpClass == nullptr) {
        return nullptr;
    }
    jmethodID gpCtor = env->GetMethodID(gpClass, "<init>", "(I[BI[FI)V");
    jmethodID gpEmpty = env->GetMethodID(gpClass, "<init>", "()V");
    if (gpCtor == nullptr || gpEmpty == nullptr) {
        return nullptr;
    }
    SkFont font;
    SkGlyphID glyph;
    std::optional<SkPath> path = resolveGlyph(context, glyphCode, &font, &glyph) ? font.getPath(glyph) : std::nullopt;
    if (!path || path->isEmpty()) {
        return env->NewObject(gpClass, gpEmpty);
    }
    SkMatrix m = context->identityRemainder ? SkMatrix::I() : context->remainder;
    m.postTranslate(x, y);
    SkPath device = path->makeTransform(m);
    // PathIterator segment types: 0 move, 1 line, 2 quad, 3 cubic, 4 close
    std::vector<jbyte> types;
    std::vector<jfloat> coords;
    SkPath::Iter iter(device, false);
    SkPoint pts[4];
    for (SkPath::Verb verb = iter.next(pts); verb != SkPath::kDone_Verb; verb = iter.next(pts)) {
        switch (verb) {
            case SkPath::kMove_Verb:  types.push_back(0); coords.insert(coords.end(), {pts[0].fX, pts[0].fY}); break;
            case SkPath::kLine_Verb:  types.push_back(1); coords.insert(coords.end(), {pts[1].fX, pts[1].fY}); break;
            case SkPath::kQuad_Verb:  types.push_back(2); coords.insert(coords.end(), {pts[1].fX, pts[1].fY, pts[2].fX, pts[2].fY}); break;
            case SkPath::kConic_Verb: {   // GeneralPath has no conics: two quads per conic
                SkPoint quads[5];
                SkPath::ConvertConicToQuads(pts[0], pts[1], pts[2], iter.conicWeight(), quads, 1);
                types.push_back(2); coords.insert(coords.end(), {quads[1].fX, quads[1].fY, quads[2].fX, quads[2].fY});
                types.push_back(2); coords.insert(coords.end(), {quads[3].fX, quads[3].fY, quads[4].fX, quads[4].fY});
                break;
            }
            case SkPath::kCubic_Verb: types.push_back(3); coords.insert(coords.end(), {pts[1].fX, pts[1].fY, pts[2].fX, pts[2].fY, pts[3].fX, pts[3].fY}); break;
            case SkPath::kClose_Verb: types.push_back(4); break;
            default: break;
        }
    }
    jbyteArray jtypes = env->NewByteArray((jsize) types.size());
    jfloatArray jcoords = env->NewFloatArray((jsize) coords.size());
    if (jtypes == nullptr || jcoords == nullptr) {
        return nullptr;
    }
    env->SetByteArrayRegion(jtypes, 0, (jsize) types.size(), types.data());
    env->SetFloatArrayRegion(jcoords, 0, (jsize) coords.size(), coords.data());
    const jint rule = device.getFillType() == SkPathFillType::kEvenOdd ? 0 : 1;   // WIND_EVEN_ODD / WIND_NON_ZERO
    return env->NewObject(gpClass, gpCtor, rule, jtypes, (jint) types.size(), jcoords, (jint) coords.size());
}

namespace {

// The strike's advance for the glyph record (same rule as getGlyphAdvanceNative).
float recordAdvance(const SKScalerContext *context, const SkFont &font, SkGlyphID glyph) {
    SkScalar width = 0;
    font.getWidths({&glyph, 1}, {&width, 1});
    if (!context->identityRemainder) {
        width = context->remainder.mapVector({width, 0}).fX;
    }
    if (context->fractionalMetrics) {
        return width;
    }
    return (float) (std::round(width / context->deviceScale) * context->deviceScale);
}

GlyphInfo *emptyGlyph(float advance) {
    auto *info = (GlyphInfo *) calloc(sizeof(GlyphInfo), 1);
    if (info != nullptr) {
        info->advanceX = advance;
        info->managed = UNMANAGED_GLYPH;
        info->format = sun_font_StrikeCache_PIXEL_FORMAT_GREYSCALE;
        info->subpixelResolutionX = info->subpixelResolutionY = 1;
        info->cellInfo = nullptr;
        info->image = nullptr;
    }
    return info;
}

const int MAX_GLYPH_DIMENSION = 1024;   // as the FreeType scaler's MAX_GLYPH_DIM

} // namespace

// The glyph's bounds in device space (Rectangle2D.Float): the outline's exact
// bounds, or the font host's for a bitmap glyph without an outline (its
// bounds are whole pixels, a pixel wider than the ink).
JNIEXPORT jobject JNICALL
Java_sun_font_SKFontScaler_getGlyphBoundsNative(JNIEnv *env, jclass clazz, jlong pContext, jint glyphCode)
{
    jclass rectClass = env->FindClass("java/awt/geom/Rectangle2D$Float");
    jmethodID ctor = rectClass != nullptr ? env->GetMethodID(rectClass, "<init>", "(FFFF)V") : nullptr;
    if (ctor == nullptr) {
        return nullptr;
    }
    auto *context = (SKScalerContext *) jlong_to_ptr(pContext);
    SkFont font;
    SkGlyphID glyph;
    SkRect bounds = SkRect::MakeEmpty();
    if (resolveGlyph(context, glyphCode, &font, &glyph)) {
        if (std::optional<SkPath> path = font.getPath(glyph)) {
            bounds = path->computeTightBounds();
        } else {
            font.getBounds({&glyph, 1}, {&bounds, 1}, nullptr);
        }
        if (!context->identityRemainder) {
            bounds = context->remainder.mapRect(bounds);
        }
    }
    return env->NewObject(rectClass, ctor, bounds.fLeft, bounds.fTop, bounds.width(), bounds.height());
}

// A StrikeCache GlyphInfo with the glyph drawn by the SkFont in device space:
// A8 coverage for grayscale and aliased text, RGB coverage triplets for LCD
// text (Skia's sub-pixel edging on an opaque RGB canvas, the ink's inverse).
// With antialiasing and fractional metrics the record holds subX * subY
// images, one per sub-pixel offset, as the FreeType scaler's do; the text
// pipes pick the image by the glyph position's fraction.
JNIEXPORT jlong JNICALL
Java_sun_font_SKFontScaler_getGlyphImageNative(JNIEnv *env, jclass clazz, jlong pContext, jint glyphCode,
                                               jint subX, jint subY)
{
    auto *context = (SKScalerContext *) jlong_to_ptr(pContext);
    SkFont font;
    SkGlyphID glyph;
    if (!resolveGlyph(context, glyphCode, &font, &glyph)) {
        return ptr_to_jlong(emptyGlyph(0));
    }
    const float advance = recordAdvance(context, font, glyph);
    const bool lcd = font.getEdging() == SkFont::Edging::kSubpixelAntiAlias;
    const bool aa = font.getEdging() != SkFont::Edging::kAlias;
    // Under the platform's mask routes the variants follow the platform rasterizer's own
    // placement grid (DirectWrite: 4 or 8 positions per pixel for grayscale by rendering mode
    // and display scale, 6 for ClearType), so a glyph lands where the platform would put it;
    // otherwise the JDK's resolution (java2d.font.subpixelResolution). LCD glyphs get variants
    // only where the platform defines an LCD grid: they replace the JDK's channel shift by
    // thirds, which the pipes apply to LCD glyphs without variants. A platform without one
    // (CoreGraphics: no colour sub-pixels) keeps the JDK's LCD rendering under these routes.
    const bool platformRoute = context->grayMode == sun_font_SKFontScaler_GRAY_CG
                               || context->grayMode == sun_font_SKFontScaler_GRAY_PLATFORM_COVERAGE;
    const int platformGrid = aa && context->fractionalMetrics && platformRoute
                             ? SKPlatform_GlyphPlacementGrid(font.getTypeface(), font.getSize(), (float) context->displayScale, lcd) : 0;
    const bool subpixel = aa && context->fractionalMetrics && (lcd ? platformGrid != 0 : (subX > 1 || subY > 1));
    // A platform that rounds positions down to its grid of g steps (negative grid): the pipes
    // round to the nearest variant (their half-pixel origin offset plus the half-step of
    // ADJUST_SUBPIXEL_GLYPH_POSITION), so the record carries 2g variants and its origin is
    // shifted left by half a variant step, which turns the pipes' pick into floor(position * 2g);
    // variant j then holds the platform's rendering at the grid step floor(j / 2) / g, exactly
    // where the platform puts a glyph at that position. (Pairs of variants are equal: 2g images.)
    const int floorGrid = platformGrid < 0 ? -platformGrid : 0;
    const int resX = subpixel ? (floorGrid > 0 ? 2 * floorGrid : platformGrid > 0 ? platformGrid : subX) : 1,
              resY = subpixel && !lcd ? subY : 1;
    // Grayscale variants (SKFontScaler, sun.java2d.skia.text.gray). The JDK's
    // CoreGraphics renderer smooths without sub-pixels and averages the
    // channels (CGGlyphImages.m, CGGI_ConvertBWPixelToByteGray); Skia's host
    // smooths only with sub-pixel edging. Measured against it: plain coverage
    // 15 % less ink, the sub-pixel render averaged 13 % more.
    // A glyph without an outline is a bitmap glyph (colour emoji): it is drawn
    // in colour into a premultiplied BGRA record, the JDK's colour glyph format.
    const bool colorGlyph = !font.getPath(glyph).has_value();
    const bool smoothedGray = aa && !lcd && !colorGlyph && context->grayMode == sun_font_SKFontScaler_GRAY_SMOOTHED;
    const bool darkenedGray = aa && !lcd && !colorGlyph && context->grayMode == sun_font_SKFontScaler_GRAY_DARKENED
                              && context->grayDarkening > 0;
    // the platform's own rasterizer (SKPlatform_SmoothedGlyphMask): smoothed for the text
    // luminance (the native look), or as linear coverage (luminance -1) for the pipes' own
    // gamma; grayscale and LCD alike where the platform provides both (DirectWrite does,
    // CoreGraphics only the smoothed grayscale). Synthetic bold strokes the outline by
    // 1/24 em there, as FreeType's embolden does.
    const bool platformMask = aa && !colorGlyph && (context->grayMode == sun_font_SKFontScaler_GRAY_CG
                                                    || context->grayMode == sun_font_SKFontScaler_GRAY_PLATFORM_COVERAGE);
    const int platformLuminance = context->grayMode == sun_font_SKFontScaler_GRAY_PLATFORM_COVERAGE ? -1 : context->grayLuminance;
    const float platformStroke = font.isEmbolden() ? font.getSize() * SK_SYNTHETIC_BOLD_EM : 0;
    if (smoothedGray) {
        font.setEdging(SkFont::Edging::kSubpixelAntiAlias);
    }
    const bool colorCanvas = (lcd || smoothedGray) && !colorGlyph;

    SkRect bounds;
    font.getBounds({&glyph, 1}, {&bounds, 1}, nullptr);
    if (!context->identityRemainder) {
        bounds = context->remainder.mapRect(bounds);
    }
    if (bounds.isEmpty()) {
        return ptr_to_jlong(emptyGlyph(advance));
    }
    // a pixel of padding for the antialiasing filter, two more on each side
    // for the LCD filter's spill, and the sub-pixel offsets to the right/bottom
    // room for the platform's vertical hinting (an edge may move by a pixel: two for its
    // grayscale), the smoothing's dilation, the LCD filter and the synthetic bold's second draw
    const int pad = (colorGlyph ? 1 : lcd ? 3 : smoothedGray || platformMask ? 2 : aa ? 1 : 0) + (int) std::ceil(platformStroke);
    const int left = (int) std::floor(bounds.fLeft) - pad, top = (int) std::floor(bounds.fTop) - pad;
    const int right = (int) std::ceil(bounds.fRight) + pad + (resX > 1 ? 1 : 0);
    const int bottom = (int) std::ceil(bounds.fBottom) + pad + (resY > 1 ? 1 : 0);
    const int width = right - left, height = bottom - top;
    if (width <= 0 || height <= 0 || width > MAX_GLYPH_DIMENSION || height > MAX_GLYPH_DIMENSION) {
        return ptr_to_jlong(emptyGlyph(advance));
    }
    const int bytesPerPixel = colorGlyph ? 4 : lcd && !colorGlyph ? 3 : 1;
    const size_t rowBytes = (size_t) width * bytesPerPixel, imageSize = rowBytes * height;
    auto *info = (GlyphInfo *) calloc(sizeof(GlyphInfo) + imageSize * resX * resY, 1);
    if (info == nullptr) {
        return ptr_to_jlong(emptyGlyph(advance));
    }
    info->advanceX = advance;
    info->advanceY = 0;
    info->width = (UInt16) width;
    info->height = (UInt16) height;
    info->rowBytes = (UInt16) rowBytes;
    info->managed = UNMANAGED_GLYPH;
    info->format = colorGlyph ? sun_font_StrikeCache_PIXEL_FORMAT_BGRA
                 : lcd ? sun_font_StrikeCache_PIXEL_FORMAT_LCD : sun_font_StrikeCache_PIXEL_FORMAT_GREYSCALE;
    info->subpixelResolutionX = (UInt8) resX;
    info->subpixelResolutionY = (UInt8) resY;
    info->topLeftX = (float) left - (floorGrid > 0 ? 0.5f / (float) resX : 0.0f);   // the half-step shift of the rounding-down emulation
    info->topLeftY = (float) top;
    info->cellInfo = nullptr;
    info->image = (UInt8 *) (info + 1);

    SkBitmap bitmap;
    const SkImageInfo imageInfo = colorGlyph ? SkImageInfo::Make(width, height, kBGRA_8888_SkColorType, kPremul_SkAlphaType)
                                 : colorCanvas ? SkImageInfo::MakeN32(width, height, kOpaque_SkAlphaType)
                                               : SkImageInfo::MakeA8(width, height);
    if (!bitmap.tryAllocPixels(imageInfo)) {
        return ptr_to_jlong(info);   // an empty image of the right size
    }
    // Linear coverage: no contrast boost and no gamma on the mask (Skia's
    // defaults are 0.5 and sRGB), as the JDK's CoreGraphics and FreeType
    // rasterizers deliver it; the text pipes apply the gamma themselves.
    SkSurfaceProps props(0, colorCanvas ? kRGB_H_SkPixelGeometry : kUnknown_SkPixelGeometry, 0.0f, 1.0f);
    SkCanvas canvas(bitmap, props);
    SkPaint paint;
    paint.setAntiAlias(aa);
    paint.setColor(colorCanvas ? SK_ColorBLACK : SK_ColorWHITE);
    if (darkenedGray) {
        // the outline, widened and filled: Skia's path rasterizer, not the host's
        paint.setStyle(SkPaint::kStrokeAndFill_Style);
        paint.setStrokeWidth(context->grayDarkening * font.getSize());
        paint.setStrokeJoin(SkPaint::kRound_Join);
    }
    // the glyph's design space in device pixels: size, synthetic italic and the transform's remainder
    SkMatrix glyphToDevice = SkMatrix::Scale(font.getSize(), font.getSize());
    if (font.getSkewX() != 0) {
        glyphToDevice.postConcat(SkMatrix::Skew(font.getSkewX(), 0));
    }
    if (!context->identityRemainder) {
        glyphToDevice.postConcat(context->remainder);
    }
    for (int sy = 0; sy < resY; sy++) {
        for (int sx = 0; sx < resX; sx++) {
            UInt8 *variant = info->image + imageSize * (size_t) (sy * resX + sx);
            // the variant's sub-pixel offset: the pipes' step, or the platform's rounded-down grid step
            const float offsetX = floorGrid > 0 ? (float) (sx / 2) / (float) floorGrid : (float) sx / resX;
            if (platformMask && SKPlatform_SmoothedGlyphMask(font.getTypeface(), glyph, glyphToDevice, (float) context->displayScale,
                                                             platformLuminance, lcd, platformStroke,
                                                             (float) -left + offsetX, (float) -top + (float) sy / resY,
                                                             width, height, variant)) {
                continue;
            }
            canvas.clear(colorCanvas && !colorGlyph ? SK_ColorWHITE : SK_ColorTRANSPARENT);
            canvas.save();
            canvas.translate((SkScalar) -left + offsetX, (SkScalar) -top + (SkScalar) sy / resY);
            if (!context->identityRemainder) {
                canvas.concat(context->remainder);
            }
            const SkPoint origin = {0, 0};
            canvas.drawGlyphs({&glyph, 1}, {&origin, 1}, {0, 0}, font, paint);
            canvas.restore();
            UInt8 *dst = info->image + imageSize * (size_t) (sy * resX + sx);
            for (int y = 0; y < height; y++) {
                if (colorGlyph) {
                    memcpy(dst + (size_t) y * rowBytes, bitmap.getAddr32(0, y), rowBytes);   // B, G, R, A premultiplied
                } else if (lcd) {
                    const uint32_t *src = bitmap.getAddr32(0, y);
                    UInt8 *row = dst + (size_t) y * rowBytes;
                    for (int x = 0; x < width; x++) {
                        const SkColor c = SkUnPreMultiply::PMColorToColor(src[x]);
                        row[3 * x] = (UInt8) (255 - SkColorGetR(c));
                        row[3 * x + 1] = (UInt8) (255 - SkColorGetG(c));
                        row[3 * x + 2] = (UInt8) (255 - SkColorGetB(c));
                    }
                } else if (smoothedGray) {
                    const uint32_t *src = bitmap.getAddr32(0, y);
                    UInt8 *row = dst + (size_t) y * rowBytes;
                    for (int x = 0; x < width; x++) {
                        const SkColor c = SkUnPreMultiply::PMColorToColor(src[x]);
                        row[x] = (UInt8) (255 - (SkColorGetR(c) + SkColorGetG(c) + SkColorGetB(c)) / 3);
                    }
                } else {
                    memcpy(dst + (size_t) y * rowBytes, bitmap.getAddr8(0, y), rowBytes);
                }
            }
        }
    }
    return ptr_to_jlong(info);
}

JNIEXPORT jboolean JNICALL
Java_sun_font_SKFontScaler_contextIsAxisAlignedNative(JNIEnv *env, jclass clazz, jlong pContext)
{
    auto *context = (SKScalerContext *) jlong_to_ptr(pContext);
    return context != nullptr && context->identityRemainder ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"

bool SKFontScaler_ResolveGlyph(jlong pContext, jint glyphCode, SkFont *font, SkGlyphID *glyph)
{
    auto *context = (SKScalerContext *) jlong_to_ptr(pContext);
    return context != nullptr && resolveGlyph(context, glyphCode, font, glyph);
}

bool SKFontScaler_Font(jlong pContext, SkFont *font)
{
    auto *context = (SKScalerContext *) jlong_to_ptr(pContext);
    if (context == nullptr) {
        return false;
    }
    *font = context->font;
    return true;
}

jint SKFontScaler_GrayMode(jlong pContext)
{
    auto *context = (SKScalerContext *) jlong_to_ptr(pContext);
    return context != nullptr ? context->grayMode : 0;
}

