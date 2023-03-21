package com.jetbrains.desktop;

import com.jetbrains.internal.JBRApi;
import sun.font.FontStrikeDesc;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.lang.invoke.MethodHandles;

public class FontExtensions {
    private interface FontExtension {
        FontExtension INSTANCE = (FontExtension) JBRApi.internalServiceBuilder(MethodHandles.lookup())
                .withStatic("getFeatures", "getFeatures", "java.awt.Font").build();

        String getFeatures(Font font);
    }

    private interface FontStrikeDescExtension {
        FontStrikeDescExtension INSTANCE = (FontStrikeDescExtension) JBRApi.internalServiceBuilder(MethodHandles.lookup())
                .withStatic("getFeatures", "getFeatures", "sun.font.FontStrikeDesc")
                .withStatic("createFontStrikeDesc", "createFontStrikeDesc", "sun.font.FontStrikeDesc")
                .build();

        String getFeatures(FontStrikeDesc font);
        FontStrikeDesc createFontStrikeDesc(AffineTransform devAt, AffineTransform at,
                                            int fStyle, int aa, int fm, String features);
    }

    public static String getFeatures(Font font) {
        return FontExtension.INSTANCE.getFeatures(font);
    }

    public static String getFeatures(FontStrikeDesc desc) {
        return FontStrikeDescExtension.INSTANCE.getFeatures(desc);
    }

    public static FontStrikeDesc createFontStrikeDesc(AffineTransform devAt, AffineTransform at,
                                                      int fStyle, int aa, int fm, String features) {
        return FontStrikeDescExtension.INSTANCE.createFontStrikeDesc(devAt, at, fStyle, aa, fm, features);
    }
}
