/*
 * Copyright 2026 JetBrains s.r.o.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
 */

package sun.font;

/**
 * A strike whose glyphs Skia's own text path can draw from glyph codes: it
 * exposes its Skia scaler context (an SkFont with size, style and hints) when
 * it has one. Implemented by {@link SKFontStrike} (platform fonts) and
 * {@link FileFontStrike} (fonts from files) when the Skia font scaler is in use.
 */
public interface SkiaDrawable {
    /** The native Skia scaler context, or 0 when this strike is not on the Skia scaler. */
    long skiaScalerContext();

    /** Whether Skia's text path can draw this strike (an axis-aligned glyph transform). */
    boolean drawsBySkia();
}
