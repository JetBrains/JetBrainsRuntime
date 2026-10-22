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
 * @summary Runs of LCD text in alternating colours on consecutive lines, as
 *          syntax-highlighted code is painted: every run is its own glyph
 *          batch, the glyph boxes of neighbouring lines overlap by a pixel,
 *          and each run must be composited in its own colour. A compositing
 *          strategy that stages coverage in a scratch texture must not let a
 *          later run's staging show through an earlier run's composite.
 * @requires (os.family == "mac" | os.family == "windows")
 * @modules java.desktop/sun.java2d
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.lcd=blend -Dsun.java2d.uiScale=1 LcdColorRunsTest skia 16
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.lcd=twopass -Dsun.java2d.uiScale=1 LcdColorRunsTest skia 32
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.lcd=accumulate -Dsun.java2d.uiScale=1 LcdColorRunsTest skia 32
 * @run main/othervm -Dsun.java2d.metal=true -Dsun.java2d.uiScale=1 LcdColorRunsTest metal 48
 */

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;

public class LcdColorRunsTest {

    private static final int W = 420;
    private static final int H = 160;
    private static final int[] BACKGROUNDS = { 0xFFFFFF, 0x1E1F22 };
    /** keyword, identifier, comment, string: the colours of a code line, light and dark */
    private static final int[][] COLORS = { { 0x0033B3, 0x080808, 0x8C8C8C, 0x067D17 }, { 0xCF8E6D, 0xBCBEC4, 0x7A7E85, 0x6AAB73 } };
    private static final String[] WORDS = { "public", "static", "/unused/", "\"text\"", "void", "main(", "return", "ffi" };

    public static void main(String[] args) throws Exception {
        String pipeline = args[0];
        int tolerance = Integer.parseInt(args[1]);
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        if (!LcdTextBackgroundsTest.pipelineActive(pipeline, gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE))) {
            return;
        }
        StringBuilder failures = new StringBuilder();
        for (int b = 0; b < BACKGROUNDS.length; b++) {
            BufferedImage reference = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = reference.createGraphics();
            try {
                paint(g, BACKGROUNDS[b], COLORS[b]);
            } finally {
                g.dispose();
            }
            VolatileImage image = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
            g = image.createGraphics();
            try {
                paint(g, BACKGROUNDS[b], COLORS[b]);
            } finally {
                g.dispose();
            }
            BufferedImage actual = image.getSnapshot();
            if (image.contentsLost()) {
                throw new RuntimeException("Volatile image contents were lost");
            }
            int max = 0;
            int off = 0;
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    int d = LcdTextBackgroundsTest.maxChannelDifference(reference.getRGB(x, y), actual.getRGB(x, y));
                    max = Math.max(max, d);
                    if (d > tolerance) {
                        off++;
                    }
                }
            }
            String what = String.format("runs on %06x", BACKGROUNDS[b]);
            System.out.println(what + ": max " + max + " levels, " + off + " pixels beyond " + tolerance);
            if (off > 0) {
                failures.append(what).append(" (max ").append(max).append(") ");
            }
        }
        if (failures.length() > 0) {
            throw new RuntimeException("Colour runs differ from the software loops by more than "
                    + tolerance + " levels: " + failures);
        }
        System.out.println("PASSED");
    }

    /** Eight lines; line spacing below the glyph box height, so the boxes of neighbouring lines overlap. */
    private static void paint(Graphics2D g, int background, int[] colors) {
        g.setColor(new Color(background));
        g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        Font font = new Font(Font.MONOSPACED, Font.PLAIN, 13);
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        int y = 14;
        for (int line = 0; line < 8; line++) {
            int x = 4 + (line % 3) * 8;
            for (int w = 0; w < WORDS.length; w++) {
                String word = WORDS[(w + line) % WORDS.length];
                if (word.startsWith("/")) {
                    // an inlay: a box behind the word, filled between the text runs as the editor does
                    g.setColor(new Color(background == 0xFFFFFF ? 0xEDEDED : 0x2B2D30));
                    g.fillRect(x - 2, y - 11, fm.stringWidth(word) + 4, 15);
                }
                g.setColor(new Color(colors[(w + line) % colors.length]));
                g.drawString(word, x, y);
                x += fm.stringWidth(word) + fm.charWidth(' ');
            }
            y += 14;   // 13 px font: the glyph boxes are taller than this
        }
    }
}
