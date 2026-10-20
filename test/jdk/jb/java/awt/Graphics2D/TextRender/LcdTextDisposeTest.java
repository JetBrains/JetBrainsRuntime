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
 */

/*
 * @test
 * @key headful
 * @summary A surface that is flushed or invalidated while LCD text drawn to
 *          it is still queued in a glyph batch: the batch has to be drawn
 *          into that surface before the pipeline forgets it, in every LCD
 *          mode. The accumulating mode composites the coverage onto the
 *          current destination from a hook, so it is the one that notices
 *          if the destination has already been dropped.
 * @requires (os.family == "mac" | os.family == "windows")
 * @modules java.desktop/sun.java2d
 * @library ..
 * @build PipelineComparison
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.lcd=blend -Dsun.java2d.uiScale=1 LcdTextDisposeTest skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.lcd=twopass -Dsun.java2d.uiScale=1 LcdTextDisposeTest skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.lcd=accumulate -Dsun.java2d.uiScale=1 LcdTextDisposeTest skia
 * @run main/othervm -Dsun.java2d.metal=true -Dsun.java2d.uiScale=1 LcdTextDisposeTest metal
 */

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import sun.java2d.SurfaceData;

public class LcdTextDisposeTest {

    private static final int W = 200;
    private static final int H = 40;
    private static final int TOLERANCE = 48;

    public static void main(String[] args) throws Exception {
        String pipeline = args[0];
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        if (!PipelineComparison.pipelineActive(pipeline, gc)) {
            return;
        }
        for (int round = 0; round < 300; round++) {
            // text queued, then the image flushed: the queued batch meets a surface on its way out
            VolatileImage image = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
            Graphics2D g = image.createGraphics();
            try {
                paint(g, round);
            } finally {
                g.dispose();
            }
            image.flush();
            // text queued, then the surface invalidated: the pipeline forgets
            // the surface while the batch still refers to it, the texture stays
            image = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
            g = image.createGraphics();
            try {
                paint(g, round);
            } finally {
                g.dispose();
            }
            SurfaceData.getPrimarySurfaceData(image).invalidate();
        }
        // and one that is read back: the pipeline is still in order
        BufferedImage reference = PipelineComparison.softwareReference(W, H, g -> paint(g, 0));
        BufferedImage actual = PipelineComparison.rendered(gc, W, H, g -> paint(g, 0));
        PipelineComparison.Difference difference = PipelineComparison.compare(reference, actual, TOLERANCE);
        System.out.println(difference.beyond() + " pixels beyond " + TOLERANCE + " levels, max " + difference.max());
        if (difference.beyond() > 0) {
            throw new RuntimeException("LCD text after flushed and invalidated surfaces differs from the software loops: "
                    + difference.beyond() + " pixels beyond " + TOLERANCE + " levels");
        }
        System.out.println("PASSED");
    }

    private static void paint(Graphics2D g, int round) {
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setFont(new Font(Font.DIALOG, Font.PLAIN, 13));
        g.setColor(new Color(0x202020 + round));
        g.drawString("Hamburgefonstiv " + round, 4, 15);
        g.drawString("Hamburgefonstiv " + round, 5, 31);
    }
}
