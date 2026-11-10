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

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import java.text.AttributedString;

/**
 * @test
 * @summary On macOS a character a font lacks is drawn in the font CoreText's cascade picks from that font, as CTLine
 *          and TextKit 2 draw it (CTFontCreateForString with the font as base; measured 2026-11-10 over 6 families × 20
 *          symbols: CTLine's run font = CTFontCreateForString = JRSFontCreateFallbackFontForCharacters in 120 of 120).
 *          The glyph-list routes resolved it so already; Skia's shaper and paragraph engine asked Skia's font manager
 *          without the font (Monaco's ⇒ ⌘ ✓ ★ λ ← ↑ ∀ ∈ and SF Mono's ⇒ ★ ∀ ∈ came out in another font than Menlo, 15 of
 *          140 cases). Each character, drawn in the base font, must render exactly as drawn in the cascade's font:
 *          drawString and an attributed string, on the loops with the JDK and the Skia scaler, the raster surface with
 *          Skia's text path and with its layout route (the shaper and the paragraph engine).
 * @requires os.family == "mac"
 * @run main/othervm -Djava.awt.headless=true FallbackCascadeTest
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.font.scaler=skia FallbackCascadeTest
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.skia.raster=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.strike=skia FallbackCascadeTest
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.skia.raster=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.layout=skia FallbackCascadeTest
 */
public class FallbackCascadeTest {
    /** base font, character, CoreText's cascade font (PostScript names). */
    static final String[][] CASCADE = {
            {"Monaco", "⇒", "Menlo-Regular"}, {"Monaco", "⌘", "Menlo-Regular"}, {"Monaco", "✓", "Menlo-Regular"},
            {"Monaco", "★", "Menlo-Regular"}, {"Monaco", "λ", "Menlo-Regular"}, {"Monaco", "←", "Menlo-Regular"},
            {"Monaco", "∀", "Menlo-Regular"}, {"Monaco", "∈", "Menlo-Regular"},
            {"SFMono-Regular", "⇒", "Menlo-Regular"}, {"SFMono-Regular", "★", "Menlo-Regular"},
            {"SFMono-Regular", "∀", "Menlo-Regular"}, {"SFMono-Regular", "∈", "Menlo-Regular"},
            {"HelveticaNeue", "→", "LucidaGrande"}, {"HelveticaNeue", "★", "HiraginoSans-W3"}, {"HelveticaNeue", "∀", "AppleSymbols"},
            {"Menlo-Regular", "中", "PingFangSC-Regular"}, {"Menlo-Regular", "⟶", "AppleSymbols"},
    };

    public static void main(String[] args) {
        StringBuilder failures = new StringBuilder();
        for (String[] c : CASCADE) {
            Font base = new Font(c[0], Font.PLAIN, 14), cascade = new Font(c[2], Font.PLAIN, 14);
            for (boolean attributed : new boolean[] {false, true}) {
                long seen = hash(base, c[1], attributed), expected = hash(cascade, c[1], attributed);
                String what = c[0] + " " + c[1] + (attributed ? " (attributed string)" : " (drawString)");
                System.out.println(what + ": " + (seen == expected ? "drawn in " : "not drawn in ") + c[2]);
                if (seen != expected) {
                    failures.append(what).append(": not drawn in ").append(c[2]).append(", CoreText's cascade font; ");
                }
            }
        }
        if (failures.length() > 0) {
            throw new RuntimeException(failures.toString());
        }
        System.out.println("PASSED");
    }

    static long hash(Font font, String s, boolean attributed) {
        BufferedImage img = new BufferedImage(60, 40, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 60, 40);
        g.setColor(Color.BLACK);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        if (attributed) {
            AttributedString as = new AttributedString(s);
            as.addAttribute(TextAttribute.FONT, font);
            g.drawString(as.getIterator(), 10f, 28f);
        } else {
            g.setFont(font);
            g.drawString(s, 10, 28);
        }
        g.dispose();
        long h = 17;
        for (int y = 0; y < 40; y++) for (int x = 0; x < 60; x++) h = 31 * h + img.getRGB(x, y);
        return h;
    }
}
