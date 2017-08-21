/*
 * Copyright 2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quality.util;


import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.File;
import java.util.function.Consumer;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class RenderUtil {


    public static BufferedImage capture(int width, int height, Consumer<Graphics2D> painter)
            throws Exception
    {
        JFrame[] f = new JFrame[1];
        Point[] p = new Point[1];
        SwingUtilities.invokeAndWait(() -> {
            f[0] = new JFrame();

            JComponent c = new MyComponent(painter);

            f[0].add(c);
            c.setSize(width + 10, height + 10);
            f[0].setSize(width + 100, height + 100); // giving some space
                                                                 // for frame border effects,
                                                                 // e.g. rounded frame
            c.setLocation(50, 50);
            f[0].setVisible(true);
            p[0]= c.getLocationOnScreen();
        });

        Rectangle screenRect;
        Robot r = new Robot();
        Color c;
        do {
            Thread.sleep(100);
            c = r.getPixelColor(p[0].x, p[0].y+5);
        } while (c.getRed() < 10 && c.getBlue() < 10 && c.getGreen() < 10);

        screenRect = new Rectangle(p[0].x + 5, p[0].y + 5, width, height);

        BufferedImage result = r.createScreenCapture(screenRect);
        SwingUtilities.invokeAndWait(f[0]::dispose);
        return result;
    }

    private static class MyComponent extends JComponent {
        private final Consumer<Graphics2D> painter;

        private MyComponent(Consumer<Graphics2D> painter) {
            this.painter = painter;
        }


        @Override
        protected void paintComponent(Graphics g) {
            g.translate(5, 5);
            Shape savedClip = g.getClip();
            g.clipRect(0, 0, getWidth() - 20, getHeight() - 20);
            painter.accept((Graphics2D)g);
            g.setClip(savedClip);
            g.setColor(Color.black);
            ((Graphics2D) g).setStroke(new BasicStroke(10));
            g.drawRect(-5, -5, getWidth() - 5, getHeight() - 5);
        }
    }

    @SuppressWarnings("SameParameterValue")
    public static void checkImage(BufferedImage image, String path, String gfName) throws Exception {

        String[] testDataVariant = {
                "osx_hardware_rendering", "osx_software_rendering",
                "osx_sierra_rendering", "osx_lowres_rendering",
                "linux_rendering", "windows_rendering"};

        String testDataStr = System.getProperty("testdata");
        assertNotNull("testdata property is not set", testDataStr);

        File testData = new File(testDataStr, "quality" + File.separator + path);
        assertTrue("Test data dir does not exist", testData.exists());

        if (System.getProperty("gentestdata") == null) {
            boolean failed = true;
            StringBuilder failureReason = new StringBuilder();
            for (String variant : testDataVariant) {
                File goldenFile = new File(testData, variant + File.separator +
                        gfName);
                if (!goldenFile.exists()) continue;

                BufferedImage goldenImage = ImageIO.read(goldenFile);
                failed = true;
                if (image.getWidth() != goldenImage.getWidth() ||
                        image.getHeight() != image.getHeight())
                {
                    failureReason.append(variant).append(" : Golden image and result have different sizes\n");
                    continue;
                }

                Raster gRaster = goldenImage.getData();
                Raster rRaster = image.getData();
                int[] gArr = new int[3];
                int[] rArr = new int[3];
                failed = false;
                scan:
                for (int i = 0; i < gRaster.getWidth(); i++) {
                    for (int j = 0; j < gRaster.getHeight(); j++) {
                        gRaster.getPixel(i, j, gArr);
                        rRaster.getPixel(i, j, rArr);
                        assertTrue(gArr.length == rArr.length);
                        for (int k = 0; k < gArr.length; k++) {
                            if (gArr[k] != rArr[k]) {
                                failureReason.append(variant).append(" : Different pixels found ").
                                        append("at (").append(i).append(",").append(j).append(")");
                                failed = true;
                                break scan;
                            }
                        }
                    }
                }

                if (!failed) break;
            }

            if (failed) throw new RuntimeException(failureReason.toString());
        }
        else {
            ImageIO.write(image, "png", new File(testData, gfName));
        }
    }
}
