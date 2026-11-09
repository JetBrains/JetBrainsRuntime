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
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.font.GlyphVector;
import java.awt.font.TextAttribute;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.text.AttributedString;
import java.util.function.Consumer;

/**
 * @test
 * @summary Text above the outline threshold (100 px) on the Skia scaler goes through Skia's
 *          text path, which draws large glyphs itself (as paths beyond its glyph atlas),
 *          instead of Java's outlines filled by the shape pipe: drawString, drawChars and
 *          drawGlyphVector are pixel-identical to one another, the text path's counter moves,
 *          and the drawing lies where Java's filled outlines lie (ink bounds within two pixels,
 *          the ink within 12 %: below Skia's atlas limit the masks are the
 *          platform's, smoothed); GlyphVector.getPixelBounds still holds the ink (the
 *          strike's records carry advances only above the threshold). Sizes 120, 200 and 400 px,
 *          a rotation, and a scaled graphics.
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.uiScale=1 -Djava.awt.headless=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.raster=true -Dsun.java2d.skia.text.strike=skia SkiaLargeTextTest raster
 */

/*
 * @test id=gpu
 * @summary The same on the Skia GPU pipe, and through Skia's shaper (strings) and paragraph engine
 *          (attributed strings), which now take large text too: pixel-identical to the text path.
 * @requires (os.family == "mac" | os.family == "windows")
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.strike=skia SkiaLargeTextTest gpu
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.layout=skia SkiaLargeTextTest layout
 */
public class SkiaLargeTextTest {
    static final String TEXT = "Hag&";
    static final int W = 1000, H = 560;
    static String mode;

    record Case(String name, Font font, AffineTransform transform, float x, float y, boolean fm) {}

    public static void main(String[] args) throws Exception {
        mode = args[0];
        if (!mode.equals("raster") && !PipelineComparison.pipelineActive("skia", GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration())) {
            return;
        }
        Font font = new Font(Font.SERIF, Font.PLAIN, 12);
        Case[] cases = {
            new Case("120 px", font.deriveFont(120f), new AffineTransform(), 20, 200, true),
            new Case("120 px, whole-pixel advances", font.deriveFont(120f), new AffineTransform(), 20, 200, false),
            new Case("200 px", font.deriveFont(200f), new AffineTransform(), 20, 300, true),
            new Case("400 px (beyond Skia's glyph atlas: paths)", font.deriveFont(400f), new AffineTransform(), 10, 420, true),
            new Case("150 px rotated 0.3", font.deriveFont(150f), AffineTransform.getRotateInstance(0.3, 300, 280), 60, 300, true),
            new Case("60 pt at a 2.5 scale", font.deriveFont(60f), AffineTransform.getScaleInstance(2.5, 2.5), 8, 120, true),
        };
        StringBuilder failures = new StringBuilder();
        for (Case c : cases) {
            Consumer<Graphics2D> glyphVector = g -> g.drawGlyphVector(c.font.createGlyphVector(g.getFontRenderContext(), TEXT), c.x, c.y);
            BufferedImage outlines = render(c, g -> g.fill(c.font.createGlyphVector(g.getFontRenderContext(), TEXT).getOutline(c.x, c.y)), true);
            long before = glyphsBySkia();
            BufferedImage text = render(c, glyphVector, false);
            long drawn = glyphsBySkia() - before;
            Rectangle ink = inkBounds(text), reference = inkBounds(outlines);
            double inkRatio = ink(text) / Math.max(1, ink(outlines));
            Rectangle pixelBounds = pixelBounds(c);
            System.out.println(c.name + ": " + drawn + " glyphs by Skia, ink " + ink + " against the outlines' " + reference
                    + String.format(", ink ratio %.4f", inkRatio) + ", pixel bounds " + pixelBounds);
            if (drawn < TEXT.length()) {
                failures.append(c.name).append(": not drawn by Skia's text path; ");
            }
            if (Math.abs(ink.x - reference.x) > 2 || Math.abs(ink.y - reference.y) > 2 || Math.abs(ink.x + ink.width - reference.x - reference.width) > 2
                    || Math.abs(ink.y + ink.height - reference.y - reference.height) > 2) {
                failures.append(c.name).append(": ink ").append(ink).append(" not within 2 px of the outlines' ").append(reference).append("; ");
            }
            if (Math.abs(inkRatio - 1) > INK_TOLERANCE) {
                failures.append(c.name).append(String.format(": ink ratio %.4f; ", inkRatio));
            }
            Rectangle grown = new Rectangle(pixelBounds.x - 1, pixelBounds.y - 1, pixelBounds.width + 2, pixelBounds.height + 2);
            if (!grown.contains(ink)) {
                failures.append(c.name).append(": GlyphVector.getPixelBounds ").append(pixelBounds).append(" does not hold the ink ").append(ink).append("; ");
            }
            // the routes alike: the strings' entries, and in the layout mode Skia's shaper and paragraph engine
            int sx = (int) c.x, sy = (int) c.y;
            identical(failures, c, "drawString", text, render(c, g -> g.drawString(TEXT, c.x, c.y), false));
            identical(failures, c, "drawChars", text, render(c, g -> g.drawChars(TEXT.toCharArray(), 0, TEXT.length(), sx, sy), false));
            if (mode.equals("layout")) {
                AttributedString as = new AttributedString(TEXT);
                as.addAttribute(TextAttribute.FONT, c.font, 0, 2);
                as.addAttribute(TextAttribute.FONT, c.font, 2, TEXT.length());
                long paragraphs = paragraphsBySkia();
                identical(failures, c, "drawString(iterator)", text, render(c, g -> g.drawString(as.getIterator(), c.x, c.y), false));
                if (paragraphsBySkia() == paragraphs) {
                    failures.append(c.name).append(": the attributed string not by Skia's paragraph engine; ");
                }
            }
        }
        if (failures.length() > 0) {
            throw new RuntimeException(failures.toString());
        }
        System.out.println("PASSED");
    }

    /**
     * Below Skia's atlas limit its text path draws the platform's masks (CoreText's smoothing: measured 4-10 % more
     * ink than Java's outline fill, a pixel or two wider); beyond it, paths (0.03 % off).
     */
    static final double INK_TOLERANCE = 0.12;

    static void identical(StringBuilder failures, Case c, String what, BufferedImage expected, BufferedImage actual) {
        int n = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) if (expected.getRGB(x, y) != actual.getRGB(x, y)) n++;
        System.out.println("  " + what + ": " + n + " pixels apart from drawGlyphVector");
        if (n != 0) {
            failures.append(c.name).append(", ").append(what).append(": ").append(n).append(" pixels apart from drawGlyphVector; ");
        }
    }

    static BufferedImage render(Case c, Consumer<Graphics2D> drawing, boolean shapeAntialiasing) {
        Consumer<Graphics2D> scene = g -> {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, W, H);
            g.setColor(Color.BLACK);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, c.fm ? RenderingHints.VALUE_FRACTIONALMETRICS_ON : RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
            if (shapeAntialiasing) {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);   // as OutlineTextRenderer fills
            }
            g.transform(c.transform);
            g.setFont(c.font);
            drawing.accept(g);
        };
        if (mode.equals("raster")) {
            BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB_PRE);
            Graphics2D g = image.createGraphics();
            scene.accept(g);
            g.dispose();
            return image;
        }
        VolatileImage vi = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().createCompatibleVolatileImage(W, H);
        Graphics2D g = vi.createGraphics();
        scene.accept(g);
        g.dispose();
        return vi.getSnapshot();
    }

    static Rectangle pixelBounds(Case c) {
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = probe.createGraphics();
        g.transform(c.transform);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, c.fm ? RenderingHints.VALUE_FRACTIONALMETRICS_ON : RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        GlyphVector gv = c.font.createGlyphVector(g.getFontRenderContext(), TEXT);
        // in device space under the render context's transform, which carries no translation
        Rectangle device = gv.getPixelBounds(g.getFontRenderContext(), c.x, c.y);
        g.dispose();
        device.translate((int) Math.round(c.transform.getTranslateX()), (int) Math.round(c.transform.getTranslateY()));
        return device;
    }

    static Rectangle inkBounds(BufferedImage image) {
        Rectangle r = null;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            if ((image.getRGB(x, y) & 0xffffff) != 0xffffff) {
                if (r == null) r = new Rectangle(x, y, 1, 1); else r.add(new Rectangle(x, y, 1, 1));
            }
        }
        return r != null ? r : new Rectangle();
    }

    static double ink(BufferedImage image) {
        double sum = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) sum += 255 - (image.getRGB(x, y) & 0xff);
        return sum;
    }

    static long glyphsBySkia() throws Exception {
        String counter = mode.equals("raster") ? "rasterGlyphsBySkia" : "glyphsDrawnBySkia";
        return (long) Class.forName("sun.java2d.skia.SKEnvironment").getMethod(counter).invoke(null);
    }

    static long paragraphsBySkia() throws Exception {
        return (long) Class.forName("sun.java2d.skia.SKEnvironment").getMethod("paragraphsBySkia").invoke(null);
    }
}
