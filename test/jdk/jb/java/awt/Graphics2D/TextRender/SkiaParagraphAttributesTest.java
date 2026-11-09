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
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.text.AttributedCharacterIterator;
import java.text.AttributedString;
import java.util.Map;
import java.util.function.Consumer;

/**
 * @test
 * @summary Skia's paragraph engine takes the attributed strings Java's TextLayout lays out with
 *          kerning or ligatures — iterator attributes on runs described by FAMILY and SIZE, or
 *          carried by a run's FONT (which, as for TextLayout, outranks the iterator's) — and renders
 *          them as TextLayout's lines are rendered (pixel-identical: the routes render alike);
 *          a run whose font carries another layout attribute (tracking) stays TextLayout's.
 * @requires (os.family == "mac" | os.family == "windows")
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.layout=skia SkiaParagraphAttributesTest
 */
public class SkiaParagraphAttributesTest {
    static final String KERNED = "AVATAR Wave To Tw Yo LT";
    static final String LIGATED = "office fluffy affine fjord";
    static final int W = 420, H = 48;

    public static void main(String[] args) throws Exception {
        Font font = new Font(System.getProperty("os.name").startsWith("Mac") ? "Helvetica Neue" : "Segoe UI", Font.PLAIN, 18);

        expect("two plain runs (the control: the Skia pipe and its paragraph engine are on)", runs(KERNED, font), true);
        // the attributes must change the rendering with this font, or the cases below prove nothing
        differ("kerning", layout(byAttributes(KERNED, font, null, null)), layout(byAttributes(KERNED, font, TextAttribute.KERNING, TextAttribute.KERNING_ON)));
        differ("ligatures", layout(byAttributes(LIGATED, font, null, null)), layout(byAttributes(LIGATED, font, TextAttribute.LIGATURES, TextAttribute.LIGATURES_ON)));
        // iterator attributes take effect on runs described by FAMILY and SIZE (Font.getFont(attributes))
        expect("KERNING_ON with FAMILY and SIZE", byAttributes(KERNED, font, TextAttribute.KERNING, TextAttribute.KERNING_ON), true);
        expect("LIGATURES_ON with FAMILY and SIZE", byAttributes(LIGATED, font, TextAttribute.LIGATURES, TextAttribute.LIGATURES_ON), true);
        // a FONT attribute is taken as it is: TextLayout ignores the iterator's KERNING next to it, and so must the paragraph
        expect("KERNING_ON next to a FONT (ignored, as by TextLayout)", withAttribute(KERNED, font, TextAttribute.KERNING, TextAttribute.KERNING_ON), true);
        expect("a font carrying KERNING_ON", runs(KERNED, font.deriveFont(Map.of(TextAttribute.KERNING, TextAttribute.KERNING_ON))), true);
        expect("a font carrying LIGATURES_ON", runs(LIGATED, font.deriveFont(Map.of(TextAttribute.LIGATURES, TextAttribute.LIGATURES_ON))), true);
        // another layout attribute on the run's font: not the paragraph's (yet), and still drawn as TextLayout draws it
        expect("a font carrying TRACKING", runs(KERNED, font.deriveFont(Map.of(TextAttribute.TRACKING, 0.1f))), false);
        System.out.println("PASSED");
    }

    /** The text in two runs of the given font (FONT attributes). */
    static AttributedString runs(String text, Font font) {
        AttributedString as = new AttributedString(text);
        int split = text.indexOf(' ') + 1;
        as.addAttribute(TextAttribute.FONT, font, 0, split);
        as.addAttribute(TextAttribute.FONT, font, split, text.length());
        return as;
    }

    /** The text in two runs described by FAMILY and SIZE (no FONT), with an optional further attribute. */
    static AttributedString byAttributes(String text, Font font, TextAttribute key, Object value) {
        AttributedString as = new AttributedString(text);
        int split = text.indexOf(' ') + 1;
        for (int[] run : new int[][] {{0, split}, {split, text.length()}}) {
            as.addAttribute(TextAttribute.FAMILY, font.getFamily(), run[0], run[1]);
            as.addAttribute(TextAttribute.SIZE, font.getSize2D(), run[0], run[1]);
        }
        if (key != null) {
            as.addAttribute(key, value, 0, text.length());
        }
        return as;
    }

    static AttributedString withAttribute(String text, Font font, TextAttribute key, Object value) {
        AttributedString as = runs(text, font);
        as.addAttribute(key, value, 0, text.length());
        return as;
    }

    /** Java's TextLayout of the string, drawn: its glyph vectors through Skia's text path. */
    static Consumer<Graphics2D> layout(AttributedString as) {
        return g -> new TextLayout(as.getIterator(), g.getFontRenderContext()).draw(g, 10, 30);
    }

    static long paragraphs() throws Exception {
        return (long) Class.forName("sun.java2d.skia.SKEnvironment").getMethod("paragraphsBySkia").invoke(null);
    }

    static BufferedImage render(Consumer<Graphics2D> drawing) {
        VolatileImage vi = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().createCompatibleVolatileImage(W, H);
        Graphics2D g = vi.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setColor(Color.BLACK);
        drawing.accept(g);
        g.dispose();
        return vi.getSnapshot();
    }

    static int differing(BufferedImage a, BufferedImage b) {
        int n = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) if (a.getRGB(x, y) != b.getRGB(x, y)) n++;
        return n;
    }

    static void differ(String what, Consumer<Graphics2D> off, Consumer<Graphics2D> on) {
        int n = differing(render(off), render(on));
        System.out.println(what + " changes " + n + " pixels of the TextLayout rendering");
        if (n == 0) {
            throw new RuntimeException(what + " has no effect with this font: the test would prove nothing");
        }
    }

    /** drawString(iterator) against TextLayout's drawing: identical pixels; byParagraph: whether Skia's paragraph engine took it. */
    static void expect(String what, AttributedString as, boolean byParagraph) throws Exception {
        long before = paragraphs();
        BufferedImage route = render(g -> g.drawString(as.getIterator(), 10, 30));
        long taken = paragraphs() - before;
        int n = differing(route, render(layout(as)));
        System.out.println(what + ": " + taken + " paragraphs by Skia, " + n + " pixels apart from TextLayout");
        if (taken != (byParagraph ? 1 : 0)) {
            throw new RuntimeException(what + ": " + taken + " paragraphs by Skia's paragraph engine (expected " + (byParagraph ? 1 : 0) + ")");
        }
        if (n != 0) {
            throw new RuntimeException(what + ": drawn " + n + " pixels apart from TextLayout");
        }
    }
}
