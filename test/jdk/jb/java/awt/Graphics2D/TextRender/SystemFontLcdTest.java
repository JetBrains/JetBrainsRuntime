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
import java.io.File;

/**
 * @test
 * @summary An LCD text request draws a system font (CFont) through the JDK's FreeType scaler, as it draws a font of
 *          Font.createFont, with real sub-pixel fringes and FreeType's advances: CoreGraphics renders no colour
 *          sub-pixels on macOS, so CoreText's LCD text was gray (the colour with fractional metrics was the pipes'
 *          channel shift). A system font under LCD must draw exactly as the same face of its file does through
 *          Font.createFonts (pixels and advance, fractional metrics off and on); under grayscale it stays CoreText's.
 * @requires os.family == "mac"
 * @run main/othervm -Djava.awt.headless=true SystemFontLcdTest
 */
public class SystemFontLcdTest {
    static final String TEXT = "Hamburgefonts Quick fox jumps 1234567890";
    static final int W = 420, H = 40;

    public static void main(String[] args) throws Exception {
        check("Menlo-Regular", new Font("Menlo", Font.PLAIN, 13), "/System/Library/Fonts/Menlo.ttc");
        check("Menlo-Bold", new Font("Menlo", Font.BOLD, 13), "/System/Library/Fonts/Menlo.ttc");
        check("HelveticaNeue", new Font("Helvetica Neue", Font.PLAIN, 13), "/System/Library/Fonts/HelveticaNeue.ttc");
        check("LucidaGrande", new Font("Lucida Grande", Font.PLAIN, 13), "/System/Library/Fonts/LucidaGrande.ttc");
    }

    static void check(String postScriptName, Font system, String path) throws Exception {
        Font file = null;
        Font[] faces;
        try {
            faces = Font.createFonts(new File(path));
        } catch (java.awt.FontFormatException e) {
            // LucidaGrande.ttc: its last two faces carry Unicode-platform names only, which the JDK does not read
            faces = new Font[] {Font.createFont(Font.TRUETYPE_FONT, new File(path))};
        }
        for (Font face : faces) {
            if (face.getPSName().equals(postScriptName)) file = face.deriveFont(13f);
        }
        if (file == null) throw new RuntimeException(path + " has no face " + postScriptName);
        if (!system.getPSName().equals(postScriptName)) throw new RuntimeException(system + " is " + system.getPSName() + ", not " + postScriptName);
        StringBuilder failures = new StringBuilder();
        for (boolean fractional : new boolean[] {false, true}) {
            Object lcd = RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB;
            BufferedImage systemImage = paint(system, lcd, fractional), fileImage = paint(file, lcd, fractional);
            int differing = differing(systemImage, fileImage), fringes = fringes(systemImage);
            float systemAdvance = advance(system, lcd, fractional), fileAdvance = advance(file, lcd, fractional);
            System.out.printf("%s, LCD, fractional metrics %b: %d pixels differ from the file's face on FreeType; fringe pixels %d (FreeType's %d); advance %.3f (FreeType's %.3f)%n",
                    postScriptName, fractional, differing, fringes, fringes(fileImage), systemAdvance, fileAdvance);
            if (differing > 0 || systemAdvance != fileAdvance) {
                failures.append(postScriptName).append(fractional ? " (fractional metrics)" : "").append(": ").append(differing)
                        .append(" pixels differ from FreeType's, advance ").append(systemAdvance).append(" against ").append(fileAdvance).append("; ");
            }
            if (fringes(fileImage) < 300) throw new RuntimeException(postScriptName + ": FreeType drew no LCD text either: the premise fails");
        }
        BufferedImage gray = paint(system, RenderingHints.VALUE_TEXT_ANTIALIAS_ON, false);
        if (differing(gray, paint(file, RenderingHints.VALUE_TEXT_ANTIALIAS_ON, false)) == 0) {
            failures.append(postScriptName).append(": grayscale text left CoreText (it draws as FreeType's); ");
        }
        if (failures.length() > 0) throw new RuntimeException(failures.toString());
    }

    static BufferedImage paint(Font font, Object aa, boolean fractional) {
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, aa);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                fractional ? RenderingHints.VALUE_FRACTIONALMETRICS_ON : RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        g.setColor(Color.BLACK);
        g.setFont(font);
        g.drawString(TEXT, 4, 26);
        g.dispose();
        return image;
    }

    static float advance(Font font, Object aa, boolean fractional) {
        FontRenderContext frc = new FontRenderContext(null, aa,
                fractional ? RenderingHints.VALUE_FRACTIONALMETRICS_ON : RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        return (float) font.getStringBounds(TEXT, frc).getWidth();
    }

    static int differing(BufferedImage a, BufferedImage b) {
        int count = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) if (a.getRGB(x, y) != b.getRGB(x, y)) count++;
        return count;
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
