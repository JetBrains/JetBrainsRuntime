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

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.LinearGradientPaint;
import java.awt.Paint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.TexturePaint;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.text.AttributedString;
import java.util.function.Consumer;

/**
 * @test
 * @summary Text on the Skia GPU pipe under a gradient or texture paint or an AlphaComposite rule
 *          other than SrcOver goes through Skia's text path (it went to the loops' text pipe): the
 *          glyphs drawn with the paint's shader and the composite's blend, drawString, drawChars and
 *          drawGlyphVector pixel-identical, the drawing within limits of the software loops' (the
 *          same Skia-scaler glyphs): ink within 6 %, ink bounds within a pixel, the inked pixels'
 *          mean colour in each half within 12 levels. A run's FOREGROUND under a gradient is the
 *          run's colour, with the composite's extra alpha (as the pipe's colour: premultiplied and
 *          back), within the system underline's gap edges of TextLayout's, under every rule. Src with
 *          a translucent colour is true Src (the loops' MaskFill lerp), not SrcOver as BufferedTextPipe
 *          assumes, for the glyphs and their decorations alike; under Clear a run has no colour.
 * @requires (os.family == "mac" | os.family == "windows")
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.strike=skia SkiaTextPaintTest text
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.layout=skia SkiaTextPaintTest layout
 */

/*
 * @test id=images
 * @summary The Skia GPU pipe's own configuration (Java's glyph images, no Skia text route): text under Src with a
 *          translucent colour and under Clear draws each glyph image as coverage, as the software loops do (the
 *          glyph quads took the image's alpha as the colour's, so Src was drawn as SrcOver, and Clear cleared each
 *          glyph's whole box: ink 0.569 and 2.3 against the loops).
 * @requires (os.family == "mac" | os.family == "windows")
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true SkiaTextPaintTest images
 */
public class SkiaTextPaintTest {
    static final String TEXT = "Hamburgefonts 1234";
    static final int W = 420, H = 80;
    static String mode;

    record Scene(String name, Paint paint, Composite composite) {}

    public static void main(String[] args) throws Exception {
        mode = args[0];
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        if (!PipelineComparison.pipelineActive("skia", gc)) {
            return;
        }
        Font font = new Font(Font.SANS_SERIF, Font.BOLD, 40);
        BufferedImage tile = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) tile.setRGB(x, y, (x + y) % 8 < 4 ? 0x2050c0 : 0xc04020);
        Scene[] scenes = {
            new Scene("GradientPaint", new GradientPaint(0, 0, Color.BLACK, W, 0, new Color(0x2060ff)), AlphaComposite.SrcOver),
            new Scene("LinearGradientPaint", new LinearGradientPaint(0, 0, W, 0, new float[] {0, 0.5f, 1}, new Color[] {Color.RED, Color.GREEN, Color.BLUE}), AlphaComposite.SrcOver),
            new Scene("TexturePaint", new TexturePaint(tile, new Rectangle2D.Double(0, 0, 8, 8)), AlphaComposite.SrcOver),
            new Scene("AlphaComposite.Src, a translucent colour", new Color(0x80, 0x20, 0xa0, 0x90), AlphaComposite.Src),
            new Scene("AlphaComposite.Clear", Color.BLACK, AlphaComposite.Clear),
            new Scene("GradientPaint under SrcOver 0.5", new GradientPaint(0, 0, Color.BLACK, W, 0, new Color(0x2060ff)), AlphaComposite.SrcOver.derive(0.5f)),
        };
        StringBuilder failures = new StringBuilder();
        for (Scene sc : scenes) {
            Consumer<Graphics2D> gv = g -> g.drawGlyphVector(g.getFont().createGlyphVector(g.getFontRenderContext(), TEXT), 10, 55);
            long before = counter("glyphsDrawnBySkia");
            BufferedImage glyphs = PipelineComparison.rendered(gc, W, H, scene(sc, font, gv));
            long drawn = counter("glyphsDrawnBySkia") - before;
            BufferedImage reference = PipelineComparison.softwareReference(W, H, scene(sc, font, gv));
            // translucent Src is the loops' true Src (MaskFill's lerp), not SrcOver (BufferedTextPipe's assumption);
            // Clear stores transparent pixels, which the loops' opaque reference image keeps as black: routes only
            if (mode.equals("images")) {
                // Java's glyph images: no Skia text route to count; Clear within a band (it cleared whole glyph boxes)
                double ratio = ink(glyphs, 0xf0e8d0) / Math.max(1, ink(reference, 0xf0e8d0));
                boolean clear = ((AlphaComposite) sc.composite).getRule() == AlphaComposite.CLEAR;
                System.out.printf("%s: the glyph images, ink ratio %.3f against the loops%n", sc.name, ratio);
                if (clear ? Math.abs(ratio - 1) > 0.25 : Math.abs(ratio - 1) > 0.06) {
                    failures.append(sc.name).append(String.format(": the glyph images' ink ratio %.3f; ", ratio));
                }
                continue;
            }
            compare(failures, sc.name, glyphs, reference, drawn, ((AlphaComposite) sc.composite).getRule() != AlphaComposite.CLEAR);
            identical(failures, sc.name + ", drawString", glyphs, PipelineComparison.rendered(gc, W, H, scene(sc, font, g -> g.drawString(TEXT, 10, 55))));
            identical(failures, sc.name + ", drawChars", glyphs, PipelineComparison.rendered(gc, W, H, scene(sc, font, g -> g.drawChars(TEXT.toCharArray(), 0, TEXT.length(), 10, 55))));
            if (mode.equals("layout")) {
                AttributedString as = new AttributedString(TEXT);
                as.addAttribute(TextAttribute.FONT, font);
                long paragraphs = counter("paragraphsBySkia");
                BufferedImage paragraph = PipelineComparison.rendered(gc, W, H, scene(sc, font, g -> g.drawString(as.getIterator(), 10, 55)));
                if (counter("paragraphsBySkia") == paragraphs) {
                    failures.append(sc.name).append(": the attributed string not by Skia's paragraph engine; ");
                }
                identical(failures, sc.name + ", the paragraph against TextLayout", paragraph,
                        PipelineComparison.rendered(gc, W, H, scene(sc, font, g -> new TextLayout(as.getIterator(), g.getFontRenderContext()).draw(g, 10, 55))));
                // a run's own colour is the run's, not the paint's, with the composite's extra alpha
                AttributedString coloured = new AttributedString(TEXT);
                coloured.addAttribute(TextAttribute.FONT, font);
                coloured.addAttribute(TextAttribute.FOREGROUND, new Color(0xd02020), 0, 6);
                coloured.addAttribute(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON, 4, 12);
                // the underline's antialiased gap edges beside a descender may flip; under Clear the run's colour is none
                // (SunGraphics2D.validateColor), its glyphs black's luminance class
                within(failures, sc.name + ", a FOREGROUND run and an underline, against TextLayout",
                        PipelineComparison.rendered(gc, W, H, scene(sc, font, g -> g.drawString(coloured.getIterator(), 10, 55))),
                        PipelineComparison.rendered(gc, W, H, scene(sc, font, g -> new TextLayout(coloured.getIterator(), g.getFontRenderContext()).draw(g, 10, 55))),
                        UNDERLINE_GAP_EDGES);
            }
        }
        if (failures.length() > 0) {
            throw new RuntimeException(failures.toString());
        }
        System.out.println("PASSED");
    }

    static Consumer<Graphics2D> scene(Scene sc, Font font, Consumer<Graphics2D> text) {
        return g -> {
            g.setColor(new Color(0xf0e8d0));
            g.fillRect(0, 0, W, H);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setFont(font);
            g.setPaint(sc.paint);
            g.setComposite(sc.composite);
            text.accept(g);
        };
    }

    /** The system underline's antialiased gap edges beside a descender: pixels that may flip between the routes. */
    static final int UNDERLINE_GAP_EDGES = 12;

    static void within(StringBuilder failures, String what, BufferedImage expected, BufferedImage actual, int limit) {
        int n = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) if (expected.getRGB(x, y) != actual.getRGB(x, y)) n++;
        System.out.println("  " + what + ": " + n + " pixels apart" + (limit == Integer.MAX_VALUE ? " (accepted)" : " (limit " + limit + ")"));
        if (n > limit) {
            failures.append(what).append(": ").append(n).append(" pixels apart; ");
        }
    }

    static void compare(StringBuilder failures, String what, BufferedImage actual, BufferedImage reference, long drawn, boolean againstLoops) {
        int background = 0xf0e8d0;
        Rectangle a = inkBounds(actual, background), r = inkBounds(reference, background);
        double inkA = ink(actual, background), inkR = ink(reference, background);
        int[] meanA = meanColour(actual, background, 0, W / 2), meanR = meanColour(reference, background, 0, W / 2);
        int[] meanA2 = meanColour(actual, background, W / 2, W), meanR2 = meanColour(reference, background, W / 2, W);
        int colourOff = 0;
        for (int c = 0; c < 3; c++) colourOff = Math.max(colourOff, Math.max(Math.abs(meanA[c] - meanR[c]), Math.abs(meanA2[c] - meanR2[c])));
        System.out.printf("%s: %d glyphs by Skia, ink %s against the loops' %s, ink ratio %.3f, mean colour off by %d levels%n",
                what, drawn, a, r, inkA / Math.max(1, inkR), colourOff);
        if (drawn < TEXT.length() - 1) {
            failures.append(what).append(": not drawn by Skia's text path; ");
        }
        if (!againstLoops) {
            return;
        }
        if (Math.abs(a.x - r.x) > 1 || Math.abs(a.y - r.y) > 1 || Math.abs(a.x + a.width - r.x - r.width) > 1 || Math.abs(a.y + a.height - r.y - r.height) > 1) {
            failures.append(what).append(": ink ").append(a).append(" against the loops' ").append(r).append("; ");
        }
        if (Math.abs(inkA / Math.max(1, inkR) - 1) > 0.06) {
            failures.append(what).append(String.format(": ink ratio %.3f; ", inkA / Math.max(1, inkR)));
        }
        if (colourOff > 12) {
            failures.append(what).append(": the inked pixels' mean colour ").append(colourOff).append(" levels off; ");
        }
    }

    static void identical(StringBuilder failures, String what, BufferedImage expected, BufferedImage actual) {
        int n = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) if (expected.getRGB(x, y) != actual.getRGB(x, y)) n++;
        System.out.println("  " + what + ": " + n + " pixels apart");
        if (n != 0) {
            failures.append(what).append(": ").append(n).append(" pixels apart; ");
        }
    }

    static Rectangle inkBounds(BufferedImage image, int background) {
        Rectangle r = null;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            if ((image.getRGB(x, y) & 0xffffff) != background) {
                if (r == null) r = new Rectangle(x, y, 1, 1); else r.add(new Rectangle(x, y, 1, 1));
            }
        }
        return r != null ? r : new Rectangle();
    }

    /** The colour distance from the background, summed over the pixels. */
    static double ink(BufferedImage image, int background) {
        double sum = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int p = image.getRGB(x, y);
            for (int s = 0; s < 24; s += 8) sum += Math.abs(((p >> s) & 0xff) - ((background >> s) & 0xff));
        }
        return sum;
    }

    /** The mean colour of the pixels in [x0, x1) that differ from the background by more than half the range. */
    static int[] meanColour(BufferedImage image, int background, int x0, int x1) {
        long[] sum = new long[3];
        int n = 0;
        for (int y = 0; y < H; y++) for (int x = x0; x < x1; x++) {
            int p = image.getRGB(x, y), d = 0;
            for (int s = 0; s < 24; s += 8) d += Math.abs(((p >> s) & 0xff) - ((background >> s) & 0xff));
            if (d > 3 * 128) {
                sum[0] += (p >> 16) & 0xff; sum[1] += (p >> 8) & 0xff; sum[2] += p & 0xff;
                n++;
            }
        }
        return n == 0 ? new int[3] : new int[] {(int) (sum[0] / n), (int) (sum[1] / n), (int) (sum[2] / n)};
    }

    static long counter(String name) throws Exception {
        return (long) Class.forName("sun.java2d.skia.SKEnvironment").getMethod(name).invoke(null);
    }
}
