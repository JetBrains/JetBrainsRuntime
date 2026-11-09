/*
 * Copyright 2026 JetBrains s.r.o.
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
 * @key headful
 * @summary Regression test for JBR-10654: an application's Composite and XOR mode with a
 *          GradientPaint render on a Skia surface as the software loops render them.
 * @requires os.family == "windows"
 * @library /test/lib
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.uiScale=1 SkiaCustomCompositeTest skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.shapes=marlin -Dsun.java2d.uiScale=1 SkiaCustomCompositeTest skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.graphite.api=vulkan -Dsun.java2d.uiScale=1 SkiaCustomCompositeTest skia
 */

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.Composite;
import java.awt.CompositeContext;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fills and strokes with and without antialiasing, under a complex clip, text, and images (whole
 * and a part of one) under the Composite; XOR fills with a gradient, with and without
 * antialiasing; on a VolatileImage and in a window (a heavyweight Canvas drawn into directly).
 * The software loops are the reference, as the default pipeline on Windows renders these. The
 * backend reads the destination back, composes in software and writes the result back; before,
 * a fill under a custom Composite threw InternalError (SKSurfaceData.getRaster), and so did an
 * XOR fill with a gradient (a software mask blit locking the Skia surface).
 */
public class SkiaCustomCompositeTest {

    private static final int WIDTH = 330;
    private static final int HEIGHT = 200;
    /** Software composition on both sides: the results must be identical. */
    private static final int TOLERANCE = 0;

    /** Where the window goes: clear of the screen's edges and of jtreg's own windows. */
    private static final Point WINDOW_LOCATION = new Point(100, 100);
    /** The first paint and present of the window, before the first capture. */
    private static final long FIRST_PAINT_MS = 1000;
    /** How often the window is brought to the front, repainted and captured again before the comparison counts. */
    private static final int CAPTURE_ATTEMPTS = 10;
    /** The repaint and present between two captures. */
    private static final long RECAPTURE_MS = 500;

    private static final int SOURCE_WIDTH = 60;
    private static final int SOURCE_HEIGHT = 40;
    private static final BufferedImage SOURCE = source();

    public static void main(String[] args) throws Exception {
        String pipeline = args[0];
        GraphicsConfiguration graphicsConfig = PipelineComparison.defaultConfiguration();
        PipelineComparison.requirePipeline(pipeline, graphicsConfig);
        BufferedImage reference = PipelineComparison.softwareReference(WIDTH, HEIGHT, SkiaCustomCompositeTest::paint);

        BufferedImage offscreen = PipelineComparison.rendered(graphicsConfig, WIDTH, HEIGHT, SkiaCustomCompositeTest::paint);
        check("a VolatileImage", reference, offscreen);

        BufferedImage window = renderedInWindow(graphicsConfig, reference);
        check("a window", reference, window);
        System.out.println("PASSED");
    }

    private static void check(String where, BufferedImage reference, BufferedImage actual) {
        PipelineComparison.Difference difference = PipelineComparison.compare(reference, actual, TOLERANCE);
        System.out.println(where + ": " + difference.beyond() + " pixels differ from the software loops, max "
                           + difference.max() + " levels");
        if (difference.beyond() > 0) {
            PipelineComparison.printDifferenceMap(reference, actual, TOLERANCE);
            throw new RuntimeException("custom composites and XOR with a gradient on " + where
                    + " differ from the software loops in " + difference.beyond() + " pixels (max " + difference.max()
                    + " levels)");
        }
    }

    /** The painter on a heavyweight Canvas (no Swing back buffer: the window surface itself), read back from the screen. */
    private static BufferedImage renderedInWindow(GraphicsConfiguration graphicsConfig, BufferedImage reference) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<String> paintedOn = new AtomicReference<>();
        AtomicReference<Frame> frameReference = new AtomicReference<>();
        Canvas canvas = new Canvas(graphicsConfig) {
            @Override
            public void paint(Graphics graphics) {
                paintedOn.set(graphics.getClass().getName() + " on " + getGraphicsConfiguration().getClass().getName());
                try {
                    SkiaCustomCompositeTest.paint((Graphics2D) graphics);
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                }
            }

            @Override
            public void update(Graphics graphics) {
                paint(graphics);
            }
        };
        EventQueue.invokeAndWait(() -> {
            Frame frame = new Frame("SkiaCustomCompositeTest", graphicsConfig);
            frame.setUndecorated(true);
            canvas.setSize(WIDTH, HEIGHT);
            frame.add(canvas);
            frame.pack();
            frame.setLocation(WINDOW_LOCATION);
            frame.setAlwaysOnTop(true);
            frame.setVisible(true);
            frameReference.set(frame);
        });
        try {
            Robot robot = new Robot();
            robot.waitForIdle();
            Thread.sleep(FIRST_PAINT_MS);
            robot.waitForIdle();
            if (failure.get() != null) {
                throw new RuntimeException("painting the window failed", failure.get());
            }
            String configName = canvas.getGraphicsConfiguration().getClass().getName();
            System.out.println("window painted: " + paintedOn.get());
            if (!configName.contains(PipelineComparison.SKIA_PACKAGE)) {
                throw new RuntimeException("the window is not on Skia: " + configName);
            }
            AtomicReference<Point> canvasOnScreen = new AtomicReference<>();
            EventQueue.invokeAndWait(() -> canvasOnScreen.set(canvas.getLocationOnScreen()));
            // the window may come up behind another one (the shell's foreground rules):
            // repaint and capture again for a few seconds before the comparison counts
            BufferedImage capture = null;
            for (int attempt = 0; attempt < CAPTURE_ATTEMPTS; attempt++) {
                capture = robot.createScreenCapture(new Rectangle(canvasOnScreen.get().x, canvasOnScreen.get().y, WIDTH, HEIGHT));
                if (PipelineComparison.compare(reference, capture, TOLERANCE).beyond() == 0 || failure.get() != null) {
                    break;
                }
                EventQueue.invokeAndWait(() -> {
                    frameReference.get().toFront();
                    canvas.repaint();
                });
                robot.waitForIdle();
                Thread.sleep(RECAPTURE_MS);
            }
            if (failure.get() != null) {
                throw new RuntimeException("painting the window failed", failure.get());
            }
            return capture;
        } finally {
            EventQueue.invokeAndWait(() -> frameReference.get().dispose());
        }
    }

    /** The image drawn under the Composite: three colors, so that a part of it is told from the whole. */
    private static BufferedImage source() {
        BufferedImage image = new BufferedImage(SOURCE_WIDTH, SOURCE_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(255, 200, 0));
        graphics.fillRect(0, 0, SOURCE_WIDTH, SOURCE_HEIGHT);
        graphics.setColor(new Color(0, 120, 255));
        graphics.fillOval(10, 5, 40, 30);
        graphics.setColor(new Color(200, 0, 120));
        graphics.fillRect(40, 20, 20, 20);
        graphics.dispose();
        return image;
    }

    static void paint(Graphics2D graphics) {
        // a background with bands, so that the results depend on the destination
        graphics.setColor(new Color(0xF0F0F0));
        graphics.fillRect(0, 0, WIDTH, HEIGHT);
        graphics.setColor(new Color(0x2050A0));
        graphics.fillRect(0, 40, WIDTH, 40);
        graphics.setColor(new Color(0xC06020));
        graphics.fillRect(100, 0, 40, HEIGHT);
        Composite paintMode = graphics.getComposite();

        graphics.setComposite(new MultiplyComposite());
        graphics.setColor(new Color(0x80FF40));
        graphics.fillRect(10, 10, 60, 100);
        graphics.fillOval(80, 20, 70, 90);
        graphics.setStroke(new BasicStroke(4f));
        graphics.drawLine(10, 115, 230, 5);
        graphics.setStroke(new BasicStroke(1f));
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.fill(new Ellipse2D.Double(160.5, 10.25, 70, 50));
        graphics.setStroke(new BasicStroke(3f));
        graphics.draw(new RoundRectangle2D.Double(160.5, 70.5, 70, 40, 12, 12));
        graphics.setStroke(new BasicStroke(1f));
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        // a complex clip, with and without antialiasing
        Shape clip = graphics.getClip();
        graphics.clip(new Ellipse2D.Double(240, 10, 80, 100));
        graphics.setColor(new Color(0xFF8080));
        graphics.fillRect(240, 10, 80, 50);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.fill(new Ellipse2D.Double(250.5, 55.5, 60, 50));
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        graphics.setClip(clip);
        // text and images under the Composite
        graphics.setFont(new Font(Font.DIALOG, Font.BOLD, 16));
        graphics.setColor(new Color(0x4040FF));
        graphics.drawString("Composite", 10, 138);
        graphics.drawImage(SOURCE, 120, 115, null);
        graphics.drawImage(SOURCE, 190, 115, 230, 140, 10, 5, 50, 30, null);
        graphics.setComposite(new InvertComposite());
        graphics.setColor(Color.BLACK);
        graphics.fillRect(250, 118, 60, 25);

        // XOR with a gradient
        graphics.setComposite(paintMode);
        graphics.setXORMode(new Color(0x00FF80));
        graphics.setPaint(new GradientPaint(0, 150, Color.RED, WIDTH, 190, Color.BLUE));
        graphics.fillRect(10, 150, 150, 40);
        graphics.fillOval(170, 150, 80, 40);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.fill(new Ellipse2D.Double(260.5, 150.5, 60, 40));
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        graphics.setPaintMode();
        graphics.setComposite(AlphaComposite.SrcOver);
    }

    /** A per-pixel operation on the source and destination colors, alpha from the destination. */
    private abstract static class PixelComposite implements Composite {
        abstract int compose(int sourceARGB, int destinationARGB);

        @Override
        public CompositeContext createContext(ColorModel sourceModel, ColorModel destinationModel, RenderingHints hints) {
            return new CompositeContext() {
                @Override
                public void compose(Raster source, Raster destinationIn, WritableRaster destinationOut) {
                    int width = Math.min(source.getWidth(), Math.min(destinationIn.getWidth(), destinationOut.getWidth()));
                    int height = Math.min(source.getHeight(), Math.min(destinationIn.getHeight(), destinationOut.getHeight()));
                    Object sourcePixel = null;
                    Object destinationPixel = null;
                    Object outputPixel = null;
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {
                            sourcePixel = source.getDataElements(source.getMinX() + x, source.getMinY() + y, sourcePixel);
                            destinationPixel = destinationIn.getDataElements(destinationIn.getMinX() + x,
                                                                             destinationIn.getMinY() + y, destinationPixel);
                            int composed = PixelComposite.this.compose(sourceModel.getRGB(sourcePixel),
                                                                       destinationModel.getRGB(destinationPixel));
                            outputPixel = destinationModel.getDataElements(composed, outputPixel);
                            destinationOut.setDataElements(destinationOut.getMinX() + x, destinationOut.getMinY() + y, outputPixel);
                        }
                    }
                }

                @Override
                public void dispose() {
                }
            };
        }
    }

    /** The destination multiplied by the source color, weighted by the source alpha. */
    private static final class MultiplyComposite extends PixelComposite {
        @Override
        int compose(int sourceARGB, int destinationARGB) {
            int sourceAlpha = sourceARGB >>> 24;
            int composed = destinationARGB & 0xff000000;
            for (int shift = 0; shift <= 16; shift += 8) {
                int sourceChannel = (sourceARGB >> shift) & 0xff;
                int destinationChannel = (destinationARGB >> shift) & 0xff;
                int product = sourceChannel * destinationChannel / 255;
                composed |= (destinationChannel + (product - destinationChannel) * sourceAlpha / 255) << shift;
            }
            return composed;
        }
    }

    /** The destination inverted where the source has any alpha. */
    private static final class InvertComposite extends PixelComposite {
        @Override
        int compose(int sourceARGB, int destinationARGB) {
            return (sourceARGB >>> 24) == 0 ? destinationARGB : (destinationARGB & 0xff000000) | (~destinationARGB & 0xffffff);
        }
    }
}
