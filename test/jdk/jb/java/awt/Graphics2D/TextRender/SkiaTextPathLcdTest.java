/*
 * Copyright 2000-2026 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
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
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.lang.reflect.Method;

/**
 * @test
 * @summary LCD-hinted text drawn through Skia's text path on the GPU pipe keeps its
 *          sub-pixel colour fringes, in the loops' order, where the platform renders
 *          sub-pixel masks (not macOS). Graphite renders LCD text from A565 masks, but
 *          Skia only asks the scaler for them when the surface declares a pixel geometry:
 *          a window surface wrapped without surface properties (unknown geometry) had
 *          every LCD request downgraded to grayscale — the IDE's UI text under the full
 *          Skia text stack lost its ClearType on Windows the day composite strikes joined
 *          the path. Nothing to test where the Skia backend is unavailable.
 * @key headful
 * @requires (os.family == "mac" | os.family == "windows")
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler -Dsun.java2d.skia.text=skia SkiaTextPathLcdTest
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler -Dsun.java2d.skia.text=paragraph SkiaTextPathLcdTest
 */
public class SkiaTextPathLcdTest {
    static final String TEXT = "Hamburgefonts Quick fox jumps 1234567890";
    static final int W = 400, H = 40;
    static final boolean MAC = System.getProperty("os.name", "").startsWith("Mac");

    public static void main(String[] args) throws Exception {
        if (MAC) {
            System.out.println("macOS renders no sub-pixel masks: nothing to test");
            return;
        }
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        if (!gc.getClass().getName().contains("SK")) {
            throw new RuntimeException("not a Skia configuration: " + gc.getClass().getName());
        }
        Font uiFont = new Font(Font.DIALOG, Font.PLAIN, 13);   // a composite strike, as every Swing UI font on Windows
        check(gc, uiFont, "logical font");
        Font fileFont = Font.createFont(Font.TRUETYPE_FONT, new java.io.File(System.getProperty("java.home"), "lib/fonts/Inter-Regular.otf")).deriveFont(13f);
        check(gc, fileFont, "file font");
        System.out.println("PASSED");
    }

    static long glyphsDrawnBySkia() throws Exception {
        Method m = Class.forName("sun.java2d.skia.SKEnvironment").getMethod("glyphsDrawnBySkia");
        return ((Number) m.invoke(null)).longValue();
    }

    /** The loops' LCD rendering (the same scaler's glyph images) against the GPU pipe's through Skia's text path. */
    static void check(GraphicsConfiguration gc, Font font, String what) throws Exception {
        BufferedImage reference = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        paint(reference.createGraphics(), font);
        long before = glyphsDrawnBySkia();
        BufferedImage skia;
        VolatileImage vi = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
        do {
            vi.validate(gc);
            paint(vi.createGraphics(), font);
            skia = vi.getSnapshot();
        } while (vi.contentsLost());
        long drawn = glyphsDrawnBySkia() - before;
        int fringes = 0, referenceFringes = 0;
        long agreement = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int s = skia.getRGB(x, y), r = reference.getRGB(x, y);
            int ds = ((s >> 16) & 255) - (s & 255), dr = ((r >> 16) & 255) - (r & 255);
            if (ds != 0) fringes++;
            if (dr != 0) referenceFringes++;
            agreement += (long) ds * dr;
        }
        System.out.printf("%s: %d glyphs by Skia's text path; fringe pixels (R != B) Skia %d, loops %d; order agreement %d%n", what, drawn, fringes, referenceFringes, agreement);
        if (drawn < TEXT.replace(" ", "").length()) {
            throw new RuntimeException(what + ": Skia's text path drew " + drawn + " glyphs, expected at least " + TEXT.replace(" ", "").length());
        }
        if (referenceFringes < 100) {
            throw new RuntimeException(what + ": the loops drew no LCD text either (" + referenceFringes + " fringe pixels) — the test's premise fails on this machine");
        }
        if (fringes < 100) {
            throw new RuntimeException(what + ": LCD-hinted text through Skia's text path came out grayscale (" + fringes + " fringe pixels; the loops: " + referenceFringes + ")");
        }
        if (agreement <= 0) {
            throw new RuntimeException(what + ": the sub-pixel order is reversed against the loops (agreement " + agreement + ")");
        }
    }

    static void paint(Graphics2D g, Font font) {
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setColor(Color.BLACK);
        g.setFont(font);
        g.drawString(TEXT, 4, 26);
        g.dispose();
    }
}
