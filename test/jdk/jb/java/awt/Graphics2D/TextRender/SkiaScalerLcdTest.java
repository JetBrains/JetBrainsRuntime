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
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

/**
 * @test
 * @summary An LCD text request on the Skia scaler gets real sub-pixel glyph images on macOS, from Skia's own outline
 *          rasterizer and LCD filter: Skia's CoreText host turned every LCD request into gray (CoreGraphics renders no
 *          colour sub-pixels), so a bundled font that FreeType draws with colour fringes lost them on the Skia scaler,
 *          and system fonts had none. With whole-pixel positions (no channel shift by the pipes) the fringes of the
 *          bundled JetBrains Mono must come within half of FreeType's (the JDK's scaler for that font, in a child JVM),
 *          and a system font (Menlo) must have them too.
 * @requires os.family == "mac"
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.font.scaler=skia SkiaScalerLcdTest
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.skia.raster=true -Dsun.java2d.font.scaler=skia SkiaScalerLcdTest
 */
public class SkiaScalerLcdTest {
    static final String TEXT = "Hamburgefonts Quick fox jumps 1234567890";
    static final int W = 420, H = 40;

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            System.out.println("fringes " + fringes(bundledFont()));
            return;
        }
        ProcessBuilder builder = new ProcessBuilder(List.of(new File(System.getProperty("java.home"), "bin/java").getPath(),
                "-Djava.awt.headless=true", "-cp", System.getProperty("test.classes", "."), SkiaScalerLcdTest.class.getName(), "freetype"));
        builder.redirectErrorStream(true);
        Process child = builder.start();
        String output = new String(child.getInputStream().readAllBytes()).trim();
        if (child.waitFor() != 0 || !output.startsWith("fringes ")) throw new RuntimeException("the FreeType child failed: " + output);
        int freeType = Integer.parseInt(output.substring("fringes ".length()).trim());
        if (freeType < 500) throw new RuntimeException("FreeType drew no LCD text either (" + freeType + " fringe pixels): the premise fails");
        int bundled = fringes(bundledFont()), system = fringes(new Font("Menlo", Font.PLAIN, 13));
        System.out.println("fringe pixels: FreeType (JetBrains Mono) " + freeType + ", Skia scaler JetBrains Mono " + bundled + ", Menlo " + system);
        StringBuilder failures = new StringBuilder();
        if (bundled < freeType / 2) failures.append("JetBrains Mono on the Skia scaler: ").append(bundled).append(" fringe pixels against FreeType's ").append(freeType).append("; ");
        if (system < freeType / 2) failures.append("Menlo on the Skia scaler: ").append(system).append(" fringe pixels; ");
        if (failures.length() > 0) throw new RuntimeException("LCD text came out (nearly) gray: " + failures);
    }

    static Font bundledFont() throws Exception {
        return Font.createFont(Font.TRUETYPE_FONT, new File(System.getProperty("java.home"), "lib/fonts/JetBrainsMono-Regular.ttf")).deriveFont(13f);
    }

    /** Pixels whose red and blue differ: black LCD text on white, whole-pixel positions. */
    static int fringes(Font font) {
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        g.setColor(Color.BLACK);
        g.setFont(font);
        g.drawString(TEXT, 4, 26);
        g.dispose();
        int count = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int p = image.getRGB(x, y);
            if (((p >> 16) & 255) != (p & 255)) count++;
        }
        return count;
    }
}
