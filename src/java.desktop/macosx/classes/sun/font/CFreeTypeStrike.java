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

import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

/**
 * A system font's LCD strike on the JDK's FreeType scaler (CFont.createStrike): the strike of the font's face in its
 * file, as a font of Font.createFont is drawn, glyphs, advances and metrics alike; the glyph codes are the face's own
 * (CoreText's glyph ids are the file's). The face's own style, so FreeType synthesizes no bold or italic CoreText
 * does not draw; a fake italic CFont gets CStrike's shear. A negative code (a character the font lacks, handed to
 * CoreText's fallback by the font's mapper) is drawn by CoreText's strike: a drawn font's fallback runs through its
 * composite's slots, each a CFont with an LCD strike of its own.
 */
final class CFreeTypeStrike extends FontStrike {
    private final CFont font;
    private final FontStrike face;
    private FontStrike coreText;

    CFreeTypeStrike(CFont font, FontStrikeDesc desc, TrueTypeFont file) {
        this.font = font;
        this.desc = desc;
        this.disposer = new FontStrikeDisposer(font, desc);
        AffineTransform glyphTx = new AffineTransform(desc.glyphTx);
        if (font.isFakeItalic()) {
            glyphTx.concatenate(AffineTransform.getShearInstance(-0.2, 0));
        }
        face = file.getStrike(new FontStrikeDesc(desc.devTx, glyphTx, file.getStyle(), desc.aaHint, desc.fmHint, desc.luminance));
    }

    /** The strike's size in points: its glyph transform without the device transform. */
    static float pointSize(FontStrikeDesc desc) {
        AffineTransform user = new AffineTransform(desc.glyphTx);
        try {
            if (desc.devTx != null && !desc.devTx.isIdentity()) {
                user.preConcatenate(desc.devTx.createInverse());
            }
        } catch (java.awt.geom.NoninvertibleTransformException e) {
            user = desc.glyphTx;
        }
        return (float) Math.hypot(user.getShearX(), user.getScaleY());
    }

    private synchronized FontStrike coreText() {
        if (coreText == null) {
            coreText = font.coreTextStrike(desc);
        }
        return coreText;
    }

    private FontStrike strikeFor(int glyphCode) {
        return glyphCode < 0 ? coreText() : face;
    }

    @Override
    public int getNumGlyphs() {
        return face.getNumGlyphs();
    }

    @Override
    StrikeMetrics getFontMetrics() {
        return face.getFontMetrics();
    }

    @Override
    void getGlyphImagePtrs(int[] glyphCodes, long[] images, int len) {
        for (int i = 0; i < len; i++) {
            if (glyphCodes[i] < 0) {
                for (int k = 0; k < len; k++) {
                    images[k] = getGlyphImagePtr(glyphCodes[k]);
                }
                return;
            }
        }
        face.getGlyphImagePtrs(glyphCodes, images, len);
    }

    @Override
    long getGlyphImagePtr(int glyphCode) {
        return strikeFor(glyphCode).getGlyphImagePtr(glyphCode);
    }

    @Override
    void getGlyphImageBounds(int glyphCode, Point2D.Float pt, Rectangle result) {
        strikeFor(glyphCode).getGlyphImageBounds(glyphCode, pt, result);
    }

    @Override
    Point2D.Float getGlyphMetrics(int glyphCode) {
        return strikeFor(glyphCode).getGlyphMetrics(glyphCode);
    }

    @Override
    Point2D.Float getCharMetrics(char ch) {
        return getGlyphMetrics(font.getMapper().charToGlyph(ch));
    }

    @Override
    float getGlyphAdvance(int glyphCode) {
        return strikeFor(glyphCode).getGlyphAdvance(glyphCode);
    }

    @Override
    float getCodePointAdvance(int cp) {
        return getGlyphAdvance(font.getMapper().charToGlyph(cp));
    }

    @Override
    Rectangle2D.Float getGlyphOutlineBounds(int glyphCode) {
        return strikeFor(glyphCode).getGlyphOutlineBounds(glyphCode);
    }

    @Override
    GeneralPath getGlyphOutline(int glyphCode, float x, float y) {
        return strikeFor(glyphCode).getGlyphOutline(glyphCode, x, y);
    }

    @Override
    GlyphRenderData getGlyphRenderData(int glyphCode, float x, float y) {
        return strikeFor(glyphCode).getGlyphRenderData(glyphCode, x, y);
    }
}
