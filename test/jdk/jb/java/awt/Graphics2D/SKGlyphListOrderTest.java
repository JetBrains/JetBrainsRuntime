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
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.image.BufferedImage;
import java.util.List;

import jtreg.SkippedException;

/*
 * @test
 * @key headful
 * @summary Regression test for JBR-10654: two glyph lists of different sizes drawn one over the
 *          other on the Skia pipeline come out in Java's order.
 * @requires os.family == "windows"
 * @library /test/lib
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.uiScale=1 SKGlyphListOrderTest
 */

/*
 * Two glyph lists of different sizes and colors, one over the other with nothing drawn between
 * them: the glyph caches' size buckets (16, 32, 64, 128 pixels) keep a batch each, and a batch
 * of the first list's bucket must not be drawn after the second list's glyphs of another bucket.
 * Large blue text, then small red text across it (grayscale and LCD), then the other way round;
 * compared with the software loops. The fourth band: a list whose glyphs span two buckets after a
 * color change merges into the batch of the bucket it begins with (SKQuadBatch merges colors) and
 * begins the other bucket fresh, which draws earlier in bucket order; blue capitals of the larger
 * bucket, then red text across them that begins with such a capital and goes on in the smaller.
 */

private static final int WIDTH = 400;
private static final int HEIGHT = 320;
/** Grayscale text: the glyph rasterizers' rounding. */
private static final int TOLERANCE = 8;
/** LCD text without a destination read (the twoPass mode) is up to about 15 levels off at glyph edges (SKTextPipe.cpp). */
private static final int LCD_TOLERANCE = 16;

/** The sheet's three bands, one case each. */
private static final int BAND_HEIGHT = 80;
/** Glyph images over 32 pixels: the 64-pixel bucket. */
private static final int LARGE_POINT_SIZE = 64;
/** The 16-pixel bucket. */
private static final int SMALL_POINT_SIZE = 12;

// the small lines' pitch, and how many of them cross the large text
private static final int SMALL_LINE_PITCH = 12;
private static final int SMALL_LINES = 4;

// the large text's baseline within its band, and the first small line's
private static final int LARGE_BASELINE = 66;
private static final int FIRST_SMALL_BASELINE = 24;
private static final int TEXT_X = 10;
private static final String LARGE_TEXT = "MMMM";
private static final String SMALL_TEXT = "W".repeat(40);

// the fourth band: capitals of the 32-pixel bucket and lowercase of the 16-pixel bucket in one font; its point
// size is the first that separates them (mixedPointSize), a glyph image a pixel or two larger than its ink
private static final int MIXED_BASELINE = 56;
private static final int SMALLEST_MIXED_POINT_SIZE = 18;
private static final int LARGEST_MIXED_POINT_SIZE = 48;
private static final int LARGER_BUCKET_MIN_INK = 18;   // ink at least this tall: an image over 16 pixels
private static final int SMALLER_BUCKET_MAX_INK = 14;  // ink at most this large: an image within 16 pixels
private static final String MIXED_CAPITALS = "MMMM";
private static final String MIXED_TEXT = "M" + "x".repeat(12);

// a pixel is (nearly) pure red or blue when that channel is above the high and the other below the low threshold
private static final int STRONG_CHANNEL = 200;
private static final int WEAK_CHANNEL = 60;

private record Band(String name, Rectangle region, int tolerance) {
    Band(String name, int bandIndex, int tolerance) {
        this(name, new Rectangle(0, bandIndex * BAND_HEIGHT, WIDTH, BAND_HEIGHT), tolerance);
    }
}

private static final List<Band> BANDS = List.of(
        new Band("grayscale, large blue then small red", 0, TOLERANCE),
        new Band("LCD, large blue then small red", 1, LCD_TOLERANCE),
        new Band("grayscale, small red then large blue", 2, TOLERANCE),
        new Band("grayscale, blue capitals then red across the buckets", 3, TOLERANCE));

private Font mixed;

void main() {
    GraphicsConfiguration graphicsConfig = PipelineComparison.defaultConfiguration();
    PipelineComparison.requireSkiaSurfaces(graphicsConfig);
    mixed = new Font(Font.DIALOG, Font.BOLD, mixedPointSize());
    System.out.println("mixed-bucket font: " + mixed.getSize() + " pt");
    BufferedImage reference = PipelineComparison.softwareReference(WIDTH, HEIGHT, this::paint);
    BufferedImage actual = PipelineComparison.rendered(graphicsConfig, WIDTH, HEIGHT, this::paint);
    StringBuilder failures = new StringBuilder();
    for (Band band : BANDS) {
        PipelineComparison.Difference difference = PipelineComparison.compare(reference, actual, band.region(), band.tolerance());
        int redUnderBlue = redUnderBlue(reference, actual, band.region());
        System.out.println(band.name() + ": max " + difference.max() + " levels, " + difference.beyond() + " pixels beyond "
                + band.tolerance() + ", " + redUnderBlue + " red pixels drawn under blue");
        if (difference.beyond() > 0) {
            failures.append(band.name()).append(" (").append(difference.beyond()).append(" pixels, max ").append(difference.max())
                    .append(" levels, ").append(redUnderBlue).append(" red pixels under blue); ");
        }
    }
    if (!failures.isEmpty()) {
        throw new RuntimeException("glyph lists drawn out of order: " + failures);
    }
    System.out.println("PASSED");
}

/** Pixels where the loops show (nearly) pure red and the rendering (nearly) pure blue: red glyphs drawn under the blue ones. */
private int redUnderBlue(BufferedImage reference, BufferedImage actual, Rectangle region) {
    int redUnderBlue = 0;
    for (int y = region.y; y < region.y + region.height; y++) {
        for (int x = region.x; x < region.x + region.width; x++) {
            int referencePixel = reference.getRGB(x, y);
            int actualPixel = actual.getRGB(x, y);
            if (red(referencePixel) > STRONG_CHANNEL && blue(referencePixel) < WEAK_CHANNEL
                    && blue(actualPixel) > STRONG_CHANNEL && red(actualPixel) < WEAK_CHANNEL) {
                redUnderBlue++;
            }
        }
    }
    return redUnderBlue;
}

private int red(int pixel) {
    return (pixel >> 16) & 0xff;
}

private int blue(int pixel) {
    return pixel & 0xff;
}

private void paint(Graphics2D graphics) {
    graphics.setColor(Color.WHITE);
    graphics.fillRect(0, 0, WIDTH, HEIGHT);
    Font large = new Font(Font.DIALOG, Font.BOLD, LARGE_POINT_SIZE);
    Font small = new Font(Font.DIALOG, Font.BOLD, SMALL_POINT_SIZE);
    // the first two bands: large blue, then small red across it, grayscale and LCD
    List<Object> antialiasingHints = List.of(RenderingHints.VALUE_TEXT_ANTIALIAS_ON, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
    for (int band = 0; band < antialiasingHints.size(); band++) {
        int bandTop = BAND_HEIGHT * band;
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, antialiasingHints.get(band));
        graphics.setFont(large);
        graphics.setColor(Color.BLUE);
        graphics.drawString(LARGE_TEXT, TEXT_X, bandTop + LARGE_BASELINE);
        drawSmallLines(graphics, small, bandTop);
    }
    // the third band: the other order, which the bucket order happens to draw right
    int bandTop = BAND_HEIGHT * 2;
    graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    drawSmallLines(graphics, small, bandTop);
    graphics.setFont(large);
    graphics.setColor(Color.BLUE);
    graphics.drawString(LARGE_TEXT, TEXT_X, bandTop + LARGE_BASELINE);
    // the fourth band: blue capitals, then red text starting with a capital and continuing in lowercase,
    // half a capital to the right so that the lowercase crosses the blue capitals
    bandTop = BAND_HEIGHT * 3;
    graphics.setFont(mixed);
    graphics.setColor(Color.BLUE);
    graphics.drawString(MIXED_CAPITALS, TEXT_X, bandTop + MIXED_BASELINE);
    graphics.setColor(Color.RED);
    graphics.drawString(MIXED_TEXT, TEXT_X + graphics.getFontMetrics().charWidth('M') / 2, bandTop + MIXED_BASELINE);
}

/**
 * The smallest point size of the dialog font at which a capital's ink is over 16 pixels tall and a lowercase
 * letter's within 14, so that one glyph list spans the 32- and the 16-pixel bucket.
 */
private static int mixedPointSize() {
    FontRenderContext context = new FontRenderContext(null, RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
                                                      RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
    for (int pointSize = SMALLEST_MIXED_POINT_SIZE; pointSize <= LARGEST_MIXED_POINT_SIZE; pointSize++) {
        Font font = new Font(Font.DIALOG, Font.BOLD, pointSize);
        Rectangle capital = font.createGlyphVector(context, "M").getPixelBounds(context, 0, 0);
        Rectangle lowercase = font.createGlyphVector(context, "x").getPixelBounds(context, 0, 0);
        if (capital.height >= LARGER_BUCKET_MIN_INK && Math.max(lowercase.width, lowercase.height) <= SMALLER_BUCKET_MAX_INK) {
            return pointSize;
        }
    }
    throw new SkippedException("no point size of the dialog font puts M and x in different glyph cache buckets");
}

private void drawSmallLines(Graphics2D graphics, Font small, int bandTop) {
    graphics.setFont(small);
    graphics.setColor(Color.RED);
    for (int line = 0; line < SMALL_LINES; line++) {
        graphics.drawString(SMALL_TEXT, TEXT_X, bandTop + FIRST_SMALL_BASELINE + SMALL_LINE_PITCH * line);
    }
}
