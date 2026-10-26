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

import sun.java2d.skia.SKEnvironment;

/**
 * @test
 * @summary Without sun.java2d.skia.lcd, Skia composites LCD text in two passes
 *          on Windows (Dawn/Direct3D has no framebuffer fetch, and the
 *          coverage layer of the accumulate mode ends a render pass per text
 *          batch: twopass paints the IDE 13 % faster); the property still
 *          selects any mode. When Skia is unavailable there is nothing to test.
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true SkiaLcdModeDefaultTest
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.lcd=accumulate SkiaLcdModeDefaultTest accumulate
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.lcd=blend SkiaLcdModeDefaultTest blend
 */
public class SkiaLcdModeDefaultTest {

    public static void main(String[] args) {
        if (!SKEnvironment.isSkiaEnabled()) {
            System.out.println("Skia backend unavailable: nothing to test");
            return;
        }
        String mode = SKEnvironment.lcdMode();
        System.out.println("lcd mode: " + mode);
        String expected;
        if (args.length > 0) {
            expected = args[0];
        } else if (System.getProperty("os.name", "").startsWith("Windows")) {
            expected = "twopass";
        } else {
            System.out.println("default on this system not pinned (blend with framebuffer fetch, else accumulate until measured)");
            return;
        }
        if (!expected.equals(mode)) {
            throw new RuntimeException("LCD mode " + mode + ", expected " + expected);
        }
        System.out.println("PASSED");
    }
}
