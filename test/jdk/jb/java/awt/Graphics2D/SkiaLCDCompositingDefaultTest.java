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
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * @test
 * @summary Regression test for JBR-10654: without sun.java2d.skia.text.lcdCompositing Skia composites LCD text in
 *          two passes on Windows, and the property selects any of the modes.
 * @requires os.family == "windows"
 * @library /test/lib
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off SkiaLCDCompositingDefaultTest twoPass
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.text.lcdCompositing=accumulate SkiaLCDCompositingDefaultTest accumulate
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.text.lcdCompositing=blend SkiaLCDCompositingDefaultTest blend
 */

import sun.java2d.skia.SKEnvironment;

/**
 * Dawn on Direct3D has no framebuffer fetch, and the coverage layer of the accumulate mode ends a
 * render pass per text batch: twoPass paints the IDE 13 % faster. The run line's argument is the
 * mode expected, as {@link SKEnvironment#lcdCompositing} spells it.
 */
public class SkiaLCDCompositingDefaultTest {

    public static void main(String[] args) {
        String expectedCompositing = args[0];
        PipelineComparison.requireSkia();
        String lcdCompositing = SKEnvironment.lcdCompositing();
        System.out.println("LCD compositing: " + lcdCompositing);
        if (!expectedCompositing.equals(lcdCompositing)) {
            throw new RuntimeException("LCD compositing " + lcdCompositing + ", expected " + expectedCompositing);
        }
        System.out.println("PASSED");
    }
}
