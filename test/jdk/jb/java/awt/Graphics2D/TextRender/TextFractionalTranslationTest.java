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
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.text.AttributedString;
import java.util.function.BiConsumer;

/**
 * @test
 * @summary Text under a fractional translation is placed where the translation puts it: drawn at (0, 0) under
 *          translate(fx, fy) it renders exactly as drawn at (fx, fy) without one, on every text entry point
 *          (drawString, drawChars, drawGlyphVector, TextLayout.draw, an attributed string), with and without
 *          fractional metrics. The glyph-list pipes added SunGraphics2D's rounded translation (transX, transY) under a
 *          fractional one (TRANSFORM_ANY_TRANSLATE), so the glyphs moved by up to half a pixel from where a drawing
 *          position, or CoreText's sub-pixel positioning, puts them, while TextLayout's decorations and backgrounds
 *          took the exact transform (ledger §18a T20).
 * @run main/othervm -Djava.awt.headless=true TextFractionalTranslationTest loops
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.skia.raster=true TextFractionalTranslationTest raster
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.skia.raster=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.strike=skia TextFractionalTranslationTest raster
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.skia.raster=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.layout=skia TextFractionalTranslationTest raster
 */

/*
 * @test id=gpu
 * @summary The same on the Skia GPU pipe (Java's glyph images, Skia's text path, its layout route) and the platform's
 *          own pipeline.
 * @requires (os.family == "mac" | os.family == "windows")
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true TextFractionalTranslationTest gpu
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.strike=skia TextFractionalTranslationTest gpu
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.layout=skia TextFractionalTranslationTest gpu
 * @run main/othervm -Dsun.java2d.uiScale=1 TextFractionalTranslationTest gpu
 */
public class TextFractionalTranslationTest {
    static final int W = 260, H = 60;
    static final String TEXT = "Hamburgefonstiv 1il";

    public static void main(String[] args) {
        String mode = args[0];
        Font font = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
        StringBuilder failures = new StringBuilder();
        String[] names = {"drawString", "drawChars", "drawGlyphVector", "TextLayout.draw", "attributed string"};
        for (Object fm : new Object[] {RenderingHints.VALUE_FRACTIONALMETRICS_OFF, RenderingHints.VALUE_FRACTIONALMETRICS_ON}) {
            for (double[] t : new double[][] {{10.3, 30.0}, {10.5, 30.25}, {10.75, 30.6}, {10.0, 30.4}}) {
                for (int e = 0; e < names.length; e++) {
                    final int entry = e;
                    BufferedImage translated = render(mode, fm, (g, at) -> {
                        g.translate(at[0], at[1]);
                        draw(g, font, entry, 0, 0);
                    }, t);
                    BufferedImage placed = render(mode, fm, (g, at) -> draw(g, font, entry, (float) at[0], (float) at[1]), t);
                    int differing = 0;
                    for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) if (translated.getRGB(x, y) != placed.getRGB(x, y)) differing++;
                    String what = names[e] + (fm == RenderingHints.VALUE_FRACTIONALMETRICS_ON ? " (fractional metrics)" : "") + " at " + t[0] + ", " + t[1];
                    System.out.println(mode + ", " + what + ": " + differing + " pixels differ");
                    if (differing > 0) {
                        failures.append(what).append(": ").append(differing).append(" pixels differ between the translation and the drawing position; ");
                    }
                }
            }
        }
        if (failures.length() > 0) {
            throw new RuntimeException(mode + ": " + failures);
        }
        System.out.println("PASSED");
    }

    /** One entry point at (x, y); drawChars takes int coordinates, so a fractional position goes through drawString's float form. */
    static void draw(Graphics2D g, Font font, int entry, float x, float y) {
        g.setFont(font);
        switch (entry) {
            case 0 -> g.drawString(TEXT, x, y);
            case 1 -> {
                if (x == Math.rint(x) && y == Math.rint(y)) g.drawChars(TEXT.toCharArray(), 0, TEXT.length(), (int) x, (int) y);
                else g.drawString(TEXT, x, y);   // the reference for drawChars under the translation
            }
            case 2 -> g.drawGlyphVector(font.createGlyphVector(g.getFontRenderContext(), TEXT), x, y);
            case 3 -> new TextLayout(TEXT, font, g.getFontRenderContext()).draw(g, x, y);
            default -> {
                AttributedString as = new AttributedString(TEXT);
                as.addAttribute(TextAttribute.FONT, font);
                g.drawString(as.getIterator(), x, y);
            }
        }
    }

    static BufferedImage render(String mode, Object fm, BiConsumer<Graphics2D, double[]> draw, double[] at) {
        VolatileImage vi = null;
        BufferedImage image = null;
        Graphics2D g;
        if (mode.equals("gpu")) {
            vi = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().createCompatibleVolatileImage(W, H);
            g = vi.createGraphics();
        } else {
            image = new BufferedImage(W, H, mode.equals("raster") ? BufferedImage.TYPE_INT_ARGB_PRE : BufferedImage.TYPE_INT_RGB);
            g = image.createGraphics();
        }
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.setColor(Color.BLACK);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, fm);
        draw.accept(g, at);
        g.dispose();
        return vi != null ? vi.getSnapshot() : image;
    }
}
