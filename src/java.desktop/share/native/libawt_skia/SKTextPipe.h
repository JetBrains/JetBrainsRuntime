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
#ifndef SKTextPipe_h_Included
#define SKTextPipe_h_Included

// The native side of sun.java2d.skia.SKTextPipe: glyph lists through the glyph caches
// (SKGlyphCache.h), grayscale, color and LCD glyphs, and the LCD compositing (blend, twoPass,
// accumulate). The direct entry of a glyph list larger than the render buffer
// (drawGlyphListNative) is SKTextPipe.cpp's.
// All functions run on the Skia queue flusher thread.

#include "jni.h"

class SKGraphicsConfig;

namespace SKTextPipe {

void drawGlyphList(jint numGlyphs, jboolean usePositions, jboolean subPixPos,
                   jboolean rgbOrder, jint lcdContrast,
                   jfloat glyphListOrigX, jfloat glyphListOrigY,
                   const jlong *images, const jfloat *positions);

// A context is disposed: the previous glyph list's paint, which may hold a shader of a context's
// recorder (a texture paint's image), and the LCD accumulation's references to the context go.
void forgetGraphicsConfig(SKGraphicsConfig *graphicsConfig);

} // namespace SKTextPipe

#endif /* SKTextPipe_h_Included */
