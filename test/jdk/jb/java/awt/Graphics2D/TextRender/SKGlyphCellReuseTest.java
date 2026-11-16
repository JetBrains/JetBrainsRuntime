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
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.lang.reflect.Field;

import sun.font.CompositeFont;
import sun.font.Font2D;
import sun.font.FontStrike;
import sun.font.FontUtilities;
import sun.java2d.DisposerRecord;

/*
 * @test
 * @key headful
 * @summary Regression test for JBR-10654: a glyph cache cell freed by the disposal of its strike is not
 *          reused while a glyph of the current recording still samples it.
 * @requires os.family == "windows"
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia java.desktop/sun.font:+open
 * @library ../ /test/lib
 * @build PipelineComparison
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.glyphCacheTexels=64 -Dsun.java2d.uiScale=1 SKGlyphCellReuseTest
 */

/*
 * The backend holds a recording across flushes that present nothing and keeps the glyph batches open
 * across glyph lists. A strike disposed meanwhile (the Disposer runs the disposal on the flusher, through
 * the render queue) frees its cells without a flush, and the next glyph list that needs a cell of a full
 * atlas may take such a cell at once: the batch that still holds the earlier list's glyph for it then
 * draws the new glyph in its place. With a 64-texel configuration the 16-pixel bucket's atlas has four
 * cells, so the walk that picks a cell is known: a frame fills the atlas (five glyphs, one eviction),
 * the next draws two glyphs of a second strike into it, and the frame under test draws those two again,
 * disposes their strike as the Disposer would, and draws three glyphs of a third strike: two take the
 * idle cells, the third reaches the freed ones. The snapshot must show the second strike's glyphs as the
 * software loops draw them.
 */

private static final int WIDTH = 120;
private static final int HEIGHT = 60;

/** Glyph images of this size stay within the 16-pixel bucket (SK_GLYPH_BUCKET_CELLS). */
private static final int FONT_SIZE = 10;

/** Five glyphs fill the four cells of the bucket and set it full. */
private static final String FILLER = "abcde";
/** The glyphs whose strike is disposed while they are pending. */
private static final String DISPOSED = "xy";
/** Three glyphs after the disposal: two for the idle cells, one that reaches the freed ones. */
private static final String AFTER = "pqr";

private static final int LEFT = 10;
private static final int FILLER_Y = 20;
private static final int DISPOSED_Y = 40;
private static final int AFTER_Y = 55;

/** Grayscale antialiased text of the backend against the software loops, as the glyph atlas tests allow. */
private static final int TOLERANCE = 32;

// three strikes: different families at one size
private final Font filler = new Font(Font.SANS_SERIF, Font.PLAIN, FONT_SIZE);
private final Font disposed = new Font(Font.SERIF, Font.PLAIN, FONT_SIZE);
private final Font after = new Font(Font.MONOSPACED, Font.PLAIN, FONT_SIZE);

void main() throws Exception {
    GraphicsConfiguration graphicsConfig = PipelineComparison.defaultConfiguration();
    PipelineComparison.requireSkiaSurfaces(graphicsConfig);
    // the reference first: the disposed strike must not be drawn with after its disposal
    BufferedImage reference = PipelineComparison.softwareReference(WIDTH, HEIGHT, this::paintFrameUnderTest);
    VolatileImage image = graphicsConfig.createCompatibleVolatileImage(WIDTH, HEIGHT, Transparency.OPAQUE);
    image.validate(graphicsConfig);
    // the atlas filled, then the two glyphs added to it; each snapshot ends the recording, leaving every cell idle
    PipelineComparison.rendered(image, graphics -> paintText(graphics, filler, FILLER, FILLER_Y));
    PipelineComparison.rendered(image, graphics -> paintText(graphics, disposed, DISPOSED, DISPOSED_Y));
    Graphics2D graphics = image.createGraphics();
    try {
        paintText(graphics, disposed, DISPOSED, DISPOSED_Y);
        disposeStrike(disposed, graphics);
        drawText(graphics, after, AFTER, AFTER_Y);
    } finally {
        graphics.dispose();
    }
    BufferedImage actual = image.getSnapshot();
    if (image.contentsLost()) {
        throw new RuntimeException("Volatile image contents were lost");
    }
    PipelineComparison.Difference difference = PipelineComparison.compare(reference, actual, TOLERANCE);
    System.out.println(difference.missingInk() + " pixels of ink missing, " + difference.beyond()
            + " pixels more than " + TOLERANCE + " levels off, max " + difference.max());
    if (difference.missingInk() > 0 || difference.beyond() > 0) {
        PipelineComparison.printDifferenceMap(reference, actual, TOLERANCE);
        throw new RuntimeException("the glyphs pending when their strike was disposed differ from the software loops: "
                + difference.missingInk() + " pixels of ink missing, " + difference.beyond() + " pixels more than "
                + TOLERANCE + " levels off");
    }
    System.out.println("PASSED");
}

/** What the frame under test shows: the disposed strike's glyphs and the three drawn after the disposal. */
private void paintFrameUnderTest(Graphics2D graphics) {
    paintText(graphics, disposed, DISPOSED, DISPOSED_Y);
    drawText(graphics, after, AFTER, AFTER_Y);
}

/** A white frame with one string. */
private void paintText(Graphics2D graphics, Font font, String text, int y) {
    graphics.setColor(Color.WHITE);
    graphics.fillRect(0, 0, WIDTH, HEIGHT);
    drawText(graphics, font, text, y);
}

private void drawText(Graphics2D graphics, Font font, String text, int y) {
    graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    graphics.setColor(Color.BLACK);
    graphics.setFont(font);
    graphics.drawString(text, LEFT, y);
}

/**
 * Disposes the strike whose glyph images {@code font} draws with on {@code graphics}, as the Disposer does once
 * the strike is collected: through the render queue, on the flusher, in the middle of the held recording. A
 * logical font's strike is a composite that holds no images; its first slot's physical strike holds the ones
 * of these ASCII glyphs. Nothing may draw with that strike afterward.
 */
private static void disposeStrike(Font font, Graphics2D graphics) throws ReflectiveOperationException {
    Font2D font2D = FontUtilities.getFont2D(font);
    if (font2D instanceof CompositeFont composite) {
        font2D = composite.getSlotFont(0);
    }
    FontStrike strike = font2D.getStrike(font, graphics.getFontRenderContext());
    Field disposerField = FontStrike.class.getDeclaredField("disposer");
    disposerField.setAccessible(true);
    ((DisposerRecord) disposerField.get(strike)).dispose();   // its FontStrikeDisposer, a package-private class
}
