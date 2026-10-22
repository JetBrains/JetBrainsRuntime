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

#include <cmath>
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

namespace {

struct SKScaler {
    sk_sp<SkTypeface> typeface;
};

// One per FontStrikeDesc: the glyph transform split into the SkFont's size and
// the remaining matrix (device transform, skew, non-uniform scale), which the
// glyph images and outlines are mapped through.
struct SKScalerContext {
    SkFont font;
    SkMatrix remainder;   // glyphTx / size
    bool fractionalMetrics = false;
    bool identityRemainder = true;
    double deviceScale = 1.0;   // whole-pixel advances are rounded in user space when it is not 1
};

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
    return ptr_to_jlong(new SKScaler{ std::move(typeface) });
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
                                               jint aa, jint fm, jfloat boldness, jfloat italic, jdouble deviceScale)
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
    context->font = SkFont(scaler->typeface, (SkScalar) size);
    // AffineTransform order: m00 m10 m01 m11
    context->remainder = SkMatrix::MakeAll((SkScalar) (m[0] / size), (SkScalar) (m[2] / size), 0,
                                           (SkScalar) (m[1] / size), (SkScalar) (m[3] / size), 0,
                                           0, 0, 1);
    context->identityRemainder = context->remainder.isIdentity();
    context->fractionalMetrics = fm == sun_awt_SunHints_INTVAL_FRACTIONALMETRICS_ON;
    context->deviceScale = deviceScale > 0 ? deviceScale : 1.0;
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
    if (context == nullptr || glyphCode < 0 || glyphCode > 0xffff) {
        return 0;
    }
    const SkGlyphID glyph = (SkGlyphID) glyphCode;
    SkScalar width = 0;
    context->font.getWidths({&glyph, 1}, {&width, 1});
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
    std::optional<SkPath> path = context != nullptr && glyphCode >= 0 && glyphCode <= 0xffff
                                 ? context->font.getPath((SkGlyphID) glyphCode) : std::nullopt;
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

// Glyph images (StrikeCache's GlyphInfo) follow in the next step of the text
// track; 0 means "no image" to the strike.
JNIEXPORT jlong JNICALL
Java_sun_font_SKFontScaler_getGlyphImageNative(JNIEnv *env, jclass clazz, jlong pContext, jint glyphCode)
{
    return 0;
}

} // extern "C"
