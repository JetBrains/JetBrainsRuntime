/*
 * Copyright (c) 2019, 2023, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2022, JetBrains s.r.o.. All rights reserved.
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

package renderperf;

import java.awt.AlphaComposite;
import java.awt.AWTException;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Image;
import java.awt.Insets;
import java.awt.LinearGradientPaint;
import java.awt.Point;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.Transparency;

import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.geom.QuadCurve2D;

import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.awt.image.DataBufferShort;
import java.awt.image.VolatileImage;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;


public final class RenderPerfTest {

    private final static String VERSION = "RenderPerfTest 2023.09";
    private static HashSet<String> ignoredTests = new HashSet<>();

    static {
        // add ignored tests here
        // ignoredTests.add("testMyIgnoredTest");
        ignoredTests.add("testCalibration"); // not from command line
    }

    private final static String EXEC_MODE_ROBOT = "robot";
    private final static String EXEC_MODE_BUFFER = "buffer";
    private final static String EXEC_MODE_VOLATILE = "volatile";
    private final static String EXEC_MODE_DEFAULT = EXEC_MODE_ROBOT;

    public final static List<String> EXEC_MODES = Arrays.asList(new String[]{
            EXEC_MODE_ROBOT, EXEC_MODE_BUFFER, EXEC_MODE_VOLATILE
    });

    private static String EXEC_MODE = EXEC_MODE_DEFAULT;

    private final static boolean CALIBRATION = "true".equalsIgnoreCase(System.getProperty("CALIBRATION", "false"));
    private final static boolean TRACE = "true".equalsIgnoreCase(System.getProperty("TRACE", "false"));

    private static boolean VERBOSE = false;
    private static int REPEATS = 1;

    private static boolean USE_FPS = true;

    private final static int N_DEFAULT = 1000;
    private static int N = N_DEFAULT;
    private final static float WIDTH = 800;
    private final static float HEIGHT = 800;
    private final static float R = 25;
    private final static int BW = 50;
    private final static int BH = 50;
    private final static int IMAGE_W = (int) (WIDTH + BW);
    private final static int IMAGE_H = (int) (HEIGHT + BH);

    private final static int COUNT = 600;
    private final static int MIN_COUNT = 20;
    private final static int WARMUP_COUNT = MIN_COUNT;

    private final static int DELAY = 1;
    private final static int CYCLE_DELAY = DELAY;

    private final static int MIN_MEASURE_TIME_MS = 1000;
    private final static int MAX_MEASURE_TIME_MS = 6000;
    private final static int MAX_FRAME_CYCLES = 3000/CYCLE_DELAY;
    private final static int MAX_MEASURE_CYCLES = MAX_MEASURE_TIME_MS/CYCLE_DELAY;

    private final static int COLOR_TOLERANCE = 10;

    private final static Color[] marker = {Color.RED, Color.BLUE, Color.GREEN};

    private final static Toolkit TOOLKIT = Toolkit.getDefaultToolkit();

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
        return new ParticleRenderable(balls, renderer);
    }

    static final class ParticleRenderable implements Renderable {
        final Particles balls;
        final ParticleRenderer renderer;
        Configurable configure = null;

        ParticleRenderable(final Particles balls, ParticleRenderer renderer) {
            this.balls = balls;
            this.renderer = renderer;
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

    static class CalibrationParticleRenderer implements ParticleRenderer {

        CalibrationParticleRenderer() {
        }

        @Override
        public void render(Graphics2D g2d, int id, float[] x, float[] y, float[] vx, float[] vy) {
            // no-op
        }
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

    static class ClipFlatParticleRenderer extends FlatParticleRenderer {

        ClipFlatParticleRenderer(int n, float r) {
            super(n, r);
        }

        @Override
        public void render(Graphics2D g2d, int id, float[] x, float[] y, float[] vx, float[] vy) {
            if ((id % 10) == 0) {
                g2d.setColor(colors[id % colors.length]);
                g2d.setClip(new Ellipse2D.Double((int) (x[id] - r), (int) (y[id] - r), (int) (2 * r), (int) (2 * r)));
                g2d.fillRect((int) (x[id] - 2 * r), (int) (y[id] - 2 * r), (int) (4 * r), (int) (4 * r));
            }
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
            if (id % 100 != 0) return;
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

    static class LinGrad3OvalRotParticleRenderer extends FlatOvalRotParticleRenderer {


        LinGrad3OvalRotParticleRenderer(int n, float r) {
            super(n, r);
        }

        @Override
        void setPaint(Graphics2D g2d, int id) {
            Point2D start = new Point2D.Double(- r,  - 0.5*r);
            Point2D end = new Point2D.Double( 2 * r, r);
            float[] dist = {0.0f, 0.5f, 1.0f};
            Color[] cls = {
                    colors[id %colors.length],
                    colors[(colors.length - id) %colors.length],
                    colors[(id*5) %colors.length]};
            LinearGradientPaint p =
                    new LinearGradientPaint(start, end, dist, cls);
            g2d.setPaint(p);
        }
    }

    static class RadGrad3OvalRotParticleRenderer extends FlatOvalRotParticleRenderer {


        RadGrad3OvalRotParticleRenderer(int n, float r) {
            super(n, r);
        }

        @Override
        void setPaint(Graphics2D g2d, int id) {
            Point2D start = new Point2D.Double();
            float[] dist = {0.0f, 0.5f, 1.0f};
            Color[] cls = {
                    colors[id %colors.length],
                    colors[(colors.length - id) %colors.length],
                    colors[(id*5) %colors.length]};
            RadialGradientPaint p =
                    new RadialGradientPaint(start, r, dist, cls);
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

    static class ClipFlatBoxParticleRenderer extends FlatParticleRenderer {


        ClipFlatBoxParticleRenderer(int n, float r) {
            super(n, r);
        }
        @Override
        public void render(Graphics2D g2d, int id, float[] x, float[] y, float[] vx, float[] vy) {
            if ((id % 10) == 0) {
                g2d.setColor(colors[id % colors.length]);
                g2d.setClip((int) (x[id] - r), (int) (y[id] - r), (int) (2 * r), (int) (2 * r));
                g2d.fillRect((int) (x[id] - 2 * r), (int) (y[id] - 2 * r), (int) (4 * r), (int) (4 * r));
            }
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

    static class VolImgParticleRenderer extends ImgParticleRenderer {
        VolatileImage volImg;

        VolImgParticleRenderer(int n, float r) {
            super(n, r);
        }

        @Override
        public void render(Graphics2D g2d, int id, float[] x, float[] y, float[] vx, float[] vy) {
            GraphicsConfiguration config = g2d.getDeviceConfiguration();
            if (volImg == null) {
                volImg = config.createCompatibleVolatileImage(dukeImg.getWidth(), dukeImg.getHeight(),
                        Transparency.TRANSLUCENT);
                Graphics2D g = volImg.createGraphics();
                g.setComposite(AlphaComposite.Src);
                g.drawImage(dukeImg, null, null);
                g.dispose();
            } else {
                int status = volImg.validate(config);
                if (status == VolatileImage.IMAGE_INCOMPATIBLE) {
                    volImg = config.createCompatibleVolatileImage(dukeImg.getWidth(), dukeImg.getHeight(),
                            Transparency.TRANSLUCENT);
                }
                if (status != VolatileImage.IMAGE_OK) {
                    Graphics2D g = volImg.createGraphics();
                    g.setComposite(AlphaComposite.Src);
                    g.drawImage(dukeImg, null, null);
                    g.dispose();
                }
            }
            Composite savedComposite = g2d.getComposite();
            g2d.setComposite(AlphaComposite.SrcOver);
            g2d.drawImage(volImg, (int)(x[id] - r), (int)(y[id] - r), (int)(2*r), (int)(2*r), null);
            g2d.setComposite(savedComposite);
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

    static class BlitImageParticleRenderer extends FlatParticleRenderer {
        BufferedImage image;

        BlitImageParticleRenderer(int n, float r, BufferedImage img) {
            super(n, r);
            image = img;
            fill(image);
        }

        @Override
        public void render(Graphics2D g2d, int id, float[] x, float[] y, float[] vx, float[] vy) {
            g2d.drawImage(image, (int)(x[id] - r), (int)(y[id] - r), (int)(2*r), (int)(2*r), null);
        }

        private static void fill(final Image image) {
            final Graphics2D graphics = (Graphics2D) image.getGraphics();
            graphics.setComposite(AlphaComposite.Src);
            for (int i = 0; i < image.getHeight(null); ++i) {
                graphics.setColor(new Color(i, 0, 0));
                graphics.fillRect(0, i, image.getWidth(null), 1);
            }
            graphics.dispose();
        }

    }

    static class SwBlitImageParticleRenderer extends BlitImageParticleRenderer {

        SwBlitImageParticleRenderer(int n, float r, final int type) {
            super(n, r, makeUnmanagedBI(type));
        }

        private static BufferedImage makeUnmanagedBI(final int type) {
            final BufferedImage bi = new BufferedImage(17, 33, type);
            final DataBuffer db = bi.getRaster().getDataBuffer();
            if (db instanceof DataBufferInt) {
                ((DataBufferInt) db).getData();
            } else if (db instanceof DataBufferShort) {
                ((DataBufferShort) db).getData();
            } else if (db instanceof DataBufferByte) {
                ((DataBufferByte) db).getData();
            }
            bi.setAccelerationPriority(0.0f);
            return bi;
        }
    }

    static class SurfaceBlitImageParticleRenderer extends BlitImageParticleRenderer {

        SurfaceBlitImageParticleRenderer(int n, float r, final int type) {
            super(n, r, makeManagedBI(type));
        }

        private static BufferedImage makeManagedBI(final int type) {
            final BufferedImage bi = new BufferedImage(17, 33, type);
            bi.setAccelerationPriority(1.0f);
            return bi;
        }
    }

    static final class PerfMeter {

        private final String name;
        private final PerfMeterExecutor executor;

        PerfMeter(String name) {
            this.name = name;
            executor = getExecutor();
        }

        PerfMeter exec(final Renderable renderable) throws Exception {
            executor.exec(name, renderable);
            return this;
        }

        private void report() {
            System.err.println(name + " : " + executor.getResults());
        }

        private PerfMeterExecutor getExecutor() {
            switch (EXEC_MODE) {
                default:
                case EXEC_MODE_ROBOT:
                    return new PerfMeterRobot();
                case EXEC_MODE_BUFFER:
                    return new PerfMeterImageProvider(ImageProvider.INSTANCE_BUFFERED_IMAGE);
                case EXEC_MODE_VOLATILE:
                    return new PerfMeterImageProvider(ImageProvider.INSTANCE_VOLATILE_IMAGE);
            }
        }
    }
    public final static void paintTest(final Renderable renderable, final Graphics2D g2d, final Color markerColor) {
        // clear background:
        g2d.setColor(Color.BLACK);
        g2d.fillRect(0, 0, IMAGE_W, IMAGE_H);

        // test:
        renderable.setup(g2d);
        renderable.render(g2d);

        // draw marker:
        g2d.setClip(null);
        g2d.setPaintMode();
        g2d.setColor(markerColor);
        g2d.fillRect(0, 0, BW, BH);

        // synchronize toolkit:
        TOOLKIT.sync();
    }

    static abstract class PerfMeterExecutor {
        protected JFrame f = null;

        protected final AtomicInteger markerIdx = new AtomicInteger(0);
        protected final AtomicLong markerPaintTime = new AtomicLong(0);

        protected int skippedFrame = 0;
        protected final ArrayList<Long> execTime = new ArrayList<>(COUNT);

        protected final double[] scores = new double[3];
        protected final double[] results = new double[4];
        private int nData = 0;

        protected void beforeExec() {}
        protected void afterExec() {}

        protected void reset() {
            markerIdx.set(0);
            markerPaintTime.set(0);
        }

        protected final void exec(final String name, final Renderable renderable) throws Exception {
            if (TRACE) System.out.print("\n!");

            final CountDownLatch latchShownFrame = new CountDownLatch(1);
            final CountDownLatch latchClosedFrame = new CountDownLatch(1);

            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    f = new JFrame(name);
                    // call beforeExec() after frame is created:
                    beforeExec();

                    f.addComponentListener(new ComponentAdapter() {
                        @Override
                        public void componentShown(ComponentEvent e) {
                            latchShownFrame.countDown();
                        }
                    });
                    f.addWindowListener(new WindowAdapter() {
                        @Override
                        public void windowClosed(WindowEvent e) {
                            latchClosedFrame.countDown();
                        }
                    });

                    JPanel panel = new JPanel() {
                        @Override
                        protected void paintComponent(Graphics g) {
                            if (TRACE) System.out.print("P");
                            paintPanel(renderable, g);
                            if (TRACE) System.out.print("Q");
                        }
                    };

                    panel.setPreferredSize(new Dimension(IMAGE_W, IMAGE_H));
                    panel.setBackground(Color.BLACK);
                    f.add(panel);
                    f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
                    f.pack();
                    f.setVisible(true);
                    if (TRACE) System.out.print(">>");
                }
            });

            // Wait frame to be shown:
            latchShownFrame.await();

            if (TRACE) System.out.print(":");

            // Reset before warmup:
            reset();

            // Warmup to prepare frame synchronization:
            for (int i = 0; i < WARMUP_COUNT; i++) {
                markerIdx.accumulateAndGet(marker.length, (x, y) -> (x + 1) % y);
                renderable.update();
                repaint();
                sleep(10);
                while (markerPaintTime.get() == 0) {
                    if (TRACE) System.out.print("-");
                    sleep(1);
                }
                markerPaintTime.set(0);
            }
            // Reset before measurements:
            reset();
            if (TRACE) System.out.print(":>>");

            final long startTime = System.currentTimeMillis();
            final long minTime = startTime + MIN_MEASURE_TIME_MS;
            final long endTime = startTime + MAX_MEASURE_TIME_MS;

            // Start 1st measurement:
            repaint();

            int cycle = 0;
            int frame = 0;
            long paintTime = 0;

            for (;;) {
                long t;
                if ((t = markerPaintTime.getAndSet(0)) > 0) {
                    paintTime = t;
                    if (TRACE) System.out.print("|");
                }

                if (paintTime > 0) {
                    if (TRACE) System.out.print(".");

                    final Color c = getMarkerColor();

                    if (isAlmostEqual(c, marker[markerIdx.get()])) {
                        execTime.add(getElapsedTime(paintTime));
                        if (TRACE) System.out.print("R");
                        frame++;
                        paintTime = 0;
                        cycle = 0;
                        markerIdx.accumulateAndGet(marker.length, (x, y) -> (x + 1) % y);
                        renderable.update();
                        repaint();
                    } else if (cycle >= MAX_FRAME_CYCLES) {
                        if (TRACE) System.out.print("M");
                        skippedFrame++;
                        paintTime = 0;
                        cycle = 0;
                        markerIdx.accumulateAndGet(marker.length, (x, y) -> (x + 1) % y);
                        repaint();
                    } else {
                        if (TRACE) System.out.print("-");
                    }
                }
                final long currentTime = System.currentTimeMillis();
                if ((frame >= MIN_COUNT) && (currentTime >= endTime)) {
                    break;
                }
                if ((frame >= COUNT) && (currentTime >= minTime)) {
                    break;
                }
                sleep(CYCLE_DELAY);
                cycle++;
            } // end measurements

            SwingUtilities.invokeAndWait(() -> {
                f.setVisible(false);
                f.dispose();
            });

            latchClosedFrame.await();
            if (!execTime.isEmpty()) {
                processTimes();
            }
            if (TRACE) System.out.print("<<\n");
            afterExec();
        }

        protected abstract void paintPanel(final Renderable renderable, final Graphics g);

        protected abstract long getElapsedTime(long paintTime);

        protected abstract Color getMarkerColor() throws Exception;

        protected void repaint() throws Exception {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    f.repaint();
                }
            });
        }

        protected void sleep(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException ie) {
                ie.printStackTrace(System.err);
            }
        }

        protected boolean isAlmostEqual(Color c1, Color c2) {
            return (Math.abs(c1.getRed() - c2.getRed()) < COLOR_TOLERANCE) &&
                    (Math.abs(c1.getGreen() - c2.getGreen()) < COLOR_TOLERANCE) &&
                    (Math.abs(c1.getBlue() - c2.getBlue()) < COLOR_TOLERANCE);
        }

        protected void processTimes() {
            nData = execTime.size();

            if (!execTime.isEmpty()) {
                // Ignore first 10% (warmup at the beginning):
                final int thIdx = (int)Math.ceil(execTime.size() * 0.10);

                final ArrayList<Long> times = new ArrayList<>(nData - thIdx);
                for (int i = thIdx; i < nData; i++) {
                    times.add(execTime.get(i));
                }

                // Sort values to get percentiles:
                Collections.sort(times);
                final int last = times.size() - 1;

                if (USE_FPS) {
                    scores[0] = fps(times.get(pctIndex(last, 0.5000))); //    50% (median)

                    results[3] = fps(times.get(0)); // 0.0 (min)
                    results[2] = fps(times.get(pctIndex(last, 0.1587))); // 15.87% (-1 stddev)
                    results[1] = fps(times.get(pctIndex(last, 0.8413))); // 84.13% (+1 stddev)
                    results[0] = fps(times.get(pctIndex(last, 1.0000))); // 100% (max)

                    scores[1] = (results[2] - results[1]) / 2.0;
                    scores[2] = millis(times.get(pctIndex(last, 0.5000))); //    50% (median)
                } else {
                    scores[0] = millis(times.get(pctIndex(last, 0.5000))); //    50% (median)

                    results[0] = millis(times.get(0)); // 0.0 (min)
                    results[1] = millis(times.get(pctIndex(last, 0.1587))); // 15.87% (-1 stddev)
                    results[2] = millis(times.get(pctIndex(last, 0.8413))); // 84.13% (+1 stddev)
                    results[3] = millis(times.get(pctIndex(last, 1.0000))); // 100% (max)

                    scores[1] = (results[2] - results[1]) / 2.0;
                    scores[2] = fps(times.get(pctIndex(last, 0.5000))); //    50% (median)
                }
            }
        }

        protected String getResults() {
            if (skippedFrame > 0) {
                System.err.println(skippedFrame + " frame(s) skipped");
            }
            if (VERBOSE) {
                return String.format("%.3f (%.3f) %s [%.3f %s] (p00: %.3f p15: %.3f p50: %.3f p85: %.3f p100: %.3f %s) (%d frames)",
                        scores[0], scores[1], (USE_FPS ? "FPS" : "ms"),
                        scores[2], (USE_FPS ? "ms" : "FPS"),
                        results[0], results[1], scores[0], results[2], results[3],
                        (USE_FPS ? "FPS" : "ms"),
                        nData);
            }
            return String.format("%.3f (%.3f) %s", scores[0], scores[1], (USE_FPS ? "FPS" : "ms"));
        }

        protected double fps(long timeNs) {
            return 1e9 / timeNs;
        }
        protected double millis(long timeNs) {
            return 1e-6 * timeNs;
        }

        protected int pctIndex(final int last, final double pct) {
            return (int) Math.round(last * pct);
        }
    }

    static final class PerfMeterRobot extends PerfMeterExecutor {
        private static boolean CALIBRATE = VERBOSE;

        private final ArrayList<Long> robotTime = (CALIBRATE) ? new ArrayList<>(COUNT) : null;

        private int renderedMarkerIdx = -1;

        private Robot robot = null;

        private int capture = 0;

        protected void beforeExec() {
            try {
                robot = new Robot();
            } catch (AWTException ae) {
                throw new RuntimeException(ae);
            }
        }

        protected void reset() {
            super.reset();
            renderedMarkerIdx = -1;
        }

        protected void paintPanel(final Renderable renderable, final Graphics g) {
            final int idx = markerIdx.get();
            final long start = System.nanoTime();

            final Graphics2D g2d = (Graphics2D) g.create();
            try {
                paintTest(renderable, g2d, marker[idx]);
            } finally {
                g2d.dispose();
            }

            // publish start time:
            if (idx != renderedMarkerIdx) {
                renderedMarkerIdx = idx;
                markerPaintTime.set(start);
            }
        }

        protected long getElapsedTime(long paintTime) {
            return System.nanoTime() - paintTime;
        }

        protected Color getMarkerColor() throws Exception {
            final Point frameOffset = f.getLocationOnScreen();
            final Insets insets = f.getInsets();
            final int px = frameOffset.x + insets.left + BW / 2;
            final int py = frameOffset.y + insets.top  + BH / 2;

            final long beforeRobot = (CALIBRATE) ? System.nanoTime() : 0L;

            final Color c = robot.getPixelColor(px, py);

            if (CALIBRATE) {
                robotTime.add((System.nanoTime() - beforeRobot));
            }
            return c;
        }

        protected String getResults() {
            if (CALIBRATE && !robotTime.isEmpty()) {
                CALIBRATE = false; // only first time

                Collections.sort(robotTime);
                final int last = robotTime.size() - 1;

                final double[] robotStats = new double[5];
                robotStats[0] = millis(robotTime.get(0)); // 0.0 (min)
                robotStats[1] = millis(robotTime.get(pctIndex(last, 0.1587))); // 15.87% (-1 stddev)
                robotStats[2] = millis(robotTime.get(pctIndex(last, 0.5000))); //    50% (median)
                robotStats[3] = millis(robotTime.get(pctIndex(last, 0.8413))); // 84.13% (+1 stddev)
                robotStats[4] = millis(robotTime.get(pctIndex(last, 1.0000))); //   100% (max)

                System.err.println(String.format("Robot: %.3f ms (p00: %.3f p15: %.3f p50: %.3f p85: %.3f p100: %.3f ms) (%d times)",
                        robotStats[2], robotStats[0], robotStats[1], robotStats[2], robotStats[3], robotStats[4], last + 1));
            }
            return super.getResults();
        }
    }

    static final class PerfMeterImageProvider extends PerfMeterExecutor {
        private final ImageProvider imageProvider;

        PerfMeterImageProvider(final ImageProvider imageProvider) {
            this.imageProvider = imageProvider;
        }

        protected void beforeExec() {
            imageProvider.create(f.getGraphicsConfiguration(), IMAGE_W, IMAGE_H);
        }

        protected void afterExec() {
            imageProvider.reset();
        }

        protected void paintPanel(final Renderable renderable, final Graphics g) {
            // TODO: check image provider is ready ?
            final int idx = markerIdx.get();
            long start = System.nanoTime();

            // Get Graphics from image provider:
            final Graphics2D g2d = imageProvider.createGraphics();
            try {
                paintTest(renderable, g2d, marker[idx]);
            } finally {
                g2d.dispose();
            }

            // publish elapsed time:
            markerPaintTime.set(System.nanoTime() - start);

            // Draw image on screen:
            g.drawImage(imageProvider.getImage(), 0, 0, null);
        }

        protected long getElapsedTime(long paintTime) {
            return paintTime;
        }

        protected Color getMarkerColor() throws Exception {
            final int px = BW / 2;
            final int py = BH / 2;

            return new Color(imageProvider.getSnapshot().getRGB(px, py));
        }
    }

    private final static class ImageProvider {
        private final static int TRANSPARENCY = Transparency.TRANSLUCENT;

        final static ImageProvider INSTANCE_BUFFERED_IMAGE =  new ImageProvider(false);
        final static ImageProvider INSTANCE_VOLATILE_IMAGE =  new ImageProvider(true);

        private final boolean useVolatile;
        private Image image = null;

        private ImageProvider(boolean useVolatile) {
            this.useVolatile = useVolatile;
        }

        void create(GraphicsConfiguration gc, int width, int height) {
            this.image = (useVolatile) ? gc.createCompatibleVolatileImage(width, height, TRANSPARENCY)
                    : gc.createCompatibleImage(width, height, TRANSPARENCY);
        }

        public void reset() {
            image = null;
        }

        public Image getImage() {
            return image;
        }

        public Graphics2D createGraphics() {
            return (useVolatile) ? ((VolatileImage) image).createGraphics()
                    : ((BufferedImage) image).createGraphics();
        }

        public BufferedImage getSnapshot() {
            return (useVolatile) ? ((VolatileImage)image).getSnapshot()
                    : (BufferedImage)image;
        }
    }


    // Tests:
    private final Particles balls = new Particles(N, R, BW, BH, WIDTH, HEIGHT);

    private final ParticleRenderer calibRenderer = new CalibrationParticleRenderer();
    private final ParticleRenderer flatRenderer = new FlatParticleRenderer(N, R);
    private final ParticleRenderer clipFlatRenderer = new ClipFlatParticleRenderer(N, R);
    private final ParticleRenderer flatOvalRotRenderer = new FlatOvalRotParticleRenderer(N, R);
    private final ParticleRenderer flatBoxRenderer = new FlatBoxParticleRenderer(N, R);
    private final ParticleRenderer clipFlatBoxParticleRenderer = new ClipFlatBoxParticleRenderer(N, R);
    private final ParticleRenderer flatBoxRotRenderer = new FlatBoxRotParticleRenderer(N, R);
    private final ParticleRenderer linGradOvalRotRenderer = new LinGradOvalRotParticleRenderer(N, R);
    private final ParticleRenderer linGrad3OvalRotRenderer = new LinGrad3OvalRotParticleRenderer(N, R);
    private final ParticleRenderer radGrad3OvalRotRenderer = new RadGrad3OvalRotParticleRenderer(N, R);
    private final ParticleRenderer wiredRenderer = new WiredParticleRenderer(N, R);
    private final ParticleRenderer wiredBoxRenderer = new WiredBoxParticleRenderer(N, R);
    private final ParticleRenderer segRenderer = new SegParticleRenderer(N, R);
    private final ParticleRenderer flatQuadRenderer = new FlatQuadParticleRenderer(N, R);
    private final ParticleRenderer wiredQuadRenderer = new WiredQuadParticleRenderer(N, R);
    private final ParticleRenderer imgRenderer = new ImgParticleRenderer(N, R);
    private final ParticleRenderer volImgRenderer = new VolImgParticleRenderer(N, R);
    private final ParticleRenderer textRenderer = new TextParticleRenderer(N, R);
    private final ParticleRenderer largeTextRenderer = new LargeTextParticleRenderer(N, R);
    private final ParticleRenderer whiteTextRenderer = new WhiteTextParticleRenderer(R);
    private final ParticleRenderer argbSwBlitImageRenderer = new SwBlitImageParticleRenderer(N, R, BufferedImage.TYPE_INT_ARGB);
    private final ParticleRenderer bgrSwBlitImageRenderer = new SwBlitImageParticleRenderer(N, R, BufferedImage.TYPE_INT_BGR);
    private final ParticleRenderer argbSurfaceBlitImageRenderer = new SurfaceBlitImageParticleRenderer(N, R, BufferedImage.TYPE_INT_ARGB);
    private final ParticleRenderer bgrSurfaceBlitImageRenderer = new SurfaceBlitImageParticleRenderer(N, R, BufferedImage.TYPE_INT_BGR);

    private static final Configurable AA = (Graphics2D g2d) ->
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

    private static final Configurable TextLCD = (Graphics2D g2d) ->
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);

    private static final Configurable TextAA = (Graphics2D g2d) ->
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

    private static final Configurable XORMode = (Graphics2D g2d) ->
    {g2d.setXORMode(Color.WHITE);};

    private static final Configurable XORModeLCDText = (Graphics2D g2d) ->
    {g2d.setXORMode(Color.WHITE);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);};

    public void testCalibration() throws Exception {
        (new PerfMeter("Calibration")).exec(createPR(calibRenderer)).report();
    }

    public void testFlatOval() throws Exception {
        (new PerfMeter("FlatOval")).exec(createPR(flatRenderer)).report();
    }

    public void testFlatOvalAA() throws Exception {
        (new PerfMeter("FlatOvalAA")).exec(createPR(flatRenderer).configure(AA)).report();
    }

    public void testClipFlatOval() throws Exception {
        (new PerfMeter("ClipFlatOval")).exec(createPR(clipFlatRenderer)).report();
    }

    public void testClipFlatOvalAA() throws Exception {
        (new PerfMeter("ClipFlatOvalAA")).exec(createPR(clipFlatRenderer).configure(AA)).report();
    }

    public void testFlatBox() throws Exception {
        (new PerfMeter("FlatBox")).exec(createPR(flatBoxRenderer)).report();
    }

    public void testFlatBoxAA() throws Exception {
        (new PerfMeter("FlatBoxAA")).exec(createPR(flatBoxRenderer).configure(AA)).report();
    }

    public void testClipFlatBox() throws Exception {
        (new PerfMeter("ClipFlatBox")).exec(createPR(clipFlatBoxParticleRenderer)).report();
    }

    public void testClipFlatBoxAA() throws Exception {
        (new PerfMeter("ClipFlatBoxAA")).exec(createPR(clipFlatBoxParticleRenderer).configure(AA)).report();
    }

    public void testImage() throws Exception {
        (new PerfMeter("Image")).exec(createPR(imgRenderer)).report();
    }

    public void testImageAA() throws Exception {
        (new PerfMeter("ImageAA")).exec(createPR(imgRenderer).configure(AA)).report();
    }
    public void testVolImage() throws Exception {
        (new PerfMeter("VolImage")).exec(createPR(volImgRenderer)).report();
    }

    public void testVolImageAA() throws Exception {
        (new PerfMeter("VolImageAA")).exec(createPR(volImgRenderer).configure(AA)).report();
    }

    public void testRotatedBox() throws Exception {
        (new PerfMeter("RotatedBox")).exec(createPR(flatBoxRotRenderer)).report();
    }

    public void testRotatedBoxAA() throws Exception {
        (new PerfMeter("RotatedBoxAA")).exec(createPR(flatBoxRotRenderer).configure(AA)).report();
    }

    public void testRotatedOval() throws Exception {
        (new PerfMeter("RotatedOval")).exec(createPR(flatOvalRotRenderer)).report();
    }

    public void testRotatedOvalAA() throws Exception {
        (new PerfMeter("RotatedOvalAA")).exec(createPR(flatOvalRotRenderer).configure(AA)).report();
    }

    public void testLinGrad3RotatedOval() throws Exception {
        (new PerfMeter("LinGrad3RotatedOval")).exec(createPR(linGrad3OvalRotRenderer)).report();
    }

    public void testLinGrad3RotatedOvalAA() throws Exception {
        (new PerfMeter("LinGrad3RotatedOvalAA")).exec(createPR(linGrad3OvalRotRenderer).configure(AA)).report();
    }

    public void testRadGrad3RotatedOval() throws Exception {
        (new PerfMeter("RadGrad3RotatedOval")).exec(createPR(radGrad3OvalRotRenderer)).report();
    }

    public void testRadGrad3RotatedOvalAA() throws Exception {
        (new PerfMeter("RadGrad3RotatedOvalAA")).exec(createPR(radGrad3OvalRotRenderer).configure(AA)).report();
    }

    public void testLinGradRotatedOval() throws Exception {
        (new PerfMeter("LinGradRotatedOval")).exec(createPR(linGradOvalRotRenderer)).report();
    }

    public void testLinGradRotatedOvalAA() throws Exception {
        (new PerfMeter("LinGradRotatedOvalAA")).exec(createPR(linGradOvalRotRenderer).configure(AA)).report();
    }

    public void testWiredBubbles() throws Exception {
        (new PerfMeter("WiredBubbles")).exec(createPR(wiredRenderer)).report();
    }

    public void testWiredBubblesAA() throws Exception {
        (new PerfMeter("WiredBubblesAA")).exec(createPR(wiredRenderer).configure(AA)).report();
    }

    public void testWiredBox() throws Exception {
        (new PerfMeter("WiredBox")).exec(createPR(wiredBoxRenderer)).report();
    }

    public void testWiredBoxAA() throws Exception {
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

    public void testTextNoAA() throws Exception {
        (new PerfMeter("TextNoAA")).exec(createPR(textRenderer)).report();
    }

    public void testTextLCD() throws Exception {
        (new PerfMeter("TextLCD")).exec(createPR(textRenderer).configure(TextLCD)).report();
    }

    public void testTextGray() throws Exception {
        (new PerfMeter("TextGray")).exec(createPR(textRenderer).configure(TextAA)).report();
    }

    public void testLargeTextNoAA() throws Exception {
        (new PerfMeter("LargeTextNoAA")).exec(createPR(largeTextRenderer)).report();
    }

    public void testLargeTextLCD() throws Exception {
        (new PerfMeter("LargeTextLCD")).exec(createPR(largeTextRenderer).configure(TextLCD)).report();
    }

    public void testLargeTextGray() throws Exception {
        (new PerfMeter("LargeTextGray")).exec(createPR(largeTextRenderer).configure(TextAA)).report();
    }
    public void testWhiteTextNoAA() throws Exception {
        (new PerfMeter("WhiteTextNoAA")).exec(createPR(whiteTextRenderer)).report();
    }

    public void testWhiteTextLCD() throws Exception {
        (new PerfMeter("WhiteTextLCD")).exec(createPR(whiteTextRenderer).configure(TextLCD)).report();
    }

    public void testWhiteTextGray() throws Exception {
        (new PerfMeter("WhiteTextGray")).exec(createPR(whiteTextRenderer).configure(TextAA)).report();
    }

    public void testArgbSwBlitImage() throws Exception {
        (new PerfMeter("ArgbSwBlitImage")).exec(createPR(argbSwBlitImageRenderer)).report();
    }

    public void testBgrSwBlitImage() throws Exception {
        (new PerfMeter("BgrSwBlitImage")).exec(createPR(bgrSwBlitImageRenderer)).report();
    }

    public void testArgbSurfaceBlitImage() throws Exception {
        (new PerfMeter("ArgbSurfaceBlitImage")).exec(createPR(argbSurfaceBlitImageRenderer)).report();
    }

    public void testBgrSurfaceBlitImage() throws Exception {
        (new PerfMeter("BgrSurfaceBlitImage")).exec(createPR(bgrSurfaceBlitImageRenderer)).report();
    }

    public void testFlatOval_XOR() throws Exception {
        (new PerfMeter("FlatOval_XOR")).exec(createPR(flatRenderer).configure(XORMode)).report();
    }

    public void testRotatedBox_XOR() throws Exception {
        (new PerfMeter("RotatedBox_XOR")).exec(createPR(flatBoxRotRenderer).configure(XORMode)).report();
    }

    public void testLines_XOR() throws Exception {
        (new PerfMeter("Lines_XOR")).exec(createPR(segRenderer).configure(XORMode)).report();
    }

    public void testImage_XOR() throws Exception {
        (new PerfMeter("Image_XOR")).exec(createPR(imgRenderer).configure(XORMode)).report();
    }

    public void testTextNoAA_XOR() throws Exception {
        (new PerfMeter("TextNoAA_XOR")).exec(createPR(textRenderer).configure(XORMode)).report();
    }

    public void testTextLCD_XOR() throws Exception {
        (new PerfMeter("TextLCD_XOR")).exec(createPR(textRenderer).configure(XORModeLCDText)).report();
    }

    private static void help() {
        if (!VERBOSE) {
            System.out.printf("##############################################################\n");
            System.out.printf("# %s\n", VERSION);
            System.out.printf("##############################################################\n");
            System.out.println("# java ... RenderPerfTest <args>");
            System.out.println("#");
            System.out.println("# Supported Arguments <args>:");
            System.out.println("#");
            System.out.println("# -h         : display this help");
            System.out.println("# -v         : set verbose outputs");
            System.out.println("# -e<mode>   : set execution mode (default: " + EXEC_MODE_DEFAULT + ") among " + EXEC_MODES);
            System.out.println("#");
            System.out.println("# -f         : use FPS unit (default)");
            System.out.println("# -t         : use TIME(ms) unit");
            System.out.println("#");
            System.out.println("# -n<number> : set number of primitives (default: " + N_DEFAULT + ")");
            System.out.println("# -r<number> : set number of test repeats (default: 1)");
            System.out.println("#");
            System.out.print("# Test arguments: ");

            final ArrayList<Method> testCases = new ArrayList<>();
            for (Method m : RenderPerfTest.class.getDeclaredMethods()) {
                if (m.getName().startsWith("test") && !ignoredTests.contains(m.getName())) {
                    testCases.add(m);
                }
            }
            testCases.sort(Comparator.comparing(Method::getName));
            for (Method m : testCases) {
                System.out.print(m.getName().substring(4));
                System.out.print(" ");
            }
            System.out.println();
        }
    }

    public static void main(String[] args)
            throws InvocationTargetException, IllegalAccessException, NoSuchMethodException, NumberFormatException
    {
        // Set the default locale to en-US locale (for Numerical Fields "." ",")
        Locale.setDefault(Locale.US);

        boolean help = false;
        final ArrayList<Method> testCases = new ArrayList<>();

        if (args.length > 0) {
            for (String arg : args) {
                if (arg.length() >= 2) {
                    if (arg.startsWith("-")) {
                        switch (arg.substring(1, 2)) {
                            case "e":
                                EXEC_MODE = arg.substring(2).toLowerCase();
                                break;
                            case "f":
                                USE_FPS = true;
                                break;
                            case "h":
                                help = true;
                                break;
                            case "t":
                                USE_FPS = false;
                                break;
                            case "n":
                                N = Integer.parseInt(arg.substring(2));
                                break;
                            case "r":
                                REPEATS = Integer.parseInt(arg.substring(2));
                                break;
                            case "v":
                                VERBOSE = true;
                                break;
                            default:
                                System.err.println("Unsupported argument '" + arg + "' !");
                                help = true;
                        }
                    } else {
                        Method m = RenderPerfTest.class.getDeclaredMethod("test" + arg);
                        testCases.add(m);
                    }
                }
            }
        }
        if (testCases.isEmpty()) {
            for (Method m : RenderPerfTest.class.getDeclaredMethods()) {
                if (m.getName().startsWith("test") && !ignoredTests.contains(m.getName())) {
                    testCases.add(m);
                }
            }
            testCases.sort(Comparator.comparing(Method::getName));
        }

        if (CALIBRATION) {
            Method m = RenderPerfTest.class.getDeclaredMethod("testCalibration");
            testCases.add(0, m); // first
        }

        if (VERBOSE) {
            System.out.printf("##############################################################\n");
            System.out.printf("# %s\n", VERSION);
            System.out.printf("##############################################################\n");
            System.out.printf("# Java: %s\n", System.getProperty("java.runtime.version"));
            System.out.printf("#   VM: %s %s (%s)\n", System.getProperty("java.vm.name"), System.getProperty("java.vm.version"), System.getProperty("java.vm.info"));
            System.out.printf("#   OS: %s %s (%s)\n", System.getProperty("os.name"), System.getProperty("os.version"), System.getProperty("os.arch"));
            System.out.printf("# CPUs: %d (virtual)\n", Runtime.getRuntime().availableProcessors());
            System.out.printf("##############################################################\n");
            System.out.printf("# AWT Toolkit   : %s \n", TOOLKIT.getClass().getSimpleName());
            System.out.printf("# Execution mode: %s\n", EXEC_MODE);
            System.out.printf("# Repeats: %d\n", REPEATS);
            System.out.printf("# N:       %d\n", N);
            System.out.printf("# Unit:    %s\n", USE_FPS ? "FPS" : "TIME(ms)");
            System.out.printf("##############################################################\n");
        }

        int retCode = 0;
        try {
            if (help) {
                help();
            } else {
                final RenderPerfTest test = new RenderPerfTest();
                for (Method m : testCases) {
                    for (int i = 0; i < REPEATS; i++) {
                        m.invoke(test);
                    }
                }
            }
        } catch (Throwable th) {
            System.err.println("Exception occured:");
            th.printStackTrace(System.err);
            retCode = 1;
        } finally {
            // ensure jvm shutdown now (wayland)
            System.exit(retCode);
        }
    }
}
