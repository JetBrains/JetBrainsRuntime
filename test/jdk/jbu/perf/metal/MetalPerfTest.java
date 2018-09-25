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

package perf.metal;

import org.junit.Test;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

public class MetalPerfTest {
    private final static int N = 50;
    private final static float WIDTH = 800;
    private final static float HEIGHT = 800;
    private final static float R = 25;
    private final static int BW = 50;
    private final static int BH = 50;
    private final static int COUNT = 100;
    private final static int DELAY = 10;
    private final static int RESOLUTION = 5;
    private final static int COLOR_TOLERANCE = 10;



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
                renderer.render(g2d, i, bx[i], by[i]);
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
        void render(Graphics2D g2d, int id, float x, float y);
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
        public void render(Graphics2D g2d, int id, float x, float y) {
            g2d.setColor(colors[id % colors.length]);
            g2d.fillOval((int)(x - r), (int)(y - r), (int)(2*r), (int)(2*r));
        }
    }

    static class FlatBoxParticleRenderer extends FlatParticleRenderer {

        FlatBoxParticleRenderer(int n, float r) {
            super(n, r);
        }

        @Override
        public void render(Graphics2D g2d, int id, float x, float y) {
            g2d.setColor(colors[id % colors.length]);
            g2d.fillRect((int)(x - r), (int)(y - r), (int)(2*r), (int)(2*r));

        }
    }

    interface Renderable {
        void render(Graphics2D g2d);
        void update();
    }

    class PerfMeter {

        private int frame = 0;

        private JPanel panel;

        private long time;
        private double execTime = 0;
        private Color expColor = Color.RED;
        AtomicBoolean waiting = new AtomicBoolean(false);

        double exec(final Renderable renderable) throws Exception{
            final CountDownLatch latch = new CountDownLatch(COUNT);
            final CountDownLatch latchFrame = new CountDownLatch(1);

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
                            Graphics2D g2d = (Graphics2D) g;
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
                    Color c = robot.getPixelColor(panel.getTopLevelAncestor().getX() + 25,
                            panel.getTopLevelAncestor().getY() + 25);
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
                                    panel.getTopLevelAncestor().getX() + BW / 2,
                                    panel.getTopLevelAncestor().getY() + BH / 2),
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

                latch.countDown();
            });
            timer.start();
            latch.await();
            SwingUtilities.invokeAndWait(() -> {
                timer.stop();
                f.setVisible(false);
                f.dispose();
            });

            latchFrame.await();
            return 1e9/(execTime / frame);
        }

        private boolean isAlmostEqual(Color c1, Color c2) {
            return Math.abs(c1.getRed() - c2.getRed()) < COLOR_TOLERANCE ||
                    Math.abs(c1.getGreen() - c2.getGreen()) < COLOR_TOLERANCE ||
                    Math.abs(c1.getBlue() - c2.getBlue()) < COLOR_TOLERANCE;

        }
    }

    private static final Particles balls = new Particles(N, R, BW, BH, WIDTH, HEIGHT);
    private static final FlatParticleRenderer flatRenderer = new FlatParticleRenderer(N, R);
    private static final FlatBoxParticleRenderer flatBoxRenderer = new FlatBoxParticleRenderer(N, R);

    @Test
    public void testFlatBubbles() throws Exception {

        double fps = (new PerfMeter()).exec(new Renderable() {
            @Override
            public void render(Graphics2D g2d) {
                balls.render(g2d, flatRenderer);
            }

            @Override
            public void update() {
                balls.update();
            }
        });

        System.out.println(fps);
    }

    @Test
    public void testFlatBoxBubbles() throws Exception {

        double fps = (new PerfMeter()).exec(new Renderable() {
            @Override
            public void render(Graphics2D g2d) {
                balls.render(g2d, flatBoxRenderer);
            }

            @Override
            public void update() {
                balls.update();
            }
        });

        System.out.println(fps);
    }
}
