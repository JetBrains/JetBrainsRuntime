/*
 * Copyright 2000-2023 JetBrains s.r.o.
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

public final class CCompositeFont extends CompositeFont {
    private final List<CFont> fallbackFonts = new ArrayList<>();

    public CCompositeFont(CFont font) {
        super(new PhysicalFont[]{font});
        mapper = new CCompositeGlyphMapper(this);
    }

    @Override
    protected void initSlotMask() {
        // List of fallback fonts can grow dynamically for CCompositeFont.
        // Adding a new font to fallback list may require more bits
        // to represent slot index, which will cause slotShift to increment,
        // which in turn will invalidate all glyph codes returned earlier.
        // This will cause rendering garbage when fallback list grows
        // while rendering a chunk of text, so here we just set slotShift
        // to fixed 8 bits and hope we never exceed it (just like before).
        slotShift = 8;
        slotMask = 0xff;
    }

    @Override
    public synchronized int getNumSlots() {
        return super.getNumSlots();
    }

    @Override
    public CFont getSlotFont(int slot) {
        if (slot == 0) return (CFont) super.getSlotFont(0);
        synchronized (this) {
            return fallbackFonts.get(slot - 1);
        }
    }

    @Override
    synchronized FontStrike getStrike(FontStrikeDesc desc, boolean copy) {
        return super.getStrike(desc, copy);
    }

    @Override
    protected synchronized int getValidatedGlyphCode(int glyphCode) {
        return super.getValidatedGlyphCode(glyphCode);
    }

    @Override
    public boolean hasSupplementaryChars() {
        return false;
    }

    @Override
    public boolean useAAForPtSize(int ptsize) {
        return true;
    }

    public synchronized int findSlot(String fontName) {
        for (int slot = 0; slot < numSlots; slot++) {
            CFont slotFont = getSlotFont(slot);
            if (fontName.equals(slotFont.getNativeFontName())) {
                return slot;
            }
        }
        return -1;
    }

    public synchronized int addSlot(CFont font) {
        int slot = findSlot(font.getNativeFontName());
        if (slot >= 0) return slot;
        fallbackFonts.add(font);
        lastFontStrike = new SoftReference<>(null);
        strikeCache.clear();
        return numSlots++;
    }
}
