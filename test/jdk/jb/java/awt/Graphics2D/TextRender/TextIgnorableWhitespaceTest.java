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

/**
 * @test
 * @summary Java's ignorable whitespace — the tab and the line terminators (FontUtilities.isIgnorableWhitespace:
 *          U+0009 to U+000D, U+0085, U+2028, U+2029) — is neither drawn nor measured on Skia's text routes either, as
 *          Java's glyph mappers make it an invisible glyph without advance (JDK-8350203, IgnoredWhitespaceTest; a tab's
 *          stops belong to the layer above, Swing's TabExpander): "x" + c + "|" must render as "x|", pixel for pixel,
 *          through drawString, drawChars, TextLayout.draw and an attributed string. Skia's shaper took the font's tab
 *          glyph (a space wide: the bar 4 pixels right in Helvetica Neue 13) and the paragraph engine broke the line
 *          at a terminator.
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Djava.awt.headless=true TextIgnorableWhitespaceTest loops
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.skia.raster=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.strike=skia TextIgnorableWhitespaceTest raster
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.skia.raster=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.layout=skia TextIgnorableWhitespaceTest raster-layout
 */

/*
 * @test id=gpu
 * @summary The same on the Skia GPU pipe with Skia's layout.
 * @requires (os.family == "mac" | os.family == "windows")
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.layout=skia TextIgnorableWhitespaceTest gpu-layout
 */
public class TextIgnorableWhitespaceTest {
    static final char[] IGNORABLE = {'\t', '\n', '\u000B', '\u000C', '\r', '\u0085', '\u2028', '\u2029'};
    static final String[] ENTRIES = {"drawString", "drawChars", "TextLayout.draw", "attributed string"};

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        Font font = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
        long shapedBefore = counter("glyphsShapedBySkia"), paragraphsBefore = counter("paragraphsBySkia");
        StringBuilder failures = new StringBuilder();
        for (int e = 0; e < ENTRIES.length; e++) {
            BufferedImage reference = render(mode, font, e, "x|");
            for (char c : IGNORABLE) {
                for (String text : new String[] {"x" + c + "|", c + "x|", "x|" + c}) {
                    BufferedImage image = render(mode, font, e, text);
                    int differing = 0;
                    for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
                        if (image.getRGB(x, y) != reference.getRGB(x, y)) differing++;
                    }
                    if (differing > 0) {
                        failures.append(String.format("%s of \"%s\": %d pixels differ from \"x|\"; ", ENTRIES[e], escaped(text), differing));
                    }
                }
            }
        }
        if (mode.endsWith("layout") && (counter("glyphsShapedBySkia") == shapedBefore || counter("paragraphsBySkia") == paragraphsBefore)) {
            failures.append("Skia's shaper or paragraph engine drew nothing: the test would prove nothing for them; ");
        }
        if (failures.length() > 0) {
            throw new RuntimeException(mode + ": " + failures);
        }
        System.out.println("PASSED");
    }

    static String escaped(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) b.append(c < 0x20 || c > 0x7e ? String.format("\\u%04x", (int) c) : String.valueOf(c));
        return b.toString();
    }

    static BufferedImage render(String mode, Font font, int entry, String text) {
        VolatileImage vi = null;
        BufferedImage image = null;
        Graphics2D g;
        if (mode.startsWith("gpu")) {
            vi = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().createCompatibleVolatileImage(100, 40);
            g = vi.createGraphics();
        } else {
            image = new BufferedImage(100, 40, mode.startsWith("raster") ? BufferedImage.TYPE_INT_ARGB_PRE : BufferedImage.TYPE_INT_RGB);
            g = image.createGraphics();
        }
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 100, 40);
        g.setColor(Color.BLACK);
        g.setFont(font);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        switch (entry) {
            case 0 -> g.drawString(text, 10, 25);
            case 1 -> g.drawChars(text.toCharArray(), 0, text.length(), 10, 25);
            case 2 -> new TextLayout(text, font, g.getFontRenderContext()).draw(g, 10, 25);
            default -> {
                AttributedString as = new AttributedString(text);
                as.addAttribute(TextAttribute.FONT, font);
                g.drawString(as.getIterator(), 10, 25);
            }
        }
        g.dispose();
        return vi != null ? vi.getSnapshot() : image;
    }

    static long counter(String name) throws Exception {
        return (long) Class.forName("sun.java2d.skia.SKEnvironment").getMethod(name).invoke(null);
    }
}
