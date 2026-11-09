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
 * @summary Rotated, sheared and scaled text through Skia's text path on the Skia GPU pipe.
 * @requires (os.family == "mac" | os.family == "windows")
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.strike=skia SkiaRotatedTextTest gpu
 */
public class SkiaRotatedTextTest {
    static final String TEXT = "Hamburgefonts Quick fox 1234";
    static final int W = 300, H = 300;

    /** fm: fractional metrics, so the advances along an oblique baseline and the reference's agree exactly (whole-pixel advances round only an axis-parallel vector). */
    static String mode = "loops";

    record Case(String name, AffineTransform transform, Font font, float x, float y, boolean fm) {}

    public static void main(String[] args) throws Exception {
        mode = args[0];
        if (mode.equals("gpu") && !PipelineComparison.pipelineActive("skia", java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
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
            check(c.name + ", drawString", c, g -> g.drawString(TEXT, c.x, c.y));
            check(c.name + ", drawChars", c, g -> g.drawChars(TEXT.toCharArray(), 0, TEXT.length(), (int) c.x, (int) c.y));
            check(c.name + ", drawGlyphVector", c, g -> g.drawGlyphVector(c.font.createGlyphVector(g.getFontRenderContext(), TEXT), c.x, c.y));
        }
        // a font's own transform: the glyphs and the baseline turn with it; the reference turns the unrotated text about its origin
        check("a font with its own rotation", new Case("font rotation", AffineTransform.getRotateInstance(0.6, 60, 60),
                font.deriveFont(AffineTransform.getRotateInstance(0.6)), 60, 60, true), g -> g.drawString(TEXT, 60, 60), true);
        System.out.println("PASSED");
    }

    static void check(String what, Case c, Consumer<Graphics2D> drawing) {
        check(what, c, drawing, false);
    }

    /**
     * The text under the case's transform, against the reference: the text drawn axis-aligned (with the font's own
     * transform taken out when the font carries it) by the loops on a 4-byte image, which no Skia surface replaces,
     * and that image drawn under the transform, bilinear.
     */
    static void check(String what, Case c, Consumer<Graphics2D> drawing, boolean fontTransform) {
        long before = glyphsBySkia();
        // loops: the loops draw the Skia scaler's glyph images; raster: an INT_ARGB_PRE raster surface; gpu: a Skia volatile image
        java.awt.image.VolatileImage volatileImage = mode.equals("gpu") ? java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
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
        if (!mode.equals("loops") && drawn < TEXT.replace(" ", "").length()) {
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
        String counter = switch (mode) { case "raster" -> "rasterGlyphsBySkia"; case "gpu" -> "glyphsDrawnBySkia"; default -> null; };
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
