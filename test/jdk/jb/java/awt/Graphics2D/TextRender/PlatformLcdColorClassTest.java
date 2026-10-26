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
 * @summary Native ClearType corrects each colour channel of a glyph for the
 *          actual text colour. The platform route's LCD mask was rendered by
 *          DirectWrite for a gray of the text's luminance class, so saturated
 *          text got a gray's corrections in every channel (red text on dark:
 *          red channel up to 38 levels off native, sheets of 2026-10-26).
 *          With colour classes (sun.java2d.skia.text.lcdColorBits, bits per
 *          channel) an LCD strike is keyed by the quantized colour and each
 *          channel of its mask is rendered for that colour. Checked through
 *          the mask recovered from text on black (pixel / ink per channel):
 *          red text and a gray of red's luminance must get different masks
 *          (the colour, not the luminance, decides); two inks of one class
 *          must get the same mask and inks of neighbouring classes different
 *          ones (the quantization); grayscale text is unaffected.
 * @requires (os.family == "windows")
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.gray=platform -Dsun.java2d.skia.text.lcdColorBits=3 PlatformLcdColorClassTest 3
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.gray=platform -Dsun.java2d.skia.text.lcdColorBits=2 PlatformLcdColorClassTest 2
 */

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

public class PlatformLcdColorClassTest {

    static final String TEXT = "Hamburgefonts Quick fox jumps 1234567890";
    static final int W = 320, H = 32;

    public static void main(String[] args) {
        int bits = Integer.parseInt(args[0]);
        Font font = new Font("Segoe UI", Font.PLAIN, 13);
        if (!font.getFamily().equalsIgnoreCase("Segoe UI")) {
            System.out.println("Segoe UI not installed: nothing to test");
            return;
        }
        StringBuilder failures = new StringBuilder();
        // the colour decides, not the luminance: red (255,0,0) has the luminance 76
        Color red = new Color(255, 0, 0), gray = new Color(76, 76, 76);
        int colourVsGray = maskDifference(mask(font, red, 0), mask(font, gray, 0));
        System.out.println("red channel mask of red text against that of a gray of its luminance: max " + colourVsGray);
        if (colourVsGray <= 8) {
            failures.append("red text gets the mask of a gray of its luminance (max difference ").append(colourVsGray).append("); ");
        }
        // the quantization: inks of one class share the mask, neighbouring classes do not
        int step = 1 << (8 - bits);
        Color sameClass = new Color(255 - step / 4, 0, 0), otherClass = new Color(255 - step - step / 4, 0, 0);
        int same = maskDifference(mask(font, red, 0), mask(font, sameClass, 0)), other = maskDifference(mask(font, red, 0), mask(font, otherClass, 0));
        System.out.println(bits + " bits: a red of the same class differs by max " + same + ", of the next class by max " + other);
        if (same > 8) {
            failures.append("two inks of one colour class got different masks (max ").append(same).append("); ");
        }
        if (other <= 8) {
            failures.append("inks of neighbouring classes got the same mask (max ").append(other).append("); ");
        }
        // grayscale text is keyed by luminance as before: red and the gray of its luminance agree
        int grayscale = maskDifference(maskGray(font, red), maskGray(font, gray));
        System.out.println("grayscale antialiasing: red text against the gray of its luminance, max " + grayscale);
        if (grayscale > 8) {
            failures.append("grayscale text depends on the colour beyond its luminance (max ").append(grayscale).append("); ");
        }
        if (failures.length() > 0) {
            throw new RuntimeException("LCD colour classes (" + bits + " bits): " + failures);
        }
        System.out.println("PASSED");
    }

    /** The coverage the pipe applied in one channel, recovered from text of the ink on black: pixel * 255 / ink. */
    static int[] mask(Font font, Color ink, int channel) {
        BufferedImage image = render(font, ink, Color.BLACK, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        int shift = 16 - 8 * channel, inkValue = (ink.getRGB() >> shift) & 255;
        int[] mask = new int[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                mask[y * W + x] = ((image.getRGB(x, y) >> shift) & 255) * 255 / inkValue;
            }
        }
        return mask;
    }

    /** The grayscale coverage recovered from text of the ink on black, from the ink's brightest channel. */
    static int[] maskGray(Font font, Color ink) {
        BufferedImage image = render(font, ink, Color.BLACK, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int inkValue = Math.max(ink.getRed(), Math.max(ink.getGreen(), ink.getBlue()));
        int shift = inkValue == ink.getRed() ? 16 : inkValue == ink.getGreen() ? 8 : 0;
        int[] mask = new int[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                mask[y * W + x] = ((image.getRGB(x, y) >> shift) & 255) * 255 / inkValue;
            }
        }
        return mask;
    }

    static int maskDifference(int[] a, int[] b) {
        int max = 0;
        for (int i = 0; i < a.length; i++) {
            max = Math.max(max, Math.abs(a[i] - b[i]));
        }
        return max;
    }

    static BufferedImage render(Font font, Color ink, Color paper, Object aa) {
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(paper);
            g.fillRect(0, 0, W, H);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, aa);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setColor(ink);
            g.setFont(font);
            g.drawString(TEXT, 4, 22);
        } finally {
            g.dispose();
        }
        return image;
    }
}
