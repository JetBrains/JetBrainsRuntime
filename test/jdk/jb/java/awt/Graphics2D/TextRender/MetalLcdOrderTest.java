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
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.io.File;

/**
 * @test
 * @key headful
 * @summary Metal draws LCD text in the sub-pixel order the hint asks for, as the software loops do: it copied a glyph
 *          image's R, G, B coverage into the B, G, R channels of its BGRA glyph texture whatever the order, so LCD_HRGB
 *          text came out with BGR fringes (and LCD_HBGR with RGB ones). Unseen with CoreText's masks, whose channels
 *          are equal; a font on FreeType (here the bundled JetBrains Mono, the IDE's editor font under
 *          enable.macos.editor.subpixelAA) has real sub-pixel masks. Cached glyphs (13 pt) and uncached ones (72 pt).
 * @requires os.family == "mac"
 * @run main/othervm -Dsun.java2d.metal=true -Dsun.java2d.uiScale=1 MetalLcdOrderTest
 */
public class MetalLcdOrderTest {
    static final int W = 420, H = 100, TOLERANCE = 48;

    public static void main(String[] args) throws Exception {
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        if (!gc.getClass().getName().contains("MTL")) throw new RuntimeException("not a Metal configuration: " + gc.getClass().getName());
        Font font = Font.createFont(Font.TRUETYPE_FONT, new File(System.getProperty("java.home"), "lib/fonts/JetBrainsMono-Regular.ttf"));
        StringBuilder failures = new StringBuilder();
        for (Object hint : new Object[] {RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HBGR}) {
            for (float size : new float[] {13f, 72f}) {
                Font sized = font.deriveFont(size);
                String text = size > 20 ? "Hamb" : "Hamburgefonts Quick fox jumps 1234567890";
                BufferedImage loops = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
                paint(loops.createGraphics(), sized, hint, text);
                BufferedImage metal;
                VolatileImage image = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
                do {
                    image.validate(gc);
                    paint(image.createGraphics(), sized, hint, text);
                    metal = image.getSnapshot();
                } while (image.contentsLost());
                long agreement = 0;
                int beyond = 0, fringes = 0;
                for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
                    int l = loops.getRGB(x, y), m = metal.getRGB(x, y);
                    int dl = ((l >> 16) & 255) - (l & 255), dm = ((m >> 16) & 255) - (m & 255);
                    agreement += (long) dl * dm;
                    if (dl != 0) fringes++;
                    for (int s = 0; s < 24; s += 8) if (Math.abs(((l >> s) & 255) - ((m >> s) & 255)) > TOLERANCE) { beyond++; break; }
                }
                String what = hint + " " + size + " pt";
                System.out.println(what + ": loops' fringe pixels " + fringes + ", order agreement " + agreement + ", pixels beyond " + TOLERANCE + " levels " + beyond);
                if (fringes < 100) throw new RuntimeException(what + ": the loops drew no LCD text: the premise fails");
                if (agreement <= 0 || beyond > 0) {
                    failures.append(what).append(": order agreement ").append(agreement).append(", ").append(beyond).append(" pixels beyond ").append(TOLERANCE).append("; ");
                }
            }
        }
        if (failures.length() > 0) throw new RuntimeException("Metal's LCD text is not in the loops' sub-pixel order: " + failures);
    }

    static void paint(Graphics2D g, Font font, Object hint, String text) {
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, hint);
        g.setColor(Color.BLACK);
        g.setFont(font);
        g.drawString(text, 4, font.getSize() > 20 ? 80 : 26);
        g.dispose();
    }
}
