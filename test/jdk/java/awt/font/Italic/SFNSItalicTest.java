/*
 * Copyright 2000-2021 JetBrains s.r.o.
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
 * @summary SFNS italic font inclination on macOS
 * @requires os.family == "mac"
 */

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.ArrayList;

public class SFNSItalicTest {
    /**
     * 0.2 is a default inclination for italic fonts, see {@link sun.font.CFont#createStrike}
     */
    private static final double TARGET_INCLINATION = 0.2;
    private static final double TARGET_INCLINATION_ERROR = 0.01;
    private static final char SYMBOL = 'I';
    private static final Font FONT = new Font(".SFNS-Regular", Font.ITALIC, 16);
    private static final int IMAGE_WIDTH = 20;
    private static final int IMAGE_HEIGHT = 20;
    private static final int GLYPH_X = 6;
    private static final int GLYPH_Y = 16;

    public static void main(String[] args) throws Exception {
        BufferedImage image = createImage();
        double inclination = getInclination(image);
        if (inclination < TARGET_INCLINATION - TARGET_INCLINATION_ERROR ||
            inclination > TARGET_INCLINATION + TARGET_INCLINATION_ERROR) {
            File file = new File( "SFNS-italic.png");
            ImageIO.write(image, "PNG", file);
            throw new RuntimeException("Incorrect inclination. Expected: " + TARGET_INCLINATION + "+-" +
                    TARGET_INCLINATION_ERROR + ", Actual: " + inclination + ", see " + file);
        }
    }

    private static BufferedImage createImage() {
        BufferedImage image = new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.black);
        g.fillRect(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);
        g.setColor(Color.white);
        g.setFont(FONT);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.drawString(String.valueOf(SYMBOL), GLYPH_X, GLYPH_Y);
        g.dispose();
        return image;
    }

    private static double getInclination(BufferedImage image) {
        List<Point2D> points = new ArrayList<>(IMAGE_HEIGHT);
        double mx = 0, my = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            double totalWeight = 0;
            double weightedX = 0;
            for (int x = 0; x < image.getWidth(); x++) {
                double weight = (image.getRGB(x, y) & 0xFF) / 255.0;
                weightedX += x * weight;
                totalWeight += weight;
            }
            double x = weightedX / totalWeight;
            if (Double.isFinite(x)) {
                mx += x;
                my += y;
                points.add(new Point2D.Double(x, y));
            }
        }
        mx /= points.size();
        my /= points.size();
        double xySum = 0;
        double y2Sum = 0;
        for (Point2D p : points) {
            xySum += (p.getX() - mx) * (p.getY() - my);
            y2Sum += Math.pow(p.getY() - my, 2);
        }
        return -xySum / y2Sum;
    }
}
