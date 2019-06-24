/*
 * Copyright 2019 JetBrains s.r.o.
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

package performance.rendering;

import org.junit.Test;
import util.Renderer;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.QuadCurve2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class RenderPerfTest {
    private final static int N = 1000;
    private final static float R = 25;
    private final static int COUNT = 300;


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

    interface ParticleRenderer {
        void render(Graphics2D g2d, int id, float[] x, float[] y, float[] vx, float[] vy);

    }

    private static void report(String name, double value) {
        System.err.println("##teamcity[buildStatisticValue key='" + name + "' value='" + value +"']");
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
        Object hint;

        WhiteTextParticleRenderer(float r, Object hint) {
            this.r = r;
            this.hint = hint;
        }

        void setPaint(Graphics2D g2d, int id) {
            g2d.setColor(Color.WHITE);
        }

        @Override
        public void render(Graphics2D g2d, int id, float[] x, float[] y, float[] vx, float[] vy) {
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, hint);

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

        TextParticleRenderer(int n, float r, Object hint) {
            super(r, hint);
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
            String testDataStr = System.getProperty("testdata");
            assertNotNull("testdata property is not set", testDataStr);

            File testData = new File(testDataStr, "performance" + File.separator + "rendering");
            assertTrue("Test data dir does not exist", testData.exists());
            File dukeFile = new File(testData, "duke.png");

            if (!dukeFile.exists()) throw new RuntimeException(dukeFile.toString() + " not found");

            try {
                dukeImg = ImageIO.read(dukeFile);
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

    private static final Particles balls = new Particles(N, R, 0, 0, Renderer.WIDTH, Renderer.HEIGHT);
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
    private static final ParticleRenderer textRendererNoAA =
            new TextParticleRenderer(N, R, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
    private static final ParticleRenderer textRendererLCD =
            new TextParticleRenderer(N, R, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
    private static final ParticleRenderer textRendererGray =
            new TextParticleRenderer(N, R, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

    private static final ParticleRenderer whiteTextRendererNoAA =
            new WhiteTextParticleRenderer(R, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
    private static final ParticleRenderer whiteTextRendererLCD =
            new WhiteTextParticleRenderer(R, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
    private static final ParticleRenderer whiteTextRendererGray =
            new WhiteTextParticleRenderer(R, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);


    @Test
    public void testFlatBubbles() throws Exception {

        double fps = (new util.Renderer(COUNT)).exec(new Renderer.Renderable() {
            @Override
            public void render(Graphics2D g2d) {
                balls.render(g2d, flatRenderer);
            }

            @Override
            public void update() {
                balls.update();
            }

            @Override
            public void screenShot(BufferedImage result) {
                System.err.println();
            }
        });

        report("FlatOval", fps);
    }

    @Test
    public void testFlatBoxBubbles() throws Exception {

        double fps = (new util.Renderer(COUNT)).exec(new Renderer.DefaultRenderable() {
            @Override
            public void render(Graphics2D g2d) {
                balls.render(g2d, flatBoxRenderer);
            }

            @Override
            public void update() {
                balls.update();
            }
        });

        report("FlatBox", fps);
    }

    @Test
    public void testImgBubbles() throws Exception {

        double fps = (new util.Renderer(COUNT)).exec(new Renderer.DefaultRenderable() {
            @Override
            public void render(Graphics2D g2d) {
                balls.render(g2d, imgRenderer);
            }

            @Override
            public void update() {
                balls.update();
            }
        });

        report("Image", fps);
    }

    @Test
    public void testFlatBoxRotBubbles() throws Exception {

        double fps = (new util.Renderer(COUNT)).exec(new Renderer.DefaultRenderable() {
            @Override
            public void render(Graphics2D g2d) {
                balls.render(g2d, flatBoxRotRenderer);
            }

            @Override
            public void update() {
                balls.update();
            }
        });

        report("RotatedBox", fps);
    }

    @Test
    public void testFlatOvalRotBubbles() throws Exception {

        double fps = (new util.Renderer(COUNT)).exec(new Renderer.DefaultRenderable() {
            @Override
            public void render(Graphics2D g2d) {
                balls.render(g2d, flatOvalRotRenderer);
            }

            @Override
            public void update() {
                balls.update();
            }
        });

        report("RotatedOval", fps);
    }

    @Test
    public void testLinGradOvalRotBubbles() throws Exception {

        double fps = (new util.Renderer(COUNT)).exec(new Renderer.DefaultRenderable() {
            @Override
            public void render(Graphics2D g2d) {
                balls.render(g2d, linGradOvalRotRenderer);
            }

            @Override
            public void update() {
                balls.update();
            }
        });

        report("LinGradRotatedOval", fps);
    }


    @Test
    public void testWiredBubbles() throws Exception {

        double fps = (new util.Renderer(COUNT)).exec(new Renderer.DefaultRenderable() {
            @Override
            public void render(Graphics2D g2d) {
                balls.render(g2d, wiredRenderer);
            }

            @Override
            public void update() {
                balls.update();
            }
        });

        report("WiredOval", fps);
    }

    @Test
    public void testWiredBoxBubbles() throws Exception {

        double fps = (new util.Renderer(COUNT)).exec(new Renderer.DefaultRenderable() {
            @Override
            public void render(Graphics2D g2d) {
                balls.render(g2d, wiredBoxRenderer);
            }

            @Override
            public void update() {
                balls.update();
            }
        });

        report("WiredBox", fps);
    }

    @Test
    public void testLines() throws Exception {

        double fps = (new util.Renderer(COUNT)).exec(new Renderer.DefaultRenderable() {
            @Override
            public void render(Graphics2D g2d) {
                balls.render(g2d, segRenderer);
            }

            @Override
            public void update() {
                balls.update();
            }
        });

        report("Lines", fps);
    }

    @Test
    public void testFlatQuad() throws Exception {

        double fps = (new util.Renderer(COUNT)).exec(new Renderer.DefaultRenderable() {
            @Override
            public void render(Graphics2D g2d) {
                balls.render(g2d, flatQuadRenderer);
            }

            @Override
            public void update() {
                balls.update();
            }
        });

        report("FlatQuad", fps);
    }

    @Test
    public void testWiredQuad() throws Exception {

        double fps = (new util.Renderer(COUNT)).exec(new Renderer.DefaultRenderable() {
            @Override
            public void render(Graphics2D g2d) {
                balls.render(g2d, wiredQuadRenderer);
            }

            @Override
            public void update() {
                balls.update();
            }
        });

        report("WiredQuad", fps);
    }

    @Test
    public void testTextBubblesNoAA() throws Exception {

        double fps = (new util.Renderer(COUNT)).exec(new Renderer.DefaultRenderable() {
            @Override
            public void render(Graphics2D g2d) {
                balls.render(g2d, textRendererNoAA);
            }

            @Override
            public void update() {
                balls.update();
            }
        });

        report("TextNoAA", fps);
    }

    @Test
    public void testTextBubblesLCD() throws Exception {

        double fps = (new util.Renderer(COUNT)).exec(new Renderer.DefaultRenderable() {
            @Override
            public void render(Graphics2D g2d) {
                balls.render(g2d, textRendererLCD);
            }

            @Override
            public void update() {
                balls.update();
            }
        });

        report("TextLCD", fps);
    }

    @Test
    public void testTextBubblesGray() throws Exception {

        double fps = (new util.Renderer(COUNT)).exec(new Renderer.DefaultRenderable() {
            @Override
            public void render(Graphics2D g2d) {
                balls.render(g2d, textRendererGray);
            }

            @Override
            public void update() {
                balls.update();
            }
        });

        report("TextGray", fps);
    }

    @Test
    public void testWhiteTextBubblesNoAA() throws Exception {

        double fps = (new util.Renderer(COUNT)).exec(new Renderer.DefaultRenderable() {
            @Override
            public void render(Graphics2D g2d) {
                balls.render(g2d, whiteTextRendererNoAA);
            }

            @Override
            public void update() {
                balls.update();
            }
        });

        report("WhiteTextNoAA", fps);
    }

    @Test
    public void testWhiteTextBubblesLCD() throws Exception {

        double fps = (new util.Renderer(COUNT)).exec(new Renderer.DefaultRenderable() {
            @Override
            public void render(Graphics2D g2d) {
                balls.render(g2d, whiteTextRendererLCD);
            }

            @Override
            public void update() {
                balls.update();
            }
        });

        report("WhiteTextLCD", fps);
    }

    @Test
    public void testWhiteTextBubblesGray() throws Exception {

        double fps = (new Renderer(COUNT)).exec(new Renderer.DefaultRenderable() {
            @Override
            public void render(Graphics2D g2d) {
                balls.render(g2d, whiteTextRendererGray);
            }

            @Override
            public void update() {
                balls.update();
            }
        });

        report("WhiteTextGray", fps);
    }

}
