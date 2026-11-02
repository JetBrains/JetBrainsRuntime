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
import java.awt.font.FontRenderContext;
import java.awt.font.LineMetrics;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.lang.reflect.Method;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.List;

import sun.font.FontStrikeDesc;
import sun.font.FontUtilities;
import sun.font.StrikeMetrics;

/**
 * @test
 * @key headful
 * @summary Under the system text decorations (sun.java2d.skia.text.decorations=system, the
 *          default) the line metrics describe what is drawn: Font.getLineMetrics reports the
 *          font's own underline position and thickness (as the scaler provides them — Java's
 *          Font2D.getStyleMetrics values are nominal, size / 18 and size / 12 for every font),
 *          and a TextLayout's bounds reach the underline's device rows, so a component that
 *          lays out or clips by the bounds neither cuts the underline nor leaves more than a
 *          row of slack under it (2026-11-02).
 * @requires (os.family == "mac" | os.family == "windows")
 * @modules java.desktop/sun.font:+open
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia TextDecorationMetricsTest
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.paragraphs=skia TextDecorationMetricsTest
 */
public class TextDecorationMetricsTest {
    static final int W = 400, H = 60, BASELINE = 30;
    static final String TEXT = "jumpy gyp Quick";
    static final String FLAT = "minimum sum";   // no descenders: below the baseline only the underline

    public static void main(String[] args) throws Exception {
        boolean mac = System.getProperty("os.name", "").toLowerCase().contains("mac");
        Font font = new Font(mac ? "Helvetica Neue" : "Segoe UI", Font.PLAIN, 13);
        FontRenderContext frc = new FontRenderContext(null, RenderingHints.VALUE_TEXT_ANTIALIAS_ON, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        List<String> errors = new ArrayList<>();

        // 1. the line metrics are the font's own
        int aa = FontStrikeDesc.getAAHintIntVal(frc.getAntiAliasingHint(), FontUtilities.getFont2D(font), font.getSize());
        int fm = FontStrikeDesc.getFMHintIntVal(frc.getFractionalMetricsHint());
        Object strike = FontUtilities.getFont2D(font).getStrike(font, new AffineTransform(), aa, fm);
        Method getFontMetrics = Class.forName("sun.font.FontStrike").getDeclaredMethod("getFontMetrics");
        getFontMetrics.setAccessible(true);
        StrikeMetrics sm = (StrikeMetrics) getFontMetrics.invoke(strike);
        if (Float.isNaN(sm.underlineOffset) || !(sm.underlineThickness > 0)) {
            throw new RuntimeException("the Skia scaler reports no underline metrics for " + font);
        }
        LineMetrics lm = font.getLineMetrics(TEXT, frc);
        float expectedOffset = sm.underlineOffset + sm.underlineThickness / 2;   // Java's offset is the stroke's centre
        System.out.printf("strike: underline top %.3f thickness %.3f; line metrics: offset %.3f thickness %.3f (nominal would be %.3f / %.3f)%n",
                          sm.underlineOffset, sm.underlineThickness, lm.getUnderlineOffset(), lm.getUnderlineThickness(), font.getSize2D() / 18, font.getSize2D() / 12);
        if (Math.abs(lm.getUnderlineOffset() - expectedOffset) > 1e-3 || Math.abs(lm.getUnderlineThickness() - sm.underlineThickness) > 1e-3) {
            errors.add("Font.getLineMetrics reports underline offset " + lm.getUnderlineOffset() + " thickness " + lm.getUnderlineThickness()
                       + ", the font's own are " + expectedOffset + " and " + sm.underlineThickness);
        }

        // 2. the layout's bounds reach the drawn underline (a text without descenders: the lowest ink is the underline)
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        AttributedString as = new AttributedString(FLAT);
        as.addAttribute(TextAttribute.FONT, font);
        as.addAttribute(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON);
        BufferedImage img = render(gc, as);
        int lowestInkRow = -1;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) if (ink(img, x, y) > 0.5) lowestInkRow = y;
        if (lowestInkRow <= BASELINE) {
            throw new RuntimeException("no underline below the baseline (lowest ink row " + lowestInkRow + ")");
        }
        TextLayout layout = new TextLayout(as.getIterator(), frc);
        Rectangle2D bounds = layout.getBounds();
        double bottom = BASELINE + bounds.getMaxY();
        Rectangle2D pixelBounds = layout.getPixelBounds(frc, 10, BASELINE);
        System.out.printf("lowest ink row %d; layout bounds bottom %.3f, pixel bounds bottom %.1f%n", lowestInkRow, bottom, pixelBounds.getMaxY());
        if (bottom < lowestInkRow + 1 - 1e-3) {
            errors.add("the layout bounds end at " + bottom + " but the underline occupies row " + lowestInkRow + " (to " + (lowestInkRow + 1) + ")");
        }
        if (bottom > lowestInkRow + 2) {
            errors.add("the layout bounds end at " + bottom + ", more than a row under the underline's row " + lowestInkRow);
        }
        if (pixelBounds.getMaxY() < lowestInkRow + 1) {
            errors.add("the pixel bounds end at " + pixelBounds.getMaxY() + " but the underline occupies row " + lowestInkRow);
        }
        if (!errors.isEmpty()) {
            throw new RuntimeException(String.join("; ", errors));
        }
        System.out.println("PASSED");
    }

    static BufferedImage render(GraphicsConfiguration gc, AttributedString as) {
        VolatileImage vi = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
        BufferedImage img;
        do {
            vi.validate(gc);
            Graphics2D g = vi.createGraphics();
            g.setColor(Color.WHITE); g.fillRect(0, 0, W, H);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setColor(Color.BLACK);
            g.drawString(as.getIterator(), 10, BASELINE);
            g.dispose();
            img = vi.getSnapshot();
        } while (vi.contentsLost());
        return img;
    }

    static double ink(BufferedImage img, int x, int y) {
        int p = img.getRGB(x, y);
        return (255 - Math.min((p >> 16) & 0xff, Math.min((p >> 8) & 0xff, p & 0xff))) / 255.0;
    }
}
