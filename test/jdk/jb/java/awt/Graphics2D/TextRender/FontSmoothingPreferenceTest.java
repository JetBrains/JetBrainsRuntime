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
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

/**
 * @test
 * @summary On macOS the user's font smoothing preference (AppleFontSmoothing: 0 is off,
 *          anything else on — the historic strength levels render identically on
 *          current macOS, as AppKit does) is honoured by every Java text route: the
 *          JDK's CoreGraphics glyph renderer, the Skia scaler's cg route, and Skia's
 *          own text path when it requests the platform's smoothing. Text rendered
 *          with the preference off carries the ink of CoreGraphics' unsmoothed
 *          coverage (measured 14 % less than smoothed), and with 2 the ink of the
 *          default. The preference is read as AppKit reads it, through user
 *          defaults, so each child JVM sets it for itself with -AppleFontSmoothing
 *          on its command line. With smoothing off the cg route's per-luminance
 *          strikes collapse to one class.
 * @requires os.family == "mac"
 * @library /test/lib
 * @modules java.desktop/sun.font
 * @run main/othervm FontSmoothingPreferenceTest
 */
public class FontSmoothingPreferenceTest {
    static final String TEXT = "Hamburgefonts 123 Quick fox";
    static final int W = 360, H = 26;

    record Route(String name, String... flags) {}

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            child();
            return;
        }
        Route[] routes = {
            new Route("jdk-cstrike"),
            new Route("skia-cg", "-Dsun.java2d.font.scaler=skia"),   // blend system, the default
            new Route("skiapath-darkening", "-Dsun.java2d.skia.raster=true", "-Dsun.java2d.font.scaler=skia", "-Dsun.java2d.skia.text.strike=skia", "-Dsun.java2d.skia.text.blend=corrected"),
        };
        List<String> errors = new ArrayList<>();
        for (Route route : routes) {
            long[] def = run(route, null), off = run(route, 0), two = run(route, 2);
            double offRatio = (double) off[0] / def[0], twoRatio = (double) two[0] / def[0];
            System.out.printf("%s: ink default %d (%d luminance classes), AppleFontSmoothing=0 %d (%d classes, ratio %.3f), =2 %d (ratio %.3f)%n",
                              route.name(), def[0], def[1], off[0], off[1], offRatio, two[0], twoRatio);
            if (offRatio > 0.93) {
                errors.add(route.name() + ": smoothing off should lose at least 7 % of the ink (ratio " + offRatio + ")");
            }
            if (Math.abs(twoRatio - 1) > 0.01) {
                errors.add(route.name() + ": AppleFontSmoothing=2 must render as the default (ratio " + twoRatio + ")");
            }
            if (route.name().equals("skia-cg") && off[1] != 1) {
                errors.add(route.name() + ": with smoothing off the luminance classes should collapse to 1, not " + off[1]);
            }
        }
        if (!errors.isEmpty()) {
            throw new RuntimeException(String.join("; ", errors));
        }
        System.out.println("PASSED");
    }

    /** A child JVM on the route with the preference set for its process alone: its ink and luminance class count. */
    static long[] run(Route route, Integer appleFontSmoothing) throws Exception {
        List<String> command = new ArrayList<>(List.of("--add-exports", "java.desktop/sun.font=ALL-UNNAMED",
                                                       "-Djava.awt.headless=true", "-Dsun.java2d.uiScale=1"));
        command.addAll(List.of(route.flags()));
        command.add(FontSmoothingPreferenceTest.class.getName());
        command.add("child");
        if (appleFontSmoothing != null) {
            command.add("-AppleFontSmoothing");
            command.add(appleFontSmoothing.toString());
        }
        OutputAnalyzer out = new OutputAnalyzer(ProcessTools.createTestJavaProcessBuilder(command).start());
        out.shouldHaveExitValue(0);
        String line = out.firstMatch("ink (\\d+) classes (\\d+)");
        if (line == null) {
            throw new RuntimeException(route.name() + ": no ink line in the child's output");
        }
        String[] parts = line.split(" ");
        return new long[] {Long.parseLong(parts[1]), Long.parseLong(parts[3])};
    }

    static void child() throws Exception {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font("Helvetica Neue", Font.PLAIN, 13)); g.setColor(Color.BLACK);
        g.drawString(TEXT, 4, 18);
        g.dispose();
        long ink = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int p = img.getRGB(x, y);
            ink += 255 - (((p >> 16) & 255) + ((p >> 8) & 255) + (p & 255)) / 3;
        }
        int classes = sun.font.FontUtilities.textLuminanceClasses;
        System.out.println("ink " + ink + " classes " + classes);
    }
}
