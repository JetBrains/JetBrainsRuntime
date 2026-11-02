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

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

/**
 * @test
 * @key headful
 * @summary On the Skia pipe with the platform's masks (blend system) and fractional metrics,
 *          text through Skia's own text path (strike skia) lands where the glyph-image route
 *          puts it: on CoreGraphics' placement grid — thirds of a pixel at 1x, rounded down,
 *          as AppKit places glyphs — not on Skia's nearest-quarter positions. Both routes
 *          draw the same line at seven sub-pixel offsets from the same CoreGraphics masks;
 *          the two images must be identical. They were identical at whole-pixel positions and
 *          differed by thousands of pixels at fractional ones (2026-11-02).
 * @requires os.family == "mac"
 * @library /test/lib
 * @run main/othervm SkiaTextPathPlatformGridTest
 */
public class SkiaTextPathPlatformGridTest {
    static final int W = 520, H = 160;

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            child(new File(args[0]));
            return;
        }
        File glyphImages = render("java2d"), textPath = render("skia");
        BufferedImage a = ImageIO.read(glyphImages), b = ImageIO.read(textPath);
        int differing = 0, max = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int p = a.getRGB(x, y) & 0xff, q = b.getRGB(x, y) & 0xff;   // gray: one channel tells
            if (p != q) { differing++; max = Math.max(max, Math.abs(p - q)); }
        }
        System.out.println("glyph images vs Skia's text path at fractional positions: " + differing + " differing pixels, max " + max + " levels");
        if (differing > 0) {
            throw new RuntimeException("Skia's text path does not place glyphs on the platform's grid: " + differing + " pixels differ, up to " + max + " levels");
        }
        System.out.println("PASSED");
    }

    /** A child JVM on the route, drawing into a file: the strike choice is a start-up property. */
    static File render(String strike) throws Exception {
        File out = new File("grid-" + strike + ".png").getAbsoluteFile();
        List<String> command = new ArrayList<>(List.of("-Dsun.java2d.uiScale=1", "-Dsun.java2d.skia=true", "-Dsun.java2d.font.scaler=skia",
                                                       "-Dsun.java2d.skia.text.blend=system", "-Dsun.java2d.skia.text.strike=" + strike,
                                                       SkiaTextPathPlatformGridTest.class.getName(), out.getPath()));
        OutputAnalyzer o = new OutputAnalyzer(ProcessTools.createTestJavaProcessBuilder(command).start());
        o.shouldHaveExitValue(0);
        return out;
    }

    static void child(File out) throws Exception {
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        if (!gc.getClass().getName().contains("MTL")) {
            throw new RuntimeException("not an accelerated configuration: " + gc.getClass().getName());
        }
        VolatileImage vi = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
        BufferedImage snapshot;
        do {
            vi.validate(gc);
            Graphics2D g = vi.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, W, H);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setFont(new Font("Helvetica Neue", Font.PLAIN, 13));
            g.setColor(Color.BLACK);
            for (int k = 0; k < 7; k++) {
                g.drawString("Quick fox jumps 1234567890 {}[]();:,.", 10 + k / 7.0f, 20 + 19 * k);   // seven sub-pixel offsets
            }
            g.dispose();
            snapshot = vi.getSnapshot();
        } while (vi.contentsLost());
        ImageIO.write(snapshot, "png", out);
    }
}
