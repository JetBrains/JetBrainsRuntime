/*
 * Copyright 2023 JetBrains s.r.o.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

import com.jetbrains.FontMetricsAccessor;
import com.jetbrains.JBR;

import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

/**
 * @test
 * @summary verify the implementation of FontMetricsAccessor in JBR API
 */

public class FontMetricsAccessorTest {
    private static final FontMetricsAccessor ACCESSOR = JBR.getFontMetricsAccessor();
    private static final Font FONT = new Font(Font.SERIF, Font.ITALIC, 12);
    private static final FontRenderContext CONTEXT = new FontRenderContext(AffineTransform.getScaleInstance(2, 2),
            true, true);

    public static void main(final String[] args) {
        if (!JBR.isFontMetricsAccessorSupported()) {
            throw new RuntimeException("JBR FontMetricsAccessor API is not available");
        }
        testGetMetricsInstance();
        testNotRoundedMetrics();
        testOverriding();
        testRemoveAllOverrides();
    }

    private static void testGetMetricsInstance() {
        BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setTransform(CONTEXT.getTransform());
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, CONTEXT.getAntiAliasingHint());
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, CONTEXT.getFractionalMetricsHint());
        g.setFont(FONT);

        FontMetrics expectedFromGraphics = g.getFontMetrics();
        FontMetrics actualFromAccessor = ACCESSOR.getMetrics(g.getFont(), g.getFontRenderContext());
        if (actualFromAccessor != expectedFromGraphics) {
            throw new RuntimeException("Font metrics instance doesn't match one obtained from Graphics2D");
        }
    }

    private static void testNotRoundedMetrics() {
        FontMetrics baseMetrics = ACCESSOR.getMetrics(FONT, CONTEXT);
        for (char character = 'A'; character <= 'Z'; character++) {
            int roundedAdvance = baseMetrics.charWidth(character);
            float notRoundedAdvance = ACCESSOR.codePointWidth(baseMetrics, character);
            if (Math.round(notRoundedAdvance) != roundedAdvance) {
                throw new RuntimeException("Unexpected advance returned: notRoundedAdvance=" + notRoundedAdvance +
                        ", roundedAdvance=" + roundedAdvance + ", character=" + character);
            }
        }

        float baseAdvance = ACCESSOR.codePointWidth(baseMetrics, 'A');
        for (int scale = 2; scale <= 10; scale++) {
            Font scaledFont = FONT.deriveFont(FONT.getSize2D() / scale);
            float scaledAdvance = ACCESSOR.codePointWidth(ACCESSOR.getMetrics(scaledFont, CONTEXT), 'A');
            if (Math.abs(scaledAdvance * scale - baseAdvance) > 0.1) {
                throw new RuntimeException("Unexpected advance returned: baseAdvance=" + baseAdvance +
                        ", scaledAdvance=" + scaledAdvance + ", scale=" + scale);
            }
        }
    }

    private static void testOverriding() {
        FontMetrics metrics = ACCESSOR.getMetrics(FONT, CONTEXT);

        if (ACCESSOR.hasOverride(metrics)) {
            throw new RuntimeException("Override is reported incorrectly");
        }

        float aWidth = ACCESSOR.codePointWidth(metrics, 'A');
        float bWidth = ACCESSOR.codePointWidth(metrics, 'B');
        float bWidthOverride = bWidth * 2;

        ACCESSOR.setOverride(metrics, cp -> cp == 'B' ? bWidthOverride : Float.NaN);

        if (!ACCESSOR.hasOverride(metrics)) {
            throw new RuntimeException("Override is not reported");
        }

        float aWidthAfterOverride = ACCESSOR.codePointWidth(metrics, 'A');
        float bWidthAfterOverride = ACCESSOR.codePointWidth(metrics, 'B');

        if (aWidthAfterOverride != aWidth) {
            throw new RuntimeException("Override works where it shouldn't: aWidthAfterOverride=" + aWidthAfterOverride +
                    ", aWidth=" + aWidth);
        }
        if (bWidthAfterOverride != bWidthOverride) {
            throw new RuntimeException("Override doesn't work: bWidthAfterOverride=" + bWidthAfterOverride +
                    ", bWidthOverride=" + bWidthOverride);
        }

        ACCESSOR.setOverride(metrics, null);

        if (ACCESSOR.hasOverride(metrics)) {
            throw new RuntimeException("Override is reported after clearing");
        }

        float aWidthAfterReset = ACCESSOR.codePointWidth(metrics, 'A');
        float bWidthAfterReset = ACCESSOR.codePointWidth(metrics, 'B');

        if (aWidthAfterReset != aWidth) {
            throw new RuntimeException("Override has an effect after reset: aWidthAfterReset=" + aWidthAfterReset +
                    ", aWidth=" + aWidth);
        }
        if (bWidthAfterReset != bWidth) {
            throw new RuntimeException("Override has an effect after reset: bWidthAfterReset=" + bWidthAfterReset +
                    ", bWidth=" + bWidth);
        }
    }

    private static void testRemoveAllOverrides() {
        FontMetrics m1 = ACCESSOR.getMetrics(FONT, CONTEXT);
        FontMetrics m2 = ACCESSOR.getMetrics(FONT.deriveFont(24f), CONTEXT);

        float m1Width = ACCESSOR.codePointWidth(m1, 'A');
        float m2Width = ACCESSOR.codePointWidth(m2, 'B');

        ACCESSOR.setOverride(m1, cp -> 12345f);
        ACCESSOR.setOverride(m2, cp -> 67890f);
        ACCESSOR.removeAllOverrides();

        if (ACCESSOR.hasOverride(m1) || ACCESSOR.hasOverride(m2)) {
            throw new RuntimeException("Override is reported after clearing");
        }

        float m1WidthAfterReset = ACCESSOR.codePointWidth(m1, 'A');
        float m2WidthAfterReset = ACCESSOR.codePointWidth(m2, 'B');

        if (m1WidthAfterReset != m1Width || m2WidthAfterReset != m2Width) {
            throw new RuntimeException("Override has an effect after reset: m1Width=" + m1Width + ", m2Width=" + m2Width
                    + ", m1WidthAfterReset=" + m1WidthAfterReset + ", m2WidthAfterReset=" + m2WidthAfterReset);
        }
    }
}
