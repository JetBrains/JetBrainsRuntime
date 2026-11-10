/*
 * Copyright (c) 2000, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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

package sun.java2d.pipe;

import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.font.TextLayout;

import sun.java2d.SunGraphics2D;
import sun.java2d.SurfaceData;
import sun.font.FontUtilities;
import sun.font.GlyphList;
import sun.java2d.loops.FontInfo;

/**
 * A delegate pipe of SG2D for drawing text.
 */

public abstract class GlyphListPipe implements TextPipe {

    public void drawString(SunGraphics2D sg2d, String s,
                           double x, double y)
    {
        FontInfo info = sg2d.getFontInfo();
        if (info.nonInvertibleTx) {
            return;
        }
        if (info.pixelHeight > OutlineTextRenderer.THRESHHOLD) {
            SurfaceData.outlineTextRenderer.drawString(sg2d, s, x, y);
            return;
        }

        float devx, devy;
        // a fractional translation through the transform too: the rounded one (transX, transY) moved the glyphs by up
        // to half a pixel from where the translation puts them, and from TextLayout's decorations (exact transform)
        if (sg2d.transformState >= SunGraphics2D.TRANSFORM_ANY_TRANSLATE) {
            double[] origin = {x + info.originX, y + info.originY};
            sg2d.transform.transform(origin, 0, origin, 0, 1);
            devx = (float)origin[0];
            devy = (float)origin[1];
        } else {
            devx = (float)(x + info.originX + sg2d.transX);
            devy = (float)(y + info.originY + sg2d.transY);
        }
        /* setFromString returns false if shaping is needed, and we then back
         * off to a TextLayout. Such text may benefit slightly from a lower
         * overhead in this approach over the approach in previous releases.
         */
        devy = glyphRow(sg2d, info, devy);
        GlyphList gl = GlyphList.getInstance();
        if (gl.setFromString(info, s, devx, devy)) {
            drawGlyphList(sg2d, gl);
            gl.dispose();
        } else {
            gl.dispose(); // release this asap.
            TextLayout tl = new TextLayout(s, sg2d.getFont(),
                                           sg2d.getFontRenderContext());
            tl.draw(sg2d, (float)x, (float)y);
        }
    }

    public void drawChars(SunGraphics2D sg2d,
                          char[] data, int offset, int length,
                          int ix, int iy)
    {
        FontInfo info = sg2d.getFontInfo();
        if (info.nonInvertibleTx) {
            return;
        }
        float x, y;
        if (info.pixelHeight > OutlineTextRenderer.THRESHHOLD) {
            SurfaceData.outlineTextRenderer.drawChars(
                                        sg2d, data, offset, length, ix, iy);
            return;
        }
        if (sg2d.transformState >= SunGraphics2D.TRANSFORM_ANY_TRANSLATE) {
            double[] origin = {ix + info.originX, iy + info.originY};
            sg2d.transform.transform(origin, 0, origin, 0, 1);
            x = (float) origin[0];
            y = (float) origin[1];
        } else {
            x = ix + info.originX + sg2d.transX;
            y = iy + info.originY + sg2d.transY;
        }
        y = glyphRow(sg2d, info, y);
        GlyphList gl = GlyphList.getInstance();
        if (gl.setFromChars(info, data, offset, length, x, y)) {
            drawGlyphList(sg2d, gl);
            gl.dispose();
        } else {
            gl.dispose(); // release this asap.
            TextLayout tl = new TextLayout(new String(data, offset, length),
                                           sg2d.getFont(),
                                           sg2d.getFontRenderContext());
            tl.draw(sg2d, ix, iy);

        }
    }

    public void drawGlyphVector(SunGraphics2D sg2d, GlyphVector gv,
                                float x, float y)
    {
        FontRenderContext frc = gv.getFontRenderContext();
        FontInfo info = sg2d.getGVFontInfo(gv.getFont(), frc);
        if (info.nonInvertibleTx) {
            return;
        }
        if (info.pixelHeight > OutlineTextRenderer.THRESHHOLD) {
            SurfaceData.outlineTextRenderer.drawGlyphVector(sg2d, gv, x, y);
            return;
        }
        if (sg2d.transformState >= SunGraphics2D.TRANSFORM_ANY_TRANSLATE) {
            double[] origin = {x, y};
            sg2d.transform.transform(origin, 0, origin, 0, 1);
            x = (float) origin[0];
            y = (float) origin[1];
        } else {
            x += sg2d.transX; // don't use the glyph info origin, already in gv.
            y += sg2d.transY;
        }

        y = glyphRow(sg2d, info, y);
        GlyphList gl = GlyphList.getInstance();
        gl.setFromGlyphVector(info, gv, x, y);
        drawGlyphList(sg2d, gl, info.aaHint);
        gl.dispose();
    }

    /**
     * The device baseline the glyphs of a run are placed from. On macOS a horizontal run's glyphs go to the row below a
     * fractional baseline, as CoreText places them (CTLineDraw, string drawing and TextKit 2 alike at 1x and 2x,
     * measured 2026-11-10: any fraction past 1/1024 of a point moves them, 0.0005 does not); the glyph lists' own
     * rounding to the nearest row then keeps it. TextLayout's decorations stay at the exact baseline, as CoreText's.
     * Elsewhere, and for rotated or sheared runs, the baseline as it is.
     */
    public static float glyphRow(SunGraphics2D sg2d, FontInfo info, float y) {
        if (!FontUtilities.isMacOSX || sg2d.transform.getShearX() != 0 || sg2d.transform.getShearY() != 0 || sg2d.transform.getScaleY() <= 0
                || info.glyphTx[1] != 0 || info.glyphTx[2] != 0 || info.glyphTx[3] <= 0) {
            return y;
        }
        return (float) Math.ceil(y - sg2d.transform.getScaleY() / 1024);
    }

    protected abstract void drawGlyphList(SunGraphics2D sg2d, GlyphList gl);

    protected void drawGlyphList(SunGraphics2D sg2d, GlyphList gl,
                                 int aaHint) {
        drawGlyphList(sg2d, gl);
    }
}
