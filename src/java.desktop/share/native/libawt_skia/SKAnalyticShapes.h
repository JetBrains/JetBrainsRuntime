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
#ifndef SKAnalyticShapes_h_Included
#define SKAnalyticShapes_h_Included

// The native side of sun.java2d.skia.SKAnalyticShapes: antialiased shapes rasterized by Skia.
// All functions run on the Skia queue flusher thread.

#include "jni.h"

namespace SKAnalyticShapes {

// kind: SKAnalyticShapes.KIND_* (rect, round rect, oval with params x y w h [arcw arch], or a path
// with types/coords). xform is an AffineTransform matrix (m00 m10 m01 m11 m02 m12) applied around
// the draw; stroke (DRAW only): width, cap, join, miter limit. Dashed strokes never arrive: Java's
// stroker dashes them and they come as fills.
void drawShape(bool fill, jint kind, const jfloat *xform, const jfloat *stroke,
               const jfloat *params, jint windingRule, jint numTypes, const jint *types,
               jint numCoords, const jfloat *coords);

} // namespace SKAnalyticShapes

#endif /* SKAnalyticShapes_h_Included */
