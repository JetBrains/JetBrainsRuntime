/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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

import sun.java2d.SunGraphics2D;
import sun.java2d.SurfaceData;
import sun.java2d.opengl.OGLSurfaceData;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Transparency;
import java.awt.image.VolatileImage;

import jtreg.SkippedException;

/*
 * @test
 * @key headful
 * @summary Verifies composites in opaque and translucent modes on OpenGL.
 * @library /java/awt/AlphaComposite/lib
 * @library /test/lib
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.opengl
 * @build AlphaCompositeTestRunner
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.opengl=True OGLCompositeTest
 */
public class OGLCompositeTest {
    static final AlphaCompositeTestRunner.RunOptions RUN_OPTIONS = new AlphaCompositeTestRunner.RunOptions(
            AlphaCompositeTestRunner.TranslucentOutputOnOpaqueDstPolicy.MUST_DROP_ALPHA,
            AlphaCompositeTestRunner.FullyTransparentOnOpaqueDstPolicy.MUST_SET_TO_BLACK,
            2.0f / 255.0f,
            (_) -> false // don't skip any tests
    );

    record TransparencyWithDescription(int transparency, String description) {}

    static final TransparencyWithDescription[] TRANSPARENCIES = {
            new TransparencyWithDescription(Transparency.TRANSLUCENT, "TRANSLUCENT"),
            new TransparencyWithDescription(Transparency.OPAQUE, "OPAQUE")
    };

    static void main() {
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();

        boolean success = true;
        for (var transparencyWithDescription : TRANSPARENCIES) {
            VolatileImage image = gc.createCompatibleVolatileImage(AlphaCompositeTestRunner.W, AlphaCompositeTestRunner.H, transparencyWithDescription.transparency);

            SunGraphics2D sg2d = (SunGraphics2D) image.createGraphics();
            SurfaceData sd = sg2d.getSurfaceData();
            sg2d.dispose();
            if (!(sd instanceof OGLSurfaceData oglsd && oglsd.getType() == OGLSurfaceData.FBOBJECT)) {
                throw new SkippedException("Failed to enable OGL");
            }

            if (!AlphaCompositeTestRunner.run(image, gc, RUN_OPTIONS, "OpenGL[" + transparencyWithDescription.description + "]")) {
                success = false;
            }
        }
        if (!success) {
            throw new RuntimeException("Some tests failed, see stderr");
        }
    }
}
