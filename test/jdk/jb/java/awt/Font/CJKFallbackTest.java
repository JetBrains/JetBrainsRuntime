/*
 * Copyright 2000-2026 JetBrains s.r.o.
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

import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.image.BufferedImage;

/*
 * @test
 * @summary JBR-10245 CJK font fallback must work on macOS — Latin-only fonts
 *          must render CJK characters via CoreText substitution, producing
 *          visible (non-blank) glyphs.
 * @requires os.family == "mac"
 * @run main/othervm CJKFallbackTest
 */
public class CJKFallbackTest {

    // CJK characters that should be renderable via fallback on any macOS system
    // (PingFang SC / STSongti / Hiragino are always installed)
    private static final String CJK_TEXT = "\u4F60\u597D"; // 你好
    private static final int FONT_SIZE = 24;
    private static final int IMG_W = 120;
    private static final int IMG_H = 40;

    public static void main(String[] args) throws Exception {
        // Use Courier, a Latin-only font guaranteed on macOS that has no CJK glyphs
        String[] latinFonts = {"Courier", "Menlo", "Monaco"};
        Font testFont = null;
        for (String name : latinFonts) {
            Font f = new Font(name, Font.PLAIN, FONT_SIZE);
            // Verify this font is actually resolved (not a default fallback)
            if (f.getFamily().equalsIgnoreCase(name) ||
                f.getFontName().toLowerCase().contains(name.toLowerCase())) {
                testFont = f;
                break;
            }
        }
        if (testFont == null) {
            System.out.println("No suitable Latin-only font found, skipping test");
            return;
        }
        System.out.println("Testing with font: " + testFont.getFontName());

        testGlyphVectorFallback(testFont);
        testRenderedPixels(testFont);

        System.out.println("PASSED: CJK fallback works correctly");
    }

    /**
     * Test 1: GlyphVector glyph codes must be non-zero for CJK characters.
     * A zero glyph code means .notdef (missing glyph) — fallback did not work.
     */
    private static void testGlyphVectorFallback(Font font) {
        FontRenderContext frc = new FontRenderContext(null, true, true);
        GlyphVector gv = font.createGlyphVector(frc, CJK_TEXT);
        int numGlyphs = gv.getNumGlyphs();

        System.out.println("GlyphVector has " + numGlyphs + " glyphs for CJK_TEXT");

        for (int i = 0; i < numGlyphs; i++) {
            int code = gv.getGlyphCode(i);
            System.out.println("  glyph[" + i + "] code = " + code +
                               " (0x" + Integer.toHexString(code) + ")");
            if (code == 0) {
                throw new RuntimeException(
                    "GlyphVector glyph code is 0 (.notdef) for CJK character at index " + i +
                    " — font fallback/substitution is broken. Font: " + font.getFontName());
            }
        }
    }

    /**
     * Test 2: Rendering CJK text must produce non-blank output.
     * Draw the CJK string onto a white image and verify that some pixels changed,
     * proving that actual glyphs were rendered (not invisible .notdef boxes).
     */
    private static void testRenderedPixels(Font font) {
        BufferedImage img = new BufferedImage(IMG_W, IMG_H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = img.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                             RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, IMG_W, IMG_H);
        g2d.setFont(font);
        g2d.setColor(Color.BLACK);
        g2d.drawString(CJK_TEXT, 10, 30);
        g2d.dispose();

        int nonWhitePixels = 0;
        for (int y = 0; y < IMG_H; y++) {
            for (int x = 0; x < IMG_W; x++) {
                if ((img.getRGB(x, y) & 0xFFFFFF) != 0xFFFFFF) {
                    nonWhitePixels++;
                }
            }
        }

        System.out.println("Rendered CJK text: " + nonWhitePixels + " non-white pixels");

        // CJK characters at 24pt should produce many colored pixels (typically 200+)
        // A .notdef glyph may produce a small outline box (~20-40 pixels) or nothing
        if (nonWhitePixels < 50) {
            throw new RuntimeException(
                "CJK text rendered with only " + nonWhitePixels + " non-white pixels — " +
                "expected substantial glyph rendering. Font fallback/substitution is broken. " +
                "Font: " + font.getFontName());
        }
    }
}
