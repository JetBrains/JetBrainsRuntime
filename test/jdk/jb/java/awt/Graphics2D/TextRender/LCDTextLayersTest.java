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

/*
 * @test
 * @key headful
 * @summary Regression test for JBR-10654: dense, overlapping LCD text drawn as one batch on the
 *          Skia pipeline matches the software loops in every LCD compositing mode.
 * @requires os.family == "windows"
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @library .. /test/lib
 * @build PipelineComparison
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.text.lcdCompositing=twoPass -Dsun.java2d.uiScale=1 LCDTextLayersTest skia 32
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.text.lcdCompositing=blend -Dsun.java2d.uiScale=1 LCDTextLayersTest skia 32
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.text.lcdCompositing=accumulate -Dsun.java2d.uiScale=1 LCDTextLayersTest skia 32
 */

/*
 * @test id=vulkan
 * @key headful
 * @summary Regression test for JBR-10654: the same on Graphite's native Vulkan backend, where a kPlus
 *          second pass was blended in the shader from an input attachment and the overlap layers lost it.
 * @requires (os.family == "windows")
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @library .. /test/lib
 * @build PipelineComparison
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.graphite.api=vulkan -Dsun.java2d.skia.text.lcdCompositing=twoPass -Dsun.java2d.uiScale=1 LCDTextLayersTest skia 32
 */

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * 100 lines of 200 glyphs, each line drawn twice with a one-pixel shift, so that half of the
 * 40,000 glyphs overlap a pending glyph. In every LCD compositing mode each glyph has to be
 * composited on top of the previous one like the software loops do it, within the tolerance on
 * the run line, and no glyph may go missing; the base mesh splits where its 16-bit vertex indices
 * (65,536 vertices, four per glyph) run out, which 40,000 glyphs exceed.
 */
public class LCDTextLayersTest {

    private static final int WIDTH = 1400;
    private static final int HEIGHT = 1000;
    private static final int ROWS = 100;
    private static final int GLYPHS_PER_ROW = 200;
    private static final int FONT_SIZE = 9;
    private static final int ROW_PITCH = 10;
    private static final int FIRST_BASELINE = 12;
    private static final int LEFT_MARGIN = 4;
    private static final int LETTERS = 26;

    public static void main(String[] args) throws Exception {
        String pipeline = args[0];
        int tolerance = Integer.parseInt(args[1]);
        GraphicsConfiguration graphicsConfig = PipelineComparison.defaultConfiguration();
        PipelineComparison.requirePipeline(pipeline, graphicsConfig);
        BufferedImage reference = PipelineComparison.softwareReference(WIDTH, HEIGHT, LCDTextLayersTest::paint);
        BufferedImage actual = PipelineComparison.rendered(graphicsConfig, WIDTH, HEIGHT, LCDTextLayersTest::paint);
        PipelineComparison.Difference difference = PipelineComparison.compare(reference, actual, tolerance);
        System.out.println(difference.beyond() + " pixels beyond " + tolerance + " levels, "
                + difference.missingInk() + " pixels of ink missing, max " + difference.max());
        if (difference.beyond() > 0 || difference.missingInk() > 0) {
            throw new RuntimeException("Dense LCD text differs from the software loops: " + difference.beyond()
                    + " pixels beyond " + tolerance + " levels, " + difference.missingInk() + " pixels of ink missing");
        }
        System.out.println("PASSED");
    }

    /** {@link #ROWS} lines of {@link #GLYPHS_PER_ROW} glyphs, each line twice: 40,000 glyphs in one batch, half of them overlapping. */
    private static void paint(Graphics2D graphics) {
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, WIDTH, HEIGHT);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        graphics.setFont(new Font(Font.MONOSPACED, Font.PLAIN, FONT_SIZE));
        graphics.setColor(new Color(0x303030));
        StringBuilder lineBuilder = new StringBuilder();
        for (int glyph = 0; glyph < GLYPHS_PER_ROW; glyph++) {
            lineBuilder.append((char) ('a' + glyph % LETTERS));
        }
        String line = lineBuilder.toString();
        for (int row = 0; row < ROWS; row++) {
            int y = FIRST_BASELINE + row * ROW_PITCH;
            graphics.drawString(line, LEFT_MARGIN, y);
            graphics.drawString(line, LEFT_MARGIN + 1, y);
        }
    }
}
