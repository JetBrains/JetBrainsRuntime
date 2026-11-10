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
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.text.AttributedString;
import java.util.HashMap;
import java.util.Map;

/**
 * @test
 * @summary Under the system decorations on macOS (sun.java2d.skia.text.decorations=system, the default) underline and
 *          strikethrough leave out the line's leading and trailing whitespace, as CoreText does (measured 2026-11-10:
 *          CTLineDraw underlines "x          " under the x alone and a line of spaces not at all). CoreText's set: the
 *          space separators (Unicode Zs: the no-break, em and ideographic spaces too) and line terminators; a tab is
 *          ink (decorated at either end, and the spaces before a final tab are not trailing); whitespace between ink
 *          stays decorated. TextLayout decorated the whole line (ledger §18a T19), the
 *          paragraph route the leading whitespace (SkParagraph's lines leave the trailing out) — the two routes
 *          differed. Each case: the decoration's columns (the decorated rendering against the plain one on the band's
 *          rows) must span the ink's characters, from the first non-whitespace character's caret to the last's, within
 *          a pixel; and TextLayout's outline must match. Several runs in one line (a coloured middle) too. A tab is
 *          Java's on every route, an invisible glyph without advance (TextIgnorableWhitespaceTest).
 * @requires os.family == "mac"
 * @run main/othervm -Djava.awt.headless=true TextWhitespaceDecorationsTest layout
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.skia.raster=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.layout=skia TextWhitespaceDecorationsTest paragraph
 */
public class TextWhitespaceDecorationsTest {
    static final int W = 320, H = 60, X = 10, BASELINE = 30;
    static final String[] TEXTS = {"x      ", "      x", "      ", "x   x", "  x x  ", "\tab c\t ", "\u00a0x\u2003\u3000", "x  \t", "x   \n"};

    /** CoreText's line-end whitespace: the space separators and the line terminators, not the tab. */
    static boolean trimmed(char c) {
        return Character.getType(c) == Character.SPACE_SEPARATOR || c == '\n' || c == '\r' || c == '\u0085' || c == '\u2028' || c == '\u2029';
    }

    public static void main(String[] args) {
        boolean paragraph = args[0].equals("paragraph");
        StringBuilder failures = new StringBuilder();
        for (TextAttribute decoration : new TextAttribute[] {TextAttribute.UNDERLINE, TextAttribute.STRIKETHROUGH}) {
            for (String text : TEXTS) {
                for (boolean runs : new boolean[] {false, true}) {
                    String what = (decoration == TextAttribute.UNDERLINE ? "underline" : "strikethrough") + " of \""
                            + text.replace("\t", "\\t").replace("\n", "\\n").replace("\u00a0", "\\u00a0").replace("\u2003", "\\u2003").replace("\u3000", "\\u3000") + "\"" + (runs ? " in three runs" : "");
                    String failure = check(text, decoration, runs, paragraph);
                    System.out.println(what + ": " + (failure == null ? "ok" : failure));
                    if (failure != null) {
                        failures.append(what).append(": ").append(failure).append("; ");
                    }
                }
            }
        }
        if (failures.length() > 0) {
            throw new RuntimeException(args[0] + ": " + failures);
        }
        System.out.println("PASSED");
    }

    static AttributedString attributed(String text, TextAttribute decoration, boolean runs, boolean decorated) {
        Map<TextAttribute, Object> attributes = new HashMap<>();
        attributes.put(TextAttribute.FAMILY, "Helvetica Neue");
        attributes.put(TextAttribute.SIZE, 13f);
        if (decorated) {
            attributes.put(decoration, decoration == TextAttribute.UNDERLINE ? TextAttribute.UNDERLINE_ON : TextAttribute.STRIKETHROUGH_ON);
        }
        AttributedString as = new AttributedString(text, attributes);
        if (runs && text.length() >= 3) {
            as.addAttribute(TextAttribute.FOREGROUND, new Color(0, 0, 1), 1, text.length() - 1);   // all but the first and last character
        }
        return as;
    }

    static BufferedImage render(String text, TextAttribute decoration, boolean runs, boolean decorated, boolean paragraph) {
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.setColor(Color.BLACK);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        AttributedString as = attributed(text, decoration, runs, decorated);
        if (paragraph) {
            g.drawString(as.getIterator(), X, BASELINE);
        } else {
            new TextLayout(as.getIterator(), g.getFontRenderContext()).draw(g, X, BASELINE);
        }
        g.dispose();
        return image;
    }

    /** Null when the decoration spans the ink's characters, else what is wrong. */
    static String check(String text, TextAttribute decoration, boolean runs, boolean paragraph) {
        BufferedImage plain = render(text, decoration, runs, false, paragraph), decorated = render(text, decoration, runs, true, paragraph);
        // the columns the decoration adds ink to (any row: it is the only difference)
        int x1 = W, x2 = -1;
        for (int x = 0; x < W; x++) {
            for (int y = 0; y < H; y++) {
                int d = (plain.getRGB(x, y) & 0xff) - (decorated.getRGB(x, y) & 0xff);
                if (d > 96) { x1 = Math.min(x1, x); x2 = Math.max(x2, x); break; }
            }
        }
        // the ink's characters: from the first non-whitespace character to the last, by TextLayout's carets
        int first = 0, last = text.length();
        while (first < last && trimmed(text.charAt(first))) first++;
        while (last > first && trimmed(text.charAt(last - 1))) last--;
        java.awt.font.FontRenderContext frc = new java.awt.font.FontRenderContext(null, true, false);
        TextLayout tl = new TextLayout(attributed(text, decoration, runs, true).getIterator(), frc);
        if (first == last) {
            if (x2 >= 0) return "decorated columns " + x1 + ".." + x2 + " on a line of whitespace";
            Rectangle2D outline = tl.getOutline(null).getBounds2D();
            return outline.isEmpty() ? null : "the outline holds " + outline + " on a line of whitespace";
        }
        double left = X + tl.getCaretInfo(java.awt.font.TextHitInfo.leading(first))[0];
        double right = X + tl.getCaretInfo(java.awt.font.TextHitInfo.leading(last))[0];
        if (x2 < 0) return "no decoration drawn (the ink spans " + left + ".." + right + ")";
        if (Math.abs(x1 - left) > 1 || Math.abs(x2 + 1 - right) > 1) {
            return "decorated columns " + x1 + ".." + x2 + ", the ink's characters span " + String.format("%.2f..%.2f", left, right);
        }
        if (!paragraph) {
            // the outline's decoration part: the decorated layout's outline against the plain one's, in x
            TextLayout plainLayout = new TextLayout(attributed(text, decoration, runs, false).getIterator(), frc);
            Rectangle2D outline = tl.getOutline(null).getBounds2D(), glyphs = plainLayout.getOutline(null).getBounds2D();
            double oLeft = X + Math.min(outline.getMinX(), glyphs.getMinX()), oRight = X + Math.max(outline.getMaxX(), glyphs.getMaxX());
            if (outline.getMinX() < glyphs.getMinX() - 1 || outline.getMaxX() > Math.max(glyphs.getMaxX(), right - X) + 1) {
                return String.format("the outline spans %.2f..%.2f, beyond the ink's characters %.2f..%.2f", oLeft, oRight, left, right);
            }
        }
        return null;
    }
}
