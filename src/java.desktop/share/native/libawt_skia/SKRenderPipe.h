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
#ifndef SKRenderPipe_h_Included
#define SKRenderPipe_h_Included

// The native side of sun.java2d.skia.SKRenderPipe (BufferedRenderPipe): lines, rects, polygons,
// scanlines, spans and parallelograms, in device coordinates. The non-AA ones go into the rect
// batch (SKRectBatch.h). The direct entry of a polygon larger than the render buffer
// (drawPolyNative) is SKRenderPipe.cpp's.
// All functions run on the Skia queue flusher thread.

#include "jni.h"

namespace SKRenderPipe {

// The parallelograms come as the other pipelines' do (MTLRenderer_FillParallelogram): the origin
// (x11, y11), the two edge vectors (dx21, dy21) and (dx12, dy12), and for a stroke the line width
// relative to each edge (lwr21, lwr12).
void fillRect(jint x, jint y, jint width, jint height);
void drawRect(jint x, jint y, jint width, jint height);
void drawLine(jint x1, jint y1, jint x2, jint y2);
void drawPixel(jint x, jint y);
void drawScanlines(jint count, const jint *scanlines);
void fillSpans(jint count, const jint *spans);
void drawPoly(jint pointCount, jboolean isClosed, jint transX, jint transY,
              const jint *xPoints, const jint *yPoints);
void fillParallelogram(bool antialiased, jfloat x11, jfloat y11, jfloat dx21, jfloat dy21, jfloat dx12, jfloat dy12);
void drawParallelogram(bool antialiased, jfloat x11, jfloat y11, jfloat dx21, jfloat dy21, jfloat dx12, jfloat dy12,
                       jfloat lwr21, jfloat lwr12);

} // namespace SKRenderPipe

#endif /* SKRenderPipe_h_Included */
