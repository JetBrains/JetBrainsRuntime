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

import com.sun.jna.Pointer;
import org.apache.commons.lang3.SystemUtils;
import quality.util.osx.Foundation;
import quality.util.osx.FoundationLibrary;
import quality.util.osx.ID;
import quality.util.osx.MacUtil;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorConvertOp;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.function.Consumer;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class RenderUtil {

    private static BufferedImage captureScreen(Window belowWindow, Rectangle rect) {
        ID pool = Foundation.invoke("NSAutoreleasePool", "new");
        try {
            ID windowId = belowWindow != null ? MacUtil.findWindowFromJavaWindow(belowWindow) : null;
            Foundation.NSRect nsRect = new Foundation.NSRect(rect.x, rect.y, rect.width, rect.height);
            ID cgWindowId = windowId != null ? Foundation.invoke(windowId, "windowNumber") : ID.NIL;
            int windowListOptions = cgWindowId != null
                    ? FoundationLibrary.kCGWindowListOptionIncludingWindow
                    : FoundationLibrary.kCGWindowListOptionAll;
            int windowImageOptions = FoundationLibrary.kCGWindowImageBestResolution |
                    FoundationLibrary.kCGWindowImageShouldBeOpaque;
            ID cgImageRef = Foundation.cgWindowListCreateImage(
                    nsRect, windowListOptions, cgWindowId, windowImageOptions);

            ID bitmapRep = Foundation.invoke(
                    Foundation.invoke("NSBitmapImageRep", "alloc"),
                    "initWithCGImage:", cgImageRef);
            ID nsImage = Foundation.invoke(
                    Foundation.invoke("NSImage", "alloc"),
                    "init");
            Foundation.invoke(nsImage, "addRepresentation:", bitmapRep);
            ID data = Foundation.invoke(nsImage, "TIFFRepresentation");
            ID bytes = Foundation.invoke(data, "bytes");
            ID length = Foundation.invoke(data, "length");
            Pointer ptr = new Pointer(bytes.longValue());
            ByteBuffer byteBuffer = ptr.getByteBuffer(0, length.longValue());
            Foundation.invoke(nsImage, "release");
            byte[] b = new byte[byteBuffer.remaining()];
            byteBuffer.get(b);

            BufferedImage result = ImageIO.read(new ByteArrayInputStream(b));
            if (result != null) {
                ColorSpace ics = ColorSpace.getInstance(ColorSpace.CS_sRGB);
                ColorConvertOp cco = new ColorConvertOp(ics, null);
                return cco.filter(result, null);
            }
            return null;
        }
        catch (Throwable t) {
            return null;
        }
        finally {
            Foundation.invoke(pool, "release");
        }
    }

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
        while (!Color.black.equals(r.getPixelColor(p[0].x, p[0].y))) {
            Thread.sleep(100);
        }
        screenRect = new Rectangle(p[0].x + 5, p[0].y + 5, width, height);

        BufferedImage result = SystemUtils.IS_OS_MAC ?
                captureScreen(f[0], screenRect) : r.createScreenCapture(screenRect);
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
