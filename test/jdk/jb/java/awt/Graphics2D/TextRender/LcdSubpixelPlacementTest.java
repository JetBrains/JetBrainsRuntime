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

/**
 * @test
 * @key headful
 * @summary LCD text with fractional metrics lands where the software loops put it on the GPU pipes: an LCD glyph
 *          without sub-pixel variants is placed at the nearest third of a pixel, the loops nudging the position by a
 *          sixth less a half (DrawGlyphList.c) so that a glyph at a whole position takes no shift and its ink sits
 *          where grayscale text's does. Metal (as OpenGL and D3D) split the raw position instead: a third of a pixel
 *          right at whole positions, half a pixel at a half. The ink centroid of an "l" (JetBrains Mono, FreeType's
 *          real sub-pixel masks) at seven positions across a pixel must agree with the loops' within 0.1 px.
 * @requires os.family == "mac"
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.metal=true LcdSubpixelPlacementTest
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true LcdSubpixelPlacementTest
 */
public class LcdSubpixelPlacementTest {
    static final int W = 60, H = 40;

    public static void main(String[] args) throws Exception {
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        Font font = new Font("JetBrains Mono", Font.PLAIN, 20);
        if (!font.getPSName().equals("JetBrainsMono-Regular")) throw new RuntimeException("JetBrains Mono not found: " + font.getPSName());
        StringBuilder failures = new StringBuilder();
        for (float dx : new float[] {0f, 1f / 6, 1f / 3, 0.5f, 2f / 3, 5f / 6, 1f}) {
            BufferedImage loops = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
            paint(loops.createGraphics(), font, 20 + dx);
            BufferedImage pipe;
            VolatileImage image = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
            do {
                image.validate(gc);
                paint(image.createGraphics(), font, 20 + dx);
                pipe = image.getSnapshot();
            } while (image.contentsLost());
            double l = centroid(loops), p = centroid(pipe);
            System.out.printf("x 20 + %.3f: ink centroid loops %.3f, pipe %.3f%n", dx, l, p);
            if (Math.abs(p - l) > 0.1) failures.append(String.format("at +%.3f the pipe's ink is %.3f px off the loops'; ", dx, p - l));
        }
        if (failures.length() > 0) throw new RuntimeException(failures.toString());
    }

    static void paint(Graphics2D g, Font font, float x) {
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setColor(Color.BLACK);
        g.setFont(font);
        g.drawString("l", x, 30);
        g.dispose();
    }

    /** The ink's centroid in pixels, each channel at its third of the pixel. */
    static double centroid(BufferedImage image) {
        double sum = 0, weight = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int p = image.getRGB(x, y);
            int[] channels = {(p >> 16) & 255, (p >> 8) & 255, p & 255};
            for (int s = 0; s < 3; s++) {
                double ink = 255 - channels[s];
                sum += ink * (x + (s + 0.5) / 3);
                weight += ink;
            }
        }
        return sum / weight;
    }
}
