/*
 * Copyright (c) 2019, 2023, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2023, JetBrains s.r.o.. All rights reserved.
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

import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.ImageIO;
import javax.swing.*;

/**
 * @test
 * @key headful
 * @bug 8311165
 * @requires os.family == "mac"
 * @summary [macosx] compares displayed colors versus input colors on macos opengl or metal pipelines.
 * @run main/othervm -Dsun.java2d.uiScale=1.0 -Dsun.java2d.opengl=true MetalLayerColorTest
 * @run main/othervm -Dsun.java2d.uiScale=1.0 -Dsun.java2d.metal=true MetalLayerColorTest
 * @run main/othervm -Dsun.java2d.uiScale=1.0 -Dsun.java2d.metal=true -Dsun.java2d.metal.colorMatching=true MetalLayerColorTest
 * @author lbourges
 *
 *
 * Expected results:
 * As opengl is the reference implementation on MacOS since 20 years, it uses directly sRGB values (no color matching).
 * If the monitor is using display P3, colors are displayed as sRGB colors => color differences are important !
 *
 * With MacOS Monitor profile 'sRGB':
 *      Java2D pipeline: OpenGL
 *      COLOR_TOLERANCE: 5
 *      Stats: delta[729] sum: 0 avg: 0.0 [0 | 0]
 *      Max colors: 729
 *      Bad colors: 0
 *
 * With MacOS Monitor profile 'XDR Display P3 (1600 nits)':
 *      Java2D pipeline: OpenGL
 *      COLOR_TOLERANCE: 5
 *      OpenGL pipeline enabled on screen 1
 *      Stats: delta[729] sum: 16534 avg: 22.68 [0 | 96]
 *      Max colors: 729
 *      Bad colors: 615

 *
 * For the apple Metal pipeline, CAMetalLayer was converting sRGB to monitor color space (color matching) until 2023.06.
 * A new system property has been introduced to enable back color matching (disabled by default to mimic opengl output)
 *
 * With MacOS Monitor profile 'sRGB':
 *      Java2D pipeline: Metal
 *      COLOR_TOLERANCE: 5
 *      Stats: delta[729] sum: 0 avg: 0.0 [0 | 0]
 *      Max colors: 729
 *      Bad colors: 0
 *
 * With MacOS Monitor profile 'Display P3 (1600 nits)' -Dsun.java2d.metal.colorMatching=true (before 2023.06.28):
 *      Java2D pipeline: Metal
 *      COLOR_TOLERANCE: 5
 *      Metal pipeline enabled on screen 1
 *      Stats: delta[729] sum: 389 avg: 0.533 [0 | 5]
 *      Max colors: 729
 *      Bad colors: 0
 *
 * With MacOS Monitor profile 'Display P3 (1600 nits)' -Dsun.java2d.metal.colorMatching=false (new default on 2023.06.28):
 *      Java2D pipeline: Metal
 *      COLOR_TOLERANCE: 5
 *      Metal pipeline enabled on screen 1
 *      Stats: delta[729] sum: 16534 avg: 22.68 [0 | 96]
 *      Max colors: 729
 *      Bad colors: 615
 *
 * With this fix, the metal pipeline renders colors like opengl pipeline (without any color matching) on XDR display.
 */
public class MetalLayerColorTest {

    private final static boolean TRACE_MARKER = false;
    private final static boolean TRACE = false;

    private final static int BW = 30;
    private final static int BH = 30;

    private final static int MW = BW / 2;
    private final static int MH = BH / 2;

    private final static int M = 2;
    private final static int M2 = M * 2;

    private final static int CYCLE_DELAY = (1000 / 60);
    private final static int MAX_MEASURE_CYCLES = 2000 / CYCLE_DELAY;
    private final static int COUNT = 60;

    private final static int COLOR_N_STEP = 8;
    private final static int COLOR_STEP = 256 / COLOR_N_STEP;

    private final static int COLOR_TOLERANCE = 5;

    private static final Color GRAY_50 = new Color(192, 192, 192);

    private final static Color[] palette;

    private final static Color[] marker = new Color[] {
            Color.BLUE,
            Color.GREEN,
            Color.RED
    };

    static {
        if (TRACE) {
            System.out.println("Marker colors: " + Arrays.toString(marker));
        }

        final ArrayList<Color> colors = new ArrayList<>();

        for (int b = 0; b <= 256; b += COLOR_STEP) {
            for (int g = 0; g <= 256; g += COLOR_STEP) {
                for (int r = 0; r <= 256; r += COLOR_STEP) {
                    colors.add(new Color(clamp(r), clamp(g), clamp(b)));
                }
            }
        }
        palette = colors.toArray(new Color[0]);
        if (TRACE) {
            System.out.println("Palette colors: " + Arrays.toString(palette));
        }
    }

    private final static int N = palette.length;

    private static final Blocks blocks = new Blocks(N, BW, BH);

    static class Blocks {

        private final float[] bx;
        private final float[] by;
        private final int n;

        private final float bw;
        private final float bh;
        final float width;
        final float height;

        Blocks(int n, float bw, float bh) {
            this.n = n;
            this.bw = bw;
            this.bh = bh;
            bx = new float[n];
            by = new float[n];

            final int rows = (int) Math.ceil(Math.sqrt((n * bh) / bw));

            this.width  = (rows + 2) * bw;
            this.height = (rows + 2) * bh;

            if (TRACE) {
                System.out.println("width:  " + width);
                System.out.println("height: " + height);
            }

            for (int i = 0, x = 1, y = 1; i < n; i++) {
                bx[i] = x * bw;
                by[i] = y * bh;
                if (x++ >= rows) {
                    x = 1;
                    y++;
                }
            }
        }

        void render(Graphics2D g2d) {
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

            g2d.setBackground(GRAY_50);
            g2d.clearRect(0, 0, (int) Math.ceil(width), (int) Math.ceil(height));

            for (int i = 0; i < n; i++) {
                g2d.setColor(palette[i % N]);
                g2d.fillRect((int) (bx[i] + M), (int) (by[i] + M), BW - M2, BH - M2);
            }
        }

        int test(final BufferedImage image) {
            int res = 0;

            final StatLong stats = new StatLong("delta");

            for (int i = 0; i < n; i++) {
                final Color exp = palette[i % N];
                final Color c = new Color(image.getRGB((int) (bx[i] + MW), (int) (by[i] + MH)));

                final int delta = delta(c, exp, TRACE);
                if (delta <= COLOR_TOLERANCE) {
                    if (TRACE) {
                        System.out.println("Same color["+i+"].");
                    }
                } else {
                    res++;
                    if (TRACE) {
                        System.out.println("Diff color["+i+"] !");
                    }
                }
                stats.add(delta);
            }
            System.out.println("Stats: "+stats.toString());

            return res;
        }
    }

    static class PerfMeter {

        private String name;

        private JPanel panel;
        private AtomicInteger markerIdx = new AtomicInteger(0);
        private AtomicLong markerPaintTime = new AtomicLong(0);

        PerfMeter(String name) {
            this.name = name;
        }

        PerfMeter exec() throws Exception {
            final CountDownLatch latchFrame = new CountDownLatch(1);

            final JFrame f = new JFrame(
                    "Java2D @ " + (IS_RDR_METAL ? "Metal" : "OpenGL")
            );
            f.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent e) {
                    latchFrame.countDown();
                }
            });

            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {

                    panel = new JPanel() {
                        @Override
                        protected void paintComponent(Graphics g) {
                            super.paintComponent(g);

                            final int idx = markerIdx.get();

                            final Graphics2D g2d = (Graphics2D) g.create();
                            try {
                                blocks.render(g2d);

                                for (int i = 0; i < marker.length; i++) {
                                    final int midx = (idx + i) % marker.length;
                                    final Color mcol = marker[midx];

                                    if (TRACE_MARKER) {
                                        System.out.println("paintComponent() [idx = " + midx + "] (RGB) = ("
                                                + mcol.getRed() + ","
                                                + mcol.getGreen() + ","
                                                + mcol.getBlue() + ")");
                                    }
                                    g2d.setColor(mcol);
                                    g2d.fillRect(i * BW, 0, BW, BH);
                                }
                                markerPaintTime.set(System.nanoTime());
                                sync();
                            } finally {
                                g2d.dispose();
                            }
                        }
                    };

                    panel.setPreferredSize(new Dimension((int) blocks.width, (int) blocks.height));
                    panel.setBackground(Color.BLACK);
                    f.add(panel);
                    f.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
                    f.pack();

                    // Disable double-buffering:
                    RepaintManager.currentManager(f).setDoubleBufferingEnabled(false);

                    f.setVisible(true);
                }
            });

            final Robot robot = new Robot();
            int cycle = 0;
            int frame = 0;

            while (frame < COUNT) {
                sync();

                if (markerPaintTime.getAndSet(0) > 0) {
                    final Container parent = panel.getTopLevelAncestor();
                    final Insets insets = parent.getInsets();
                    final int x0 = parent.getX() + insets.left;
                    final int y0 = parent.getY() + insets.top;

                    final int w = panel.getWidth() + insets.right;
                    final int h = panel.getHeight() + insets.bottom;

                    boolean markersFound = true;

                    final int idx = markerIdx.get();
                    if (TRACE_MARKER) {
                        System.out.println("expected marker idx: " + idx);
                    }

                    for (int i = 0; i < marker.length; i++) {
                        final int midx = (idx + i) % marker.length;
                        final Color exp = marker[midx];
                        final Color c = robot.getPixelColor(x0 + MW + i * BW, y0 + MH);

                        if (delta(c, exp, TRACE_MARKER) <= COLOR_TOLERANCE) {
                            if (TRACE_MARKER) {
                                System.out.println("Same color["+midx+"].");
                            }
                        } else {
                            markersFound = false;
                            if (TRACE_MARKER) {
                                System.out.println("Diff color["+midx+"] !");
                            }
                            break;
                        }
                    }

                    if (markersFound) {
                        if (TRACE_MARKER) {
                            System.out.println("All markers match.");
                        }

                        final AffineTransform tx = parent.getGraphicsConfiguration().getDefaultTransform();

                        final Rectangle windowRect = new Rectangle(
                                (int) (x0 * tx.getScaleX()), (int) (y0 * tx.getScaleY()),
                                (int) (w * tx.getScaleX()), (int) (h * tx.getScaleY()));

                        if (frame == 0) {
                            if (TRACE) {
                                System.out.println("parent: " + parent);
                                System.out.println("insets: " + insets);
                                System.out.println("tx: " + tx);

                                System.out.println("windowRect: " + windowRect);
                            }
                            final BufferedImage capture = robot.createScreenCapture(windowRect);

                            if (TRACE) {
                                System.out.println("image width:  " + capture.getWidth());
                                System.out.println("image height: " + capture.getHeight());
                            }

                            ImageIO.write(capture, "png", new File("capture_"
                                + ((IS_RDR_METAL ? (IS_RDR_METAL_CM ? "metal_cm1" : "metal_cm0") : "opengl")) +".png")
                            );

                            final int res = blocks.test(capture);

                            System.out.println("Max colors: " + N);
                            System.out.println("Bad colors: " + res);

                            if (false) {
                                break;
                            }
                        }

                        frame++;
                        markerIdx.accumulateAndGet(marker.length, (x, y) -> (x + 1) % y);
                    }
                }
                // always repaint:
                panel.getParent().repaint();

                try {
                    Thread.sleep(CYCLE_DELAY);
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                }
                if (cycle >= MAX_MEASURE_CYCLES) {
                    System.out.println("Max cycle : " + cycle);
                    break;
                }
                cycle++;
            } // while

            SwingUtilities.invokeAndWait(() -> {
                f.setVisible(false);
                f.dispose();
            });
            latchFrame.await();
            return this;
        }
    }

    private static boolean IS_RDR_METAL = false;
    private static boolean IS_RDR_METAL_CM = false;

    public static void main(String[] args) {
        if (System.getProperty("sun.java2d.opengl") == null && System.getProperty("sun.java2d.metal") == null) {
            System.setProperty("sun.java2d.metal", "true"); // default
        }

        IS_RDR_METAL = "true".equalsIgnoreCase(System.getProperty("sun.java2d.metal"));

        if ("true".equalsIgnoreCase(System.getProperty("sun.java2d.opengl"))) {
            IS_RDR_METAL = false;
        }
        System.out.println("Java2D pipeline: " + (IS_RDR_METAL ? "Metal" : "OpenGL"));

        if (IS_RDR_METAL) {
            IS_RDR_METAL_CM = !"false".equalsIgnoreCase(System.getProperty("sun.java2d.metal.colorMatching"));
            System.out.println("IS_RDR_METAL_CM: " + IS_RDR_METAL_CM);
        }

        System.out.println("COLOR_TOLERANCE: " + COLOR_TOLERANCE);

        try {
            new PerfMeter("FlatBoxAA").exec();
        } catch (Exception e) {
            new RuntimeException("Test failed.", e);
        }
    }

    // --- Utility methods ---
    private static void sync() {
        Toolkit.getDefaultToolkit().sync();
    }

    private static int clamp(final int v) {
        return Math.max(0, Math.min(v, 255));
    }

    private static int delta(final Color c, final Color exp, final boolean dump) {
        final int dr = Math.abs(c.getRed() - exp.getRed());
        final int dg = Math.abs(c.getGreen() - exp.getGreen());
        final int db = Math.abs(c.getBlue() - exp.getBlue());

        final int delta = Math.max(Math.max(dr, dg), db);

        if (dump && (delta > COLOR_TOLERANCE)) {
            System.out.println("Expected: (RGB) = (" + exp.getRed() + "," + exp.getGreen() + "," + exp.getBlue() + ")");
            System.out.println("Color:    (RGB) = (" + c.getRed() + "," + c.getGreen() + "," + c.getBlue() + ")");
            System.out.println("Delta col (RGB) = (" + dr + "," + dg + "," + db + ")");
            System.out.println("Delta:          =  " + delta);
        }
        return delta;
    }

    private static boolean isAlmostEqual(final Color c, final Color exp, final boolean dump) {
        return (delta(c, exp, dump) <= COLOR_TOLERANCE);
    }

    /**
     * Statistics as long values
     */
    final static class StatLong {

        public final String name;
        public long count = 0L;
        public long sum = 0L;
        public long min = Integer.MAX_VALUE;
        public long max = Integer.MIN_VALUE;

        public StatLong(final String name) {
            this.name = name;
        }

        public void reset() {
            count = 0L;
            sum = 0L;
            min = Integer.MAX_VALUE;
            max = Integer.MIN_VALUE;
        }

        public void add(final int val) {
            count++;
            sum += val;
            if (val < min) {
                min = val;
            }
            if (val > max) {
                max = val;
            }
        }

        public void add(final long val) {
            count++;
            sum += val;
            if (val < min) {
                min = val;
            }
            if (val > max) {
                max = val;
            }
        }

        @Override
        public String toString() {
            return toString(new StringBuilder(128)).toString();
        }

        public final StringBuilder toString(final StringBuilder sb) {
            sb.append(name).append('[').append(count);
            sb.append("] sum: ").append(sum).append(" avg: ");
            sb.append(trimTo3Digits(((double) sum) / count));
            sb.append(" [").append(min).append(" | ").append(max).append("]");
            return sb;
        }

        /**
         * Adjust the given double value to keep only 3 decimal digits
         *
         * @param value value to adjust
         * @return double value with only 3 decimal digits
         */
        public static double trimTo3Digits(final double value) {
            return ((long) (1e3d * value)) / 1e3d;
        }
    }

}
