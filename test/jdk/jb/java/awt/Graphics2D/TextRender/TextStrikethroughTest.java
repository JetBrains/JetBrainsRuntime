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
import java.awt.image.BufferedImage;
import java.text.AttributedString;
import java.util.HashMap;
import java.util.Map;

/**
 * @test
 * @summary Under the system decorations on macOS (sun.java2d.skia.text.decorations=system, the default) a strikethrough
 *          sits where AppKit draws it: centred half the x-height above the baseline, as many whole device rows as the
 *          font's underline thickness rounds up to, the top row the rounded top of that band. Measured 2026-11-10 with
 *          NSAttributedString in an NSView at 1x and 2x backing scale (akviewprobe.swift; 32 of 32 cases over 16 fonts
 *          follow the rule). The rows expected here are AppKit's. Both scalers took the OS/2 table's strikeout instead
 *          (Lucida Grande 13: rows -6 where AppKit's is -4; Helvetica Neue 40: -12 against -11) and rounded the thickness
 *          (Menlo 12 at 2x: one row where AppKit draws two). TextLayout on the loops with the JDK scaler and the Skia
 *          scaler, and the paragraph route on a raster surface.
 * @requires os.family == "mac"
 * @run main/othervm -Djava.awt.headless=true TextStrikethroughTest layout
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.font.scaler=skia TextStrikethroughTest layout
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.skia.raster=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.layout=skia TextStrikethroughTest paragraph
 */
public class TextStrikethroughTest {
    static final String TEXT = "x          x";
    static final int BASELINE = 100;

    /** family, size, then AppKit's top row (from the baseline, negative above) and row count at 1x and at 2x. */
    static final Object[][] APPKIT = {
            {"Helvetica Neue", 13, -4, 1, -8, 2},
            {"Helvetica Neue", 26, -8, 2, -15, 3},
            {"Helvetica Neue", 40, -11, 2, -23, 4},
            {"Menlo", 12, -4, 1, -8, 2},
            {"Menlo", 20, -6, 1, -12, 2},
            {"Georgia", 14, -4, 1, -8, 2},
            {"Georgia", 28, -8, 2, -15, 3},
            {"Verdana", 13, -4, 1, -8, 2},
            {"Verdana", 22, -7, 2, -14, 3},
            {"Courier New", 13, -3, 1, -6, 2},
            {"Lucida Grande", 13, -4, 1, -8, 2},
            {"Monaco", 12, -4, 1, -8, 2},
            {"Arial", 13, -4, 1, -8, 2},
    };

    public static void main(String[] args) {
        boolean paragraph = args[0].equals("paragraph");
        StringBuilder failures = new StringBuilder();
        for (Object[] c : APPKIT) {
            for (int scale = 1; scale <= 2; scale++) {
                int top = (int) c[2 * scale], rows = (int) c[2 * scale + 1];
                String seen = rows((String) c[0], (int) c[1], scale, paragraph);
                String expected = top + "," + rows;
                System.out.println(c[0] + " " + c[1] + " at " + scale + "x: " + seen + " (AppKit " + expected + ")");
                if (!seen.equals(expected)) {
                    failures.append(c[0]).append(' ').append(c[1]).append(" at ").append(scale).append("x: rows ").append(seen)
                            .append(", AppKit's ").append(expected).append("; ");
                }
            }
        }
        if (failures.length() > 0) {
            throw new RuntimeException(failures.toString());
        }
        System.out.println("PASSED");
    }

    /** The strikethrough's whole rows over the spaces as "top,count", the top from the device baseline. */
    static String rows(String family, int size, int scale, boolean paragraph) {
        Map<TextAttribute, Object> attributes = new HashMap<>();
        attributes.put(TextAttribute.FAMILY, family);
        attributes.put(TextAttribute.SIZE, (float) size);
        attributes.put(TextAttribute.STRIKETHROUGH, TextAttribute.STRIKETHROUGH_ON);
        int w = 400 * scale, h = 160 * scale;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        g.scale(scale, scale);
        TextLayout tl = new TextLayout(TEXT, new Font(attributes), g.getFontRenderContext());
        double glyph = new TextLayout("x", new Font(attributes), g.getFontRenderContext()).getAdvance();
        if (paragraph) {
            g.drawString(new AttributedString(TEXT, attributes).getIterator(), 10f, BASELINE);
        } else {
            tl.draw(g, 10, BASELINE);
        }
        g.dispose();
        // over the first half of the spaces: the strikethrough's pixels alone
        int x1 = (int) Math.ceil((10 + glyph + 1.5) * scale), x2 = (int) ((10 + glyph + 1.5 + (tl.getAdvance() - 2 * glyph - 3) / 2) * scale);
        int top = Integer.MIN_VALUE, count = 0;
        for (int y = 0; y < BASELINE * scale; y++) {
            double c = 0;
            for (int x = x1; x < x2; x++) c += (255 - (image.getRGB(x, y) >> 8 & 0xff)) / 255.0;
            if (c / (x2 - x1) > 0.9) {
                if (top == Integer.MIN_VALUE) top = y - BASELINE * scale;
                count++;
            }
        }
        return top == Integer.MIN_VALUE ? "none" : top + "," + count;
    }
}
