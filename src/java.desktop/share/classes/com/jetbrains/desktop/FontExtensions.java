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
}
