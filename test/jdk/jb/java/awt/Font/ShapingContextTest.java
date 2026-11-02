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
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * @test
 * @summary JBR-10592 Checks that a shortened text is shaped the same way as a full text
 * @library /test/lib
 * @run main/othervm -Dsun.font.layout.ffm=true ShapingContextTest
 * @run main/othervm -Dsun.font.layout.ffm=false ShapingContextTest
 */

import jtreg.SkippedException;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.util.Arrays;
import java.util.stream.Stream;

public class ShapingContextTest {
    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);
    // Dual-joining Arabic letters: the shape of each letter depends on both neighbours
    private static final String joiningLetters = "\u0628\u064a\u062a";

    private static final char[] LONG_TEXT = joiningLetters.repeat(20).toCharArray();
    private static final int START = 30;
    private static final int LIMIT = 36;
    private static final int CONTEXT = 5;
    private static final char FATHA = '\u064e'; // a mark that letter joining skips
    private static final char[] SHORT_TEXT = Arrays.copyOfRange(LONG_TEXT, START - CONTEXT, LIMIT + CONTEXT);

    public static void main(String[] args) {
        String[] fontFamilies = Stream.concat(
                Stream.of(Font.DIALOG),
                Arrays.stream(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames())
        ).toArray(String[]::new);

        for (String fontFamily : fontFamilies) {
            Font font = new Font(fontFamily, Font.PLAIN, 20);
            if (font.canDisplayUpTo(SHORT_TEXT, 0, SHORT_TEXT.length) != -1 || !font.canDisplay(FATHA)) {
                continue;
            }

            String alone = glyphs(font, Arrays.copyOfRange(LONG_TEXT, START, LIMIT), 0, LIMIT - START, 0, LIMIT - START);
            // SHORT_TEXT is short enough to reach HarfBuzz uncut
            String expected = glyphs(font, SHORT_TEXT, CONTEXT, CONTEXT + LIMIT - START, 0, LIMIT - START);
            if (expected.equals(alone)) {
                continue; // HarfBuzz uses no context with this font, e.g. AAT fonts with 'morx' on macOS
            }

            String actual = glyphs(font, LONG_TEXT, START, LIMIT, 0, LIMIT - START);
            if (!actual.equals(expected)) {
                throw new RuntimeException("Font " + font.getFontName() + ": run cut out of long text is shaped as "
                        + actual + ", expected " + expected + " (the run alone: " + alone + ")");
            }
            checkHarfBuzzContextLength(font);
            System.out.println("Passed with font " + font.getFontName());
            return;
        }
        throw new SkippedException("No font with which HarfBuzz joins Arabic letters across runs");
    }

    /**
     * SunLayoutEngine passes HarfBuzz only {@code HB_CONTEXT_LENGTH} chars of context, sized for 5 code points.
     * The text is too short to be cut, so this checks HarfBuzz itself.
     */
    private static void checkHarfBuzzContextLength(Font font) {
        for (int marks = CONTEXT - 1; marks <= CONTEXT; marks++) {
            String before = String.valueOf(FATHA).repeat(marks);
            boolean joins = !glyphsAfter(font, "\u0628" + before, joiningLetters).equals(glyphsAfter(font, before, joiningLetters));
            if (joins != (marks < CONTEXT)) {
                throw new RuntimeException("Font " + font.getFontName() + ": a letter behind " + marks + " marks "
                        + (joins ? "joins" : "does not join") + " the next run, so HarfBuzz context is no longer "
                        + CONTEXT + " code points. Update HB_CONTEXT_LENGTH in SunLayoutEngine and this test.");
            }
        }
    }

    private static String glyphsAfter(Font font, String before, String run) {
        char[] text = (before + run).toCharArray();
        return glyphs(font, text, before.length(), text.length, 0, run.length());
    }

    /**
     * Lays out {@code text[start, limit)} and lists the glyphs of the chars in {@code [from, to)}
     * as {@code glyph@char}, with char indices relative to {@code from}.
     */
    private static String glyphs(Font font, char[] text, int start, int limit, int from, int to) {
        GlyphVector gv = font.layoutGlyphVector(FRC, text, start, limit, Font.LAYOUT_RIGHT_TO_LEFT);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < gv.getNumGlyphs(); i++) {
            int c = gv.getGlyphCharIndex(i);
            if (c >= from && c < to) {
                sb.append(gv.getGlyphCode(i)).append('@').append(c - from).append(' ');
            }
        }
        return sb.toString().trim();
    }
}
