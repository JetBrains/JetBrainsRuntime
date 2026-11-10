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
import java.awt.font.TransformAttribute;
import java.awt.geom.AffineTransform;
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
 *          tracking likewise (on runs and carried by fonts, positive and negative, with kerning
 *          and with ligatures, fractional and whole-pixel advances); a run whose font carries
 *          another layout attribute (width) stays TextLayout's. Superscripts and subscripts
 *          (levels 1 and 2, on a first run, carried by a font, with a TRANSFORM, whole-pixel
 *          advances, underlined) are the paragraph's too, each glyph column TextLayout's up to a
 *          vertical offset of one layout pixel (SkParagraph puts shifted runs on whole layout pixels
 *          where Java's layout does not; accepted), the underline where SkParagraph puts it; an
 *          underline on runs described by FAMILY and SIZE is the paragraph's. BACKGROUND and
 *          SWAP_COLORS (with and without FOREGROUND and BACKGROUND, the swapped text black or white
 *          by the background's brightness as Java's Decoration chooses) are the paragraph's: the box
 *          behind the run SkParagraph's, within a pixel of TextLayout's fill of the run's logical
 *          bounds; swapped over a paint that is not a colour, TextLayout's. Iterators without a
 *          FONT or FAMILY (no attributes at all, colours only, SIZE or WEIGHT alone, next to a FONT
 *          run, characters from several scripts) are the paragraph's with the fonts TextLayout
 *          resolves for them (FontResolver: Dialog 12 derived with the font attributes, else the
 *          first font that displays the character), pixel-identical. Keys that are not
 *          TextAttributes (LANGUAGE, READING, INPUT_METHOD_SEGMENT, an application's own) and
 *          the off values of UNDERLINE (-1) and STRIKETHROUGH (false) are the paragraph's too;
 *          the dotted underline style stays TextLayout's. Bidi as TextLayout runs it: a right-to-left
 *          base from the first strong character or RUN_DIRECTION, BIDI_EMBEDDING's explicit levels,
 *          NUMERIC_SHAPING (Arabic, contextual), JUSTIFICATION, and a FONT's own RUN_DIRECTION ignored,
 *          pixel-identical; overrides reverse the text alike but resolve the neutrals beside them by
 *          Unicode's controls, not Java's explicit levels (accepted, printed). Plain Hebrew-first and
 *          Arabic strings through the shaper in Java's base direction, pixel-identical. Emoji in a bold
 *          run (a regular colour face falling back) drawn, pixel-identical. Plain drawString, drawChars
 *          and drawBytes in a font with layout attributes (kerning, ligatures, tracking, underline,
 *          strikethrough, a colour, a background, a superscript) are the paragraph's too, as
 *          TextLayout(String, Font) draws them; a font with WIDTH stays TextLayout's.
 * @requires (os.family == "mac" | os.family == "windows")
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.layout=skia SkiaParagraphAttributesTest
 * @run main/othervm -Dsun.java2d.uiScale=2 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.layout=skia SkiaParagraphAttributesTest 2x
 */

/*
 * @test id=raster
 * @summary The same on a Skia raster surface (a BufferedImage drawn by Skia): the paragraph engine and the shaper
 *          draw there too, on the surface's pixels locked for the text's device bounds.
 * @requires (os.family == "mac" | os.family == "windows")
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.uiScale=1 -Dsun.java2d.skia.raster=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.layout=skia SkiaParagraphAttributesTest raster
 */
public class SkiaParagraphAttributesTest {
    static final String KERNED = "AVATAR Wave To Tw Yo LT";
    static final String LIGATED = "office fluffy affine fjord";
    static final String SCRIPTS = "E = mc2 and H2O, x_i";
    static final int W = 420, H = 48;
    static boolean fractional = true;
    /** At 2x the snapshot samples the device image in user pixels: a column moved by a device row gains or loses a sampled row. */
    static boolean hidpi;
    /** On a Skia raster surface (a BufferedImage) instead of the GPU pipe's volatile image. */
    static boolean raster;
    /** The graphics' paint for the text, or null for black. */
    static java.awt.Paint paint;

    public static void main(String[] args) throws Exception {
        // at 2x whole-pixel advances lay a system font out under the device scale: two device pixels a layout pixel
        hidpi = args.length > 0 && args[0].equals("2x");
        raster = args.length > 0 && args[0].equals("raster");
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
        // tracking: Java adds tracking x the font's size to each glyph's advance (glyphs sharing a position, a ligature's, move together)
        differ("tracking", layout(byAttributes(KERNED, font, null, null)), layout(byAttributes(KERNED, font, TextAttribute.TRACKING, 0.1f)));
        expect("TRACKING 0.1 with FAMILY and SIZE", byAttributes(KERNED, font, TextAttribute.TRACKING, 0.1f), true);
        expect("TRACKING -0.05 with FAMILY and SIZE", byAttributes(KERNED, font, TextAttribute.TRACKING, -0.05f), true);
        expect("a font carrying TRACKING", runs(KERNED, font.deriveFont(Map.of(TextAttribute.TRACKING, 0.1f))), true);
        expect("a font carrying TRACKING and KERNING_ON", runs(KERNED, font.deriveFont(Map.of(TextAttribute.TRACKING, 0.08f, TextAttribute.KERNING, TextAttribute.KERNING_ON))), true);
        expect("a font carrying TRACKING and LIGATURES_ON", runs(LIGATED, font.deriveFont(Map.of(TextAttribute.TRACKING, 0.08f, TextAttribute.LIGATURES, TextAttribute.LIGATURES_ON))), true);
        fractional = false;
        expect("TRACKING 0.1, whole-pixel advances", byAttributes(KERNED, font, TextAttribute.TRACKING, 0.1f), true);
        expect("a font carrying TRACKING and KERNING_ON, whole-pixel advances", runs(KERNED, font.deriveFont(Map.of(TextAttribute.TRACKING, 0.08f, TextAttribute.KERNING, TextAttribute.KERNING_ON))), true);
        fractional = true;
        // superscript and subscript: the run's font scaled by 2/3 a level, its baseline shifted (Font.getTransform)
        differ("superscript", layout(byAttributes(SCRIPTS, font, null, null)), layout(scripted(font, TextAttribute.SUPERSCRIPT_SUPER, 7, 8)));
        expectShifted("SUPERSCRIPT_SUPER on a run", scripted(font, TextAttribute.SUPERSCRIPT_SUPER, 7, 8), 1);
        expectShifted("SUPERSCRIPT_SUB on a run", scripted(font, TextAttribute.SUPERSCRIPT_SUB, 12, 13), 1);
        expectShifted("superscript level 2", scripted(font, 2, 7, 8), 1);
        expectShifted("subscript level 2 next to superscript", withAttribute(scripted(font, TextAttribute.SUPERSCRIPT_SUPER, 7, 8), TextAttribute.SUPERSCRIPT, -2, 12, 13), 1);
        expectShifted("a superscripted first run", scripted(font, TextAttribute.SUPERSCRIPT_SUPER, 0, 2), 1);
        expectShifted("a font carrying SUPERSCRIPT", runs(SCRIPTS, font.deriveFont(Map.of(TextAttribute.SUPERSCRIPT, TextAttribute.SUPERSCRIPT_SUPER))), 1, false);
        // the offset is the user's (Font.getTransform applies it outside the font's TRANSFORM): not scaled with it
        expectShifted("a font carrying SUPERSCRIPT and a scaling TRANSFORM", runs(SCRIPTS, font.deriveFont(Map.of(TextAttribute.SUPERSCRIPT, TextAttribute.SUPERSCRIPT_SUB,
                TextAttribute.TRANSFORM, new TransformAttribute(AffineTransform.getScaleInstance(1.25, 1.25))))), 1, false);
        // an underline on runs described by FAMILY and SIZE is the route's own decoration, not a font the route refuses
        expect("an underline with FAMILY and SIZE", byAttributes(KERNED, font, TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON), true);
        // a decoration with shifted runs: where SkParagraph puts it (TextLayout's rule for a superscript's underline differs)
        expectTaken("an underlined superscript", withAttribute(scripted(font, TextAttribute.SUPERSCRIPT_SUPER, 7, 8), TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON, 0, SCRIPTS.length()));
        fractional = false;
        expectShifted("SUPERSCRIPT_SUPER on a run, whole-pixel advances", scripted(font, TextAttribute.SUPERSCRIPT_SUPER, 7, 8), hidpi ? 2 : 1);
        expectShifted("SUPERSCRIPT_SUB on a run, whole-pixel advances", scripted(font, TextAttribute.SUPERSCRIPT_SUB, 12, 13), hidpi ? 2 : 1);
        fractional = true;
        // a box behind the run: SkParagraph's (its top a row above TextLayout's, which fills only rows whose centres
        // the logical bounds cover; with antialiasing the edges blend), the text in the colour Java resolves
        Color yellow = Color.YELLOW, blue = new Color(0x2050c0), light = new Color(0xe0e080);
        expectBox("BACKGROUND on a run", withAttribute(byAttributes(SCRIPTS, font, null, null), TextAttribute.BACKGROUND, yellow, 4, 11), yellow, Color.BLACK, false);
        expectBox("BACKGROUND, antialiased", withAttribute(byAttributes(SCRIPTS, font, null, null), TextAttribute.BACKGROUND, yellow, 4, 11), yellow, Color.BLACK, true);
        expectBox("BACKGROUND on a superscript", withAttribute(scripted(font, TextAttribute.SUPERSCRIPT_SUPER, 6, 7), TextAttribute.BACKGROUND, yellow, 6, 8), yellow, Color.BLACK, false);
        expectBox("BACKGROUND next to a larger run", withAttribute(withAttribute(byAttributes(SCRIPTS, font, null, null), TextAttribute.BACKGROUND, yellow, 4, 11),
                TextAttribute.SIZE, 26f, 12, 15), yellow, Color.BLACK, false);
        AttributedString swapped = withAttribute(byAttributes(SCRIPTS, font, null, null), TextAttribute.SWAP_COLORS, TextAttribute.SWAP_COLORS_ON, 4, 11);
        expectBox("SWAP_COLORS (the graphics' black behind, the text white)", swapped, Color.BLACK, Color.WHITE, false);
        expectBox("SWAP_COLORS with a dark FOREGROUND (the text white)", withAttribute(withAttribute(byAttributes(SCRIPTS, font, null, null), TextAttribute.SWAP_COLORS, TextAttribute.SWAP_COLORS_ON, 4, 11),
                TextAttribute.FOREGROUND, blue, 4, 11), blue, Color.WHITE, false);
        expectBox("SWAP_COLORS with a light FOREGROUND (the text black)", withAttribute(withAttribute(byAttributes(SCRIPTS, font, null, null), TextAttribute.SWAP_COLORS, TextAttribute.SWAP_COLORS_ON, 4, 11),
                TextAttribute.FOREGROUND, light, 4, 11), light, Color.BLACK, false);
        expectBox("SWAP_COLORS with FOREGROUND and BACKGROUND (the text the background colour)", withAttribute(withAttribute(withAttribute(byAttributes(SCRIPTS, font, null, null),
                TextAttribute.SWAP_COLORS, TextAttribute.SWAP_COLORS_ON, 4, 11), TextAttribute.FOREGROUND, blue, 4, 11), TextAttribute.BACKGROUND, Color.ORANGE, 4, 11), blue, Color.ORANGE, false);
        paint = new java.awt.GradientPaint(0, 0, Color.BLACK, W, 0, Color.BLUE);
        expect("SWAP_COLORS over a gradient paint", swapped, false);
        paint = null;
        // no FONT and no FAMILY: TextLayout's StyledParagraph resolves the fonts per character (FontResolver), not the graphics' font
        expect("no attributes", new AttributedString(KERNED), true);
        expect("FOREGROUND only", withAttribute(new AttributedString(KERNED), TextAttribute.FOREGROUND, new Color(0x2050c0), 0, 6), true);
        expect("SIZE without FAMILY", withAttribute(new AttributedString(KERNED), TextAttribute.SIZE, 18f, 0, KERNED.length()), true);
        expect("WEIGHT_BOLD and UNDERLINE without FAMILY", withAttribute(withAttribute(new AttributedString(KERNED), TextAttribute.WEIGHT, TextAttribute.WEIGHT_BOLD, 0, 6),
                TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON, 3, 12), true);
        AttributedString mixed = new AttributedString(KERNED);
        mixed.addAttribute(TextAttribute.FONT, font, 0, 7);
        expect("a FONT run next to a run without font attributes", mixed, true);
        expect("several scripts without font attributes", withAttribute(new AttributedString("Text \u6f22\u5b57 \u2211\u2202\u0394 \u2192 \u2260 \u05e9\u05dc"), TextAttribute.SIZE, 16f, 0, 5), true);
        // plain drawString / drawChars / drawBytes in a font with layout attributes: SunGraphics2D hands the string to the
        // paragraph route as TextLayout(String, Font) lays it out (the font's attributes on the string, and the font)
        expectPlain("a font with KERNING_ON", font.deriveFont(Map.of(TextAttribute.KERNING, TextAttribute.KERNING_ON)), KERNED, true, 0);
        expectPlain("a font with LIGATURES_ON", font.deriveFont(Map.of(TextAttribute.LIGATURES, TextAttribute.LIGATURES_ON)), LIGATED, true, 0);
        expectPlain("a font with TRACKING", font.deriveFont(Map.of(TextAttribute.TRACKING, 0.1f)), KERNED, true, 0);
        expectPlain("a font with UNDERLINE_ON", font.deriveFont(Map.of(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON)), KERNED, true, 0);
        expectPlain("a font with STRIKETHROUGH_ON and KERNING_ON", font.deriveFont(Map.of(TextAttribute.STRIKETHROUGH, TextAttribute.STRIKETHROUGH_ON,
                TextAttribute.KERNING, TextAttribute.KERNING_ON)), KERNED, true, 0);
        expectPlain("a font with FOREGROUND", font.deriveFont(Map.of(TextAttribute.FOREGROUND, new Color(0x2050c0))), KERNED, true, 0);
        expectPlain("a font with BACKGROUND and SWAP_COLORS", font.deriveFont(Map.of(TextAttribute.BACKGROUND, Color.YELLOW,
                TextAttribute.SWAP_COLORS, TextAttribute.SWAP_COLORS_ON)), KERNED, true, -1);
        // a FONT attribute's own decorations and colours: TextLayout ignores them (it reads the iterator's), and so must the route
        expect("a FONT carrying UNDERLINE and FOREGROUND (ignored, as by TextLayout)", runs(KERNED, font.deriveFont(Map.of(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON,
                TextAttribute.FOREGROUND, Color.RED))), true);
        expectPlain("a font with SUPERSCRIPT_SUB", font.deriveFont(Map.of(TextAttribute.SUPERSCRIPT, TextAttribute.SUPERSCRIPT_SUB)), KERNED, true, hidpi ? 2 : 1);
        expectPlain("a font with WIDTH (TextLayout's)", font.deriveFont(Map.of(TextAttribute.WIDTH, TextAttribute.WIDTH_EXTENDED)), KERNED, false, 0);
        // keys that are not TextAttributes (TextLayout reads none of them) and the off values of UNDERLINE and STRIKETHROUGH
        expect("LANGUAGE, READING and INPUT_METHOD_SEGMENT", withAttribute(withAttribute(withAttribute(runs(KERNED, font),
                java.text.AttributedCharacterIterator.Attribute.LANGUAGE, java.util.Locale.GERMAN, 0, 6),
                java.text.AttributedCharacterIterator.Attribute.READING, new java.text.Annotation("r"), 3, 9),
                java.text.AttributedCharacterIterator.Attribute.INPUT_METHOD_SEGMENT, new java.text.Annotation("s"), 7, 14), true);
        expect("an application's own attribute key", withAttribute(byAttributes(KERNED, font, null, null), new java.text.AttributedCharacterIterator.Attribute("app.marker") {}, "x", 2, 12), true);
        expect("STRIKETHROUGH false", byAttributes(KERNED, font, TextAttribute.STRIKETHROUGH, Boolean.FALSE), true);
        expect("UNDERLINE -1 (none)", byAttributes(KERNED, font, TextAttribute.UNDERLINE, -1), true);
        expect("UNDERLINE_LOW_DOTTED (TextLayout's)", byAttributes(KERNED, font, TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_LOW_DOTTED), false);
        // bidi as TextLayout runs it: the base direction from RUN_DIRECTION or the first strong character, BIDI_EMBEDDING's
        // explicit levels (and overrides), NUMERIC_SHAPING from the first character; JUSTIFICATION changes nothing drawn
        final String hebrewFirst = "\u05e9\u05dc\u05d5\u05dd world 123, \u05e2\u05d5\u05dc\u05dd!";
        final String bidiMixed = "Hello \u05e9\u05dc\u05d5\u05dd world (1 2 3).";
        expect("Hebrew first (a right-to-left base)", runs(hebrewFirst, font), true);
        expect("RUN_DIRECTION_RTL on Latin-first text", withAttribute(runs(bidiMixed, font), TextAttribute.RUN_DIRECTION, TextAttribute.RUN_DIRECTION_RTL, 0, bidiMixed.length()), true);
        expect("RUN_DIRECTION_LTR on Hebrew-first text", withAttribute(runs(hebrewFirst, font), TextAttribute.RUN_DIRECTION, TextAttribute.RUN_DIRECTION_LTR, 0, hebrewFirst.length()), true);
        expect("BIDI_EMBEDDING 1 on a Latin word", withAttribute(runs(bidiMixed, font), TextAttribute.BIDI_EMBEDDING, 1, 0, 5), true);
        expect("BIDI_EMBEDDING 2 on a Hebrew word", withAttribute(runs(bidiMixed, font), TextAttribute.BIDI_EMBEDDING, 2, 6, 10), true);
        // overrides: the paragraph reaches them with Unicode's override controls (the standard algorithm, as AppKit's
        // writing-direction attribute), Java hands java.text.Bidi explicit levels, which resolve the neutrals next to
        // the override differently (measured: the parentheses after it): the overridden text alike, the rest accepted
        expectTaken("BIDI_EMBEDDING -1 (an override) on a Latin word", withAttribute(runs(bidiMixed, font), TextAttribute.BIDI_EMBEDDING, -1, 11, 16));
        expectTaken("BIDI_EMBEDDING -2 (an override) on a Hebrew word", withAttribute(runs(bidiMixed, font), TextAttribute.BIDI_EMBEDDING, -2, 6, 10));
        final String digits = "Price 123 and 4567, \u0627\u0644\u0633\u0639\u0631 89";
        expect("NUMERIC_SHAPING (Arabic digits)", withAttribute(runs(digits, font), TextAttribute.NUMERIC_SHAPING, java.awt.font.NumericShaper.getShaper(java.awt.font.NumericShaper.ARABIC), 0, digits.length()), true);
        expect("NUMERIC_SHAPING (contextual)", withAttribute(runs(digits, font), TextAttribute.NUMERIC_SHAPING,
                java.awt.font.NumericShaper.getContextualShaper(java.awt.font.NumericShaper.ARABIC | java.awt.font.NumericShaper.EASTERN_ARABIC), 0, digits.length()), true);
        expect("JUSTIFICATION_FULL", withAttribute(runs(KERNED, font), TextAttribute.JUSTIFICATION, TextAttribute.JUSTIFICATION_FULL, 0, KERNED.length()), true);
        expect("a FONT carrying RUN_DIRECTION_RTL (ignored, as by TextLayout)", runs(bidiMixed, font.deriveFont(Map.of(TextAttribute.RUN_DIRECTION, TextAttribute.RUN_DIRECTION_RTL))), true);
        expectShaped("a Hebrew-first string (the shaper)", font, hebrewFirst);
        expectShaped("an Arabic string with digits (the shaper)", font, "\u0627\u0644\u0633\u0639\u0631 89 Price");
        // a bold run falling back to a regular colour face: drawn in that face's own style, as the text path draws it
        // (SkParagraph emboldened it, and an emboldened bitmap glyph came out empty)
        expect("emoji in a bold run", runs("Bold \uD83D\uDE00\uD83D\uDC4D text", font.deriveFont(Font.BOLD)), true);
        // another layout attribute on the run's font: not the paragraph's (yet), and still drawn as TextLayout draws it
        expect("a font carrying WIDTH", runs(KERNED, font.deriveFont(Map.of(TextAttribute.WIDTH, TextAttribute.WIDTH_EXTENDED))), false);
        expect("WIDTH next to a FONT (ignored, as by TextLayout)", withAttribute(KERNED, font, TextAttribute.WIDTH, TextAttribute.WIDTH_EXTENDED), true);
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

    /** SCRIPTS on FAMILY and SIZE runs with SUPERSCRIPT (a level, or SUPERSCRIPT_SUPER / _SUB) on [from, to). */
    static AttributedString scripted(Font font, Object level, int from, int to) {
        return withAttribute(byAttributes(SCRIPTS, font, null, null), TextAttribute.SUPERSCRIPT, level, from, to);
    }

    static AttributedString withAttribute(AttributedString as, java.text.AttributedCharacterIterator.Attribute key, Object value, int from, int to) {
        as.addAttribute(key, value, from, to);
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
        VolatileImage vi = raster ? null : GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().createCompatibleVolatileImage(W, H);
        BufferedImage bi = raster ? new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB_PRE) : null;
        Graphics2D g = raster ? bi.createGraphics() : vi.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, fractional ? RenderingHints.VALUE_FRACTIONALMETRICS_ON : RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        g.setColor(Color.BLACK);
        if (paint != null) {
            g.setPaint(paint);
        }
        drawing.accept(g);
        g.dispose();
        return raster ? bi : vi.getSnapshot();
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

    /**
     * drawString(iterator) by Skia's paragraph engine with a box of the colour box: its edges within a pixel of
     * TextLayout's, the text inside it in the colour text (at least half as many pixels of it as TextLayout draws).
     */
    static void expectBox(String what, AttributedString as, Color box, Color text, boolean antialiased) throws Exception {
        Consumer<Graphics2D> aa = g -> g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                antialiased ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);
        long before = paragraphs();
        BufferedImage route = render(aa.andThen(g -> g.drawString(as.getIterator(), 10, 30)));
        long taken = paragraphs() - before;
        BufferedImage reference = render(aa.andThen(layout(as)));
        java.awt.Rectangle r = bounds(route, box), l = bounds(reference, box);
        int textRoute = count(route, text, l), textReference = count(reference, text, l);
        System.out.println(what + ": " + taken + " paragraphs by Skia, box " + r + " against TextLayout's " + l + ", "
                + textRoute + " text pixels against " + textReference);
        if (taken != 1) {
            throw new RuntimeException(what + ": " + taken + " paragraphs by Skia's paragraph engine (expected 1)");
        }
        if (l.isEmpty() || Math.abs(r.x - l.x) > 1 || Math.abs(r.y - l.y) > 1 || Math.abs(r.x + r.width - l.x - l.width) > 1
                || Math.abs(r.y + r.height - l.y - l.height) > 1) {
            throw new RuntimeException(what + ": the box " + r + " is not TextLayout's " + l + " within a pixel");
        }
        if (textReference == 0 || textRoute * 2 < textReference) {
            throw new RuntimeException(what + ": " + textRoute + " pixels of the text's colour in the box (TextLayout: " + textReference + ")");
        }
    }

    /** The bounds of the pixels of exactly the colour c. */
    static java.awt.Rectangle bounds(BufferedImage a, Color c) {
        java.awt.Rectangle r = null;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            if (a.getRGB(x, y) == c.getRGB()) {
                if (r == null) r = new java.awt.Rectangle(x, y, 1, 1); else r.add(new java.awt.Rectangle(x, y, 1, 1));
            }
        }
        return r != null ? r : new java.awt.Rectangle();
    }

    /** The pixels of exactly the colour c in the region. */
    static int count(BufferedImage a, Color c, java.awt.Rectangle region) {
        int n = 0;
        for (int y = region.y; y < region.y + region.height; y++) for (int x = region.x; x < region.x + region.width; x++) {
            if (a.getRGB(x, y) == c.getRGB()) n++;
        }
        return n;
    }

    /** A plain string through Skia's shaper against TextLayout(String, Font): identical pixels. */
    static void expectShaped(String what, Font f, String text) throws Exception {
        java.lang.reflect.Method shaped = Class.forName("sun.java2d.skia.SKEnvironment").getMethod("glyphsShapedBySkia");
        long before = (long) shaped.invoke(null);
        BufferedImage route = render(g -> { g.setFont(f); g.drawString(text, 10, 30); });
        long taken = (long) shaped.invoke(null) - before;
        int n = differing(route, render(g -> new TextLayout(text, f, g.getFontRenderContext()).draw(g, 10, 30)));
        System.out.println(what + ": " + taken + " glyphs shaped by Skia, " + n + " pixels apart from TextLayout");
        if (taken == 0) {
            throw new RuntimeException(what + ": not shaped by Skia");
        }
        if (n != 0) {
            throw new RuntimeException(what + ": drawn " + n + " pixels apart from TextLayout");
        }
    }

    /** drawString(iterator) by Skia's paragraph engine (its pixels printed against TextLayout's). */
    static void expectTaken(String what, AttributedString as) throws Exception {
        long before = paragraphs();
        BufferedImage route = render(g -> g.drawString(as.getIterator(), 10, 30));
        long taken = paragraphs() - before;
        System.out.println(what + ": " + taken + " paragraphs by Skia, " + differing(route, render(layout(as))) + " pixels apart from TextLayout");
        if (taken != 1) {
            throw new RuntimeException(what + ": " + taken + " paragraphs by Skia's paragraph engine (expected 1)");
        }
    }

    /**
     * drawString(iterator) by Skia's paragraph engine, each pixel column TextLayout's moved by at most maxRows
     * rows (a shifted run's whole-pixel baseline); with unshifted runs, some column moved by none (the line's own
     * baseline, which a line of shifted runs only may move).
     */
    static void expectShifted(String what, AttributedString as, int maxRows) throws Exception {
        expectShifted(what, as, maxRows, true);
    }

    static void expectShifted(String what, AttributedString as, int maxRows, boolean unshiftedRuns) throws Exception {
        long before = paragraphs();
        BufferedImage route = render(g -> g.drawString(as.getIterator(), 10, 30));
        long taken = paragraphs() - before;
        BufferedImage reference = render(layout(as));
        int[] moved = new int[2 * maxRows + 1];
        int blended = 0;
        int unmatched = -1;
        for (int x = 0; x < W && unmatched < 0; x++) {
            int found = Integer.MIN_VALUE;
            for (int dy = 0; dy <= maxRows && found == Integer.MIN_VALUE; dy++) {
                for (int d : dy == 0 ? new int[] {0} : new int[] {dy, -dy}) {
                    if (found == Integer.MIN_VALUE && columnMovedBy(route, reference, x, d)) {
                        found = d;
                    }
                }
            }
            if (found == Integer.MIN_VALUE && hidpi && inkMovedWithin(route, reference, x, maxRows)) {
                blended++;
            } else if (found == Integer.MIN_VALUE) {
                unmatched = x;
            } else if (!blankColumn(reference, x)) {
                moved[found + maxRows]++;
            }
        }
        System.out.println(what + ": " + taken + " paragraphs by Skia, " + differing(route, reference) + " pixels apart from TextLayout, inked columns by row offset "
                + java.util.Arrays.toString(moved) + " (-" + maxRows + ".." + maxRows + ")" + (hidpi ? ", " + blended + " moved by device rows" : ""));
        if (taken != 1) {
            throw new RuntimeException(what + ": " + taken + " paragraphs by Skia's paragraph engine (expected 1)");
        }
        if (unmatched >= 0) {
            throw new RuntimeException(what + ": column " + unmatched + " is not TextLayout's within " + maxRows + " rows");
        }
        if (unshiftedRuns && moved[maxRows] == 0) {
            throw new RuntimeException(what + ": no column on TextLayout's rows (the line's baseline moved)");
        }
    }

    /**
     * The four plain entries in the font against TextLayout(String, Font).draw (the old hook's drawing): taken by
     * the paragraph engine or not, identical pixels (maxRows 0) or each column within maxRows rows.
     */
    static void expectPlain(String what, Font f, String text, boolean byParagraph, int maxRows) throws Exception {
        java.util.List<Map.Entry<String, Consumer<Graphics2D>>> entries = java.util.List.of(
                Map.entry("drawString(float)", g -> { g.setFont(f); g.drawString(text, 10f, 30f); }),
                Map.entry("drawString(int)", g -> { g.setFont(f); g.drawString(text, 10, 30); }),
                Map.entry("drawChars", g -> { g.setFont(f); g.drawChars(("<" + text + ">").toCharArray(), 1, text.length(), 10, 30); }),
                Map.entry("drawBytes", g -> { g.setFont(f); g.drawBytes(text.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), 0, text.length(), 10, 30); }));
        BufferedImage reference = render(g -> new TextLayout(text, f, g.getFontRenderContext()).draw(g, 10, 30));
        for (Map.Entry<String, Consumer<Graphics2D>> e : entries) {
            long before = paragraphs();
            BufferedImage route = render(e.getValue());
            long taken = paragraphs() - before;
            int n = differing(route, reference);
            // maxRows -1: a background box, SkParagraph's (its edges within a pixel of TextLayout's, as expectBox)
            int unmatched = maxRows < 0 ? (boxWithinPixel(route, reference) ? -1 : 0)
                    : maxRows == 0 ? (n == 0 ? -1 : 0) : unmatchedColumn(route, reference, maxRows);
            System.out.println(what + ", " + e.getKey() + ": " + taken + " paragraphs by Skia, " + n + " pixels apart from TextLayout");
            if (taken != (byParagraph ? 1 : 0)) {
                throw new RuntimeException(what + ", " + e.getKey() + ": " + taken + " paragraphs by Skia's paragraph engine (expected " + (byParagraph ? 1 : 0) + ")");
            }
            if (unmatched >= 0) {
                throw new RuntimeException(what + ", " + e.getKey() + ": drawn " + n + " pixels apart from TextLayout"
                        + (maxRows > 0 ? " (column " + unmatched + " beyond " + maxRows + " rows)" : ""));
            }
        }
    }

    /** The non-white pixels' bounds of a within a pixel of b's on every edge. */
    static boolean boxWithinPixel(BufferedImage a, BufferedImage b) {
        java.awt.Rectangle r = inked(a), l = inked(b);
        return !l.isEmpty() && Math.abs(r.x - l.x) <= 1 && Math.abs(r.y - l.y) <= 1
                && Math.abs(r.x + r.width - l.x - l.width) <= 1 && Math.abs(r.y + r.height - l.y - l.height) <= 1;
    }

    static java.awt.Rectangle inked(BufferedImage a) {
        java.awt.Rectangle r = null;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            if (a.getRGB(x, y) != 0xffffffff) {
                if (r == null) r = new java.awt.Rectangle(x, y, 1, 1); else r.add(new java.awt.Rectangle(x, y, 1, 1));
            }
        }
        return r != null ? r : new java.awt.Rectangle();
    }

    /** The first column of a that is not b's moved by at most maxRows rows (2x: by its ink's centroid), or -1. */
    static int unmatchedColumn(BufferedImage a, BufferedImage b, int maxRows) {
        for (int x = 0; x < W; x++) {
            boolean found = false;
            for (int dy = -maxRows; dy <= maxRows && !found; dy++) {
                found = columnMovedBy(a, b, x, dy);
            }
            if (!found && !(hidpi && inkMovedWithin(a, b, x, maxRows))) {
                return x;
            }
        }
        return -1;
    }

    /** Column x of a equals column x of b moved down by dy rows (rows moved in from outside are blank). */
    static boolean columnMovedBy(BufferedImage a, BufferedImage b, int x, int dy) {
        for (int y = 0; y < H; y++) {
            int from = y - dy;
            int expected = from >= 0 && from < H ? b.getRGB(x, from) : 0xffffffff;
            if (a.getRGB(x, y) != expected) {
                return false;
            }
        }
        return true;
    }

    /**
     * Column x of a inked where b's is, its ink's centroid moved by at most maxRows rows (the sampled snapshot
     * changes a column's ink when it moves by a device row: a thin stroke lands on another sample).
     */
    static boolean inkMovedWithin(BufferedImage a, BufferedImage b, int x, int maxRows) {
        double inkA = 0, inkB = 0, momentA = 0, momentB = 0;
        for (int y = 0; y < H; y++) {
            int ia = 255 - (a.getRGB(x, y) & 0xff), ib = 255 - (b.getRGB(x, y) & 0xff);
            inkA += ia; inkB += ib; momentA += ia * y; momentB += ib * y;
        }
        if (inkB == 0) {
            return inkA == 0;
        }
        return inkA > 0 && Math.abs(momentA / inkA - momentB / inkB) <= maxRows;
    }

    static boolean blankColumn(BufferedImage a, int x) {
        for (int y = 0; y < H; y++) if (a.getRGB(x, y) != 0xffffffff) return false;
        return true;
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
