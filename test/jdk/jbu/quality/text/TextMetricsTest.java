/*
 * Copyright 2017-2023 JetBrains s.r.o.
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

package quality.text;

import org.junit.Assert;
import org.junit.Test;
import quality.util.RenderUtil;

import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;

public class TextMetricsTest {

    @Test
    public void testTextBounds() throws Exception {
        final Font font = new Font("Menlo", Font.PLAIN, 22);


        BufferedImage bi = RenderUtil.capture(120, 120,
                g2 -> {
                    String s = "A";
                    String s1 = "q";

                    g2.setFont(font);

                    FontRenderContext frc = g2.getFontRenderContext();
                    Rectangle2D bnd = font.getStringBounds(s, frc);
                    TextLayout textLayout = new TextLayout(s, font, frc);
                    Rectangle2D bnd1 = textLayout.getBounds();
                    GlyphVector gv = font.createGlyphVector(frc, s);
                    Rectangle2D bnd2 = gv.getGlyphVisualBounds(0).getBounds2D();
                    textLayout = new TextLayout(s1, font, frc);
                    Rectangle2D bnd3 = textLayout.getBounds();


                    g2.drawString(s, 5, 50);
                    g2.draw(new Path2D.Double(bnd,
                            AffineTransform.getTranslateInstance(5, 50 )));

                    g2.drawString(s, 30, 50);
                    g2.draw(new Path2D.Double(bnd1,
                            AffineTransform.getTranslateInstance(30, 50 )));

                    g2.drawString(s, 50, 50);
                    g2.draw(new Path2D.Double(bnd2,
                            AffineTransform.getTranslateInstance(50, 50 )));

                    g2.drawString(s1, 75, 50);
                    g2.draw(new Path2D.Double(bnd3,
                            AffineTransform.getTranslateInstance(75, 50 )));
                });

        RenderUtil.checkImage(bi, "text", "bndtext.png");

    }

    @Test
    public void testSpaceTextBounds() {
        final Font font = new Font("Menlo", Font.PLAIN, 22);

        GlyphVector gv = font.createGlyphVector(
                new FontRenderContext(null, false, false), " ");
        Rectangle2D bnd = gv.getGlyphVisualBounds(0).getBounds2D();

        Assert.assertTrue(bnd.getX() == 0.0 && bnd.getY() == 0.0 &&
                bnd.getWidth() == 0.0 && bnd.getHeight() == 0.0);
    }
}
