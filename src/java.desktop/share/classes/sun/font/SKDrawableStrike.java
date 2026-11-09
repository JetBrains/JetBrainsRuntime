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

package sun.font;

/**
 * A strike whose glyphs Skia's own text path can draw from glyph codes: it
 * exposes its Skia scaler context (an SkFont with size, style and hints) when
 * it has one. Implemented by {@link SKFontStrike} (platform fonts) and
 * {@link FileFontStrike} (fonts from files) when the Skia font scaler is in use.
 */
public interface SKDrawableStrike {
    /** The native Skia scaler context, or 0 when this strike is not on the Skia scaler. */
    long skiaScalerContext();

    /** Whether Skia's text path can draw this strike's glyphs (any invertible glyph transform: rotated, sheared, scaled). */
    boolean drawsBySkia();

    /** Whether the glyph transform is axis-aligned, as Skia's shaper and paragraph routes need it. */
    boolean axisAligned();

    /** The scale Skia's layout routes lay this strike's text out under: 1, or the device scale for whole-pixel advances. */
    double layoutScale();

    /** Whether none of a glyph list's first n records carries an image (advances only, or blank glyphs). */
    static boolean imageless(GlyphList gl, int n) {
        for (int i = 0; i < n; i++) {
            long ptr = gl.images[i];
            if (ptr != 0L && StrikeCache.getGlyphWidth(ptr) != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * The device bounds {x0, y0, x1, y1} of a glyph list's first n glyphs from their outlines, for lists whose
     * records carry advances only (a Skia strike above the outline threshold, SKFontStrike); all zero without ink.
     */
    static int[] outlineBounds(GlyphList gl, int n) {
        int[] result = new int[4];
        if (!(gl.getFontStrike() instanceof FontStrike strike)) {
            return result;
        }
        float penX = gl.x, penY = gl.y;
        float x0 = Float.POSITIVE_INFINITY, y0 = Float.POSITIVE_INFINITY, x1 = Float.NEGATIVE_INFINITY, y1 = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            int code = gl.glyphData[i];
            float px = gl.usePositions ? gl.x + gl.positions[2 * i] : penX, py = gl.usePositions ? gl.y + gl.positions[2 * i + 1] : penY;
            if (code < CharToGlyphMapper.INVISIBLE_GLYPHS) {
                java.awt.geom.Rectangle2D.Float b = strike.getGlyphOutlineBounds(code);
                if (!b.isEmpty()) {
                    x0 = Math.min(x0, px + b.x); y0 = Math.min(y0, py + b.y);
                    x1 = Math.max(x1, px + b.x + b.width); y1 = Math.max(y1, py + b.y + b.height);
                }
            }
            if (!gl.usePositions && gl.images[i] != 0L) {
                penX += StrikeCache.getGlyphXAdvance(gl.images[i]);
                penY += StrikeCache.getGlyphYAdvance(gl.images[i]);
            }
        }
        if (x0 <= x1 && y0 <= y1) {
            result[0] = (int) Math.floor(x0); result[1] = (int) Math.floor(y0);
            result[2] = (int) Math.ceil(x1); result[3] = (int) Math.ceil(y1);
        }
        return result;
    }
}
