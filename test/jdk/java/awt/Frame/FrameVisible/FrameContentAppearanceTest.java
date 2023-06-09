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

/**
 * @test
 * @key headful
 * @summary check that Frame content does appear when the frame becomes visible
 * @requires (os.family == "mac")
 * @run main/othervm -Dsun.java2d.opengl=true FrameContentAppearanceTest
 * @run main/othervm -Dsun.java2d.metal=true -Dsun.java2d.metal.displaySync=true FrameContentAppearanceTest
 * @run main/othervm -Dsun.java2d.metal=true -Dsun.java2d.metal.displaySync=false FrameContentAppearanceTest
 */


import java.awt.*;
import java.awt.image.*;
public class FrameContentAppearanceTest extends Frame {
    static BufferedImage img;
    private static final int SIZE = 200;
    private static final Color C = Color.BLUE;
    private static final int COL_TOLERANCE = 6;
    private static final int FAIL_TOLERANCE = 10;
    private static final int OGL_FAIL_TOLERANCE = 25;
    private static final int COUNT = 100;

    private void UI() {
        setSize(SIZE, SIZE);
        setResizable(false);
        setLocation(50, 50);
        setVisible(true);
    }

    @Override
    public void paint(Graphics g) {
        g.drawImage(img, 0, 0, this);
    }

    private static boolean cmpColors(Color c1, Color c2) {
        return (Math.abs(c2.getRed() - c1.getRed()) +
                Math.abs(c2.getGreen() - c1.getGreen()) +
                Math.abs(c2.getBlue() - c1.getBlue()) < COL_TOLERANCE);
    }

    protected boolean doTest() throws Exception {
        Robot r = new Robot();
        r.waitForIdle();
        EventQueue.invokeAndWait(this::UI);
        r.waitForIdle();
        Point loc = getLocationOnScreen();
        Color c = r.getPixelColor(loc.x + SIZE / 2, loc.y + SIZE / 2);
        dispose();
        return cmpColors(c, C);
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

        img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics g = img.getGraphics();
        g.setColor(C);
        g.fillRect(0, 0, SIZE, SIZE);

        int imgFailCount = 0;
        int rectFailCount = 0;
        int ovalFailCount = 0;
        int aaOvalFailCount = 0;

        for (int i = 0; i < COUNT; i++) {
            imgFailCount += (new FrameContentAppearanceTest().doTest()) ? 0 : 1;
            rectFailCount += (new FrameContentAppearanceTest() {
                @Override
                public void paint(Graphics g) {
                    g.setColor(Color.BLUE);
                    g.fillRect(0, 0, SIZE, SIZE);
                }
            }.doTest()) ? 0 : 1;
            ovalFailCount += (new FrameContentAppearanceTest() {
                @Override
                public void paint(Graphics g) {
                    g.setColor(Color.BLUE);
                    g.fillOval(0, 0, SIZE, SIZE);
                }
            }.doTest()) ? 0 : 1;
            aaOvalFailCount += (new FrameContentAppearanceTest() {
                @Override
                public void paint(Graphics g) {
                    Graphics2D g2d = (Graphics2D) g;
                    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2d.setColor(Color.BLUE);
                    g.fillOval(0, 0, SIZE, SIZE);
                }
            }.doTest()) ? 0 : 1;
        }

        if (imgFailCount > 0 || rectFailCount > 0 || ovalFailCount > 0 || aaOvalFailCount > 0) {
            String report = rendering + "\n" +
                    ((imgFailCount > 0) ? imgFailCount + " image rendering failure(s)\n" : "") +
                    ((rectFailCount > 0) ? rectFailCount + " rect rendering failure(s)\n" : "") +
                    ((ovalFailCount > 0) ? ovalFailCount + " oval rendering failure(s)\n" : "") +
                    ((aaOvalFailCount > 0) ? aaOvalFailCount + " aa oval rendering failure(s)\n" : "");

            int failTolerance = isOpenGL ? OGL_FAIL_TOLERANCE : FAIL_TOLERANCE;
            if (imgFailCount > failTolerance || rectFailCount > failTolerance ||
                ovalFailCount > failTolerance || aaOvalFailCount > failTolerance)
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
