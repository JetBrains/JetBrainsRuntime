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
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/**
 * @test
 * @summary Text under a rotation, a shear or a non-uniform scale — of the
 *          graphics or of the font — on the Skia scaler lies along its
 *          transformed baseline: the glyph records carry the transformed
 *          advance vector, as the JDK's FreeType scaler gives it (whole pixels
 *          when it is horizontal or vertical, the exact vector otherwise).
 *          Reference: the same text drawn axis-aligned by the loops, then that
 *          image drawn under the transform with bilinear filtering (fractional
 *          metrics for an oblique baseline, so the advances agree exactly) — ink bounds
 *          within three pixels, the ink's centre within one and a half pixels
 *          (the reference's own offset, about a pixel on the platform's scaler),
 *          the ink within 15 %.
 *          On a Skia raster surface and on the Skia GPU pipe the text goes
 *          through Skia's text path like axis-aligned text (every glyph counted
 *          there): the strike's transform beyond its size is applied to the
 *          canvas for the run and the positions mapped back through it.
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.uiScale=1 -Djava.awt.headless=true -Dsun.java2d.font.scaler=skia SkiaRotatedTextTest loops
 * @run main/othervm -Dsun.java2d.uiScale=1 -Djava.awt.headless=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.raster=true -Dsun.java2d.skia.text.strike=skia SkiaRotatedTextTest raster
 */

/*
 * @test id=gpu
 * @summary Rotated, sheared and scaled text through Skia's text path on the Skia GPU pipe, and through
 *          Skia's shaper (strings) and paragraph engine (attributed strings) — shaped in the font's own
 *          space and drawn under the strike's transform beyond its size, as the text path draws — against
 *          the same reference with the same limits, the route's counter moving for every case. Whole-pixel
 *          advances along an oblique baseline or under an uneven scale are Skia's layout's too: they round in
 *          the font's space there, on the device on the text path (accepted, measured). Underline and
 *          strikethrough under the system convention, rotated or unevenly scaled: Java2D's rule, as TextLayout
 *          draws it there, within a quarter pixel across the baseline.
 * @requires (os.family == "mac" | os.family == "windows")
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.strike=skia SkiaRotatedTextTest gpu
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.layout=skia SkiaRotatedTextTest shaper
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.layout=skia SkiaRotatedTextTest paragraph
 */
public class SkiaRotatedTextTest {
    static final String TEXT = "Hamburgefonts Quick fox 1234";
    static final int W = 300, H = 300;

    /** fm: fractional metrics, so the advances along an oblique baseline and the reference's agree exactly (whole-pixel advances round only an axis-parallel vector). */
    static String mode = "loops";

    record Case(String name, AffineTransform transform, Font font, float x, float y, boolean fm) {}

    public static void main(String[] args) throws Exception {
        mode = args[0];
        if ((mode.equals("gpu") || mode.equals("shaper") || mode.equals("paragraph")) && !PipelineComparison.pipelineActive("skia", java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration())) {
            return;
        }
        Font font = new Font(Font.DIALOG, Font.PLAIN, 14);
        AffineTransform scaledRotation = AffineTransform.getScaleInstance(1.25, 1.25);
        scaledRotation.rotate(0.5, 120, 120);
        Case[] cases = {
            new Case("rotation 0.5", AffineTransform.getRotateInstance(0.5, 150, 150), font, 40, 150, true),
            new Case("quarter turn", AffineTransform.getRotateInstance(Math.PI / 2, 150, 150), font, 40, 150, true),
            new Case("quarter turn, whole-pixel advances", AffineTransform.getRotateInstance(Math.PI / 2, 150, 150), font, 40, 150, false),
            new Case("shear 0.3", AffineTransform.getShearInstance(0.3, 0.1), font, 20, 150, true),
            new Case("scale 1.5 x 0.8, whole-pixel advances", AffineTransform.getScaleInstance(1.5, 0.8), font, 10, 150, false),
            new Case("rotation 0.5 at scale 1.25", scaledRotation, font, 30, 120, true),
        };
        for (Case c : cases) {
            if (mode.equals("paragraph") || mode.equals("shaper")) {
                // Skia's layout: an attributed string of two font runs (the same font) through the paragraph engine, a
                // string through the shaper, against the reference; and against the text path (a glyph vector, never
                // shaped): identical, except that whole-pixel advances along anything but a device axis at unit length
                // round in the font's space there and on the device on the text path (accepted, user 2026-11-10: the
                // layout takes such text; measured and printed)
                boolean likeTextPath = c.fm || c.name.startsWith("quarter turn");
                Consumer<Graphics2D> route = mode.equals("paragraph") ? g -> g.drawString(attributed(c.font), c.x, c.y) : g -> g.drawString(TEXT, c.x, c.y);
                check(c.name + ", " + (mode.equals("paragraph") ? "attributed string" : "drawString"), c, route, false, true);
                identical(c, route, g -> g.drawGlyphVector(c.font.createGlyphVector(g.getFontRenderContext(), TEXT), c.x, c.y), likeTextPath);
                continue;
            }
            check(c.name + ", drawString", c, g -> g.drawString(TEXT, c.x, c.y));
            check(c.name + ", drawChars", c, g -> g.drawChars(TEXT.toCharArray(), 0, TEXT.length(), (int) c.x, (int) c.y));
            check(c.name + ", drawGlyphVector", c, g -> g.drawGlyphVector(c.font.createGlyphVector(g.getFontRenderContext(), TEXT), c.x, c.y));
        }
        // a font's own transform: the glyphs and the baseline turn with it; the reference turns the unrotated text about its origin
        if (mode.equals("paragraph")) {
            // runs under different transforms (one font with its own rotation): one paint cannot place them, TextLayout
            java.text.AttributedString mixed = new java.text.AttributedString(TEXT);
            mixed.addAttribute(java.awt.font.TextAttribute.FONT, font, 0, 10);
            mixed.addAttribute(java.awt.font.TextAttribute.FONT, font.deriveFont(AffineTransform.getRotateInstance(0.3)), 10, TEXT.length());
            expectParagraphs("two runs under one transform (the control)", g -> g.drawString(attributed(font), 40, 150), 1);
            expectTextLayout("runs under different transforms", g -> g.drawString(mixed.getIterator(), 40, 150));
            // the system decorations' device rows under a rotation: TextLayout
            java.text.AttributedString underlined = new java.text.AttributedString(TEXT);
            underlined.addAttribute(java.awt.font.TextAttribute.FONT, font);
            underlined.addAttribute(java.awt.font.TextAttribute.UNDERLINE, java.awt.font.TextAttribute.UNDERLINE_ON);
            boolean systemDecorations = !"java2d".equals(System.getProperty("sun.java2d.skia.text.decorations"));
            if (systemDecorations) {
                expectParagraphs("the same underline, axis-aligned (the control)", g -> g.drawString(underlined.getIterator(), 40, 150), 1);
                // under a rotation the system decorations' device rows mean nothing: TextLayout draws Java2D's rule, and so does the paragraph
                expectParagraphs("an underline under a rotation (system decorations)", g -> { g.rotate(0.5, 150, 150); g.drawString(underlined.getIterator(), 40, 150); }, 1);
                java.text.AttributedString decorated = new java.text.AttributedString(TEXT);
                decorated.addAttribute(java.awt.font.TextAttribute.FONT, font);
                decorated.addAttribute(java.awt.font.TextAttribute.UNDERLINE, java.awt.font.TextAttribute.UNDERLINE_ON, 0, 13);
                decorated.addAttribute(java.awt.font.TextAttribute.STRIKETHROUGH, java.awt.font.TextAttribute.STRIKETHROUGH_ON, 9, 20);
                for (boolean aa : new boolean[] {false, true}) {
                    decorationsLikeTextLayout("underline and strikethrough rotated 0.5" + (aa ? ", antialiased shapes" : ""), decorated, AffineTransform.getRotateInstance(0.5, 150, 150), aa);
                    decorationsLikeTextLayout("underline and strikethrough at a 1.5 x 0.8 scale" + (aa ? ", antialiased shapes" : ""), decorated, AffineTransform.getScaleInstance(1.5, 0.8), aa);
                }
            }
            check("a font with its own rotation, attributed string", new Case("font rotation", AffineTransform.getRotateInstance(0.6, 60, 60),
                    font.deriveFont(AffineTransform.getRotateInstance(0.6)), 60, 60, true), g -> g.drawString(attributed(font.deriveFont(AffineTransform.getRotateInstance(0.6))), 60, 60), true);
            System.out.println("PASSED");
            return;
        }
        check("a font with its own rotation", new Case("font rotation", AffineTransform.getRotateInstance(0.6, 60, 60),
                font.deriveFont(AffineTransform.getRotateInstance(0.6)), 60, 60, true), g -> g.drawString(TEXT, 60, 60), true);
        System.out.println("PASSED");
    }

    /**
     * Decorations under a transform beyond the size: the paragraph against TextLayout's own drawing (fractional metrics,
     * so the glyphs agree), the decorations isolated by subtracting each route's undecorated drawing. TextLayout strokes
     * Java2D's line (a thin one by the 1-pixel Bresenham, a wide one normalised), the paragraph fills the band (by pixel
     * centres without antialiasing): within a quarter pixel across the baseline, the ink within 30 % (accepted).
     */
    static void decorationsLikeTextLayout(String what, java.text.AttributedString as, AffineTransform t, boolean shapeAA) {
        java.text.AttributedString plain = new java.text.AttributedString(TEXT);
        plain.addAttribute(java.awt.font.TextAttribute.FONT, as.getIterator().getAttribute(java.awt.font.TextAttribute.FONT));
        double[] ink = new double[2], along = new double[2];
        double ux = t.getScaleX(), uy = t.getShearY(), n = Math.hypot(ux, uy), px = -uy / n, py = ux / n;   // across the baseline
        for (int k = 0; k < 2; k++) {
            BufferedImage decorated = decorationScene(as, t, shapeAA, k == 1), undecorated = decorationScene(plain, t, shapeAA, k == 1);
            for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
                int v = Math.max(0, (undecorated.getRGB(x, y) & 0xff) - (decorated.getRGB(x, y) & 0xff));
                ink[k] += v;
                along[k] += v * (x * px + y * py);
            }
        }
        double offset = along[0] / ink[0] - along[1] / ink[1], ratio = ink[0] / ink[1];
        System.out.printf("%s: the paragraph's decorations against TextLayout's: %.3f px across the baseline, ink ratio %.3f%n", what, offset, ratio);
        if (Math.abs(offset) > 0.25 || Math.abs(ratio - 1) > 0.3) {
            throw new RuntimeException(what + String.format(": the decorations %.3f px across the baseline from TextLayout's, ink ratio %.3f", offset, ratio));
        }
    }

    static BufferedImage decorationScene(java.text.AttributedString as, AffineTransform t, boolean shapeAA, boolean textLayout) {
        java.awt.image.VolatileImage vi = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                .getDefaultConfiguration().createCompatibleVolatileImage(W, H);
        Graphics2D g = vi.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, shapeAA ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setColor(Color.BLACK); g.transform(t);
        if (textLayout) {
            new java.awt.font.TextLayout(as.getIterator(), g.getFontRenderContext()).draw(g, 40, 150);
        } else {
            g.drawString(as.getIterator(), 40, 150);
        }
        g.dispose();
        return vi.getSnapshot();
    }

    /** The drawing must not reach Skia's paragraph engine (the paragraph counter stays). */
    static void expectTextLayout(String what, Consumer<Graphics2D> drawing) throws Exception {
        expectParagraphs(what, drawing, 0);
    }

    static void expectParagraphs(String what, Consumer<Graphics2D> drawing, long expected) throws Exception {
        java.lang.reflect.Method paragraphs = Class.forName("sun.java2d.skia.SKEnvironment").getMethod("paragraphsBySkia");
        long before = (long) paragraphs.invoke(null);
        java.awt.image.VolatileImage vi = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                .getDefaultConfiguration().createCompatibleVolatileImage(W, H);
        Graphics2D g = vi.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        drawing.accept(g);
        g.dispose();
        vi.getSnapshot();
        long drawn = (long) paragraphs.invoke(null) - before;
        System.out.println(what + ": " + drawn + " paragraphs by Skia");
        if (drawn != expected) {
            throw new RuntimeException(what + ": " + drawn + " paragraphs by Skia's paragraph engine (expected " + expected + ")");
        }
    }

    /** The text as an attributed string of two runs in the same font (FONT attributes): what the paragraph route takes. */
    static java.text.AttributedCharacterIterator attributed(Font font) {
        java.text.AttributedString as = new java.text.AttributedString(TEXT);
        int split = TEXT.indexOf(' ') + 1;
        as.addAttribute(java.awt.font.TextAttribute.FONT, font, 0, split);
        as.addAttribute(java.awt.font.TextAttribute.FONT, font, split, TEXT.length());
        return as.getIterator();
    }

    static void check(String what, Case c, Consumer<Graphics2D> drawing) {
        check(what, c, drawing, false);
    }

    static void check(String what, Case c, Consumer<Graphics2D> drawing, boolean fontTransform) {
        check(what, c, drawing, fontTransform, true);
    }

    /** The case drawn two ways on the Skia GPU pipe: the pixels must be the same (required), or are counted (not). */
    static void identical(Case c, Consumer<Graphics2D> a, Consumer<Graphics2D> b, boolean required) {
        BufferedImage[] out = new BufferedImage[2];
        int k = 0;
        for (Consumer<Graphics2D> drawing : java.util.List.of(a, b)) {
            java.awt.image.VolatileImage vi = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                    .getDefaultConfiguration().createCompatibleVolatileImage(W, H);
            Graphics2D g = vi.createGraphics();
            g.setColor(Color.WHITE); g.fillRect(0, 0, W, H);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, c.fm ? RenderingHints.VALUE_FRACTIONALMETRICS_ON : RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
            g.setColor(Color.BLACK); g.setFont(c.font); g.transform(c.transform);
            drawing.accept(g);
            g.dispose();
            out[k++] = vi.getSnapshot();
        }
        int differ = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) if (out[0].getRGB(x, y) != out[1].getRGB(x, y)) differ++;
        System.out.println(c.name + ": " + mode + " route against the text path: " + differ + " pixels differ" + (required ? "" : " (accepted: the rounding's space)"));
        if (required && differ > 0) {
            throw new RuntimeException(c.name + ": Skia's " + mode + " route renders apart from its text path (" + differ + " pixels)");
        }
    }

    /**
     * The text under the case's transform, against the reference: the text drawn axis-aligned (with the font's own
     * transform taken out when the font carries it) by the loops on a 4-byte image, which no Skia surface replaces,
     * and that image drawn under the transform, bilinear.
     */
    static void check(String what, Case c, Consumer<Graphics2D> drawing, boolean fontTransform, boolean bySkia) {
        long before = glyphsBySkia();
        // loops: the loops draw the Skia scaler's glyph images; raster: an INT_ARGB_PRE raster surface; gpu: a Skia volatile image
        java.awt.image.VolatileImage volatileImage = !mode.equals("loops") && !mode.equals("raster") ? java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration().createCompatibleVolatileImage(W, H) : null;
        BufferedImage actual = new BufferedImage(W, H, mode.equals("raster") ? BufferedImage.TYPE_INT_ARGB_PRE : BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = volatileImage != null ? volatileImage.createGraphics() : actual.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, c.fm ? RenderingHints.VALUE_FRACTIONALMETRICS_ON : RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        g.setColor(Color.BLACK); g.setFont(c.font);
        if (!fontTransform) g.transform(c.transform);
        drawing.accept(g);
        g.dispose();
        if (volatileImage != null) {
            actual = volatileImage.getSnapshot();
        }
        long drawn = glyphsBySkia() - before;

        BufferedImage flat = new BufferedImage(W, H, BufferedImage.TYPE_4BYTE_ABGR);
        Graphics2D f = flat.createGraphics();
        f.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        f.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, c.fm ? RenderingHints.VALUE_FRACTIONALMETRICS_ON : RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        f.setColor(Color.BLACK); f.setFont(fontTransform ? c.font.deriveFont(new AffineTransform()) : c.font);
        f.drawString(TEXT, c.x, c.y);
        f.dispose();
        BufferedImage reference = new BufferedImage(W, H, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D r = reference.createGraphics();
        r.setColor(Color.WHITE); r.fillRect(0, 0, W, H);
        r.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        r.drawImage(flat, c.transform, null);
        r.dispose();

        double[] a = inkMoments(actual), e = inkMoments(reference);
        int[] ab = inkBounds(actual), eb = inkBounds(reference);
        double ratio = a[0] / e[0], dx = a[1] - e[1], dy = a[2] - e[2];
        System.out.printf("%s: %d glyphs by Skia; ink bounds [%d,%d..%d,%d], reference [%d,%d..%d,%d]; centre off by (%.2f, %.2f); ink ratio %.3f%n",
                what, drawn, ab[0], ab[1], ab[2], ab[3], eb[0], eb[1], eb[2], eb[3], dx, dy, ratio);
        if (bySkia && !mode.equals("loops") && drawn < TEXT.replace(" ", "").length()) {
            throw new RuntimeException(what + ": only " + drawn + " glyphs went through Skia's text path");
        }
        for (int i = 0; i < 4; i++) {
            if (Math.abs(ab[i] - eb[i]) > 3) {
                throw new RuntimeException(what + ": the text does not lie along its transformed baseline (ink bounds differ by more than three pixels)");
            }
        }
        // the reference's own offset: the platform's scaler measures -0.31 ... -0.89 px in x against it (glyphs rendered
        // under the transform against a resampled rendering); the defect this guards put the centre 47 px off
        if (Math.abs(dx) > 1.5 || Math.abs(dy) > 1.5) {
            throw new RuntimeException(what + ": the ink's centre is off by (" + dx + ", " + dy + ") pixels");
        }
        if (ratio < 0.85 || ratio > 1.15) {
            throw new RuntimeException(what + ": ink ratio " + ratio + " outside [0.85, 1.15]");
        }
    }

    static long glyphsBySkia() {
        String counter = switch (mode) {
            case "raster" -> "rasterGlyphsBySkia";
            case "gpu" -> "glyphsDrawnBySkia";
            case "shaper", "paragraph" -> "glyphsShapedBySkia";   // a paragraph counts its characters
            default -> null;
        };
        if (counter == null) return 0;
        try {
            return (long) Class.forName("sun.java2d.skia.SKEnvironment").getMethod(counter).invoke(null);
        } catch (ReflectiveOperationException e) {
            return 0;
        }
    }

    /** Ink (darkness summed over the channels) and its centre. */
    static double[] inkMoments(BufferedImage img) {
        double sum = 0, sx = 0, sy = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int p = img.getRGB(x, y);
            double ink = 765 - ((p >> 16) & 255) - ((p >> 8) & 255) - (p & 255);
            sum += ink; sx += ink * x; sy += ink * y;
        }
        return new double[] {sum, sum > 0 ? sx / sum : 0, sum > 0 ? sy / sum : 0};
    }

    /** Pixels darker than a quarter of full ink: the bilinear reference spreads a faint halo the text does not have. */
    static int[] inkBounds(BufferedImage img) {
        int x1 = W, y1 = H, x2 = -1, y2 = -1;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int p = img.getRGB(x, y);
            if (765 - ((p >> 16) & 255) - ((p >> 8) & 255) - (p & 255) > 190) { x1 = Math.min(x1, x); y1 = Math.min(y1, y); x2 = Math.max(x2, x); y2 = Math.max(y2, y); }
        }
        return new int[] {x1, y1, x2, y2};
    }
}
