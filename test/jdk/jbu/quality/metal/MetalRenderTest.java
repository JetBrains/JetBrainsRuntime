/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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

package quality.metal;

import org.junit.Test;
import quality.util.RenderUtil;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

public class MetalRenderTest {

    @Test
    public void testMetal() throws Exception {
        BufferedImage bi = RenderUtil.capture(120, 120,
                graphics2D -> {
                    graphics2D.setColor(Color.GREEN);
                    graphics2D.fillRect(10, 10, 50, 50);
                    graphics2D.setColor(Color.RED);
                    graphics2D.fillOval(30, 30, 150, 150);

                });
        RenderUtil.checkImage(bi, "metal", "geom.png");
    }
    @Test
    public void testMetal1() throws Exception {
        JFrame[] f = new JFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            f[0] = new JFrame();

            f[0].setSize(300, 300);
            // for frame border effects,
            // e.g. rounded frame
            f[0].setVisible(true);
        });

        Thread.sleep(4000);
    }

    @Test
    public void testMetal2() throws Exception {
        Frame[] f = new Frame[1];
        SwingUtilities.invokeAndWait(() -> {
            f[0] = new Frame();

            f[0].setSize(300, 300);
            // for frame border effects,
            // e.g. rounded frame
            f[0].setVisible(true);
        });

        Thread.sleep(4000);
    }
}
