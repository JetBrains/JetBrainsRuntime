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

import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.List;

/**
 * The composite of a created TrueType font on macOS (CCascadeTrueTypeFont): the font in slot 0, then the CoreText
 * fonts its cascade picked, added as characters ask for them (CCascadeGlyphMapper), as CCompositeFont grows a CFont's.
 */
final class CCascadeCompositeFont extends CompositeFont {
    private final List<CFont> fallbackFonts = new ArrayList<>();

    CCascadeCompositeFont(CCascadeTrueTypeFont font) {
        super(new PhysicalFont[]{font});
        mapper = new CCascadeGlyphMapper(this);
    }

    @Override
    protected void initSlotMask() {
        // the slots grow while text is drawn: a fixed shift keeps the glyph codes handed out valid (as CCompositeFont)
        slotShift = 8;
        slotMask = 0xff;
    }

    @Override
    public synchronized int getNumSlots() {
        return super.getNumSlots();
    }

    @Override
    public PhysicalFont getSlotFont(int slot) {
        if (slot == 0) return (PhysicalFont) super.getSlotFont(0);
        synchronized (this) {
            return fallbackFonts.get(slot - 1);
        }
    }

    CCascadeTrueTypeFont baseFont() {
        return (CCascadeTrueTypeFont) super.getSlotFont(0);
    }

    @Override
    synchronized FontStrike getStrike(FontStrikeDesc desc, boolean copy) {
        return super.getStrike(desc, copy);
    }

    @Override
    protected synchronized int getValidatedGlyphCode(int glyphCode) {
        return super.getValidatedGlyphCode(glyphCode);
    }

    synchronized int findSlot(String fontName) {
        for (int i = 0; i < fallbackFonts.size(); i++) {
            if (fontName.equals(fallbackFonts.get(i).getNativeFontName())) {
                return i + 1;
            }
        }
        return -1;
    }

    synchronized int addSlot(CFont font) {
        int slot = findSlot(font.getNativeFontName());
        if (slot >= 0) return slot;
        fallbackFonts.add(font);
        lastFontStrike = new SoftReference<>(null);
        strikeCache.clear();
        return numSlots++;
    }
}
