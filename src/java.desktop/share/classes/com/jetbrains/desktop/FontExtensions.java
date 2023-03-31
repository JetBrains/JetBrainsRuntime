package com.jetbrains.desktop;

import com.jetbrains.internal.JBRApi;

import java.awt.*;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

public class FontExtensions {
    private interface FontExtension {
        FontExtension INSTANCE = (FontExtension) JBRApi.internalServiceBuilder(MethodHandles.lookup())
                .withStatic("getFeatures", "getFeatures", "java.awt.Font").build();

        TreeMap<String, Integer> getFeatures(Font font);
    }

    public static String[] featuresToStringArray(Map<String, Integer> features) {
        List<String> list = features.entrySet().stream().map(feature -> (feature.getKey() + "=" + feature.getValue())).
                collect(Collectors.toList());
        return list.toArray(String[]::new);
    }

    public static TreeMap<String, Integer> getFeatures(Font font) {
        return FontExtension.INSTANCE.getFeatures(font);
    }
}
