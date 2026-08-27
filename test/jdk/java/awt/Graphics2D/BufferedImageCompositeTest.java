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

/* @test
 * @summary Verifies composites in opaque and translucent modes for software BufferedImage rendering.
 * @library /java/awt/AlphaComposite/lib
 * @build AlphaCompositeTestRunner
 * @run main BufferedImageCompositeTest
 */

import java.awt.image.BufferedImage;

public class BufferedImageCompositeTest {

    static final AlphaCompositeTestRunner.RunOptions RUN_OPTIONS = new AlphaCompositeTestRunner.RunOptions(
            AlphaCompositeTestRunner.TranslucentOutputOnOpaqueDstPolicy.MUST_UNPREMULTIPLY,
            AlphaCompositeTestRunner.FullyTransparentOnOpaqueDstPolicy.DONT_CARE,
            3.0f / 255.0f,
            (testCase) -> false // don't skip any tests
    );

    record ColorModelWithDescription(int colorModel, String description) {}

    static final ColorModelWithDescription[] COLOR_MODELS = new ColorModelWithDescription[]{
            new ColorModelWithDescription(BufferedImage.TYPE_INT_ARGB_PRE, "TYPE_INT_ARGB_PRE"),
            new ColorModelWithDescription(BufferedImage.TYPE_INT_ARGB, "TYPE_INT_ARGB"),
            new ColorModelWithDescription(BufferedImage.TYPE_INT_RGB, "TYPE_INT_RGB")
    };

    static void main() {
        boolean success = true;
        for (var colorModelWithDescription : COLOR_MODELS) {
            BufferedImage image = new BufferedImage(AlphaCompositeTestRunner.W, AlphaCompositeTestRunner.H, colorModelWithDescription.colorModel);
            if (!AlphaCompositeTestRunner.run(image, null, RUN_OPTIONS, "BufferedImage[" + colorModelWithDescription.description + "]")) {
                success = false;
            }
        }
        if (!success) {
            throw new RuntimeException("Some tests failed, see stderr");
        }
    }
}
