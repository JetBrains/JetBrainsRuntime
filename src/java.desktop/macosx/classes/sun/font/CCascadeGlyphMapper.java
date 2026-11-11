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

import java.awt.Font;

import static sun.font.FontUtilities.isDefaultIgnorable;
import static sun.font.FontUtilities.isIgnorableWhitespace;

/**
 * The glyph mapper of a created TrueType font's composite on macOS: the font's own glyph where it has one, else the
 * glyph of the font CoreText's cascade from that font picks (nativeCascadeGlyph), that font added as a slot.
 */
final class CCascadeGlyphMapper extends CompositeGlyphMapper {
    CCascadeGlyphMapper(CCascadeCompositeFont font) {
        super(font);
    }

    @Override
    int getGlyph(int unicode, int variationSelector, boolean raw) {
        if (isIgnorableWhitespace(unicode) || (isDefaultIgnorable(unicode) && !raw)) {
            return INVISIBLE_GLYPH_ID;
        }
        int glyphCode;
        if (variationSelector == 0 && (glyphCode = getCachedGlyphCode(unicode, raw)) != UNINITIALIZED_GLYPH) {
            return glyphCode;
        }
        CCascadeCompositeFont composite = (CCascadeCompositeFont) font;
        CCascadeTrueTypeFont base = composite.baseFont();
        int own = base.getMapper().charToGlyph(unicode);
        int result;
        if (own != base.getMissingGlyphCode()) {
            result = font.compositeGlyphCode(0, own);
        } else {
            String[] fallback = new String[2];
            long cascadeBase = base.cascadeBase();
            int glyph = cascadeBase != 0 ? nativeCascadeGlyph(cascadeBase, unicode, fallback) : 0;
            if (glyph <= 0 || fallback[0] == null || fallback[1] == null) {
                result = font.compositeGlyphCode(0, own);
            } else {
                int slot = composite.findSlot(fallback[0]);
                if (slot < 0) {
                    Font2D found = FontManagerFactory.getInstance().findFont2D(fallback[0], Font.PLAIN, FontManager.NO_FALLBACK);
                    CFont cfont = found instanceof CFont c && fallback[0].equals(c.getNativeFontName()) ? c
                            : new CFont(fallback[0], fallback[1], null);   // "hidden" fonts, as CCompositeGlyphMapper takes them
                    slot = composite.addSlot(cfont);
                }
                result = font.compositeGlyphCode(slot, glyph);
            }
        }
        if (variationSelector == 0) setCachedGlyphCode(unicode, result, raw);
        return result;
    }

    /** The cascade bases alive (diagnostics, tests). */
    static int basesHeld() {
        return nativeBasesHeld();
    }

    /** A CTFont of the file's font at the index, the cascade's base; 0 when CoreText cannot read the file. */
    static native long nativeCreateBase(String path, int index);

    static native void nativeReleaseBase(long base);

    private static native int nativeBasesHeld();

    /**
     * CoreText's cascade from the base for the code point: the glyph in the font it picks, its PostScript and family
     * names in result; 0 when it picks none.
     */
    private static native int nativeCascadeGlyph(long base, int codePoint, String[] result);
}
