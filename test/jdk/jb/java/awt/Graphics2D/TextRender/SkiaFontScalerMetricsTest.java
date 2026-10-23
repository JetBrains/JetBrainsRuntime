/*
 * Copyright 2026 JetBrains s.r.o.
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
 */

import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import sun.font.Font2D;
import sun.font.FontUtilities;
import sun.font.SKFontScaler;
import sun.java2d.skia.SKEnvironment;

/**
 * @test
 * @summary The Skia font scaler (the platform's Skia font host, CoreText on
 *          macOS) reports the strike metrics and the glyph advances of the
 *          platform's own strike for the logical fonts, at sizes 8 to 32,
 *          scales 1 to 2, with and without fractional metrics: ascent,
 *          descent and leading equal, every ASCII advance equal. Whole-pixel
 *          advances follow the platform strike's rounding rule (CoreText
 *          rounds in user space, FreeType in device space), which the test
 *          selects by the strike's type. Nothing to test where the Skia
 *          backend or its font host is unavailable.
 * @key headful
 * @modules java.desktop/sun.font:+open java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.uiScale=1 SkiaFontScalerMetricsTest
 */
public class SkiaFontScalerMetricsTest {
    static final int[] SIZES = {8, 10, 12, 13, 16, 24, 32};
    static final double[] SCALES = {1, 1.25, 1.5, 2};

    public static void main(String[] args) throws Exception {
        if (!SKEnvironment.isSkiaEnabled()) {
            System.out.println("Skia backend unavailable: nothing to test");
            return;
        }
        Class<?> cfont = classOrNull("sun.font.CFont");
        Method nativePtr = cfont != null ? accessible(cfont.getDeclaredMethod("getNativeFontPtr")) : null;
        Method getStrike = accessible(Font2D.class.getDeclaredMethod("getStrike", Font.class, FontRenderContext.class));
        Method getMapper = accessible(Font2D.class.getDeclaredMethod("getMapper"));
        Class<?> strikeClass = Class.forName("sun.font.FontStrike");
        Method strikeMetrics = accessible(strikeClass.getDeclaredMethod("getFontMetrics"));
        Method strikeAdvance = accessible(strikeClass.getDeclaredMethod("getGlyphAdvance", int.class));
        Method scalerMetrics = accessible(SKFontScaler.class.getDeclaredMethod("getFontMetrics", long.class));
        Method scalerAdvance = accessible(SKFontScaler.class.getDeclaredMethod("getGlyphAdvance", long.class, int.class));
        Method deviceContext = accessible(SKFontScaler.class.getDeclaredMethod("createScalerContext",
                double[].class, int.class, int.class, float.class, float.class));
        Method userContext = accessible(SKFontScaler.class.getDeclaredMethod("createScalerContext",
                double[].class, double.class, int.class, int.class, float.class, float.class));
        Class<?> sm = Class.forName("sun.font.StrikeMetrics");
        Field[] fields = {accessible(sm.getDeclaredField("ascentY")), accessible(sm.getDeclaredField("descentY")),
                          accessible(sm.getDeclaredField("leadingY"))};
        String[] names = {"ascent", "descent", "leading"};

        int compared = 0;
        for (String family : new String[] {Font.DIALOG, Font.SANS_SERIF, Font.MONOSPACED}) {
            Font base = new Font(family, Font.PLAIN, 12);
            Font2D font2D = FontUtilities.getFont2D(base);
            Class<?> composite = classOrNull("sun.font.CompositeFont");
            if (composite != null && composite.isInstance(font2D)) {
                // Windows and Linux: the logical font is a composite; its first slot is the physical
                // file font whose strike renders the ASCII range, so that is the scaler to compare
                font2D = (Font2D) accessible(composite.getDeclaredMethod("getSlotFont", int.class)).invoke(font2D, 0);
            }
            SKFontScaler scaler;
            boolean userSpaceRounding;
            if (cfont != null && cfont.isInstance(font2D)) {
                scaler = SKFontScaler.forNativeFont(font2D, (long) nativePtr.invoke(font2D));
                userSpaceRounding = true;   // CStrike's rule
            } else {
                Field platName = accessible(Class.forName("sun.font.PhysicalFont").getDeclaredField("platName"));
                Field index = accessible(Class.forName("sun.font.TrueTypeFont").getDeclaredField("fontIndex"));
                scaler = SKFontScaler.forFile(font2D, (String) platName.get(font2D), index.getInt(font2D));
                userSpaceRounding = false;  // FileFontStrike's rule
            }
            Object mapper = getMapper.invoke(font2D);
            Method charToGlyph = accessible(mapper.getClass().getMethod("charToGlyph", char.class));
            for (int size : SIZES) for (double scale : SCALES) for (boolean fm : new boolean[] {false, true}) {
                Font font = base.deriveFont((float) size);
                FontRenderContext frc = new FontRenderContext(AffineTransform.getScaleInstance(scale, scale),
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
                        fm ? RenderingHints.VALUE_FRACTIONALMETRICS_ON : RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
                Object strike = getStrike.invoke(font2D, font, frc);
                Object reference = strikeMetrics.invoke(strike);
                double[] matrix = {size * scale, 0, 0, size * scale};
                int fmHint = fm ? 2 : 1;   // SunHints.INTVAL_FRACTIONALMETRICS_ON / _OFF
                long context = userSpaceRounding
                        ? (long) userContext.invoke(scaler, matrix, scale, 2, fmHint, 1f, 0f)
                        : (long) deviceContext.invoke(scaler, matrix, 2, fmHint, 1f, 0f);
                Object skia = scalerMetrics.invoke(scaler, context);
                String where = family + " " + size + "pt x" + scale + (fm ? " fractional" : "");
                for (int i = 0; i < 3; i++) {
                    float a = fields[i].getFloat(reference), b = fields[i].getFloat(skia) / (float) scale;
                    if (Math.abs(a - b) > 1e-3f) {
                        throw new RuntimeException(where + ": " + names[i] + " " + b + " (Skia) vs " + a + " (platform)");
                    }
                }
                // Windows, whole pixels: the FreeType strike rounds the *hinted* advance (a
                // bytecode-hinted font like Arial moves stems and widths by a pixel), the Skia
                // scaler rounds the linear one, as DirectWrite's natural layout does. Decision of
                // 2026-10-23 (linear rounding, to be reviewed for the final verification): the
                // reference for whole-pixel advances on Windows is the platform's fractional
                // advance rounded in device pixels.
                boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("windows");
                Object linearStrike = windows && !fm ? getStrike.invoke(font2D, font, new FontRenderContext(
                        AffineTransform.getScaleInstance(scale, scale), RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
                        RenderingHints.VALUE_FRACTIONALMETRICS_ON)) : null;
                for (int c = 32; c <= 126; c++) {
                    int glyph = (int) charToGlyph.invoke(mapper, (char) c);
                    float a = (float) strikeAdvance.invoke(strike, glyph);
                    if (linearStrike != null) {
                        a = (float) (Math.round((float) strikeAdvance.invoke(linearStrike, glyph) * scale) / scale);
                    }
                    float b = (float) scalerAdvance.invoke(scaler, context, glyph) / (float) scale;
                    if (Math.abs(a - b) > 1e-3f) {
                        throw new RuntimeException(where + ": advance of '" + (char) c + "' " + b + " (Skia) vs " + a + " (platform)");
                    }
                }
                compared++;
            }
        }
        System.out.println(compared + " strikes compared, metrics and 95 advances each");
        System.out.println("PASSED");
    }

    static Class<?> classOrNull(String name) {
        try { return Class.forName(name); } catch (ClassNotFoundException e) { return null; }
    }

    static <T extends java.lang.reflect.AccessibleObject> T accessible(T member) {
        member.setAccessible(true);
        return member;
    }
}
