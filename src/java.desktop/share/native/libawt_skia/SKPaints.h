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
#ifndef SKPaints_h_Included
#define SKPaints_h_Included

// The native side of sun.java2d.skia.SKPaints: the paints as BufferedPaints sends them, set on the
// render state (SKContext), and the SkPaint a draw takes from the render state (paint and composite).
// All functions run on the Skia queue flusher thread.

#include "jni.h"

#include "include/core/SkPaint.h"
#include "include/core/SkUnPreMultiply.h"

#include <algorithm>
#include <cstdint>
#include <optional>

namespace SKPaints {

// The paints arrive as BufferedPaints encodes them: a gradient axis as the coefficients of
// t = p0 * x + p1 * y + p3, a radial gradient as the matrix (m00 to m12) onto the unit circle,
// a texture as the coefficients of (u, v) = (xp0 * x + xp1 * y + xp3, yp0 * x + yp1 * y + yp3).
void setColor(jint pixel);
void resetPaint();
void setGradientPaint(jboolean cyclic, jdouble p0, jdouble p1, jdouble p3, jint pixel1, jint pixel2);
void setLinearGradientPaint(jboolean linear, jint cycleMethod, jint numStops,
                            jfloat p0, jfloat p1, jfloat p3,
                            const jfloat *fractions, const jint *pixels);
void setRadialGradientPaint(jboolean linear, jint cycleMethod, jint numStops,
                            jfloat m00, jfloat m01, jfloat m02, jfloat m10, jfloat m11, jfloat m12,
                            jfloat focusX, const jfloat *fractions, const jint *pixels);
void setTexturePaint(JNIEnv *env, jboolean filter, jlong sourceOps,
                     jdouble xp0, jdouble xp1, jdouble xp3, jdouble yp0, jdouble yp1, jdouble yp3);

// Paint for the current state (color or shader, composite); `antialiased` selects antialiasing.
[[nodiscard]] SkPaint makePaint(bool antialiased);
// The paint for coverage carried as the source's alpha (glyphs, mask tiles) under the current paint and
// composite: makePaint, but for a color under a rule that copies it onto an opaque destination.
[[nodiscard]] SkPaint coveragePaint();
// A paint for an image source under the current composite: no color, the extra alpha in the alpha.
[[nodiscard]] SkPaint imageSourcePaint();
// A color glyph's paints, drawn in turn (the second only if set): its alpha is the coverage and its straight
// color the source, applied as Java2D's general loops apply coverage (dst * (1 - coverage) + rule result *
// coverage). So a rule that copies the color draws the glyph, not its transparent box, and Clear clears by its
// shape; XOR is an image's XOR.
struct ColorGlyphPaints {
    SkPaint first;
    std::optional<SkPaint> second;
};
[[nodiscard]] ColorGlyphPaints colorGlyphPaints();

// IntArgbPre -> IntArgb with Skia's unpremultiply table, which gives what the software loops' div8table
// does for every translucent value (read back through an IntArgb blit: 0 of 32,639 differ). A channel
// above its alpha (XOR stores such pixels, as the loops' XOR does) reads as 255, as div8table clamps it.
[[nodiscard]] inline uint32_t unpremultiply(const uint32_t premultiplied) {
    const U8CPU alpha = premultiplied >> 24;
    if (alpha == 0 || alpha == UINT8_MAX) {
        return premultiplied;
    }
    const SkUnPreMultiply::Scale scale = SkUnPreMultiply::GetScale(alpha);
    auto channel = [&](const int shift) -> uint32_t {
        const U8CPU value = std::min<U8CPU>((premultiplied >> shift) & UINT8_MAX, alpha);
        return SkUnPreMultiply::ApplyScale(scale, value) << shift;
    };
    return alpha << 24 | channel(16) | channel(8) | channel(0);
}

} // namespace SKPaints

#endif /* SKPaints_h_Included */
