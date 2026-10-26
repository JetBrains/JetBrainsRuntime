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
 * @summary -Dsun.java2d.skia.strikeCache=<MB> sets the budget of Skia's strike cache
 *          (the CPU-side glyph masks of Skia's text path); without it the backend runs
 *          on Skia's default of 2 MB, which thrashes under a few dozen text colours
 *          (one strike per quantized colour) and many more under the cg route.
 * @requires os.family == "mac"
 * @key headful
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true SkiaStrikeCacheBudgetTest 2
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.strikeCache=64 SkiaStrikeCacheBudgetTest 64
 */
public class SkiaStrikeCacheBudgetTest {
    public static void main(String[] args) throws Exception {
        GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();   // initializes the backend
        final long expected = Long.parseLong(args[0]) * 1024 * 1024;
        Method m = Class.forName("sun.java2d.skia.SKEnvironment").getMethod("strikeCacheLimit");
        final long limit = ((Number) m.invoke(null)).longValue();
        System.out.println("Skia strike cache limit: " + limit + " bytes, expected " + expected);
        if (limit != expected) {
            throw new RuntimeException("Skia strike cache limit is " + limit + ", expected " + expected);
        }
    }
}
