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
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.text.AttributedString;

/**
 * @test
 * @summary On Skia's paragraph route the glyphs of a run that its font lacks (CoreText's fallback) are rasterized for
 *          that run's text colour, as the run's own glyphs are and as Skia's text path draws them (the per-luminance
 *          CoreGraphics masks of blend=system): the paragraph took one fallback font manager, made for the first
 *          run's colour, so a fallback glyph of a run in another colour got the first run's luminance class. Two
 *          paragraphs that differ only in the first run's colour must draw the second run's fallback glyph alike.
 * @requires os.family == "mac"
 * @key headful
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.skia.raster=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.paragraphs=skia ParagraphFallbackLuminanceTest
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.skia.raster=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.layout=skia ParagraphFallbackLuminanceTest
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.paragraphs=skia ParagraphFallbackLuminanceTest
 */
public class ParagraphFallbackLuminanceTest {
    static final String TEXT = "Ab 中文";   // Helvetica Neue lacks the last two
    static final int SPLIT = 3, W = 160, H = 40;
    static final Color GRAY = new Color(150, 150, 150);

    public static void main(String[] args) throws Exception {
        Font font = new Font("Helvetica Neue", Font.PLAIN, 20);
        long before = paragraphs();
        BufferedImage black = paint(font, Color.BLACK), gray = paint(font, GRAY);
        if (paragraphs() - before < 2) throw new RuntimeException("Skia's paragraph route drew no paragraph: the test does not reach it");
        int left = (int) Math.ceil(5 + font.getStringBounds(TEXT.substring(0, SPLIT), black.createGraphics().getFontRenderContext()).getWidth()) + 1;
        int differing = 0, maxDelta = 0;
        for (int y = 0; y < H; y++) for (int x = left; x < W; x++) {
            int delta = Math.abs((black.getRGB(x, y) & 0xff) - (gray.getRGB(x, y) & 0xff));
            if (delta > 0) differing++;
            maxDelta = Math.max(maxDelta, delta);
        }
        System.out.println("the second run's pixels from x " + left + ": " + differing + " differ, by up to " + maxDelta + " levels");
        if (differing > 0) {
            throw new RuntimeException("the gray run's fallback glyphs depend on the first run's colour: " + differing
                    + " pixels differ by up to " + maxDelta + " levels");
        }
    }

    /** "Ab " in the first colour, then "中文" in gray, on white: into an image, or on the GPU pipe (-Dsun.java2d.skia). */
    static BufferedImage paint(Font font, Color first) {
        if (Boolean.getBoolean("sun.java2d.skia")) {
            GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
            VolatileImage image = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
            BufferedImage snapshot;
            do {
                image.validate(gc);
                paint(image.createGraphics(), font, first);
                snapshot = image.getSnapshot();
            } while (image.contentsLost());
            return snapshot;
        }
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        paint(image.createGraphics(), font, first);
        return image;
    }

    static void paint(Graphics2D g, Font font, Color first) {
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        AttributedString text = new AttributedString(TEXT);
        text.addAttribute(TextAttribute.FONT, font);
        text.addAttribute(TextAttribute.FOREGROUND, first, 0, SPLIT);
        text.addAttribute(TextAttribute.FOREGROUND, GRAY, SPLIT, TEXT.length());
        g.drawString(text.getIterator(), 5, 28);
        g.dispose();
    }

    static long paragraphs() throws Exception {
        return (Long) Class.forName("sun.java2d.skia.SKEnvironment").getMethod("paragraphsBySkia").invoke(null);
    }
}
