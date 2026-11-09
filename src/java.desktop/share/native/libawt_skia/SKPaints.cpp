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
#include "include/core/SkColorSpace.h"
#include "include/core/SkData.h"
#include "include/core/SkMatrix.h"
#include "include/core/SkSamplingOptions.h"
#include "include/core/SkSpan.h"
#include "include/core/SkTileMode.h"
#include "include/effects/SkGradient.h"
#include "include/effects/SkRuntimeEffect.h"

#include <array>
#include <vector>

namespace {

// Java2D hands us premultiplied IntArgbPre pixels; Skia wants straight SkColor.
SkColor unpremul(jint pixel) {
    if ((((unsigned) pixel) >> 24) == 0) {
        return SK_ColorTRANSPARENT;
    }
    return (SkColor) SKPaints::unpremulJava((uint32_t) pixel);
}

// Skia samples shaders at pixel centers; Java's paint contexts and the Metal shaders
// (position - 0.5) evaluate a paint at the pixel's integer coordinates.
constexpr float HALF_PIXEL = 0.5f;

// A half-pixel local translation makes Skia evaluate t(x, y) instead of
// t(x + 0.5, y + 0.5): visible as shifted seams in REPEAT gradients and as a
// small bias in every gradient and texture otherwise.
sk_sp<SkShader> atPixelOrigin(sk_sp<SkShader> shader) {
    return shader ? shader->makeWithLocalMatrix(SkMatrix::Translate(HALF_PIXEL, HALF_PIXEL)) : nullptr;
}

// Gradient stop pixels arrive as IntArgbPre (BufferedPaints); Skia wants straight colors.
SkColor4f argbPreToColor4f(jint argbPre) {
    return SkColor4f::FromColor(unpremul(argbPre));
}

std::vector<SkColor4f> stopColors(jint numStops, const jint *pixels) {
    std::vector<SkColor4f> colors(numStops);
    for (int stop = 0; stop < numStops; stop++) {
        colors[stop] = argbPreToColor4f(pixels[stop]);
    }
    return colors;
}

// Java (and the Metal shaders) interpolate the premultiplied stop pixels; with
// straight interpolation a transparent stop would fade through its color.
SkGradient::Interpolation premulInterpolation(bool linear = false) {
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
sk_sp<SkColorSpace> stopColorSpace(jboolean linear) {
    return linear ? SkColorSpace::MakeSRGBLinear() : nullptr;
}

// REPEAT: Java evaluates t in float and the boundary pixel of a cycle usually
// lands on the integer and wraps to the first stop; Skia's evaluation order can
// leave it an ulp below and show the last stop instead. A bias of 1e-5 in t
// (a 20000th of a cycle) makes the boundary pixel wrap the same way.
constexpr float REPEAT_SEAM_BIAS = 1e-5f;

// MultipleGradientPaint.CycleMethod ordinals as sent by BufferedPaints.
constexpr jint CYCLE_METHOD_REFLECT = 1, CYCLE_METHOD_REPEAT = 2;

// BufferedPaints.setGradientPaint places the two-color GradientPaint's point1 at t == 0.25
// and point2 at t == 0.75 (the cyclic gradient mirrors beyond them).
constexpr double GRADIENT_POINT1_T = 0.25, GRADIENT_POINT2_T = 0.75;

SkTileMode tileModeFor(jint cycleMethod) {
    switch (cycleMethod) {
        case CYCLE_METHOD_REFLECT: return SkTileMode::kMirror;
        case CYCLE_METHOD_REPEAT:  return SkTileMode::kRepeat;
        default:                   return SkTileMode::kClamp;   // NO_CYCLE
    }
}

// Java encodes the gradient axis as t = p0*x + p1*y + p3 (device space); recover
// the two points where t == t0 and t == t1 on the axis through the origin's projection.
bool axisPoints(double p0, double p1, double p3, double t0, double t1, SkPoint points[2]) {
    const double lengthSquared = p0 * p0 + p1 * p1;
    if (lengthSquared <= 0) {
        return false;
    }
    const double unitX = p0 / lengthSquared, unitY = p1 / lengthSquared;   // the point where t == 1, relative to t == 0
    const double originX = -p3 * unitX, originY = -p3 * unitY;             // a point with t == 0
    points[0] = SkPoint::Make((SkScalar) (originX + t0 * unitX), (SkScalar) (originY + t0 * unitY));
    points[1] = SkPoint::Make((SkScalar) (originX + t1 * unitX), (SkScalar) (originY + t1 * unitY));
    return true;
}

// Texture paint: Java encodes device -> image-normalized coordinates as
// u = xp0*x + xp1*y + xp3, v = yp0*x + yp1*y + yp3; the shader's local matrix
// maps device space to image pixels. Null if the mapping cannot be inverted.
sk_sp<SkShader> texturePaintShader(const sk_sp<SkImage> &image, jboolean filter,
                                   jdouble xp0, jdouble xp1, jdouble xp3, jdouble yp0, jdouble yp1, jdouble yp3) {
    // (u, v) = (xp0*x + xp1*y + xp3, yp0*x + yp1*y + yp3) in [0,1]: device -> image.
    SkMatrix toImage = SkMatrix::MakeAll((SkScalar)(xp0 * image->width()),  (SkScalar)(xp1 * image->width()),  (SkScalar)(xp3 * image->width()),
                                         (SkScalar)(yp0 * image->height()), (SkScalar)(yp1 * image->height()), (SkScalar)(yp3 * image->height()),
                                         0, 0, 1);
    SkMatrix local;
    if (!toImage.invert(&local)) {
        return nullptr;
    }
    // Java (TexturePaintContext) takes texel floor(u) at the pixel's integer coordinates
    // (atPixelOrigin) and filters between floor(u) and floor(u) + 1; a GPU texel's value
    // sits at its center, so the sample point is u + 0.5. Without it every sample of an
    // untransformed texture lands on a texel edge and nearest sampling rounds either way
    // (the tile shifted by a pixel).
    local.preTranslate(-HALF_PIXEL, -HALF_PIXEL);
    SkSamplingOptions sampling = filter ? SkSamplingOptions(SkFilterMode::kLinear) : SkSamplingOptions(SkFilterMode::kNearest);
    return atPixelOrigin(image->makeShader(SkTileMode::kRepeat, SkTileMode::kRepeat, sampling, &local));
}

// A color channel as the shaders' uniforms take it: 0..255 scaled to 0..1.
constexpr float CHANNEL_MAX = 255.0f;

// Java2D's XOR mode, as the Metal pipeline implements it (frag_*_xorMode):
// for color paints dst' = dst ^ ((color ^ xorPixel) * coverage), coverage being
// the source alpha (1 for fills, the glyph/mask value for text and shapes);
// for image sources dst' = dst ^ pixel ^ xorPixel. 8-bit channel arithmetic.
// Runtime blenders are restricted to ES2 SkSL, which has no integer bit
// operations, so the XOR is done bit by bit (the 8 iterations of bxor).
sk_sp<SkBlender> xorBlender(jint xorPixel, SkColor color, bool colorPaint) {
    static const SkRuntimeEffect *effect = [] {
        auto result = SkRuntimeEffect::MakeForBlender(SkString(R"(
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
                    float3 colorXor = bxor(floor(float3(srcColor) * 255.0 + 0.5), xorValue);
                    source = floor(colorXor * float(src.a) + 0.5);
                } else {
                    source = bxor(floor(float3(src.rgb) * 255.0 + 0.5), xorValue);
                }
                return half4(half3(bxor(destination, source) / 255.0), src.a + (1.0 - src.a) * dst.a);
            }
        )"));
        if (!result.effect) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "SKPaints: XOR blender does not compile: %s", result.errorText.c_str());
        }
        return result.effect.release();
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
        const std::array<float, 7> uniforms = {
            ((xorPixel >> 16) & 0xff) / CHANNEL_MAX, ((xorPixel >> 8) & 0xff) / CHANNEL_MAX, (xorPixel & 0xff) / CHANNEL_MAX,
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

// The current composite on a paint: a blend mode, or the XOR blender (colorPaint: the XOR of a
// color paint takes the paint color; false for an image source).
void applyComposite(SkPaint &paint, bool colorPaint = true) {
    const SKContext &context = SKContext::current();
    if (context.xorMode()) {
        paint.setBlender(xorBlender(context.xorPixel(), context.color(), colorPaint && !context.shader()));
    } else {
        paint.setBlendMode(context.blendMode());
    }
}

} // namespace

void SKPaints::setColor(jint pixel) {
    SKContext::current().setColorPaint(unpremul(pixel));
}

void SKPaints::resetPaint() {
    SKContext::current().setShaderPaint(nullptr);
}

void SKPaints::setGradientPaint(jboolean cyclic, jdouble p0, jdouble p1, jdouble p3, jint pixel1, jint pixel2) {
    SKContext &context = SKContext::current();
    SkPoint points[2];
    if (!axisPoints(p0, p1, p3, GRADIENT_POINT1_T, GRADIENT_POINT2_T, points)) {
        context.setShaderPaint(nullptr);
        return;
    }
    SkColor4f colors[2] = { argbPreToColor4f(pixel1), argbPreToColor4f(pixel2) };
    SkGradient gradient(SkGradient::Colors(SkSpan(colors, 2), cyclic ? SkTileMode::kMirror : SkTileMode::kClamp),
                        premulInterpolation());
    context.setShaderPaint(atPixelOrigin(SkShaders::LinearGradient(points, gradient)));
}

void SKPaints::setLinearGradientPaint(jboolean linear, jint cycleMethod, jint numStops,
                                      jfloat p0, jfloat p1, jfloat p3,
                                      const jfloat *fractions, const jint *pixels) {
    SKContext &context = SKContext::current();
    SkPoint points[2];
    if (!axisPoints(p0, p1, p3, 0.0, 1.0, points) || numStops <= 0) {
        context.setShaderPaint(nullptr);
        return;
    }
    std::vector<SkColor4f> colors = stopColors(numStops, pixels);
    if (cycleMethod == CYCLE_METHOD_REPEAT) {
        const SkVector axis = points[1] - points[0];
        points[0] -= axis * REPEAT_SEAM_BIAS;
        points[1] -= axis * REPEAT_SEAM_BIAS;
    }
    SkGradient gradient(SkGradient::Colors(SkSpan(colors.data(), numStops), SkSpan(fractions, numStops),
                                           tileModeFor(cycleMethod), stopColorSpace(linear)),
                        premulInterpolation(linear));
    context.setShaderPaint(atPixelOrigin(SkShaders::LinearGradient(points, gradient)));
}

void SKPaints::setRadialGradientPaint(jboolean linear, jint cycleMethod, jint numStops,
                                      jfloat m00, jfloat m01, jfloat m02, jfloat m10, jfloat m11, jfloat m12,
                                      jfloat focusX, const jfloat *fractions, const jint *pixels) {
    SKContext &context = SKContext::current();
    if (numStops <= 0) {
        context.setShaderPaint(nullptr);
        return;
    }
    // m* maps device space to the unit circle (center (0,0), radius 1, focus (focusX, 0)).
    SkMatrix toUnit = SkMatrix::MakeAll(m00, m01, m02, m10, m11, m12, 0, 0, 1);
    SkMatrix local;
    if (!toUnit.invert(&local)) {
        context.setShaderPaint(nullptr);
        return;
    }
    std::vector<SkColor4f> colors = stopColors(numStops, pixels);
    SkGradient gradient(SkGradient::Colors(SkSpan(colors.data(), numStops), SkSpan(fractions, numStops),
                                           tileModeFor(cycleMethod), stopColorSpace(linear)),
                        premulInterpolation(linear));
    // t = d / r, so a slightly smaller radius biases t upward at the cycle boundary
    const float radius = cycleMethod == CYCLE_METHOD_REPEAT ? 1.0f / (1.0f + REPEAT_SEAM_BIAS) : 1.0f;
    context.setShaderPaint(atPixelOrigin(SkShaders::TwoPointConicalGradient(SkPoint::Make(focusX, 0), 0.0f,
                                                                       SkPoint::Make(0, 0), radius, gradient, &local)));
}

void SKPaints::setTexturePaint(JNIEnv *env, jboolean filter, jlong sourceOps,
                               jdouble xp0, jdouble xp1, jdouble xp3, jdouble yp0, jdouble yp1, jdouble yp3) {
    SKContext &context = SKContext::current();
    // SKPaints accepts a TexturePaint once the proxy has cached its image as a Skia
    // texture (as in the Metal pipeline), but BufferedPaints looks the image up again
    // when it sends the paint: if the cached texture was dropped in between (Image.flush,
    // a modification from another thread), the source is the image's software surface,
    // which is uploaded here instead.
    SurfaceDataOps *source = (SurfaceDataOps *) jlong_to_ptr(sourceOps);
    if (source != nullptr && !SKSurfaceData::isSkiaOps(source)) {
        const SKOwnedImage uploaded = SKImageSources::imageOfSoftwareSurface(env, source);
        if (!uploaded.image) {
            J2dRlsTraceLn(J2D_TRACE_WARNING, "SKPaints::setTexturePaint: cannot upload the software source %p", source);
            context.setShaderPaint(nullptr);
            return;
        }
        // uploaded with the destination's recorder (SKContext::uploadPixmap); the shader owns its image: no
        // source to forget
        uploaded.graphicsConfig->trackTexturePaintImage(uploaded.image);
        context.setShaderPaint(texturePaintShader(uploaded.image, filter, xp0, xp1, xp3, yp0, yp1, yp3),
                               uploaded.graphicsConfig);
        return;
    }
    if (source == nullptr) {
        context.setShaderPaint(nullptr);
        return;
    }
    SKSDOps *skiaSource = (SKSDOps *) source;
    if (context.graphicsConfig() != nullptr && !SKImageSources::sourceUsable(skiaSource, "SKPaints::setTexturePaint")) {
        context.setShaderPaint(nullptr);
        return;
    }
    // a linked source's image is of the destination's context, a view of another surface of the context
    // getImage makes it with; either comes with that context
    const SKOwnedImage owned = SKImageSources::isLinkedSource(skiaSource) ? SKImageSources::blitSourceImage(skiaSource)
                                                                          : context.getImage(skiaSource);
    if (!owned.image) {
        context.setShaderPaint(nullptr);
        return;
    }
    owned.graphicsConfig->trackTexturePaintImage(owned.image);
    context.setShaderPaint(texturePaintShader(owned.image, filter, xp0, xp1, xp3, yp0, yp1, yp3), owned.graphicsConfig,
                           skiaSource);
}

SkPaint SKPaints::imageSourcePaint() {
    SkPaint paint;
    applyComposite(paint, false);
    paint.setAlphaf(SKContext::current().extraAlpha());
    return paint;
}

SkPaint SKPaints::makePaint(bool antialiased) {
    const SKContext &context = SKContext::current();
    SkPaint paint;
    paint.setAntiAlias(antialiased);
    applyComposite(paint);
    if (context.shader()) {
        paint.setShader(context.shader());
        paint.setColor4f(SkColor4f{1, 1, 1, context.extraAlpha()});
    } else {
        // SunGraphics2D.validateColor() already applied the composite's extra
        // alpha to the color pixel; do not apply it twice.
        paint.setColor(context.color());
    }
    return paint;
}
