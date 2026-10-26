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
 * @summary The budgets of the Skia backend's text caches. Skia's strike cache (the
 *          CPU-side glyph masks of Skia's text path) runs on 64 MB, not Skia's default
 *          of 2 MB, which thrashes under a few dozen text colours (one strike per
 *          quantized colour) and many more under the cg route;
 *          -Dsun.java2d.skia.strikeCache=<MB> sets it. Graphite's glyph atlas stays at
 *          Skia's budget unless -Dsun.java2d.skia.glyphAtlas=<MB> sets it. The pipe's
 *          own glyph cache (Java's glyph images) is 1024 texels a side, 2048 when text
 *          is rasterized per luminance class (the cg route: one strike per class
 *          multiplies the glyph set); -Dsun.java2d.skia.glyphCache=<texels> sets it.
 * @requires os.family == "mac"
 * @key headful
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true SkiaStrikeCacheBudgetTest 64 8 1024
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.gray=cg SkiaStrikeCacheBudgetTest 64 8 2048
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.strikeCache=16 -Dsun.java2d.skia.glyphAtlas=32 -Dsun.java2d.skia.glyphCache=4096 SkiaStrikeCacheBudgetTest 16 32 4096
 */
public class SkiaStrikeCacheBudgetTest {
    public static void main(String[] args) throws Exception {
        GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();   // initializes the backend
        check("strikeCacheLimit", Long.parseLong(args[0]) * 1024 * 1024);
        check("glyphAtlasBudget", Long.parseLong(args[1]) * 1024 * 1024);
        check("glyphCacheSize", Long.parseLong(args[2]));
    }

    static void check(String getter, long expected) throws Exception {
        Method m = Class.forName("sun.java2d.skia.SKEnvironment").getMethod(getter);
        final long value = ((Number) m.invoke(null)).longValue();
        System.out.println(getter + ": " + value + " bytes, expected " + expected);
        if (value != expected) {
            throw new RuntimeException(getter + " is " + value + ", expected " + expected);
        }
    }
}
