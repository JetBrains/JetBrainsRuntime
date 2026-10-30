/*
 * Copyright 2026 Vladimir Bely.
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
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.util.Arrays;

/*
 * @test
 * @key headful
 * @requires os.family == "linux"
 * @summary JBR-10634: Verifies color glyph rendering and subsequent grayscale text on Vulkan surfaces
 * @library /test/lib
 * @build jtreg.SkippedException
 * @run main/othervm -Dawt.toolkit.name=WLToolkit -Dsun.java2d.vulkan=True VulkanColorGlyphTest
 */
public class VulkanColorGlyphTest {
    public static void main(String[] args) throws Exception {
        if (!Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames()).contains("Noto Color Emoji")) {
            throw new jtreg.SkippedException("Noto Color Emoji is not installed");
        }
        Font font = new Font("Noto Color Emoji", Font.PLAIN, 40);
        if (!font.canDisplay(0x1F600)) throw new AssertionError("Emoji font unavailable");
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        System.out.println("Runtime: " + System.getProperty("java.runtime.version"));
        System.out.println("Configuration: " + gc.getClass().getName());
        boolean vulkan = Boolean.getBoolean("sun.java2d.vulkan");
        if (vulkan && !gc.getClass().getName().contains("WLVK")) {
            throw new AssertionError("Vulkan was requested but is not active");
        }
        VolatileImage image = gc.createCompatibleVolatileImage(900, 120);
        if (!image.getCapabilities().isAccelerated()) {
            image.flush();
            throw new AssertionError("Expected an accelerated surface");
        }
        BufferedImage snapshot = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            image.validate(gc);
            Graphics2D g = image.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 900, 120);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.BLACK);
            g.setFont(font);
            g.drawString("😀 🚀 🐱 🔥", 15, 65);
            // Switch back to the grayscale text path after drawing color glyphs.
            g.setFont(new Font(Font.DIALOG, Font.PLAIN, 20));
            g.drawString("After emoji: Hello 123", 600, 65);
            g.dispose();
            snapshot = image.getSnapshot();
            if (!image.contentsLost()) break;
            snapshot = null;
        }
        if (snapshot == null) throw new AssertionError("Surface repeatedly lost");
        int colorPixels = 0, textPixels = 0;
        for (int y = 0; y < 120; y++) {
            for (int x = 0; x < 900; x++) {
                int rgb = snapshot.getRGB(x, y);
                int r = (rgb >>> 16) & 255, green = (rgb >>> 8) & 255, b = rgb & 255;
                if (x < 500 && Math.max(r, Math.max(green, b)) - Math.min(r, Math.min(green, b)) > 30)
                    colorPixels++;
                if (x >= 600 && r < 180 && green < 180 && b < 180) textPixels++;
            }
        }
        System.out.println("Accelerated: " + image.getCapabilities().isAccelerated());
        System.out.println("Color pixels: " + colorPixels + "; text pixels: " + textPixels);
        image.flush();
        if (colorPixels < 100) throw new AssertionError("Color emoji were not rendered");
        if (textPixels < 50) throw new AssertionError("Text after emoji was not rendered");
        System.out.println("PASS");
    }
}
