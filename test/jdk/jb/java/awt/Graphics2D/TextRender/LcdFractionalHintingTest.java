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
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * @test
 * @summary With fractional metrics the JDK's FreeType scaler renders LCD glyphs unhinted, as it renders grayscale ones
 *          (the user's decision, 2026-11-11: consistency), so switching between LCD and grayscale does not move the
 *          text: LCD kept FT_LOAD_TARGET_LCD's vertical hinting, its edges snapped to rows (an "H" a row shorter at the
 *          top, its ink 0.3 px lower). Bundled fonts on FreeType (JetBrains Mono, Inter) at three sizes: LCD's first
 *          ink row must be grayscale's and its vertical ink centroid within 0.05 px.
 * @run main/othervm -Djava.awt.headless=true LcdFractionalHintingTest
 */
public class LcdFractionalHintingTest {
    static final int W = 200, H = 60;

    public static void main(String[] args) throws Exception {
        File fonts = new File(System.getProperty("java.home"), "lib/fonts");
        StringBuilder failures = new StringBuilder();
        for (String file : new String[] {"JetBrainsMono-Regular.ttf", "Inter-Regular.otf"}) {
            Font base = Font.createFont(Font.TRUETYPE_FONT, new File(fonts, file));
            for (float size : new float[] {13f, 16f, 20f}) {
                Font font = base.deriveFont(size);
                double[] lcd = profile(font, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB), gray = profile(font, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                String what = file + " " + size + " pt";
                System.out.printf("%s: first ink row LCD %d, grayscale %d; vertical ink centroid LCD %.3f, grayscale %.3f%n", what, (int) lcd[0], (int) gray[0], lcd[1], gray[1]);
                if (lcd[0] != gray[0] || Math.abs(lcd[1] - gray[1]) > 0.05) {
                    failures.append(String.format("%s: first row %d / %d, centroid %.3f / %.3f; ", what, (int) lcd[0], (int) gray[0], lcd[1], gray[1]));
                }
            }
        }
        if (failures.length() > 0) throw new RuntimeException("LCD text with fractional metrics is shaped unlike grayscale vertically: " + failures);
    }

    /** {first row with ink, the ink's vertical centroid} of "Hxgl" drawn with fractional metrics. */
    static double[] profile(Font font, Object aa) {
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, aa);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setColor(Color.BLACK);
        g.setFont(font);
        g.drawString("Hxgl", 10, 40);
        g.dispose();
        int first = -1;
        double sum = 0, weight = 0;
        for (int y = 0; y < H; y++) {
            double row = 0;
            for (int x = 0; x < W; x++) {
                int p = image.getRGB(x, y);
                row += 765 - ((p >> 16) & 255) - ((p >> 8) & 255) - (p & 255);
            }
            if (row > 0 && first < 0) first = y;
            sum += row * (y + 0.5);
            weight += row;
        }
        return new double[] {first, sum / weight};
    }
}
