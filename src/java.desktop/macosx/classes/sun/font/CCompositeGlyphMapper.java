/*
 * Copyright (c) 2015, 2018, Oracle and/or its affiliates. All rights reserved.
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

import java.awt.*;

public final class CCompositeGlyphMapper extends CompositeGlyphMapper {
    public CCompositeGlyphMapper(CCompositeFont compFont) {
        super(compFont);
    }

    @Override
    public int charToVariationGlyph(int unicode, int variationSelector) {
        if (variationSelector == 0) {
            return charToGlyph(unicode);
        } else {
            int glyph = convertToGlyph(unicode, variationSelector);
            if (glyph == missingGlyph) glyph = charToGlyph(unicode);
            return glyph;
        }
    }

    @Override
    protected int convertToGlyph(int unicode) {
        int glyph = convertToGlyph(unicode, 0);
        setCachedGlyphCode(unicode, glyph);
        return glyph;
    }

    @Override
    protected int convertToGlyph(int unicode, int variationSelector) {
        CCompositeFont compositeFont = (CCompositeFont) font;
        CFont mainFont = (CFont) font.getSlotFont(0);
        String[] fallbackFontInfo = new String[2];
        int glyphCode = nativeCodePointToGlyph(mainFont.getNativeFontPtr(), unicode, variationSelector, fallbackFontInfo);
        if (glyphCode == missingGlyph) {
            return missingGlyph;
        }
        String fallbackFontName = fallbackFontInfo[0];
        String fallbackFontFamilyName = fallbackFontInfo[1];
        if (fallbackFontName == null || fallbackFontFamilyName == null) {
            return font.compositeGlyphCode(0, glyphCode);
        }

        int slot = compositeFont.findSlot(fallbackFontName);

        if (slot < 0) {
            Font2D fallbackFont = FontManagerFactory.getInstance().findFont2D(fallbackFontName,
                    Font.PLAIN, FontManager.NO_FALLBACK);
            if (!(fallbackFont instanceof CFont) ||
                    !fallbackFontName.equals(((CFont) fallbackFont).getNativeFontName())) {
                // Native font fallback mechanism can return "hidden" fonts - their names start with dot,
                // and they are not returned in a list of fonts available in system, but they can still be used
                // if requested explicitly.
                fallbackFont = new CFont(fallbackFontName, fallbackFontFamilyName, null);
            }

            if (mainFont.isFakeItalic()) fallbackFont = ((CFont)fallbackFont).createItalicVariant(false);

            slot = compositeFont.addSlot((CFont) fallbackFont);
        }

        return font.compositeGlyphCode(slot, glyphCode);
    }

    // This invokes native font fallback mechanism, returning information about font (its Postscript and family names)
    // able to display a given character, and corresponding glyph code
    private static native int nativeCodePointToGlyph(long nativeFontPtr, int codePoint, int variationSelector, String[] result);
}