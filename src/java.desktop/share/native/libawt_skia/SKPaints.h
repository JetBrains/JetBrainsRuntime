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

#include <cstdint>

namespace SKPaints {

// The paints arrive as BufferedPaints encodes them: a gradient axis as the coefficients of
// t = p0 * x + p1 * y + p3, a radial gradient as the matrix (m00 .. m12) onto the unit circle,
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
// A paint for an image source under the current composite: no color, the extra alpha in the alpha.
[[nodiscard]] SkPaint imageSourcePaint();

// IntArgbPre -> IntArgb exactly as AlphaMath.c's div8table does it: a channel is divided by the
// alpha with the table's reciprocal (255 << 24, rounded) and rounding term (1 << 23).
[[nodiscard]] inline uint32_t unpremulJava(uint32_t premultiplied) {
    constexpr uint32_t CHANNEL_MASK = 0xff;
    const uint32_t alpha = premultiplied >> 24;
    if (alpha == 0 || alpha == 255) {
        return premultiplied;
    }
    const uint32_t reciprocal = ((255u << 24) + alpha / 2) / alpha;
    auto divide = [&](uint32_t channel) -> uint32_t {
        return channel >= alpha ? 255u : (((1u << 23) + channel * reciprocal) >> 24);
    };
    return (alpha << 24)
           | (divide((premultiplied >> 16) & CHANNEL_MASK) << 16)
           | (divide((premultiplied >> 8) & CHANNEL_MASK) << 8)
           | divide(premultiplied & CHANNEL_MASK);
}

} // namespace SKPaints

#endif /* SKPaints_h_Included */
