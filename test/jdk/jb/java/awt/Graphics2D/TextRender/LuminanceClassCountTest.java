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

import java.awt.GraphicsEnvironment;
import java.lang.reflect.Method;

/**
 * @test
 * @summary -Dsun.java2d.skia.text.luminanceClasses=<n> (1, 2, 4, ... 256; default 16) is
 *          the number of text luminance classes the Skia scaler's platform-rasterized
 *          routes (gray=cg, gray=platform) produce glyph masks for; a class's
 *          representative is its centre, 256 classes is the exact luminance, and 1 class
 *          turns the luminance dependence off (every colour gets the masks of
 *          sun.java2d.skia.text.cgLuminance, as before the classes existed). The Java
 *          side (FontUtilities, the strikes) and the native side (Skia's text path)
 *          agree on the count. Skia's own text path's colour-keyed pre-blend is not
 *          affected (it is fixed in Skia's build).
 * @requires os.family == "mac"
 * @key headful
 * @modules java.desktop/sun.font java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.gray=cg LuminanceClassCountTest 16 true 8 104 248
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.gray=cg -Dsun.java2d.skia.text.luminanceClasses=4 LuminanceClassCountTest 4 true 32 96 224
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.gray=cg -Dsun.java2d.skia.text.luminanceClasses=256 LuminanceClassCountTest 256 true 0 100 255
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.gray=cg -Dsun.java2d.skia.text.luminanceClasses=1 -Dsun.java2d.skia.text.cgLuminance=40 LuminanceClassCountTest 1 false 40 40 40
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.gray=cg -Dsun.java2d.skia.text.luminanceClasses=5 LuminanceClassCountTest 16 true 8 104 248
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.luminanceClasses=4 LuminanceClassCountTest 4 false 32 96 224
 */
public class LuminanceClassCountTest {
    public static void main(String[] args) throws Exception {
        GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();   // initializes the backend
        final int classes = Integer.parseInt(args[0]);
        final boolean dependent = Boolean.parseBoolean(args[1]);
        Class<?> utilities = Class.forName("sun.font.FontUtilities");
        final int javaClasses = utilities.getField("textLuminanceClasses").getInt(null);
        final boolean javaDependent = utilities.getField("textLuminanceDependent").getBoolean(null);
        Method classOf = utilities.getMethod("luminanceClass", int.class);
        final int[] probes = {0, 100, 255};
        StringBuilder report = new StringBuilder("classes " + javaClasses + ", luminance-dependent " + javaDependent);
        for (int i = 0; i < probes.length; i++) {
            final int expected = Integer.parseInt(args[2 + i]), actual = (Integer) classOf.invoke(null, probes[i]);
            report.append(", class(").append(probes[i]).append(") = ").append(actual);
            if (actual != expected) {
                throw new RuntimeException(report + ": expected class(" + probes[i] + ") = " + expected);
            }
        }
        Method nativeClasses = Class.forName("sun.java2d.skia.SKEnvironment").getMethod("luminanceClasses");
        final int backendClasses = ((Number) nativeClasses.invoke(null)).intValue();
        report.append(", backend ").append(backendClasses);
        System.out.println(report);
        if (javaClasses != classes || javaDependent != dependent || backendClasses != classes) {
            throw new RuntimeException(report + ": expected " + classes + " classes, luminance-dependent " + dependent + ", on both sides");
        }
    }
}
