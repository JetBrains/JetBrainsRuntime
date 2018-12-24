/*
 * Copyright 2000-2018 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
