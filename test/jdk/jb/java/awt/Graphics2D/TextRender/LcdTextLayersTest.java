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
 * @summary Dense LCD text as one batch: 100 lines of 200 glyphs, each line
 *          drawn twice with a one-pixel shift, so that half of the 40,000
 *          glyphs overlap a pending glyph. In every LCD mode each glyph has
 *          to be composited on top of the previous one like the software
 *          loops do it, within the tolerance on the run line, and no glyph
 *          may go missing; the base mesh splits where its 16-bit vertex
 *          indices run out. Metal has no run line: its LCD batches read the
 *          destination from a copy taken before the batch, so overlapping
 *          glyphs within one batch sit up to 135 levels off the loops.
 * @requires (os.family == "mac" | os.family == "windows")
 * @modules java.desktop/sun.java2d
 * @library ..
 * @build PipelineComparison
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.lcd=twopass -Dsun.java2d.uiScale=1 LcdTextLayersTest skia 32
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.lcd=blend -Dsun.java2d.uiScale=1 LcdTextLayersTest skia 32
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.lcd=accumulate -Dsun.java2d.uiScale=1 LcdTextLayersTest skia 32
 */

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

public class LcdTextLayersTest {

    private static final int W = 1400;
    private static final int H = 1000;

    public static void main(String[] args) throws Exception {
        String pipeline = args[0];
        int tolerance = Integer.parseInt(args[1]);
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        if (!PipelineComparison.pipelineActive(pipeline, gc)) {
            return;
        }
        BufferedImage reference = PipelineComparison.softwareReference(W, H, LcdTextLayersTest::paint);
        BufferedImage actual = PipelineComparison.rendered(gc, W, H, LcdTextLayersTest::paint);
        PipelineComparison.Difference difference = PipelineComparison.compare(reference, actual, tolerance);
        System.out.println(difference.beyond() + " pixels beyond " + tolerance + " levels, "
                + difference.missingInk() + " pixels of ink missing, max " + difference.max());
        if (difference.beyond() > 0 || difference.missingInk() > 0) {
            throw new RuntimeException("Dense LCD text differs from the software loops: " + difference.beyond()
                    + " pixels beyond " + tolerance + " levels, " + difference.missingInk() + " pixels of ink missing");
        }
        System.out.println("PASSED");
    }

    /** 100 lines of 200 glyphs, each line twice: 40,000 glyphs in one batch, half of them overlapping. */
    private static void paint(Graphics2D g) {
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 9));
        g.setColor(new Color(0x303030));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append((char) ('a' + i % 26));
        }
        String line = sb.toString();
        for (int row = 0; row < 100; row++) {
            int y = 12 + row * 10;
            g.drawString(line, 4, y);
            g.drawString(line, 5, y);
        }
    }
}
