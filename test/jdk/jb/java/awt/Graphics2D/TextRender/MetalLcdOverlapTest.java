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
 * @summary Metal blends an LCD glyph over the glyph drawn before it in the same glyph list, as the software loops do:
 *          its LCD shader read the destination from the render target bound as a texture, which within one render
 *          pass does not hold what the pass drew, so where neighbouring glyphs overlap (Lucida Grande's "Ha": the LCD
 *          filter's spill) the later glyph blended over the background, not over its neighbour. On Apple GPUs the
 *          shader now reads the destination by framebuffer fetch. Lucida Grande from its file (FreeType's real
 *          sub-pixel masks; CoreText's equal channels hid most of it).
 * @requires os.family == "mac"
 * @run main/othervm -Dsun.java2d.metal=true -Dsun.java2d.uiScale=1 MetalLcdOverlapTest
 */
public class MetalLcdOverlapTest {
    static final int W = 300, H = 40, TOLERANCE = 16;

    public static void main(String[] args) throws Exception {
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        if (!gc.getClass().getName().contains("MTL")) throw new RuntimeException("not a Metal configuration: " + gc.getClass().getName());
        Font font = Font.createFont(Font.TRUETYPE_FONT, new File("/System/Library/Fonts/LucidaGrande.ttc")).deriveFont(13f);
        StringBuilder failures = new StringBuilder();
        for (Color ink : new Color[] {Color.BLACK, new Color(0x3030c0), new Color(0xff8800)}) {
            BufferedImage loops = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
            paint(loops.createGraphics(), font, ink);
            BufferedImage metal;
            VolatileImage image = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
            do {
                image.validate(gc);
                paint(image.createGraphics(), font, ink);
                metal = image.getSnapshot();
            } while (image.contentsLost());
            int max = 0, beyond = 0;
            for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
                int l = loops.getRGB(x, y), m = metal.getRGB(x, y), d = 0;
                for (int s = 0; s < 24; s += 8) d = Math.max(d, Math.abs(((l >> s) & 255) - ((m >> s) & 255)));
                max = Math.max(max, d);
                if (d > TOLERANCE) beyond++;
            }
            System.out.printf("%06x on white: max %d levels, %d pixels beyond %d%n", ink.getRGB() & 0xffffff, max, beyond, TOLERANCE);
            if (beyond > 0) failures.append(String.format("%06x: %d pixels beyond %d (max %d); ", ink.getRGB() & 0xffffff, beyond, TOLERANCE, max));
        }
        if (failures.length() > 0) throw new RuntimeException("Metal's overlapping LCD glyphs differ from the loops: " + failures);
    }

    static void paint(Graphics2D g, Font font, Color ink) {
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setFont(font);
        g.setColor(ink);
        g.drawString("Hamburgefonstiv HaHaHa", 4, 25);
        g.dispose();
    }
}
