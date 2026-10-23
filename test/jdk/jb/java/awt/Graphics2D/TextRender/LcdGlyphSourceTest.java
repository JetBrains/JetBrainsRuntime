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

/*
 * @test
 * @key headful
 * @summary The source of LCD glyph images on Windows is a stated choice,
 *          sun.java2d.font.lcdGlyphs = gdi | directwrite | scaler, not an
 *          override. With no choice stated, installed fonts keep GDI's
 *          ClearType glyphs as they always had on the JDK's scaler, but a
 *          selected scaler (sun.java2d.font.scaler=skia) rasterizes its own
 *          LCD glyphs: before this change the GDI path took precedence over
 *          any scaler for every installed font at 3 to 100 px, so the Skia
 *          scaler's LCD rendering never reached the screen for them. The
 *          strike of an installed font at 13 px with LCD antialiasing is
 *          inspected through reflection for the source it will use.
 * @requires (os.family == "windows")
 * @modules java.desktop/sun.font
 * @run main/othervm -Dsun.java2d.uiScale=1 LcdGlyphSourceTest gdi
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.font.scaler=skia LcdGlyphSourceTest scaler
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=gdi LcdGlyphSourceTest gdi
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.font.lcdGlyphs=scaler LcdGlyphSourceTest scaler
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.font.lcdGlyphs=directwrite LcdGlyphSourceTest directwrite
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dfreetype.font.rendering=true LcdGlyphSourceTest scaler
 * @run main/othervm -Dsun.java2d.uiScale=1 -Ddirectwrite.font.rendering=true LcdGlyphSourceTest directwrite
 */

import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import sun.font.Font2D;
import sun.font.FontUtilities;

public class LcdGlyphSourceTest {

    public static void main(String[] args) throws Exception {
        String expected = args[0];
        Font font = new Font("Segoe UI", Font.PLAIN, 13);
        if (!font.getFamily().equalsIgnoreCase("Segoe UI")) {
            System.out.println("Segoe UI not installed: nothing to test");
            return;
        }
        FontRenderContext frc = new FontRenderContext(null, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB,
                                                      RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        Font2D font2D = FontUtilities.getFont2D(font);
        Method getStrike = Font2D.class.getDeclaredMethod("getStrike", Font.class, FontRenderContext.class);
        getStrike.setAccessible(true);
        Object strike = getStrike.invoke(font2D, font, frc);
        Class<?> fileFontStrike = Class.forName("sun.font.FileFontStrike");
        if (!fileFontStrike.isInstance(strike)) {
            throw new RuntimeException("not a file font strike: " + strike.getClass());
        }
        Field useNatives = fileFontStrike.getDeclaredField("useNatives");
        useNatives.setAccessible(true);
        Field useDirectWrite = fileFontStrike.getDeclaredField("useDirectWrite");
        useDirectWrite.setAccessible(true);
        boolean natives = useNatives.getBoolean(strike), directWrite = useDirectWrite.getBoolean(null);
        String actual = !natives ? "scaler" : directWrite ? "directwrite" : "gdi";
        System.out.println("LCD glyph source for Segoe UI 13: " + actual + " (useNatives " + natives
                           + ", useDirectWrite " + directWrite + ", Skia scaler " + FontUtilities.useSkiaScaler + ")");
        if (!actual.equals(expected)) {
            throw new RuntimeException("LCD glyphs come from " + actual + ", expected " + expected);
        }
        System.out.println("PASSED");
    }
}
