package com.jetbrains.desktop;

import com.jetbrains.internal.JBRApi;
import sun.font.FontStrikeDesc;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.lang.invoke.MethodHandles;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiConsumer;

public class FontExtensions {
    private interface FontExtension {
        FontExtension INSTANCE = (FontExtension) JBRApi.internalServiceBuilder(MethodHandles.lookup())
                .withStatic("getFeatures", "getFeatures", "java.awt.Font").build();

        TreeMap<String, Integer> getFeatures(Font font);
    }

    private interface FontStrikeDescExtension {
        FontStrikeDescExtension INSTANCE = (FontStrikeDescExtension) JBRApi.internalServiceBuilder(MethodHandles.lookup())
                .withStatic("getFeatures", "getFeatures", "sun.font.FontStrikeDesc")
                .withStatic("createFontStrikeDesc", "createFontStrikeDesc", "sun.font.FontStrikeDesc")
                .build();

        Map<String, Integer> getFeatures(FontStrikeDesc font);
        FontStrikeDesc createFontStrikeDesc(AffineTransform devAt, AffineTransform at,
                                            int fStyle, int aa, int fm, Map<String, Integer> features);
    }

    public static String featuresToString(Map<String, Integer> features) {
        StringBuilder res = new StringBuilder();

        features.forEach((String tag, Integer value) -> {
            res.append(tag).append("=").append(String.valueOf(value)).append(" ");
        });

        return res.toString();
    }

    public static TreeMap<String, Integer> getFeatures(Font font) {
        return FontExtension.INSTANCE.getFeatures(font);
    }

    public static Map<String, Integer> getFeatures(FontStrikeDesc desc) {
        return FontStrikeDescExtension.INSTANCE.getFeatures(desc);
    }

    public static FontStrikeDesc createFontStrikeDesc(AffineTransform devAt, AffineTransform at,
                                                      int fStyle, int aa, int fm, Map<String, Integer> features) {
        return FontStrikeDescExtension.INSTANCE.createFontStrikeDesc(devAt, at, fStyle, aa, fm, features);
    }

    private static String getFeaturesAsString(Font font) {
        return featuresToString(getFeatures(font));
    }
}
