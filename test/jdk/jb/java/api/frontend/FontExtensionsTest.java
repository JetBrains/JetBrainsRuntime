/*
 * Copyright 2000-2023 JetBrains s.r.o.
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

/*
  @test
  @summary checking visualisation of drawing text with custom attributes
  @run main FontExtensionsTest
*/


import com.jetbrains.FontExtensions;
import com.jetbrains.JBR;

import java.awt.*;
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

public class FontExtensionsTest {
    @Retention(RetentionPolicy.RUNTIME)
    private @interface JBRTest {}
    private static final int IMG_WIDTH = 500;
    private static final int IMG_HEIGHT = 50;
    private static final String LIGATURES_STRING = "== != -> <>";
    private static final String FRACTION_STRING = "1/2";
    private static final String ZERO_STRING = "0";
    private static final String TEST_STRING = "hello abc 012345 " + FRACTION_STRING + LIGATURES_STRING;
    private static final Font BASE_FONT = new Font("JetBrains Mono", Font.PLAIN, 20);

    private static BufferedImage getImageWithString(Font font, String str) {
        BufferedImage img = new BufferedImage(IMG_WIDTH, IMG_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics g = img.getGraphics();
        g.setColor(Color.white);
        g.fillRect(0, 0, IMG_WIDTH, IMG_HEIGHT);

        g.setColor(Color.black);
        g.setFont(font);
        g.drawString(str, 20, 20);
        g.dispose();

        return img;
    }

    private static Boolean isImageEquals(BufferedImage first, BufferedImage second) {
        assert first.getWidth() == second.getWidth() && first.getHeight() == second.getHeight();
        for (int y = 0; y < first.getHeight(); y++) {
            for (int x = 0; x < first.getWidth(); x++) {
                if (first.getRGB(x, y) != second.getRGB(x, y)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Font fontWithFeatures(Font font, FontExtensions.FeatureTag ... features) {
        Set<FontExtensions.Feature> featureSet = new HashSet<>(Arrays.stream(features).map(FontExtensions.Feature::new).toList());
        return JBR.getFontExtensions().deriveFontWithFeatures(font, new FontExtensions.Features(featureSet));
    }

    private static Font fontWithFeatures(FontExtensions.FeatureTag ... features) {
        return fontWithFeatures(BASE_FONT, features);
    }

    private static Boolean textDrawingEquals(Font font1, Font font2, String text) {
        BufferedImage image1 = getImageWithString(font1, text);
        BufferedImage image2 = getImageWithString(font2, text);
        return isImageEquals(image1, image2);
    }

    @JBRTest
    private static Boolean testFeatureFromString() {
        return  FontExtensions.Features.getFeatureTag("frac").isPresent() &&
                FontExtensions.Features.getFeatureTag("FrAc").isPresent() &&
                FontExtensions.Features.getFeatureTag("ss10").isPresent() &&
                FontExtensions.Features.getFeatureTag("tttt").isEmpty();
    }

    @JBRTest
    private static Boolean testFeaturesToString1() {
        Font font = JBR.getFontExtensions().deriveFontWithFeatures(BASE_FONT, new FontExtensions.Features(Set.of(
                new FontExtensions.Feature(FontExtensions.FeatureTag.ZERO),
                new FontExtensions.Feature(FontExtensions.FeatureTag.SALT, 123),
                new FontExtensions.Feature(FontExtensions.FeatureTag.FRAC, 0))));
        return JBR.getFontExtensions().getFeaturesAsString(font).equals("calt=0 frac=0 kern=0 liga=0 salt=123 zero=1 ");
    }

    @JBRTest
    private static Boolean testFeaturesToString2() {
        Font font = BASE_FONT.deriveFont(Map.of(TextAttribute.LIGATURES, TextAttribute.LIGATURES_ON,
                TextAttribute.KERNING, TextAttribute.KERNING_ON));
        return JBR.getFontExtensions().getFeaturesAsString(font).equals("calt=1 kern=1 liga=1 ");
    }

    @JBRTest
    private static Boolean testDisablingLigatureByDefault() {
        return textDrawingEquals(BASE_FONT, fontWithFeatures(FontExtensions.FeatureTag.ZERO), LIGATURES_STRING);
    }

    @JBRTest
    private static Boolean testSettingValuesToFeatures() {
        Font font = JBR.getFontExtensions().deriveFontWithFeatures(BASE_FONT, new FontExtensions.Features(Set.of(
                new FontExtensions.Feature(FontExtensions.FeatureTag.ZERO, 1),
                new FontExtensions.Feature(FontExtensions.FeatureTag.SALT, 123),
                new FontExtensions.Feature(FontExtensions.FeatureTag.FRAC, 9999))));
        return textDrawingEquals(fontWithFeatures(FontExtensions.FeatureTag.FRAC, FontExtensions.FeatureTag.ZERO,
                FontExtensions.FeatureTag.SALT), font, TEST_STRING);
    }

    @JBRTest
    private static Boolean testLigature() {
        Font font = BASE_FONT.deriveFont(Map.of(TextAttribute.LIGATURES, TextAttribute.LIGATURES_ON));
        return !textDrawingEquals(BASE_FONT, font, LIGATURES_STRING);
    }

    // TODO include this test when will be found suitable example
    // @JBRTest
    private static Boolean testKerning() {
        Font font = BASE_FONT.deriveFont(Map.of(TextAttribute.KERNING, TextAttribute.KERNING_ON));
        return !textDrawingEquals(BASE_FONT, font, TEST_STRING);
    }

    @JBRTest
    private static Boolean testTracking() {
        Font font = BASE_FONT.deriveFont(Map.of(TextAttribute.TRACKING, 0.3f));
        return !textDrawingEquals(BASE_FONT, font, TEST_STRING);
    }

    @JBRTest
    private static Boolean testWeight() {
        Font font = BASE_FONT.deriveFont(Map.of(TextAttribute.WEIGHT, TextAttribute.WEIGHT_BOLD));
        return !textDrawingEquals(BASE_FONT, font, TEST_STRING);
    }

    @JBRTest
    private static Boolean testFeaturesZero() {
        return !textDrawingEquals(BASE_FONT, fontWithFeatures(FontExtensions.FeatureTag.ZERO), ZERO_STRING);
    }

    @JBRTest
    private static Boolean testFeaturesFrac() {
        return !textDrawingEquals(BASE_FONT, fontWithFeatures(FontExtensions.FeatureTag.FRAC), FRACTION_STRING);
    }

    @JBRTest
    private static Boolean testFeaturesZeroFrac() {
        Font fontFZ = fontWithFeatures(FontExtensions.FeatureTag.FRAC, FontExtensions.FeatureTag.ZERO);
        return  !textDrawingEquals(fontFZ, fontWithFeatures(FontExtensions.FeatureTag.FRAC), ZERO_STRING + " " + FRACTION_STRING) &&
                !textDrawingEquals(fontFZ, fontWithFeatures(FontExtensions.FeatureTag.ZERO), ZERO_STRING + " " + FRACTION_STRING);
    }

    @JBRTest
    private static Boolean testFeaturesDerive1() {
        Font fontFZ1 = fontWithFeatures(FontExtensions.FeatureTag.FRAC, FontExtensions.FeatureTag.ZERO).
                deriveFont(Map.of(TextAttribute.LIGATURES, TextAttribute.LIGATURES_ON));
        Font fontFZ2 = fontWithFeatures(BASE_FONT.deriveFont(Map.of(TextAttribute.LIGATURES, TextAttribute.LIGATURES_ON)),
                FontExtensions.FeatureTag.FRAC, FontExtensions.FeatureTag.ZERO);
        return  textDrawingEquals(fontFZ1, fontFZ2, TEST_STRING);
    }

    @JBRTest
    private static Boolean testFeaturesDerive2() {
        Font fontFZ1 = fontWithFeatures(FontExtensions.FeatureTag.FRAC, FontExtensions.FeatureTag.ZERO);
        Font fontFZ2 = fontWithFeatures(BASE_FONT.deriveFont(Map.of(TextAttribute.LIGATURES, TextAttribute.LIGATURES_ON)),
                FontExtensions.FeatureTag.FRAC, FontExtensions.FeatureTag.ZERO);
        fontFZ2 = fontFZ2.deriveFont(Map.of(TextAttribute.LIGATURES, false));
        return  textDrawingEquals(fontFZ1, fontFZ2, TEST_STRING);
    }

    @JBRTest
    private static Boolean testTrackingWithFeatures() {
        Font font = BASE_FONT.deriveFont(Map.of(TextAttribute.TRACKING, 0.3f));
        return !textDrawingEquals(font, fontWithFeatures(font, FontExtensions.FeatureTag.ZERO), TEST_STRING);
    }

    @JBRTest
    private static Boolean testFeaturesEmpty() {
        return textDrawingEquals(BASE_FONT, fontWithFeatures(), TEST_STRING);
    }

    public static void main(final String[] args) {
        if (!JBR.isFontExtensionsSupported()) {
            throw new RuntimeException("JBR FontExtension API is not available");
        }

        String error = "";
        for (final Method method : FontExtensionsTest.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(JBRTest.class)) {
                try {
                    if (method.invoke(null) == Boolean.FALSE) {
                        error += "Test failed: " + method.getName() + System.lineSeparator();
                    }
                } catch (IllegalAccessException | InvocationTargetException e) {
                    throw new RuntimeException("JBR: internal error during testing");
                }
            }
        }
        if (!error.isEmpty()) {
            throw new RuntimeException(System.lineSeparator() + error);
        }
    }
}
