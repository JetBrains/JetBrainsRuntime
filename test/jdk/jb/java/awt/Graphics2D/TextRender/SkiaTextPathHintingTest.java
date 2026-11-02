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
import java.io.File;
import java.lang.reflect.Method;

/**
 * @test
 * @summary On Windows, text drawn through Skia's text path is grid-fitted the way
 *          the platform grid-fits it: DirectWrite decides per font and size
 *          (gasp, GetRecommendedRenderingMode, the default grid-fit mode), and
 *          Skia's DirectWrite host asks it when the scaler requests normal hinting.
 *          With no hinting requested the host forces grid fitting off, and a 13 px
 *          JetBrains Mono lowercase came out one row shorter than through the
 *          platform's glyph images (the IDE's editor, 2026-10-29). The test compares
 *          the vertical ink extent of an ascender-free lowercase word between the
 *          glyph-image path (the platform mask route) and Skia's text path on the
 *          GPU pipe, for LCD text — where the path's masks are Skia's DirectWrite
 *          host's — and, as a control, for grayscale text under the platform mask
 *          route, whose masks are the platform's own on both paths. Nothing to test
 *          where the Skia backend is unavailable, and nothing on macOS (CoreText
 *          hints nothing).
 * @key headful
 * @requires (os.family == "mac" | os.family == "windows")
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler -Dsun.java2d.skia.text.gray=platform -Dsun.java2d.skia.text=skia SkiaTextPathHintingTest
 */
public class SkiaTextPathHintingTest {
    static final String TEXT = "xoxzvwmnsceru xoxzvwmnsceru";   // no ascenders, no descenders: the x-height band alone
    static final int W = 400, H = 40;
    static Object hint;

    public static void main(String[] args) throws Exception {
        if (System.getProperty("os.name", "").startsWith("Mac")) {
            System.out.println("CoreText hints nothing: nothing to test");
            return;
        }
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        if (!gc.getClass().getName().contains("SK")) {
            throw new RuntimeException("not a Skia configuration: " + gc.getClass().getName());
        }
        Font mono = Font.createFont(Font.TRUETYPE_FONT, new File(System.getProperty("java.home"), "lib/fonts/JetBrainsMono-Regular.ttf")).deriveFont(13f);
        Font inter = Font.createFont(Font.TRUETYPE_FONT, new File(System.getProperty("java.home"), "lib/fonts/Inter-Regular.otf")).deriveFont(13f);
        for (Object aa : new Object[] {RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB, RenderingHints.VALUE_TEXT_ANTIALIAS_ON}) {
            hint = aa;
            String how = aa == RenderingHints.VALUE_TEXT_ANTIALIAS_ON ? "grayscale" : "LCD";
            check(gc, mono, "JetBrains Mono 13, " + how);
            check(gc, inter, "Inter 13, " + how);
        }
        System.out.println("PASSED");
    }

    static long glyphsDrawnBySkia() throws Exception {
        Method m = Class.forName("sun.java2d.skia.SKEnvironment").getMethod("glyphsDrawnBySkia");
        return ((Number) m.invoke(null)).longValue();
    }

    static void check(GraphicsConfiguration gc, Font font, String what) throws Exception {
        BufferedImage reference = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);   // the loops with the platform route's glyph images
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
        int[] r = extent(reference), s = extent(skia);
        System.out.printf("%s: %d glyphs by Skia's text path; x-height band rows: glyph images %d..%d (%d rows, top edge %d), Skia's text path %d..%d (%d rows, top edge %d)%n",
                what, drawn, r[0], r[1], r[1] - r[0] + 1, r[2], s[0], s[1], s[1] - s[0] + 1, s[2]);
        if (drawn < TEXT.replace(" ", "").length()) {
            throw new RuntimeException(what + ": Skia's text path drew " + drawn + " glyphs, expected at least " + TEXT.replace(" ", "").length());
        }
        if (s[2] != r[2] || s[1] != r[1]) {
            throw new RuntimeException(what + ": the x-height band through Skia's text path spans rows " + s[0] + ".." + s[1] + " (top edge " + s[2]
                    + ") where the platform's glyph images span " + r[0] + ".." + r[1] + " (top edge " + r[2] + "): the platform's grid fitting is not applied");
        }
    }

    /** First and last row carrying a tenth of the darkest row's ink, and the first row carrying half of it (the fitted top edge). */
    static int[] extent(BufferedImage im) {
        long[] rows = new long[H];
        long max = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) { int c = im.getRGB(x, y); rows[y] += 765 - ((c >> 16) & 255) - ((c >> 8) & 255) - (c & 255); }
            max = Math.max(max, rows[y]);
        }
        int first = -1, last = -1, edge = -1;
        for (int y = 0; y < H; y++) {
            if (rows[y] > max / 10) { if (first < 0) first = y; last = y; }
            if (edge < 0 && rows[y] > max / 2) edge = y;
        }
        return new int[] {first, last, edge};
    }

    static void paint(Graphics2D g, Font font) {
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, hint);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setColor(Color.BLACK);
        g.setFont(font);
        g.drawString(TEXT, 4, 26);
        g.dispose();
    }
}
