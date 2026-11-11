/*
 * Copyright 2026 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.image.BufferedImage;

/**
 * @test
 * @summary Under LCD a variable system font (SF, the IDE's UI font) is drawn through the JDK's FreeType scaler at
 *          the variation CoreText uses for it at that size: SF's optical size follows the point size (17 up to 17 pt,
 *          then the size; the file's default instance is 28) and its weights are instances (Bold 700). Without the
 *          variation FreeType draws the file's default instance (13 pt: 253 px where CoreText's advance is 277), and
 *          before variable fonts reached it at all they stayed CoreText's gray. A font whose outlines FreeType cannot
 *          read (macOS 26's PingFang, macOS' CJK fallback: Apple's hvgl only) stays CoreText's and must draw: FreeType
 *          drew it empty. Each case must carry sub-pixel fringes at whole-pixel positions (with
 *          fractional metrics the pipes' third-pixel shift colours gray masks too), the advance CoreText's strike
 *          gives (fractional metrics, within 1 %), and the weight step CoreText draws (bold over regular ink within
 *          15 % of CoreText's ratio).
 * @requires os.family == "mac"
 * @run main/othervm -Djava.awt.headless=true SystemFontVariationLcdTest
 */
public class SystemFontVariationLcdTest {
    static final int W = 900, H = 80;
    static final String LATIN = "Hamburgefonts Quick fox jumps 1234567890", CJK = "中文文本测试，简体中文";

    public static void main(String[] args) throws Exception {
        StringBuilder failures = new StringBuilder();
        Font sf = new Font(".AppleSystemUIFont", Font.PLAIN, 13), sfBold = new Font(".AppleSystemUIFont", Font.BOLD, 13);
        Font pingFang = new Font("PingFang SC", Font.PLAIN, 13), pingFangSemibold = new Font("PingFangSC-Semibold", Font.PLAIN, 13);
        if (!sfBold.getPSName().equals(".SFNS-Bold") || !pingFangSemibold.getPSName().equals("PingFangSC-Semibold")) {
            throw new RuntimeException("fonts not found: " + sfBold.getPSName() + ", " + pingFangSemibold.getPSName());
        }
        for (Object[] c : new Object[][] {{sf, LATIN}, {sfBold, LATIN}, {sf.deriveFont(40f), "Hamburgefonts"}}) {
            Font font = (Font) c[0];
            String text = (String) c[1], what = font.getPSName() + " " + font.getSize2D() + " pt";
            // whole-pixel positions: with fractional metrics the pipes' third-pixel channel shift colours gray masks too
            int fringes = fringes(paint(font, text, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB, false));
            float lcd = advance(font, text, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB), coreText = advance(font, text, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            System.out.printf("%s: LCD fringe pixels %d, advance %.2f (CoreText's %.2f)%n", what, fringes, lcd, coreText);
            if (fringes < 300) failures.append(what).append(": ").append(fringes).append(" fringe pixels; ");
            if (Math.abs(lcd / coreText - 1) > 0.01) failures.append(what).append(": advance ").append(lcd).append(" against CoreText's ").append(coreText).append("; ");
        }
        weightStep("SF Bold over Regular", sf, sfBold, LATIN, failures);
        for (Font font : new Font[] {pingFang, pingFangSemibold}) {
            double lcd = ink(paint(font, CJK, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB, false));
            double gray = ink(paint(font, CJK, RenderingHints.VALUE_TEXT_ANTIALIAS_ON, false));
            System.out.printf("%s: ink under LCD %.0f, under grayscale %.0f%n", font.getPSName(), lcd, gray);
            if (lcd < 0.8 * gray) failures.append(font.getPSName()).append(": LCD ink ").append(lcd).append(" against grayscale's ").append(gray).append("; ");
        }
        if (failures.length() > 0) throw new RuntimeException(failures.toString());
    }

    static void weightStep(String what, Font regular, Font heavier, String text, StringBuilder failures) {
        double lcd = (double) ink(paint(heavier, text, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB, true)) / ink(paint(regular, text, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB, true));
        double coreText = (double) ink(paint(heavier, text, RenderingHints.VALUE_TEXT_ANTIALIAS_ON, true)) / ink(paint(regular, text, RenderingHints.VALUE_TEXT_ANTIALIAS_ON, true));
        System.out.printf("%s: ink ratio under LCD %.3f, CoreText's %.3f%n", what, lcd, coreText);
        if (Math.abs(lcd / coreText - 1) > 0.15) failures.append(what).append(": ink ratio ").append(lcd).append(" against CoreText's ").append(coreText).append("; ");
    }

    static BufferedImage paint(Font font, String text, Object aa, boolean fractional) {
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, aa);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                fractional ? RenderingHints.VALUE_FRACTIONALMETRICS_ON : RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        g.setColor(Color.BLACK);
        g.setFont(font);
        g.drawString(text, 4, 60);
        g.dispose();
        return image;
    }

    static float advance(Font font, String text, Object aa) {
        return (float) font.getStringBounds(text, new FontRenderContext(null, aa, RenderingHints.VALUE_FRACTIONALMETRICS_ON)).getWidth();
    }

    /** Σ (255 − channel) over the image: the amount of ink. */
    static long ink(BufferedImage image) {
        long sum = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int p = image.getRGB(x, y);
            sum += 765 - ((p >> 16) & 255) - ((p >> 8) & 255) - (p & 255);
        }
        return sum;
    }

    static int fringes(BufferedImage image) {
        int count = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int p = image.getRGB(x, y);
            if (((p >> 16) & 255) != (p & 255)) count++;
        }
        return count;
    }
}
