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

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;

/**
 * @test
 * @summary An antialiased stroke carries the ink of its exact geometry — the points within half its width of the
 *          curve — on the Skia raster surface as on the software loops: the raster surface drew strokes of a device
 *          pixel or less with Skia's hairline rule (12 % short of the exact ink at widths 1 and 0.6; Marlin within
 *          0.5 %), where the GPU pipe already fills the stroke's outline. The exact ink of a stroked circle is its
 *          circumference times its width (a ring's area); each case must come within 2 %.
 * @run main/othervm -Djava.awt.headless=true StrokeInkTest
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.skia.raster=true StrokeInkTest
 */
public class StrokeInkTest {
    static final int W = 140, H = 140;

    public static void main(String[] args) {
        double r = 45;
        StringBuilder failures = new StringBuilder();
        for (float width : new float[] {0.6f, 1f, 2f, 3f}) {
            for (Object control : new Object[] {RenderingHints.VALUE_STROKE_PURE, RenderingHints.VALUE_STROKE_NORMALIZE}) {
                BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = image.createGraphics();
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, W, H);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, control);
                g.setColor(Color.BLACK);
                g.setStroke(new BasicStroke(width));
                g.draw(new Ellipse2D.Double(20.3, 15.7, 2 * r, 2 * r));
                g.dispose();
                double ink = 0;
                for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) ink += (255 - (image.getRGB(x, y) & 255)) / 255.0;
                double exact = 2 * Math.PI * r * width;   // the ring between r - w/2 and r + w/2
                double off = ink / exact - 1;
                String what = String.format("width %.1f, %s", width, control == RenderingHints.VALUE_STROKE_PURE ? "pure" : "normalized");
                System.out.printf("%s: ink %.1f px, exact %.1f px (%+.2f %%)%n", what, ink, exact, off * 100);
                if (Math.abs(off) > 0.02) failures.append(String.format("%s: %+.2f %% ink; ", what, off * 100));
            }
        }
        if (failures.length() > 0) throw new RuntimeException("strokes do not carry their geometry's ink: " + failures);
    }
}
