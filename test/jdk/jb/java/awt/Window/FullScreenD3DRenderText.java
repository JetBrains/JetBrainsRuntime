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

import java.awt.Color;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.image.VolatileImage;

import jtreg.SkippedException;

/**
 * @test
 * @summary Regression test after JBR-7051, modified to execute D3DContext::ConfigureContext on AWT Toolkit thread.
 * @requires os.family == "windows"
 * @library /test/lib
 * @key headful
 * @run main/othervm -Dsun.java2d.d3d=true FullScreenD3DRenderText
 */
public class FullScreenD3DRenderText {
    private static final int ITERATIONS = 100;

    public static void main(String[] args) throws Exception {

        var ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        var gd = ge.getDefaultScreenDevice();

        if (!gd.getClass().getName().contains("D3D")) {
            throw new SkippedException("Test must be run on Windows with D3D enabled.");
        }

        if (!gd.isFullScreenSupported()) {
            throw new SkippedException("Full-screen mode is not supported.");
        }

        Frame frame = new Frame("FullScreenD3DRenderText");
        try {
            EventQueue.invokeAndWait(() -> {
                frame.setUndecorated(true);
                frame.setBackground(Color.DARK_GRAY);
                gd.setFullScreenWindow(frame);
            });
            Thread.sleep(1000);
            VolatileImage vimg = frame.createVolatileImage(300, 100);
            for (int i = 0; i < ITERATIONS; i++) {
                if (vimg.validate(frame.getGraphicsConfiguration())
                        == VolatileImage.IMAGE_INCOMPATIBLE) {
                    vimg = frame.createVolatileImage(300, 100);
                }
                Graphics2D g2d = vimg.createGraphics();
                g2d.setColor(Color.WHITE);
                g2d.fillRect(0, 0, 300, 100);
                g2d.setColor(Color.BLACK);
                g2d.drawString("Text " + i, 10, 50);
                g2d.dispose();

                Graphics g = frame.getGraphics();
                if (g != null) {
                    g.drawImage(vimg, 100, 100, null);
                    g.dispose();
                }
                Toolkit.getDefaultToolkit().sync();
                Thread.sleep(20);
            }
            System.out.println("Test passed.");
        } finally {
            EventQueue.invokeAndWait(() -> {
                gd.setFullScreenWindow(null);
                frame.dispose();
            });
        }
    }
}
