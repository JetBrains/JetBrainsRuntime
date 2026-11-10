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
import java.awt.RenderingHints;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.text.AttributedString;
import java.util.HashMap;
import java.util.Map;

/**
 * @test
 * @summary Under the system decoration convention (sun.java2d.skia.text.decorations=system, the default: underline
 *          and strikethrough on the platform text stack's rows, CoreText's on macOS) TextLayout's outline is the band
 *          the decorations are drawn in, under any transform: translations with whole and fractional baselines,
 *          scales, rotations, a shear, a rotation with a scale. CoreText draws its axis-aligned band under a rotation
 *          or shear too, antialiased (measured 2026-11-10); TextLayout drew Java2D's stroked line there (the JDK scaler:
 *          Java's nominal band, a pixel above the outline's) and the paragraph route filled the font's own rectangle
 *          without antialiasing (Menlo 12's thin band under a shear drew nothing). Over the spaces between two glyphs every
 *          drawn pixel is the decoration's: mapped back to the text's frame each must lie in the band of the spaces'
 *          outline (TextLayout's outline of the spaces alone: nothing but the decoration), placed on the drawn
 *          baseline's device row when the baseline runs along the rows (the glyphs' images go there too), give or take
 *          the antialiasing's reach; and the drawn coverage must fill the band's area. Underline and strikethrough,
 *          three fonts, four sizes; TextLayout.draw on the software loops (both scalers) and the raster surface, and
 *          the paragraph route of an attributed string.
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Djava.awt.headless=true DecorationOutlineTest loops
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.font.scaler=skia DecorationOutlineTest loops
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.skia.raster=true DecorationOutlineTest raster
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.skia.raster=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.layout=skia DecorationOutlineTest raster-paragraph
 */

/*
 * @test id=gpu
 * @summary The same on the Skia GPU pipe, its paragraph route, and the platform's own pipeline (2x as a transform at
 *          uiScale 1, so that the snapshot's pixels are the device's).
 * @requires (os.family == "mac" | os.family == "windows")
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true DecorationOutlineTest gpu
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.layout=skia DecorationOutlineTest gpu-paragraph
 * @run main/othervm -Dsun.java2d.uiScale=1 DecorationOutlineTest gpu
 */
public class DecorationOutlineTest {
    static final int SIZE = 640;
    /** Spaces between two glyphs: the middle holds only the decoration (CoreText, and SkParagraph, decorate no line-end whitespace). */
    static final String SPACES = "           ", TEXT = "x" + SPACES + "x";
    /** How far antialiasing spreads a band's edge, in device pixels: half a pixel across, a diagonal one's half-diagonal. */
    static final double REACH = 0.75;

    record Case(String name, AffineTransform transform) {}

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        boolean paragraph = mode.endsWith("paragraph");
        String[] families = System.getProperty("os.name").startsWith("Mac")
                ? new String[] {"Helvetica Neue", "Menlo", Font.DIALOG} : new String[] {"Segoe UI", "Consolas", Font.DIALOG};
        Case[] cases = {
                new Case("identity", transform(240, 240, 0, 1, 0)),
                new Case("baseline +0.3", transform(240, 240.3, 0, 1, 0)),
                new Case("baseline +0.5", transform(240, 240.5, 0, 1, 0)),
                new Case("2x", transform(40, 240, 0, 2, 0)),
                new Case("2x, baseline +0.25", transform(40, 240.25, 0, 2, 0)),
                new Case("1.5x", transform(40, 240, 0, 1.5, 0)),
                new Case("rotated 5", transform(160, 240, 5, 1, 0)),
                new Case("rotated 30", transform(160, 200, 30, 1, 0)),
                new Case("rotated 90", transform(240, 40, 90, 1, 0)),
                new Case("rotated 180", transform(600, 240, 180, 1, 0)),
                new Case("sheared", transform(160, 240, 0, 1, 0.25)),
                new Case("rotated 30 at 1.5x", transform(40, 120, 30, 1.5, 0)),
        };
        long before = paragraphs();
        StringBuilder failures = new StringBuilder();
        int count = 0;
        for (String family : families) {
            for (float size : new float[] {9, 13, 18, 36}) {
                for (TextAttribute decoration : new TextAttribute[] {TextAttribute.UNDERLINE, TextAttribute.STRIKETHROUGH}) {
                    Map<TextAttribute, Object> attributes = new HashMap<>();
                    attributes.put(TextAttribute.FAMILY, family);
                    attributes.put(TextAttribute.SIZE, size);
                    attributes.put(decoration, decoration == TextAttribute.UNDERLINE ? TextAttribute.UNDERLINE_ON : TextAttribute.STRIKETHROUGH_ON);
                    for (Case c : cases) {
                        String failure = check(mode, paragraph, attributes, c.transform);
                        count++;
                        if (failure != null) {
                            failures.append(family).append(' ').append(size).append(' ')
                                    .append(decoration == TextAttribute.UNDERLINE ? "underline" : "strikethrough").append(", ")
                                    .append(c.name).append(": ").append(failure).append("; ");
                        }
                    }
                }
            }
        }
        if (paragraph && paragraphs() - before < count) {
            failures.append("only ").append(paragraphs() - before).append(" of ").append(count).append(" drawn by the paragraph route; ");
        }
        System.out.println(mode + ": " + count + " cases");
        if (failures.length() > 0) {
            throw new RuntimeException(mode + ": " + failures);
        }
        System.out.println("PASSED");
    }

    static AffineTransform transform(double x, double y, double degrees, double scale, double shear) {
        AffineTransform at = AffineTransform.getTranslateInstance(x, y);
        at.rotate(Math.toRadians(degrees));
        at.scale(scale, scale);
        at.shear(-shear, 0);
        return at;
    }

    /** Null when the drawn decoration is the outline's band, else what is wrong. */
    static String check(String mode, boolean paragraph, Map<TextAttribute, Object> attributes, AffineTransform at) throws Exception {
        VolatileImage vi = null;
        BufferedImage image = null;
        Graphics2D g;
        if (mode.startsWith("gpu")) {
            vi = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().createCompatibleVolatileImage(SIZE, SIZE);
            g = vi.createGraphics();
        } else {
            image = new BufferedImage(SIZE, SIZE, mode.startsWith("raster") ? BufferedImage.TYPE_INT_ARGB_PRE : BufferedImage.TYPE_INT_RGB);
            g = image.createGraphics();
        }
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, SIZE, SIZE);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        g.transform(at);
        AffineTransform toDevice = g.getTransform();
        Font font = new Font(attributes);
        TextLayout tl = new TextLayout(TEXT, font, g.getFontRenderContext());
        TextLayout spaces = new TextLayout(SPACES, font, g.getFontRenderContext());
        Rectangle2D band = spaces.getOutline(null).getBounds2D();
        double glyph = new TextLayout("x", font, g.getFontRenderContext()).getAdvance();
        if (paragraph) {
            g.drawString(new AttributedString(TEXT, attributes).getIterator(), 0f, 0f);
        } else {
            tl.draw(g, 0, 0);
        }
        g.dispose();
        BufferedImage result = vi != null ? vi.getSnapshot() : image;

        // the band where the glyphs' images go: on the drawn baseline's device row when the baseline runs along the rows
        double snap = 0;
        if (toDevice.getShearY() == 0) {
            double y = toDevice.getTranslateY();
            snap = (Math.round(y) - y) / toDevice.getScaleY();
        } else if (toDevice.getScaleX() == 0) {
            double x = toDevice.getTranslateX();
            snap = (Math.round(x) - x) / toDevice.getShearX();
        }
        double along = Math.hypot(toDevice.getScaleX(), toDevice.getShearY());
        double across = Math.abs(toDevice.getDeterminant()) / along;
        double top = band.getMinY() + snap - REACH / across, bottom = band.getMaxY() + snap + REACH / across;
        // the first half of the spaces, clear of the glyphs' ink even where the paragraph route lays the line out
        // longer or shorter than TextLayout (the Skia scaler's whole-pixel advances under a rotation, ledger §13.1)
        double left = glyph + 1.5, right = left + (tl.getAdvance() - 2 * glyph - 3) / 2;
        AffineTransform inverse = toDevice.createInverse();
        Point2D.Double p = new Point2D.Double();
        double coverage = 0;
        int outside = 0;
        double worst = 0;
        for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
            double c = (255 - (result.getRGB(x, y) >> 8 & 0xff)) / 255.0;
            if (c < 0.05) continue;
            p.setLocation(x + 0.5, y + 0.5);
            inverse.transform(p, p);
            if (p.x < left || p.x > right) continue;
            coverage += c;
            if (p.y < top || p.y > bottom) {
                outside++;
                worst = Math.max(worst, Math.max(Math.max(top - p.y, p.y - bottom), 0) * across);
            }
        }
        if (coverage == 0) {
            return "nothing drawn";
        }
        if (outside > 0) {
            return String.format("%d drawn pixels outside the outline's band [%.3f, %.3f] (up to %.2f device pixels)", outside, band.getMinY() + snap, band.getMaxY() + snap, worst);
        }
        double ratio = coverage / Math.abs(toDevice.getDeterminant()) / ((right - left) * band.getHeight());
        if (ratio < 0.8 || ratio > 1.2) {
            return String.format("the drawn coverage is %.2f of the outline's band [%.3f, %.3f]", ratio, band.getMinY() + snap, band.getMaxY() + snap);
        }
        return null;
    }

    static long paragraphs() throws Exception {
        return (long) Class.forName("sun.java2d.skia.SKEnvironment").getMethod("paragraphsBySkia").invoke(null);
    }
}
