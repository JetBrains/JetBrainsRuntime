/*
 * Copyright 2024-2025 JetBrains s.r.o.
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
 * @summary JBR-3255 Check that shear transform doesn't change character advance
 * @run main/othervm -Djava2d.font.subpixelResolution=1x1 InclinedFontMetricsTest
 */

import javax.swing.*;
import java.awt.*;
import java.awt.geom.AffineTransform;

public class InclinedFontMetricsTest {
    public static void main(String[] args) {
        JPanel jp = new JPanel();
        Font plain = new Font(Font.DIALOG, Font.PLAIN, 1200);
        Font shear = plain.deriveFont(AffineTransform.getShearInstance(-0.2, 0));
        int plainWidth = jp.getFontMetrics(plain).charWidth('a');
        int shearWidth = jp.getFontMetrics(shear).charWidth('a');
        if (plainWidth != shearWidth) throw new Error("plainWidth = " + plainWidth + ", shearWidth = " + shearWidth);
    }
}
