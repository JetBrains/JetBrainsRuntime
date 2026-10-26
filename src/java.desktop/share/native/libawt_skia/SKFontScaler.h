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

#ifndef SKFontScaler_h_Included
#define SKFontScaler_h_Included

#include "jni.h"
#include "include/core/SkFont.h"

// What the backend needs from a scaler context (sun.font.SKFontScaler) to
// draw its glyphs through Skia's own text path: the SkFont and glyph for a
// Java glyph code (negative codes are platform fallbacks).
bool SKFontScaler_ResolveGlyph(jlong pContext, jint glyphCode, SkFont *font, SkGlyphID *glyph);
// The strike's own SkFont (size, edging, sub-pixel positioning, synthetic styles), for
// shaping the strike's text with Skia's shaper.
bool SKFontScaler_Font(jlong pContext, SkFont *font);
// The strike's grayscale route (sun.font.SKFontScaler.GRAY_*), for the text path to apply it.
jint SKFontScaler_GrayMode(jlong pContext);
jint SKFontScaler_GrayLuminance(jlong pContext);   // the luminance the context's cg masks are rasterized for (SKFontScaler.GRAY_LUMINANCE when the strike has no class)

#endif /* SKFontScaler_h_Included */
