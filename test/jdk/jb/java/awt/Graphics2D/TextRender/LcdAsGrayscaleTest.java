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
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.font.TextLayout;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;

/**
 * @test
 * @summary On macOS an LCD text request renders as grayscale text (sun.java2d.skia.text.lcd=system, the default):
 *          macOS has drawn no colour sub-pixels since Mojave — CoreGraphics' sub-pixel masks have equal channels on
 *          both scalers — and the colour the JDK drew under fractional metrics came from its pipes shifting those gray
 *          masks by thirds of a pixel (about 20 000 coloured pixels on a sheet where AppKit has none). Every hint
 *          (LCD_HRGB, HBGR, VRGB, VBGR) with and without fractional metrics, through drawString and TextLayout, must
 *          render exactly as TEXT_ANTIALIAS_ON: the loops with the JDK and the Skia scaler, the raster surface with
 *          Skia's text path, and the GPU pipes.
 * @requires os.family == "mac"
 * @run main/othervm -Djava.awt.headless=true LcdAsGrayscaleTest image
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.font.scaler=skia LcdAsGrayscaleTest image
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.skia.raster=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.strike=skia LcdAsGrayscaleTest image
 */

/*
 * @test id=gpu
 * @summary The same on the Metal pipeline and the Skia GPU pipe.
 * @requires os.family == "mac"
 * @run main/othervm -Dsun.java2d.uiScale=1 LcdAsGrayscaleTest gpu
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.strike=skia LcdAsGrayscaleTest gpu
 */
public class LcdAsGrayscaleTest {
    static final int W = 640, H = 120;

    public static void main(String[] args) {
        boolean gpu = args[0].equals("gpu");
        Object[] lcd = {RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HBGR,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_VRGB, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_VBGR};
        StringBuilder failures = new StringBuilder();
        for (Object fm : new Object[] {RenderingHints.VALUE_FRACTIONALMETRICS_OFF, RenderingHints.VALUE_FRACTIONALMETRICS_ON}) {
            for (boolean layout : new boolean[] {false, true}) {
                BufferedImage gray = render(gpu, RenderingHints.VALUE_TEXT_ANTIALIAS_ON, fm, layout);
                for (Object hint : lcd) {
                    BufferedImage seen = render(gpu, hint, fm, layout);
                    int differing = 0, coloured = 0;
                    for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
                        int p = seen.getRGB(x, y);
                        if (p != gray.getRGB(x, y)) differing++;
                        int r = p >> 16 & 255, g = p >> 8 & 255, b = p & 255;
                        if (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) > 8) coloured++;
                    }
                    String what = hint + (fm == RenderingHints.VALUE_FRACTIONALMETRICS_ON ? ", fractional metrics" : "") + (layout ? ", TextLayout" : ", drawString");
                    System.out.println(what + ": " + differing + " pixels differ from grayscale, " + coloured + " coloured");
                    if (differing > 0) {
                        failures.append(what).append(": ").append(differing).append(" pixels differ from grayscale (").append(coloured).append(" coloured); ");
                    }
                }
            }
        }
        if (failures.length() > 0) {
            throw new RuntimeException(failures.toString());
        }
        System.out.println("PASSED");
    }

    static BufferedImage render(boolean gpu, Object aa, Object fm, boolean layout) {
        VolatileImage vi = null;
        BufferedImage image = null;
        Graphics2D g;
        if (gpu) {
            vi = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().createCompatibleVolatileImage(W, H);
            g = vi.createGraphics();
        } else {
            image = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
            g = image.createGraphics();
        }
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.setColor(Color.BLACK);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, aa);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, fm);
        int y = 20;
        for (String family : new String[] {"Helvetica Neue", "Menlo", "Times New Roman"}) {
            Font font = new Font(family, Font.PLAIN, 14);
            g.setFont(font);
            String text = "The quick brown fox jumps over the lazy dog 0123456789";
            if (layout) {
                new TextLayout(text, font, g.getFontRenderContext()).draw(g, 10.3f, y + 0.4f);
            } else {
                g.drawString(text, 10.3f, y + 0.4f);
            }
            y += 30;
        }
        g.dispose();
        return vi != null ? vi.getSnapshot() : image;
    }
}
