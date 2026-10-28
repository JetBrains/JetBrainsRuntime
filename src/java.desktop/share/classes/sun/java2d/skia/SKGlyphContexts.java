/*
 * Copyright 2026 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sun.java2d.skia;

import sun.font.CharToGlyphMapper;
import sun.font.CompositeStrike;
import sun.font.GlyphList;
import sun.font.SKDrawableStrike;

/**
 * What Skia's text path needs to draw a glyph list: the Skia scaler contexts of
 * the strikes behind it and the glyph codes in the natives' encoding. A physical
 * strike gives one context and the list's own codes; a composite strike (a glyph
 * vector's on macOS, a logical or Swing UI font's elsewhere) gives one context per
 * slot the list uses and codes re-encoded as {@code slot << 24 | glyph}, the slot
 * selecting the context natively. Null when any strike involved is not on the
 * Skia scaler or carries a transform Skia's path does not draw.
 */
final class SKGlyphContexts {

    /** The contexts by slot (a slot the list does not use is 0) and the codes with the slot in bits 24–31. */
    record Runs(long[] contexts, int[] codes) {}

    private SKGlyphContexts() {
    }

    static Runs of(GlyphList gl) {
        Object strike = gl.getFontStrike();
        int[] codes = gl.getGlyphCodes();
        // the composite first: every FontStrike answers the drawable interface, a composite one with no context of its own
        if (strike instanceof CompositeStrike cs) {
            int n = gl.getNumGlyphs();
            long[] contexts = new long[cs.numSlots()];
            int[] encoded = new int[n];
            for (int i = 0; i < n; i++) {
                int code = codes[i];
                if (code == CharToGlyphMapper.INVISIBLE_GLYPH_ID) {
                    encoded[i] = code;   // skipped natively, its advance kept
                    continue;
                }
                int slot = cs.slotOf(code), glyph = cs.glyphOf(code);
                if (slot < 0 || slot >= contexts.length || glyph < 0 || glyph > 0xffffff) {
                    return null;
                }
                if (contexts[slot] == 0) {
                    if (!(cs.strikeForSlot(slot) instanceof SKDrawableStrike s) || (contexts[slot] = contextOf(s)) == 0) {
                        return null;
                    }
                }
                encoded[i] = slot << 24 | glyph;
            }
            return new Runs(contexts, encoded);
        }
        if (strike instanceof SKDrawableStrike s) {
            long context = contextOf(s);
            return context != 0 ? new Runs(new long[] {context}, codes) : null;
        }
        return null;
    }

    /** Whether the entry points may build a glyph list for this strike at all (the slots decide after). */
    static boolean mayDraw(Object strike) {
        return strike instanceof CompositeStrike || (strike instanceof SKDrawableStrike s && contextOf(s) != 0);
    }

    private static long contextOf(SKDrawableStrike s) {
        return s.drawsBySkia() ? s.skiaScalerContext() : 0;
    }
}
