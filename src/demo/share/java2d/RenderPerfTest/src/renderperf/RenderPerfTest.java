/*
 * Copyright (c) 2019, 2020 Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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

package renderperf;


import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.QuadCurve2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;


public class RenderPerfTest {
    private static HashSet<String> ignoredTests = new HashSet<>();

    static {
            ignoredTests.add("testWhiteTextBubblesNoAA");
            ignoredTests.add("testWhiteTextBubblesLCD");
            ignoredTests.add("testWhiteTextBubblesGray");
            ignoredTests.add("testLinGradOvalRotBubblesAA");
            ignoredTests.add("testWiredBoxBubblesAA");
            ignoredTests.add("testLinesAA");
    }

    private final static int N = 1000;
    private final static float WIDTH = 800;
    private final static float HEIGHT = 800;
    private final static float R = 25;
    private final static int BW = 50;
    private final static int BH = 50;
    private final static int COUNT = 300;
    private final static int DELAY = 10;
    private final static int RESOLUTION = 5;
    private final static int COLOR_TOLERANCE = 10;
    private final static int MAX_MEASURE_TIME = 5000;


    interface Configurable {
        void configure(Graphics2D g2d);
    }

    interface Renderable {
        void setup(Graphics2D g2d);
        void render(Graphics2D g2d);
        void update();
    }

    static class Particles {
        private float[] bx;
        private float[] by;
        private float[] vx;
        private float[] vy;
        private float r;
        private int n;

        private float x0;
        private float y0;
        private float width;
        private float height;

        Particles(int n, float r, float x0, float y0, float width, float height) {
            bx = new float[n];
            by = new float[n];
            vx = new float[n];
            vy = new float[n];
            this.n = n;
            this.r = r;
            this.x0 = x0;
            this.y0 = y0;
            this.width = width;
            this.height = height;
            for (int i = 0; i < n; i++) {
                bx[i] = (float) (x0 + r + 0.1 + Math.random() * (width - 2 * r - 0.2 - x0));
                by[i] = (float) (y0 + r + 0.1 + Math.random() * (height - 2 * r - 0.2 - y0));
                vx[i] = 0.1f * (float) (Math.random() * 2 * r - r);
                vy[i] = 0.1f * (float) (Math.random() * 2 * r - r);
            }

        }

        void render(Graphics2D g2d, ParticleRenderer renderer) {
            for (int i = 0; i < n; i++) {
                renderer.render(g2d, i, bx, by, vx, vy);
            }
        }

        void update() {
            for (int i = 0; i < n; i++) {
                bx[i] += vx[i];
                if (bx[i] + r > width || bx[i] - r < x0) vx[i] = -vx[i];
                by[i] += vy[i];
                if (by[i] + r > height || by[i] - r < y0) vy[i] = -vy[i];
            }

        }

    }

    ParticleRenderable createPR(ParticleRenderer renderer) {
        return new ParticleRenderable(renderer);
    }

    static class ParticleRenderable implements Renderable {
        ParticleRenderer renderer;
        Configurable configure;

        ParticleRenderable(ParticleRenderer renderer, Configurable configure) {
            this.renderer = renderer;
            this.configure = configure;
        }

        ParticleRenderable(ParticleRenderer renderer) {
            this(renderer, null);
        }

        @Override
        public void setup(Graphics2D g2d) {
            if (configure != null) configure.configure(g2d);
        }

        @Override
        public void render(Graphics2D g2d) {
            balls.render(g2d, renderer);
        }

        @Override
        public void update() {
            balls.update();
        }

        public ParticleRenderable configure(Configurable configure) {
            this.configure = configure;
            return this;
        }
    }

    interface ParticleRenderer {
        void render(Graphics2D g2d, int id, float[] x, float[] y, float[] vx, float[] vy);

    }

    static class FlatParticleRenderer implements ParticleRenderer {
        Color[] colors;
        float r;

        FlatParticleRenderer(int n, float r) {
            colors = new Color[n];
            this.r = r;
            for (int i = 0; i < n; i++) {
                colors[i] = new Color((float) Math.random(),
                        (float) Math.random(), (float) Math.random());
            }
        }

        @Override
        public void render(Graphics2D g2d, int id, float[] x, float[] y, float[] vx, float[] vy) {
            g2d.setColor(colors[id % colors.length]);
            g2d.fillOval((int)(x[id] - r), (int)(y[id] - r), (int)(2*r), (int)(2*r));
        }

    }

    static class WhiteTextParticleRenderer implements ParticleRenderer {
        float r;

        WhiteTextParticleRenderer(float r) {
            this.r = r;
        }

        void setPaint(Graphics2D g2d, int id) {
            g2d.setColor(Color.WHITE);
        }

        @Override
        public void render(Graphics2D g2d, int id, float[] x, float[] y, float[] vx, float[] vy) {
            setPaint(g2d, id);
            g2d.drawString("The quick brown fox jumps over the lazy dog",
                    (int)(x[id] - r), (int)(y[id] - r));
            g2d.drawString("The quick brown fox jumps over the lazy dog",
                    (int)(x[id] - r), (int)y[id]);
            g2d.drawString("The quick brown fox jumps over the lazy dog",
                    (int)(x[id] - r), (int)(y[id] + r));
        }
    }

    static class TextParticleRenderer extends WhiteTextParticleRenderer {
        Color[] colors;

        float r;

        TextParticleRenderer(int n, float r) {
            super(r);
            colors = new Color[n];
            this.r = r;
            for (int i = 0; i < n; i++) {
                colors[i] = new Color((float) Math.random(),
                        (float) Math.random(), (float) Math.random());
            }
        }

        void setPaint(Graphics2D g2d, int id) {
            g2d.setColor(colors[id % colors.length]);
        }
    }

    static class LargeTextParticleRenderer extends TextParticleRenderer {

        LargeTextParticleRenderer(int n, float r) {
            super(n, r);
        }

        @Override
        public void render(Graphics2D g2d, int id, float[] x, float[] y, float[] vx, float[] vy) {
            setPaint(g2d, id);
            Font font = new Font("LucidaGrande", Font.PLAIN, 32);
            g2d.setFont(font);
            g2d.drawString("The quick brown fox jumps over the lazy dog",
                    (int)(x[id] - r), (int)(y[id] - r));
            g2d.drawString("The quick brown fox jumps over the lazy dog",
                    (int)(x[id] - r), (int)y[id]);
            g2d.drawString("The quick brown fox jumps over the lazy dog",
                    (int)(x[id] - r), (int)(y[id] + r));
        }
    }

    static class FlatOvalRotParticleRenderer extends FlatParticleRenderer {


        FlatOvalRotParticleRenderer(int n, float r) {
            super(n, r);
        }

        void setPaint(Graphics2D g2d, int id) {
            g2d.setColor(colors[id % colors.length]);
        }

        @Override
        public void render(Graphics2D g2d, int id, float[] x, float[] y, float[] vx, float[] vy) {
            setPaint(g2d, id);
            if (Math.abs(vx[id] + vy[id]) > 0.001) {
                AffineTransform t = (AffineTransform) g2d.getTransform().clone();
                double l = vx[id] / Math.sqrt(vx[id] * vx[id] + vy[id] * vy[id]);
                if (vy[id] < 0) {
                    l = -l;
                }
                g2d.translate(x[id], y[id]);
                g2d.rotate(Math.acos(l));
                g2d.fillOval(-(int)r, (int)(-0.5*r), (int) (2 * r), (int)r);
                g2d.setTransform(t);
            } else {
                g2d.fillOval((int)(x[id] - r), (int)(y[id] - 0.5*r),
                        (int) (2 * r), (int) r);
            }
        }
    }

    static class LinGradOvalRotParticleRenderer extends FlatOvalRotParticleRenderer {


        LinGradOvalRotParticleRenderer(int n, float r) {
            super(n, r);
        }

        @Override
        void setPaint(Graphics2D g2d, int id) {
            Point2D start = new Point2D.Double(- r,  - 0.5*r);
            Point2D end = new Point2D.Double( 2 * r, r);
            float[] dist = {0.0f, 1.0f};
            Color[] cls = {colors[id %colors.length], colors[(colors.length - id) %colors.length]};
            LinearGradientPaint p =
                    new LinearGradientPaint(start, end, dist, cls);
            g2d.setPaint(p);
        }
    }

    static class FlatBoxParticleRenderer extends FlatParticleRenderer {


        FlatBoxParticleRenderer(int n, float r) {
            super(n, r);
        }
        @Override
        public void render(Graphics2D g2d, int id, float[] x, float[] y, float[] vx, float[] vy) {
            g2d.setColor(colors[id % colors.length]);
            g2d.fillRect((int)(x[id] - r), (int)(y[id] - r), (int)(2*r), (int)(2*r));

        }

    }

    static class ImgParticleRenderer extends FlatParticleRenderer {
        BufferedImage dukeImg;

        ImgParticleRenderer(int n, float r) {
            super(n, r);
            try {
                dukeImg = ImageIO.read(
                        Objects.requireNonNull(
                                RenderPerfTest.class.getClassLoader().getResourceAsStream(
                                        "renderperf/images/duke.png")));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void render(Graphics2D g2d, int id, float[] x, float[] y, float[] vx, float[] vy) {
            g2d.setColor(colors[id % colors.length]);
            g2d.drawImage(dukeImg, (int)(x[id] - r), (int)(y[id] - r), (int)(2*r), (int)(2*r), null);
        }

    }

    static class FlatBoxRotParticleRenderer extends FlatParticleRenderer {


        FlatBoxRotParticleRenderer(int n, float r) {
            super(n, r);
        }
        @Override
        public void render(Graphics2D g2d, int id, float[] x, float[] y, float[] vx, float[] vy) {
            g2d.setColor(colors[id % colors.length]);
            if (Math.abs(vx[id] + vy[id]) > 0.001) {
                AffineTransform t = (AffineTransform) g2d.getTransform().clone();
                double l = vx[id] / Math.sqrt(vx[id] * vx[id] + vy[id] * vy[id]);
                if (vy[id] < 0) {
                    l = -l;
                }
                g2d.translate(x[id], y[id]);
                g2d.rotate(Math.acos(l));
                g2d.fillRect(-(int)r, -(int)r, (int) (2 * r), (int) (2 * r));
                g2d.setTransform(t);
            } else {
                g2d.fillRect((int)(x[id] - r), (int)(y[id] - r),
                        (int) (2 * r), (int) (2 * r));
            }
        }
    }

    static class WiredParticleRenderer extends FlatParticleRenderer {


        WiredParticleRenderer(int n, float r) {
            super(n, r);
        }
        @Override
        public void render(Graphics2D g2d, int id, float[] x, float[] y, float[] vx, float[] vy) {
            g2d.setColor(colors[id % colors.length]);
            g2d.drawOval((int)(x[id] - r), (int)(y[id] - r), (int)(2*r), (int)(2*r));
        }

    }
    static class WiredBoxParticleRenderer extends FlatParticleRenderer {

        WiredBoxParticleRenderer(int n, float r) {
            super(n, r);
        }

        @Override
        public void render(Graphics2D g2d, int id, float[] x, float[] y, float[] vx, float[] vy) {
            g2d.setColor(colors[id % colors.length]);
            g2d.drawRect((int)(x[id] - r), (int)(y[id] - r), (int)(2*r), (int)(2*r));
        }

    }
    static class SegParticleRenderer extends FlatParticleRenderer {

        SegParticleRenderer(int n, float r) {
            super(n, r);
        }

        @Override
        public void render(Graphics2D g2d, int id, float[] x, float[] y, float[] vx, float[] vy) {
            double v = Math.sqrt(vx[id]*vx[id]+vy[id]*vy[id]);
            float nvx = (float) (vx[id]/v);
            float nvy = (float) (vy[id]/v);
            g2d.setColor(colors[id % colors.length]);
            g2d.drawLine((int)(x[id] - r*nvx), (int)(y[id] - r*nvy),
                    (int)(x[id] + 2*r*nvx), (int)(y[id] + 2*r*nvy));
        }

    }


    static class WiredQuadParticleRenderer extends FlatParticleRenderer {

        WiredQuadParticleRenderer(int n, float r) {
            super(n, r);
        }

        @Override
        public void render(Graphics2D g2d, int id, float[] x, float[] y, float[] vx, float[] vy) {
            if (id > 2 && (id % 3) == 0) {
                g2d.setColor(colors[id % colors.length]);
                g2d.draw(new QuadCurve2D.Float(x[id-3], y[id-3], x[id-2], y[id-2], x[id-1], y[id-1]));
            }

        }
    }

    static class FlatQuadParticleRenderer extends FlatParticleRenderer {

        FlatQuadParticleRenderer(int n, float r) {
            super(n, r);
        }

        @Override
        public void render(Graphics2D g2d, int id, float[] x, float[] y, float[] vx, float[] vy) {
            if (id > 2 && (id % 3) == 0) {
                g2d.setColor(colors[id % colors.length]);
                g2d.fill(new QuadCurve2D.Float(x[id-3], y[id-3], x[id-2], y[id-2], x[id-1], y[id-1]));
            }

        }
    }

    static class PerfMeter {
        private String name;
        private int frame = 0;

        private JPanel panel;

        private long time;
        private double execTime = 0;
        private Color expColor = Color.RED;
        AtomicBoolean waiting = new AtomicBoolean(false);
        private double fps;

        PerfMeter(String name) {
            this.name = name;
        }

        PerfMeter exec(final Renderable renderable) throws Exception {
            final CountDownLatch latch = new CountDownLatch(COUNT);
            final CountDownLatch latchFrame = new CountDownLatch(1);
            final long endTime = System.currentTimeMillis() + MAX_MEASURE_TIME;

            final JFrame f = new JFrame();
            f.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent e) {
                    latchFrame.countDown();
                }
            });

            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {

                    panel = new JPanel()
                    {
                        @Override
                        protected void paintComponent(Graphics g) {

                            super.paintComponent(g);
                            time = System.nanoTime();
                            Graphics2D g2d = (Graphics2D) g.create();
                            renderable.setup(g2d);
                            renderable.render(g2d);
                            g2d.setColor(expColor);
                            g.fillRect(0, 0, BW, BH);
                        }
                    };

                    panel.setPreferredSize(new Dimension((int)(WIDTH + BW), (int)(HEIGHT + BH)));
                    panel.setBackground(Color.BLACK);
                    f.add(panel);
                    f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
                    f.pack();
                    f.setVisible(true);
                }
            });
            Robot robot = new Robot();

            Timer timer = new Timer(DELAY, e -> {

                if (waiting.compareAndSet(false, true)) {
                    Color c = robot.getPixelColor(
                            panel.getTopLevelAncestor().getX() + panel.getTopLevelAncestor().getInsets().left + BW / 2,
                            panel.getTopLevelAncestor().getY() + panel.getTopLevelAncestor().getInsets().top + BW / 2);
                    if (isAlmostEqual(c, Color.BLUE)) {
                        expColor = Color.RED;
                    } else {
                        expColor = Color.BLUE;
                    }
                    renderable.update();
                    panel.getParent().repaint();

                } else {
                    while (!isAlmostEqual(
                            robot.getPixelColor(
                                    panel.getTopLevelAncestor().getX() + panel.getTopLevelAncestor().getInsets().left + BW/2,
                                    panel.getTopLevelAncestor().getY() + panel.getTopLevelAncestor().getInsets().top + BH/2),
                            expColor))
                    {
                        try {
                            Thread.sleep(RESOLUTION);
                        } catch (InterruptedException ex) {
                            ex.printStackTrace();
                        }
                    }
                    time = System.nanoTime() - time;
                    execTime += time;
                    frame++;
                    waiting.set(false);
                }

                if (System.currentTimeMillis() < endTime) {
                    latch.countDown();
                } else {
                    while(latch.getCount() > 0) latch.countDown();
                }
            });
            timer.start();
            latch.await();
            SwingUtilities.invokeAndWait(() -> {
                timer.stop();
                f.setVisible(false);
                f.dispose();
            });

            latchFrame.await();
            if (execTime != 0 && frame != 0) {
                fps = 1e9 / (execTime / frame);
            } else {
                fps = 0;
            }

            return this;
        }

        private void report() {
            System.err.println(name + " : " + String.format("%.2f FPS", fps));
        }

        private boolean isAlmostEqual(Color c1, Color c2) {
            return Math.abs(c1.getRed() - c2.getRed()) < COLOR_TOLERANCE ||
                    Math.abs(c1.getGreen() - c2.getGreen()) < COLOR_TOLERANCE ||
                    Math.abs(c1.getBlue() - c2.getBlue()) < COLOR_TOLERANCE;

        }
    }

    private static final Particles balls = new Particles(N, R, BW, BH, WIDTH, HEIGHT);
    private static final ParticleRenderer flatRenderer = new FlatParticleRenderer(N, R);
    private static final ParticleRenderer flatOvalRotRenderer = new FlatOvalRotParticleRenderer(N, R);
    private static final ParticleRenderer flatBoxRenderer = new FlatBoxParticleRenderer(N, R);
    private static final ParticleRenderer flatBoxRotRenderer = new FlatBoxRotParticleRenderer(N, R);
    private static final ParticleRenderer linGradOvalRotRenderer = new LinGradOvalRotParticleRenderer(N, R);
    private static final ParticleRenderer wiredRenderer = new WiredParticleRenderer(N, R);
    private static final ParticleRenderer wiredBoxRenderer = new WiredBoxParticleRenderer(N, R);
    private static final ParticleRenderer segRenderer = new SegParticleRenderer(N, R);
    private static final ParticleRenderer flatQuadRenderer = new FlatQuadParticleRenderer(N, R);
    private static final ParticleRenderer wiredQuadRenderer = new WiredQuadParticleRenderer(N, R);
    private static final ParticleRenderer imgRenderer = new ImgParticleRenderer(N, R);
    private static final ParticleRenderer textRenderer = new TextParticleRenderer(N, R);
    private static final ParticleRenderer largeTextRenderer = new LargeTextParticleRenderer(N, R);
    private static final ParticleRenderer whiteTextRenderer = new WhiteTextParticleRenderer(R);

    private static final Configurable AA = (Graphics2D g2d) ->
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
            RenderingHints.VALUE_ANTIALIAS_ON);

    private static final Configurable TextLCD = (Graphics2D g2d) ->
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);

    private static final Configurable TextAA = (Graphics2D g2d) ->
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

    public void testFlatBubbles() throws Exception {
        (new PerfMeter("FlatOval")).exec(createPR(flatRenderer)).report();
    }

    public void testFlatBubblesAA() throws Exception {
        (new PerfMeter("FlatOvalAA")).exec(createPR(flatRenderer).configure(AA)).report();
    }

    public void testFlatBoxBubbles() throws Exception {
        (new PerfMeter("FlatBox")).exec(createPR(flatBoxRenderer)).report();
    }

    public void testFlatBoxBubblesAA() throws Exception {
        (new PerfMeter("FlatBoxAA")).exec(createPR(flatBoxRenderer).configure(AA)).report();
    }

    public void testImgBubbles() throws Exception {
        (new PerfMeter("Image")).exec(createPR(imgRenderer)).report();
    }

    public void testImgBubblesAA() throws Exception {
        (new PerfMeter("ImageAA")).exec(createPR(imgRenderer).configure(AA)).report();
    }

    public void testFlatBoxRotBubbles() throws Exception {
        (new PerfMeter("RotatedBox")).exec(createPR(flatBoxRotRenderer)).report();
    }

    public void testFlatBoxRotBubblesAA() throws Exception {
        (new PerfMeter("RotatedBoxAA")).exec(createPR(flatBoxRotRenderer).configure(AA)).report();
    }

    public void testFlatOvalRotBubbles() throws Exception {
        (new PerfMeter("RotatedOval")).exec(createPR(flatOvalRotRenderer)).report();
    }

    public void testFlatOvalRotBubblesAA() throws Exception {
        (new PerfMeter("RotatedOvalAA")).exec(createPR(flatOvalRotRenderer).configure(AA)).report();
    }

    public void testLinGradOvalRotBubbles() throws Exception {
        (new PerfMeter("LinGradRotatedOval")).exec(createPR(linGradOvalRotRenderer)).report();
    }

    public void testLinGradOvalRotBubblesAA() throws Exception {
        (new PerfMeter("LinGradRotatedOvalAA")).exec(createPR(linGradOvalRotRenderer).configure(AA)).report();
    }

    public void testWiredBubbles() throws Exception {
        (new PerfMeter("WiredBubbles")).exec(createPR(wiredRenderer)).report();
    }

    public void testWiredBubblesAA() throws Exception {
        (new PerfMeter("WiredBubblesAA")).exec(createPR(wiredRenderer).configure(AA)).report();
    }

    public void testWiredBoxBubbles() throws Exception {
        (new PerfMeter("WiredBox")).exec(createPR(wiredBoxRenderer)).report();
    }

    public void testWiredBoxBubblesAA() throws Exception {
        (new PerfMeter("WiredBoxAA")).exec(createPR(wiredBoxRenderer).configure(AA)).report();
    }

    public void testLines() throws Exception {
        (new PerfMeter("Lines")).exec(createPR(segRenderer)).report();
    }

    public void testLinesAA() throws Exception {
        (new PerfMeter("LinesAA")).exec(createPR(segRenderer).configure(AA)).report();
    }

    public void testFlatQuad() throws Exception {
        (new PerfMeter("FlatQuad")).exec(createPR(flatQuadRenderer)).report();
    }

    public void testFlatQuadAA() throws Exception {
        (new PerfMeter("FlatQuadAA")).exec(createPR(flatQuadRenderer).configure(AA)).report();
    }

    public void testWiredQuad() throws Exception {
        (new PerfMeter("WiredQuad")).exec(createPR(wiredQuadRenderer)).report();
    }

    public void testWiredQuadAA() throws Exception {
        (new PerfMeter("WiredQuadAA")).exec(createPR(wiredQuadRenderer).configure(AA)).report();
    }

    public void testTextBubblesNoAA() throws Exception {
        (new PerfMeter("TextNoAA")).exec(createPR(textRenderer)).report();
    }

    public void testTextBubblesLCD() throws Exception {
        (new PerfMeter("TextLCD")).exec(createPR(textRenderer).configure(TextLCD)).report();
    }

    public void testTextBubblesGray() throws Exception {
        (new PerfMeter("TextGray")).exec(createPR(textRenderer).configure(TextAA)).report();
    }

    public void testLargeTextBubblesNoAA() throws Exception {
        (new PerfMeter("LargeTextNoAA")).exec(createPR(largeTextRenderer)).report();
    }

    public void testLargeTextBubblesLCD() throws Exception {
        (new PerfMeter("LargeTextLCD")).exec(createPR(largeTextRenderer).configure(TextLCD)).report();
    }

    public void testLargeTextBubblesGray() throws Exception {
        (new PerfMeter("LargeTextGray")).exec(createPR(largeTextRenderer).configure(TextAA)).report();
    }
    public void testWhiteTextBubblesNoAA() throws Exception {
        (new PerfMeter("WhiteTextNoAA")).exec(createPR(whiteTextRenderer)).report();
    }

    public void testWhiteTextBubblesLCD() throws Exception {
        (new PerfMeter("WhiteTextLCD")).exec(createPR(whiteTextRenderer).configure(TextLCD)).report();
    }

    public void testWhiteTextBubblesGray() throws Exception {
        (new PerfMeter("WhiteTextGray")).exec(createPR(whiteTextRenderer).configure(TextAA)).report();
    }

    public static void main(String[] args)
            throws InvocationTargetException, IllegalAccessException, NoSuchMethodException
    {
        RenderPerfTest test = new RenderPerfTest();

        if (args.length > 0) {
            for (String testCase : args) {
                Method m = RenderPerfTest.class.getDeclaredMethod(testCase);
                m.invoke(test);
            }
        } else {
            Method[] methods = RenderPerfTest.class.getDeclaredMethods();
            for (Method m : methods) {
                if (m.getName().startsWith("test") && !ignoredTests.contains(m.getName())) {
                    m.invoke(test);
                }
            }
        }
    }
}
