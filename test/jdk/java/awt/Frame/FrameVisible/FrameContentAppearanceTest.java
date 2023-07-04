/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
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

/*
  @test
 * @key headful
 * @summary check that Frame content does appear when the frame becomes visible
 * @requires (os.family == "mac")
 * @run main/othervm -Dsun.java2d.opengl=true FrameContentAppearanceTest
 * @run main/othervm -Dsun.java2d.metal=true -Dsun.java2d.metal.displaySync=true FrameContentAppearanceTest
 * @run main/othervm -Dsun.java2d.metal=true -Dsun.java2d.metal.displaySync=false FrameContentAppearanceTest
 */

import java.awt.Color;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.util.concurrent.CountDownLatch;

public class FrameContentAppearanceTest extends Frame {
    interface Painter {
        void paint(Graphics g);
    }
    enum ColorType {PRIM, BG, OTHER}
    static BufferedImage img;
    private static final int SIZE = 200;
    private static final Color PRIM_COL = Color.BLUE;
    private static final Color BG_COL = Color.RED;
    private static final int COL_TOLERANCE = 15;
    private static final int FAIL_TOLERANCE = 10;
    private static final int OGL_FAIL_TOLERANCE = 25;
    private static final int COUNT = 100;

    private static Robot r;
    protected CountDownLatch latch = new CountDownLatch(1);
    private final Painter painter;
    public FrameContentAppearanceTest(Painter painter) {
        this.painter = painter;
    }

    @Override
    public void paint(Graphics g) {
        super.paint(g);
        painter.paint(g);
        latch.countDown();
    }

    private void UI() {
        setBackground(BG_COL);
        setSize(SIZE, SIZE);
        setResizable(false);
        setLocation(50, 50);
        setVisible(true);
    }

    private static ColorType checkColor(Color c1) {
        if (Math.abs(PRIM_COL.getRed() - c1.getRed()) +
            Math.abs(PRIM_COL.getGreen() - c1.getGreen()) +
            Math.abs(PRIM_COL.getBlue() - c1.getBlue()) < COL_TOLERANCE)
        {
            return ColorType.PRIM;
        }
        if (Math.abs(BG_COL.getRed() - c1.getRed()) +
            Math.abs(BG_COL.getGreen() - c1.getGreen()) +
            Math.abs(BG_COL.getBlue() - c1.getBlue()) < COL_TOLERANCE)
        {
            return ColorType.BG;
        }
        return ColorType.OTHER;
    }

    protected ColorType doTest() throws Exception {
        r.waitForIdle();
        EventQueue.invokeAndWait(this::UI);
        r.waitForIdle();
        Toolkit.getDefaultToolkit().sync();
        latch.await();
        r.waitForIdle();
        Point loc = getLocationOnScreen();
        Color c = r.getPixelColor(loc.x + SIZE / 2, loc.y + SIZE / 2);

        dispose();
        return checkColor(c);
    }

    public static void main(String[] args) throws Exception {
        String rendering = "Unknown graphics pipeline";
        boolean isOpenGL = false;
        if ("true".equalsIgnoreCase(System.getProperty("sun.java2d.opengl"))) {
            rendering = "OpenGL";
            isOpenGL = true;
        } else if ("true".equalsIgnoreCase(System.getProperty("sun.java2d.metal"))) {
            rendering = "Metal";
            if ("false".equalsIgnoreCase(System.getProperty("sun.java2d.metal.displaySync"))) {
                rendering += " displaySync=false";
            } else {
                rendering += " displaySync=true";
            }
        }
        r = new Robot();
        img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = img.createGraphics();
        g2.setColor(PRIM_COL);
        g2.fillRect(0, 0, SIZE, SIZE);
        g2.dispose();

        int imgFailCount = 0;
        int rectFailCount = 0;
        int ovalFailCount = 0;
        int aaOvalFailCount = 0;
        int noFrameCount = 0;

        for (int i = 0; i < COUNT; i++) {
            ColorType imgTestRes = (new FrameContentAppearanceTest((Graphics g) ->
                    g.drawImage(img, 0, 0, null))).doTest();
            imgFailCount += imgTestRes == ColorType.BG ? 1 : 0;
            noFrameCount += imgTestRes == ColorType.OTHER ? 1 : 0;

            ColorType rectTestRes = (new FrameContentAppearanceTest((Graphics g) -> {
                g.setColor(Color.BLUE);
                g.fillRect(0, 0, SIZE, SIZE);
            })).doTest();
            rectFailCount += rectTestRes == ColorType.BG ? 1 : 0;
            noFrameCount += rectTestRes == ColorType.OTHER ? 1 : 0;

            ColorType ovalTestRes = (new FrameContentAppearanceTest((Graphics g) -> {
                g.setColor(Color.BLUE);
                g.fillOval(0, 0, SIZE, SIZE);
            })).doTest();
            ovalFailCount += ovalTestRes == ColorType.BG ? 1 : 0;
            noFrameCount += ovalTestRes == ColorType.OTHER ? 1 : 0;

            ColorType aaOvalTestRes = (new FrameContentAppearanceTest((Graphics g) -> {
                Graphics2D g2d = (Graphics2D) g;
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2d.setColor(Color.BLUE);
                g.fillOval(0, 0, SIZE, SIZE);
            })).doTest();
            aaOvalFailCount += aaOvalTestRes == ColorType.BG ? 1 : 0;
            noFrameCount += aaOvalTestRes == ColorType.OTHER ? 1 : 0;
        }

        if (imgFailCount > 0 || rectFailCount > 0 || ovalFailCount > 0 || aaOvalFailCount > 0 || noFrameCount > 0) {
            String report = rendering + "\n" +
                    ((imgFailCount > 0) ? imgFailCount + " image rendering failure(s)\n" : "") +
                    ((rectFailCount > 0) ? rectFailCount + " rect rendering failure(s)\n" : "") +
                    ((ovalFailCount > 0) ? ovalFailCount + " oval rendering failure(s)\n" : "") +
                    ((aaOvalFailCount > 0) ? aaOvalFailCount + " aa oval rendering failure(s)\n" : "") +
                    ((noFrameCount > 0) ? noFrameCount + " no frame failure(s)\n" : "");

            int failTolerance = isOpenGL ? OGL_FAIL_TOLERANCE : FAIL_TOLERANCE;
            if (imgFailCount > failTolerance || rectFailCount > failTolerance ||
                ovalFailCount > failTolerance || aaOvalFailCount > failTolerance ||
                noFrameCount > failTolerance)
            {
                throw new RuntimeException("\nFailed:\n" + report);
            } else {
                System.out.println("Passed. Failures below the tolerance(" + failTolerance + "):\n" + report);
            }
        } else {
            System.out.println(rendering + "\nPassed");
        }
    }
}
