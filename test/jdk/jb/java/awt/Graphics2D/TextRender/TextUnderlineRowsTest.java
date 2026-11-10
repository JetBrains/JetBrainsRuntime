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
 * @summary Under the system decorations on macOS (sun.java2d.skia.text.decorations=system, the default) an underline
 *          takes the rows CoreText draws it on, as NSAttributedString string drawing and TextKit 2 do (measured
 *          2026-11-10 in an NSView at 1x and 2x backing scale, akbaseprobe.swift and aktk2probe.swift: 893 of 893 cases
 *          over 30 fonts follow the rule of sun.font.TextDecorations.underlineBand). The rows expected here are
 *          CoreText's. The earlier rule (rounded about the position, at least its own height below) missed 69 of
 *          them: a one-row line once past the first row sits two rows below the baseline (Helvetica 18 at 1x: row 2,
 *          not 1), and the band ends within the descent (Courier New 13 at 1x: row 2, not 3). TextLayout on the
 *          loops with the JDK scaler and the Skia scaler, and the paragraph route on a raster surface.
 * @requires os.family == "mac"
 * @run main/othervm -Djava.awt.headless=true TextUnderlineRowsTest layout
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.font.scaler=skia TextUnderlineRowsTest layout
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.skia.raster=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.layout=skia TextUnderlineRowsTest paragraph
 */
public class TextUnderlineRowsTest {
    static final String TEXT = "x          x";
    static final int BASELINE = 100;

    /** family, size, then CoreText's top row (below the baseline) and row count at 1x and at 2x. */
    static final Object[][] CORE_TEXT = {
            {"Helvetica Neue", 13, 1, 1, 2, 2},
            {"Helvetica", 9, 1, 1, 2, 1},
            {"Helvetica", 18, 2, 1, 2, 2},
            {"Menlo", 22, 2, 1, 2, 2},
            {"Avenir Next", 14, 2, 1, 2, 2},
            {"Times", 16, 1, 1, 2, 2},
            {"Trebuchet MS", 9, 1, 1, 2, 2},
            {"Courier New", 9, 1, 1, 4, 1},
            {"Courier New", 13, 2, 1, 5, 2},
            {"PT Serif", 20, 2, 1, 2, 2},
            {"Didot", 16, 2, 1, 2, 2},
            {"Georgia", 28, 2, 2, 3, 3},
            {"Arial", 16, 2, 2, 3, 3},
            {"Monaco", 14, 2, 2, 3, 3},
    };

    public static void main(String[] args) {
        boolean paragraph = args[0].equals("paragraph");
        StringBuilder failures = new StringBuilder();
        for (Object[] c : CORE_TEXT) {
            for (int scale = 1; scale <= 2; scale++) {
                int top = (int) c[2 * scale], rows = (int) c[2 * scale + 1];
                String seen = rows((String) c[0], (int) c[1], scale, paragraph);
                String expected = top + "," + rows;
                System.out.println(c[0] + " " + c[1] + " at " + scale + "x: " + seen + " (CoreText " + expected + ")");
                if (!seen.equals(expected)) {
                    failures.append(c[0]).append(' ').append(c[1]).append(" at ").append(scale).append("x: rows ").append(seen)
                            .append(", CoreText's ").append(expected).append("; ");
                }
            }
        }
        if (failures.length() > 0) {
            throw new RuntimeException(failures.toString());
        }
        System.out.println("PASSED");
    }

    /** The underline's whole rows over the spaces as "top,count", the top from the device baseline. */
    static String rows(String family, int size, int scale, boolean paragraph) {
        Map<TextAttribute, Object> attributes = new HashMap<>();
        attributes.put(TextAttribute.FAMILY, family);
        attributes.put(TextAttribute.SIZE, (float) size);
        attributes.put(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON);
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
        // over the middle of the spaces: the underline's pixels alone
        int x1 = (int) Math.ceil((10 + glyph + 1.5) * scale), x2 = (int) ((10 + tl.getAdvance() - glyph - 1.5) * scale);
        int top = Integer.MIN_VALUE, count = 0;
        for (int y = BASELINE * scale; y < h; y++) {
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
