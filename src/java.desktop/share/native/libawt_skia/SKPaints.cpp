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
// The paints (SKPaints.h): colors, gradients, texture paint, the XOR blender, and the paint of a draw.
// All functions run on the Skia queue flusher thread.

#include "SKPaints.h"
#include "SKContext.h"
#include "SKGraphicsConfig.h"
#include "SKImageSources.h"
#include "SKSurfaceData.h"

#include "jlong.h"
#include "Trace.h"

#include "include/core/SkBlender.h"
#include "include/core/SkColorFilter.h"
#include "include/core/SkColorSpace.h"
#include "include/core/SkData.h"
#include "include/core/SkMatrix.h"
#include "include/core/SkSamplingOptions.h"
#include "include/core/SkSpan.h"
#include "include/core/SkTileMode.h"
#include "include/effects/SkGradient.h"
#include "include/effects/SkRuntimeEffect.h"

#include <array>
#include <cstdint>
#include <set>
#include <string_view>
#include <utility>
#include <vector>

namespace {

// Java2D hands us premultiplied IntArgbPre pixels; Skia wants straight SkColor.
SkColor unpremul(const jint pixel) {
    if (static_cast<unsigned>(pixel) >> 24 == 0) {
        return SK_ColorTRANSPARENT;
    }
    return SKPaints::unpremultiply(static_cast<uint32_t>(pixel));
}

// Skia samples shaders at pixel centers; Java's paint contexts and the Metal shaders
// (position - 0.5) evaluate a paint at the pixel's integer coordinates.
constexpr float HALF_PIXEL = 0.5f;
// A texture paint's nearest sample point, in texels past u (texturePaintShader).
constexpr float NEAREST_TEXEL_BIAS = 1.0f / 256;

// A half-pixel local translation makes Skia evaluate t(x, y) instead of
// t(x + 0.5, y + 0.5): visible as shifted seams in REPEAT gradients and as a
// small bias in every gradient and texture otherwise.
sk_sp<SkShader> atPixelOrigin(const sk_sp<SkShader> &shader) {
    return shader ? shader->makeWithLocalMatrix(SkMatrix::Translate(HALF_PIXEL, HALF_PIXEL)) : nullptr;
}

// Gradient stop pixels arrive as IntArgbPre (BufferedPaints); Skia wants straight colors.
SkColor4f argbPreToColor4f(const jint argbPre) {
    return SkColor4f::FromColor(unpremul(argbPre));
}

std::vector<SkColor4f> stopColors(const jint numStops, const jint *pixels) {
    std::vector<SkColor4f> colors(numStops);
    for (int stop = 0; stop < numStops; stop++) {
        colors[stop] = argbPreToColor4f(pixels[stop]);
    }
    return colors;
}

// The stops arrive premultiplied (BufferedPaints), and the OpenGL, D3D and Metal
// pipelines interpolate them so; the software paint contexts interpolate straight
// colors, which differs only between translucent stops.
SkGradient::Interpolation premulInterpolation(const bool linear = false) {
    SkGradient::Interpolation interpolation;
    interpolation.fInPremul = SkGradient::Interpolation::InPremul::kYes;
    if (linear) {
        // ColorSpaceType.LINEAR_RGB: Java interpolates in linear RGB and encodes the
        // result; the stops arrive linear (stopColorSpace), so interpolate there too.
        interpolation.fColorSpace = SkGradient::Interpolation::ColorSpace::kSRGBLinear;
    }
    return interpolation;
}

// With ColorSpaceType.LINEAR_RGB the stops are already linear; tell Skia so it
// interpolates in linear space and encodes back to the destination's sRGB.
sk_sp<SkColorSpace> stopColorSpace(const jboolean linear) {
    return linear ? SkColorSpace::MakeSRGBLinear() : nullptr;
}

// MultipleGradientPaint.CycleMethod ordinals as sent by BufferedPaints.
constexpr jint CYCLE_METHOD_REFLECT = 1, CYCLE_METHOD_REPEAT = 2;

// BufferedPaints.setGradientPaint places the two-color GradientPaint's point1 at t == 0.25
// and point2 at t == 0.75 (the cyclic gradient mirrors beyond them).
constexpr double GRADIENT_POINT1_T = 0.25, GRADIENT_POINT2_T = 0.75;

// At a REPEAT cycle's seam Skia's t can land an ulp below the integer where Java's lands on it and
// wraps, so a seam pixel may take the last stop instead of the first: a liberty, not biased away.
SkTileMode tileModeFor(const jint cycleMethod) {
    switch (cycleMethod) {
        case CYCLE_METHOD_REFLECT: return SkTileMode::kMirror;
        case CYCLE_METHOD_REPEAT:  return SkTileMode::kRepeat;
        default:                   return SkTileMode::kClamp;   // NO_CYCLE
    }
}

// Java encodes the gradient axis as t = p0*x + p1*y + p3 (device space); recover
// the two points where t == t0 and t == t1 on the axis through the origin's projection.
bool axisPoints(const double p0, const double p1, const double p3, const double t0, const double t1,
                SkPoint points[2]) {
    const double lengthSquared = p0 * p0 + p1 * p1;
    if (lengthSquared <= 0) {
        return false;
    }
    const double unitX = p0 / lengthSquared, unitY = p1 / lengthSquared;   // the point where t == 1, relative to t == 0
    const double originX = -p3 * unitX, originY = -p3 * unitY;             // a point with t == 0
    points[0] = SkPoint::Make(static_cast<SkScalar>(originX + t0 * unitX),
                              static_cast<SkScalar>(originY + t0 * unitY));
    points[1] = SkPoint::Make(static_cast<SkScalar>(originX + t1 * unitX),
                              static_cast<SkScalar>(originY + t1 * unitY));
    return true;
}

// Texture paint: Java encodes device -> image-normalized coordinates as
// u = xp0*x + xp1*y + xp3, v = yp0*x + yp1*y + yp3; the shader's local matrix
// maps device space to image pixels. Null if the mapping cannot be inverted.
sk_sp<SkShader> texturePaintShader(const sk_sp<SkImage> &image, const jboolean filter,
                                   const jdouble xp0, const jdouble xp1, const jdouble xp3,
                                   const jdouble yp0, const jdouble yp1, const jdouble yp3) {
    // (u, v) = (xp0*x + xp1*y + xp3, yp0*x + yp1*y + yp3) in [0,1]: device -> image.
    const auto scaled = [](const jdouble coefficient, const int size) {
        return static_cast<SkScalar>(coefficient * size);
    };
    const int width = image->width(), height = image->height();
    const SkMatrix toImage = SkMatrix::MakeAll(scaled(xp0, width),  scaled(xp1, width),  scaled(xp3, width),
                                               scaled(yp0, height), scaled(yp1, height), scaled(yp3, height),
                                               0, 0, 1);
    SkMatrix local;
    if (!toImage.invert(&local)) {
        return nullptr;
    }
    // Java (TexturePaintContext) evaluates u at the pixel's integer coordinates (atPixelOrigin).
    // Filtered, it blends floor(u) and floor(u) + 1 by u's fraction: a GPU's bilinear sample at
    // u + 0.5 texels, its texels' values sitting at their centers. Nearest, it takes floor(u),
    // which is what the GPU's nearest sample at u itself takes; the bias keeps an untransformed
    // texture, whose u is an integer at every pixel, from taking the texel below on float error
    // (the tile shifted by a pixel). Under a scale or rotation, a coordinate within the bias below a
    // texel border takes the next texel instead: a tie as the nearest-neighbor ties of scaled blits are.
    const float offset = filter ? HALF_PIXEL : NEAREST_TEXEL_BIAS;
    local.preTranslate(-offset, -offset);
    const SkSamplingOptions sampling = filter ? SkSamplingOptions(SkFilterMode::kLinear)
                                              : SkSamplingOptions(SkFilterMode::kNearest);
    return atPixelOrigin(image->makeShader(SkTileMode::kRepeat, SkTileMode::kRepeat, sampling, &local));
}

// The paint Java sent cannot be drawn (`why`): draws with it leave the destination unchanged
// (SKContext::setUnavailablePaint). Java sends a paint again at every transform change, so each
// reason is reported once.
void unavailablePaint(const char *why) {
    static std::set<std::string_view> reported;
    if (reported.insert(why).second) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "SKPaints: %s: draws with this paint change nothing", why);
    }
    SKContext::current().setUnavailablePaint();
}

// A color channel as the shaders' uniforms take it: 0..255 scaled to 0..1.
constexpr float CHANNEL_MAX = 255.0f;

// Java2D's XOR mode as the software loops apply it, in 8-bit channel arithmetic: for color paints the new
// destination is dst ^ color ^ xorPixel wherever the source has any coverage (the glyph loops XOR every pixel
// a glyph touches; the coverage is the source alpha: 1 for fills, the glyph value for text); for image sources
// dst ^ pixel ^ xorPixel with the pixel's straight color, where its alpha is at least 128 (the XOR blits test
// the pixel's sign bit). The destination's alpha stays, as the loops' XOR masks the alpha bits out (an
// XORed premultiplied color may then exceed its alpha, as in their surfaces). Metal weights the color by the
// coverage instead; Vulkan thresholds it as here.
// Runtime blenders are restricted to ES2 SkSL, which has no integer bit
// operations, so the XOR is done bit by bit (the 8 iterations of bxor).
sk_sp<SkBlender> xorBlender(const jint xorPixel, const SkColor color, const bool colorPaint) {
    static const SkRuntimeEffect *effect = [] {
        auto [compiled, errorText] = SkRuntimeEffect::MakeForBlender(SkString(R"(
            uniform half3 xorColor;
            uniform half3 srcColor;
            uniform half colorPaint;
            float3 bxor(float3 first, float3 second) {
                float3 result = float3(0.0);
                float bitValue = 1.0;
                for (int bit = 0; bit < 8; bit++) {
                    float3 firstBit = mod(floor(first / bitValue), 2.0);
                    float3 secondBit = mod(floor(second / bitValue), 2.0);
                    result += mod(firstBit + secondBit, 2.0) * bitValue;
                    bitValue *= 2.0;
                }
                return result;
            }
            half4 main(half4 src, half4 dst) {
                float3 xorValue = floor(float3(xorColor) * 255.0 + 0.5);
                float3 destination = floor(float3(dst.rgb) * 255.0 + 0.5);
                float3 source;
                if (colorPaint > 0.5) {
                    source = src.a > 0.0 ? bxor(floor(float3(srcColor) * 255.0 + 0.5), xorValue) : float3(0.0);
                } else if (src.a * 255.0 >= 127.5) {
                    source = bxor(floor(float3(src.rgb) / float(src.a) * 255.0 + 0.5), xorValue);
                } else {
                    source = float3(0.0);
                }
                return half4(half3(bxor(destination, source) / 255.0), dst.a);
            }
        )"));
        if (!compiled) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "SKPaints: XOR blender does not compile: %s", errorText.c_str());
        }
        return compiled.release();
    }();
    if (effect == nullptr) {
        return nullptr;
    }
    // The blender of the last (xorPixel, color, colorPaint): consecutive XOR draws share it.
    static jint lastPixel = 0;
    static SkColor lastColor = 0;
    static bool lastColorPaint = false;
    static sk_sp<SkBlender> last;
    if (!last || lastPixel != xorPixel || lastColor != color || lastColorPaint != colorPaint) {
        // the uniforms in declaration order: xorColor, srcColor, colorPaint
        const std::array uniforms = {
            static_cast<float>((xorPixel >> 16) & 0xff) / CHANNEL_MAX,
            static_cast<float>((xorPixel >> 8) & 0xff) / CHANNEL_MAX,
            static_cast<float>(xorPixel & 0xff) / CHANNEL_MAX,
            SkColorGetR(color) / CHANNEL_MAX, SkColorGetG(color) / CHANNEL_MAX, SkColorGetB(color) / CHANNEL_MAX,
            colorPaint ? 1.0f : 0.0f
        };
        last = effect->makeBlender(SkData::MakeWithCopy(uniforms.data(), uniforms.size() * sizeof(float)));
        lastPixel = xorPixel;
        lastColor = color;
        lastColorPaint = colorPaint;
    }
    return last;
}

// A Porter-Duff rule on an opaque destination: the blend mode, and the filter of the source, that store
// what the software loops store there.
struct OpaqueDestinationComposite {
    SkBlendMode blendMode;
    sk_sp<SkColorFilter> sourceFilter;   // null: the source as it is
};

// The source (0, 0, 0, 1) where its alpha is the given one, transparent elsewhere: a table filter, which
// works on the unpremultiplied components and so matches the alpha exactly.
sk_sp<SkColorFilter> opaqueBlackWhereAlphaIs(const uint8_t alpha) {
    constexpr size_t TABLE_SIZE = 256;
    static constexpr std::array<uint8_t, TABLE_SIZE> ZEROS{};
    std::array<uint8_t, TABLE_SIZE> alphaTable{};
    alphaTable[alpha] = UINT8_MAX;
    return SkColorFilters::TableARGB(alphaTable.data(), ZEROS.data(), ZEROS.data(), ZEROS.data());
}

// An opaque destination (a window, an opaque VolatileImage) keeps alpha 1. Skia's modes would store the
// rule's alpha instead: 0 after Clear, which a later SrcIn reads as transparent (and the window shows
// the premultiplied color, not the color the read-back unpremultiplies). The software loops take the
// destination alpha as 1, compute the rule's color Cr and alpha Ar, and store Cr / Ar (black for Ar 0),
// which in each case is Src, SrcOver or Dst of a source derived from the source color (alpha Sa):
//   Clear, SrcOut      black:                       Src of opaque black
//   Src, SrcIn         the source's straight color: Src of that color, opaque
//   SrcOver, SrcAtop   the source over the destination (Ar 1): SrcOver
//   DstIn, DstAtop     the destination, black for Sa 0: SrcOver of opaque black where Sa is 0
//   DstOut, Xor        the destination, black for Sa 1: SrcOver of opaque black where Sa is 1
//   Dst, DstOver       the destination:             Dst
// Each derived source leaves alpha 1 under any coverage.
OpaqueDestinationComposite opaqueDestinationComposite(const SkBlendMode blendMode) {
    static const sk_sp<SkColorFilter> opaqueBlack = SkColorFilters::Blend(SK_ColorBLACK, SkBlendMode::kSrc);
    // on the unpremultiplied color: its components, alpha 1
    static constexpr float OPAQUE_STRAIGHT_COLOR[20] = {
        1, 0, 0, 0, 0,
        0, 1, 0, 0, 0,
        0, 0, 1, 0, 0,
        0, 0, 0, 0, 1,
    };
    static const sk_sp<SkColorFilter> opaqueStraightColor = SkColorFilters::Matrix(OPAQUE_STRAIGHT_COLOR);
    static const sk_sp<SkColorFilter> blackWhereTransparent = opaqueBlackWhereAlphaIs(0);
    static const sk_sp<SkColorFilter> blackWhereOpaque = opaqueBlackWhereAlphaIs(UINT8_MAX);
    switch (blendMode) {
        case SkBlendMode::kClear:
        case SkBlendMode::kSrcOut:  return {.blendMode = SkBlendMode::kSrc, .sourceFilter = opaqueBlack};
        case SkBlendMode::kSrc:
        case SkBlendMode::kSrcIn:   return {.blendMode = SkBlendMode::kSrc, .sourceFilter = opaqueStraightColor};
        case SkBlendMode::kSrcATop: return {.blendMode = SkBlendMode::kSrcOver, .sourceFilter = nullptr};
        case SkBlendMode::kDstIn:
        case SkBlendMode::kDstATop: return {.blendMode = SkBlendMode::kSrcOver, .sourceFilter = blackWhereTransparent};
        case SkBlendMode::kDstOut:
        case SkBlendMode::kXor:     return {.blendMode = SkBlendMode::kSrcOver, .sourceFilter = blackWhereOpaque};
        case SkBlendMode::kDstOver: return {.blendMode = SkBlendMode::kDst, .sourceFilter = nullptr};
        default:                    return {.blendMode = blendMode, .sourceFilter = nullptr};   // SrcOver, Dst
    }
}

// The current composite on a paint: a blend mode, or the XOR blender (colorPaint: the XOR of a
// color paint takes the paint color; false for an image source). The XOR blender keeps the
// destination's alpha by itself.
void applyComposite(SkPaint &paint, const bool colorPaint = true) {
    if (const SKContext &context = SKContext::current(); context.xorMode()) {
        paint.setBlender(xorBlender(context.xorPixel(), context.color(), colorPaint && !context.shader()));
    } else if (const SKSurfaceDataOps *destination = context.destination();
               destination != nullptr && destination->isOpaque) {
        auto [blendMode, sourceFilter] = opaqueDestinationComposite(context.blendMode());
        paint.setBlendMode(blendMode);
        paint.setColorFilter(std::move(sourceFilter));
    } else {
        paint.setBlendMode(context.blendMode());
    }
}

} // namespace

void SKPaints::setColor(const jint pixel) {
    SKContext::current().setColorPaint(unpremul(pixel));
}

void SKPaints::resetPaint() {
    SKContext::current().setShaderPaint(nullptr);
}

void SKPaints::setGradientPaint(const jboolean cyclic, const jdouble p0, const jdouble p1, const jdouble p3,
                                const jint pixel1, const jint pixel2) {
    SKContext &context = SKContext::current();
    const SkColor4f colors[2] = { argbPreToColor4f(pixel1), argbPreToColor4f(pixel2) };
    SkPoint points[2];
    if (!axisPoints(p0, p1, p3, GRADIENT_POINT1_T, GRADIENT_POINT2_T, points)) {
        // coinciding points: GradientPaintContext's gradient is the first color everywhere, cyclic or not
        context.setShaderPaint(SkShaders::Color(colors[0], nullptr));
        return;
    }
    const SkGradient gradient(SkGradient::Colors(SkSpan(colors, 2), cyclic ? SkTileMode::kMirror : SkTileMode::kClamp),
                              premulInterpolation());
    context.setShaderPaint(atPixelOrigin(SkShaders::LinearGradient(points, gradient)));
}

void SKPaints::setLinearGradientPaint(const jboolean linear, const jint cycleMethod, const jint numStops,
                                      const jfloat p0, const jfloat p1, const jfloat p3,
                                      const jfloat *fractions, const jint *pixels) {
    SKContext &context = SKContext::current();
    SkPoint points[2];
    if (!axisPoints(p0, p1, p3, 0.0, 1.0, points) || numStops <= 0) {
        unavailablePaint("a linear gradient without an axis or stops");
        return;
    }
    std::vector<SkColor4f> colors = stopColors(numStops, pixels);
    const SkGradient gradient(SkGradient::Colors(SkSpan(colors.data(), numStops), SkSpan(fractions, numStops),
                                                 tileModeFor(cycleMethod), stopColorSpace(linear)),
                              premulInterpolation(linear));
    context.setShaderPaint(atPixelOrigin(SkShaders::LinearGradient(points, gradient)));
}

void SKPaints::setRadialGradientPaint(const jboolean linear, const jint cycleMethod, const jint numStops,
                                      const jfloat m00, const jfloat m01, const jfloat m02,
                                      const jfloat m10, const jfloat m11, const jfloat m12,
                                      const jfloat focusX, const jfloat *fractions, const jint *pixels) {
    SKContext &context = SKContext::current();
    if (numStops <= 0) {
        unavailablePaint("a radial gradient without stops");
        return;
    }
    // m* maps device space to the unit circle (center (0,0), radius 1, focus (focusX, 0)).
    const SkMatrix toUnit = SkMatrix::MakeAll(m00, m01, m02, m10, m11, m12, 0, 0, 1);
    SkMatrix local;
    if (!toUnit.invert(&local)) {
        unavailablePaint("a radial gradient whose mapping cannot be inverted");
        return;
    }
    std::vector<SkColor4f> colors = stopColors(numStops, pixels);
    const SkGradient gradient(SkGradient::Colors(SkSpan(colors.data(), numStops), SkSpan(fractions, numStops),
                                                 tileModeFor(cycleMethod), stopColorSpace(linear)),
                              premulInterpolation(linear));
    context.setShaderPaint(atPixelOrigin(SkShaders::TwoPointConicalGradient(SkPoint::Make(focusX, 0), 0.0f,
                                                                       SkPoint::Make(0, 0), 1.0f, gradient, &local)));
}

void SKPaints::setTexturePaint(JNIEnv *env, const jboolean filter, const jlong sourceOps,
                               const jdouble xp0, const jdouble xp1, const jdouble xp3,
                               const jdouble yp0, const jdouble yp1, const jdouble yp3) {
    SKContext &context = SKContext::current();
    // SKPaints accepts a TexturePaint once the proxy has cached its image as a Skia
    // texture (as in the Metal pipeline), but BufferedPaints looks the image up again
    // when it sends the paint: if the cached texture was dropped in between (Image.flush,
    // a modification from another thread), the source is the image's software surface,
    // which is uploaded here instead.
    auto *source = static_cast<SurfaceDataOps *>(jlong_to_ptr(sourceOps));
    SKSurfaceDataOps *skiaSource = SKSurfaceDataOps::from(source);
    if (source != nullptr && skiaSource == nullptr) {
        const auto [image, graphicsConfig] = SKImageSources::imageOfSoftwareSurface(env, source);
        if (!image) {
            unavailablePaint("a texture paint whose software source cannot be uploaded");
            return;
        }
        // uploaded with the destination's recorder (SKContext::uploadPixmap); the shader owns its image: no
        // source to forget
        graphicsConfig->trackTexturePaintImage(image);
        if (sk_sp<SkShader> shader = texturePaintShader(image, filter, xp0, xp1, xp3, yp0, yp1, yp3)) {
            context.setShaderPaint(std::move(shader), graphicsConfig);
        } else {
            unavailablePaint("a texture paint whose mapping cannot be inverted");
        }
        return;
    }
    if (source == nullptr) {
        unavailablePaint("a texture paint without a source");
        return;
    }
    if (context.graphicsConfig() != nullptr && !SKImageSources::sourceUsable(skiaSource, "SKPaints::setTexturePaint")) {
        unavailablePaint("a texture paint whose source cannot be used here");
        return;
    }
    // a linked source's image is of the destination's context, a view of another surface of the context
    // getImage makes it with; either comes with that context
    const auto [image, graphicsConfig] = SKImageSources::isLinkedSource(skiaSource)
                                         ? SKImageSources::blitSourceImage(skiaSource)
                                         : context.getImage(skiaSource);
    if (!image) {
        unavailablePaint("a texture paint whose source has no image");
        return;
    }
    graphicsConfig->trackTexturePaintImage(image);
    if (sk_sp<SkShader> shader = texturePaintShader(image, filter, xp0, xp1, xp3, yp0, yp1, yp3)) {
        context.setShaderPaint(std::move(shader), graphicsConfig, skiaSource);
    } else {
        unavailablePaint("a texture paint whose mapping cannot be inverted");
    }
}

SkPaint SKPaints::imageSourcePaint() {
    SkPaint paint;
    applyComposite(paint, false);
    paint.setAlphaf(SKContext::current().extraAlpha());
    return paint;
}

SKPaints::ColorGlyphPaints SKPaints::colorGlyphPaints() {
    const SKContext &context = SKContext::current();
    if (context.xorMode()) {
        return {.first = imageSourcePaint()};
    }
    const SKSurfaceDataOps *destination = context.destination();
    const bool opaque = destination != nullptr && destination->isOpaque;
    SkPaint paint;   // SrcOver of the glyph
    switch (context.blendMode()) {
        case SkBlendMode::kClear:
            // dst * (1 - coverage); an opaque destination keeps its alpha: the glyph's shape in black
            if (opaque) {
                paint.setColorFilter(SkColorFilters::Blend(SK_ColorBLACK, SkBlendMode::kSrcIn));
            } else {
                paint.setBlendMode(SkBlendMode::kDstOut);
            }
            return {.first = paint};
        case SkBlendMode::kSrc:
        case SkBlendMode::kSrcIn:
            // an opaque destination stores the straight color whatever the extra alpha: SrcOver of the glyph
            if (opaque) {
                return {.first = paint};
            }
            {
                // dst * (1 - coverage) + glyph * extra alpha, as SKCoverageCopyTwoPass for gray glyphs
                SkPaint clearPaint;
                clearPaint.setBlendMode(SkBlendMode::kDstOut);
                paint.setBlendMode(SkBlendMode::kPlus);
                paint.setAlphaf(context.extraAlpha());
                return {.first = clearPaint, .second = paint};
            }
        default:
            // SrcOver (any extra alpha) and an opaque color's copying rules: SrcOver at the extra alpha
            paint.setAlphaf(context.extraAlpha());
            return {.first = paint};
    }
}

SkPaint SKPaints::coveragePaint() {
    const SKContext &context = SKContext::current();
    const SKSurfaceDataOps *destination = context.destination();
    if (const SkBlendMode blendMode = context.blendMode();
            context.paintUnavailable() || context.xorMode() || context.shader() || destination == nullptr
            || (blendMode != SkBlendMode::kSrc && blendMode != SkBlendMode::kSrcIn && blendMode != SkBlendMode::kClear)) {
        return makePaint(false);
    }
    // A rule that copies the color (Src, SrcIn on an opaque destination, Clear with its transparent black), applied
    // by the coverage: dst * (1 - coverage) + result * coverage, as Java2D's general loops apply coverage to every
    // rule. The coverage is the source's alpha here, so the rule itself would overwrite the whole glyph or tile.
    // Where the result is opaque, that is SrcOver of it: on an opaque destination, which stores the straight color
    // made opaque (black under Clear), and for an opaque color. A translucent color or Clear on a translucent
    // destination keeps the rule, for SKCoverageCopyTwoPass (glyphs; mask fills do not take it, getMaskFill).
    const SkColor color = context.color();
    if (!destination->isOpaque && SkColorGetA(color) != UINT8_MAX) {
        return makePaint(false);
    }
    SkPaint paint;
    paint.setColor(SkColorSetA(color, UINT8_MAX));
    return paint;
}

SkPaint SKPaints::makePaint(const bool antialiased) {
    const SKContext &context = SKContext::current();
    SkPaint paint;
    if (context.paintUnavailable()) {
        paint.setBlendMode(SkBlendMode::kDst);   // the destination as it is, under every rule
        return paint;
    }
    paint.setAntiAlias(antialiased);
    applyComposite(paint);
    if (context.shader()) {
        paint.setShader(context.shader());
        paint.setColor4f(SkColor4f{.fR = 1, .fG = 1, .fB = 1, .fA = context.extraAlpha()});
    } else {
        // SunGraphics2D.validateColor() already applied the composite's extra
        // alpha to the color pixel; do not apply it twice.
        paint.setColor(context.color());
    }
    return paint;
}
