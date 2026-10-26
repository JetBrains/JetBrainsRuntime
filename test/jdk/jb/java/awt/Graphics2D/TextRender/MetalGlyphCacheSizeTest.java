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

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.Method;

/**
 * @test
 * @summary The Metal pipeline's glyph cache textures are 512 texels (256 cells of
 *          32x32) by default, and 2048 texels (4096 cells) when text is rasterized
 *          per luminance class (the cg route of the Skia scaler): one strike per
 *          class multiplies the glyph set, and a frame of 16 text colours thrashes
 *          the 256-cell cache (ledger §19b.1). -Dsun.java2d.metal.glyphCacheSize
 *          sets the side explicitly.
 * @requires os.family == "mac"
 * @key headful
 * @modules java.desktop/sun.java2d.metal
 * @run main/othervm -Dsun.java2d.metal=true MetalGlyphCacheSizeTest 512
 * @run main/othervm -Dsun.java2d.metal=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.gray=cg MetalGlyphCacheSizeTest 2048
 * @run main/othervm -Dsun.java2d.metal=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.gray=cg -Dsun.java2d.metal.glyphCacheSize=1024 MetalGlyphCacheSizeTest 1024
 */
public class MetalGlyphCacheSizeTest {
    public static void main(String[] args) throws Exception {
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        if (!gc.getClass().getName().contains("MTL")) {
            throw new RuntimeException("not a Metal configuration: " + gc.getClass().getName());
        }
        final int expected = Integer.parseInt(args[0]);
        Method m = Class.forName("sun.java2d.metal.MTLGraphicsConfig").getMethod("glyphCacheSize");
        final int size = ((Number) m.invoke(null)).intValue();
        System.out.println("Metal glyph cache side: " + size + " texels, expected " + expected);
        if (size != expected) {
            throw new RuntimeException("Metal glyph cache side is " + size + ", expected " + expected);
        }
    }
}
