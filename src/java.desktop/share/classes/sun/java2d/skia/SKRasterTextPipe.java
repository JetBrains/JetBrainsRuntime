/*
 * Copyright 2026 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sun.java2d.skia;

import java.awt.AlphaComposite;
import java.awt.font.GlyphVector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import sun.font.GlyphList;
import sun.font.SKDrawableStrike;
import sun.java2d.SunGraphics2D;
import sun.java2d.loops.FontInfo;
import sun.java2d.SurfaceData;
import sun.java2d.pipe.GlyphListPipe;
import sun.java2d.pipe.OutlineTextRenderer;
import sun.java2d.pipe.TextPipe;

/**
 * Skia's text path on a {@link SKRasterSurfaceData}, the raster twin of
 * {@link SKTextRenderer}'s glyph route: the glyph codes and positions Java laid
 * out go through the strike's SkFont and Skia's glyph masks and blitters, drawn
 * synchronously into the image. Text whose strike is not on the Skia scaler
 * (or carries a rotated glyph transform) goes to the loops' pipe this one
 * wraps, decided before the loops see the call, so they see it whole. One
 * instance per wrapped pipe.
 */
final class SKRasterTextPipe extends GlyphListPipe {

    /** Glyphs drawn through Skia's raster text path, for the test of the route. */
    static final AtomicLong glyphsBySkia = new AtomicLong();

    private static final ConcurrentHashMap<TextPipe, SKRasterTextPipe> instances = new ConcurrentHashMap<>();

    private final TextPipe loops;

    private SKRasterTextPipe(TextPipe loops) {
        this.loops = loops;
    }

    /** The pipe wrapping the loops' choice, shared. */
    static SKRasterTextPipe of(TextPipe loops) {
        return loops instanceof SKRasterTextPipe p ? p : instances.computeIfAbsent(loops, SKRasterTextPipe::new);
    }

    /** The strike when Skia's text path can draw it (on the Skia scaler, axis-aligned transform), else null. */
    private static SKDrawableStrike skiaStrike(Object strike) {
        return strike instanceof SKDrawableStrike s && s.skiaScalerContext() != 0 && s.drawsBySkia() ? s : null;
    }

    @Override
    public void drawString(SunGraphics2D sg2d, String s, double x, double y) {
        if (skiaStrike(sg2d.getFontInfo().fontStrike) != null) {
            super.drawString(sg2d, s, x, y);
        } else {
            loops.drawString(sg2d, s, x, y);
        }
    }

    @Override
    public void drawChars(SunGraphics2D sg2d, char[] data, int offset, int length, int x, int y) {
        if (skiaStrike(sg2d.getFontInfo().fontStrike) != null) {
            super.drawChars(sg2d, data, offset, length, x, y);
        } else {
            loops.drawChars(sg2d, data, offset, length, x, y);
        }
    }

    /**
     * As GlyphListPipe.drawGlyphVector, but the strike decides after the list is built: a
     * glyph vector's strike is its own (a composite font's is composite, per-glyph transforms
     * give one per transform), and the loops take the whole call when Skia cannot draw it.
     */
    @Override
    public void drawGlyphVector(SunGraphics2D sg2d, GlyphVector gv, float x, float y) {
        FontInfo info = sg2d.getGVFontInfo(gv.getFont(), gv.getFontRenderContext());
        if (info.nonInvertibleTx) {
            return;
        }
        if (info.pixelHeight > OutlineTextRenderer.THRESHHOLD) {
            SurfaceData.outlineTextRenderer.drawGlyphVector(sg2d, gv, x, y);
            return;
        }
        float devX, devY;
        if (sg2d.transformState >= SunGraphics2D.TRANSFORM_TRANSLATESCALE) {
            double[] origin = {x, y};
            sg2d.transform.transform(origin, 0, origin, 0, 1);
            devX = (float) origin[0];
            devY = (float) origin[1];
        } else {
            devX = x + sg2d.transX;
            devY = y + sg2d.transY;
        }
        GlyphList gl = GlyphList.getInstance();
        gl.setFromGlyphVector(info, gv, devX, devY);
        boolean bySkia = skiaStrike(gl.getFontStrike()) != null;
        if (bySkia) {
            drawGlyphList(sg2d, gl);
        }
        gl.dispose();
        if (!bySkia) {
            loops.drawGlyphVector(sg2d, gv, x, y);
        }
    }

    @Override
    protected void drawGlyphList(SunGraphics2D sg2d, GlyphList gl) {
        SKDrawableStrike strike = skiaStrike(gl.getFontStrike());
        if (strike == null || !(sg2d.surfaceData instanceof SKRasterSurfaceData dst)) {
            // the entry points checked this strike (the FontInfo's, which the list carries) on this surface
            throw new IllegalStateException("a glyph list the raster text pipe cannot draw: " + gl.getFontStrike() + " on " + sg2d.surfaceData);
        }
        final int n = gl.getNumGlyphs();
        final boolean usePositions = gl.usePositions();
        // with an opaque colour Src equals SrcOver, as BufferedTextPipe takes it
        int rule = sg2d.composite instanceof AlphaComposite ac ? ac.getRule() : AlphaComposite.SRC_OVER;
        if (rule == AlphaComposite.SRC && (sg2d.eargb >>> 24) == 0xff) {
            rule = AlphaComposite.SRC_OVER;
        }
        int cx1 = sg2d.getCompClip().getLoX(), cy1 = sg2d.getCompClip().getLoY(), cx2 = sg2d.getCompClip().getHiX(), cy2 = sg2d.getCompClip().getHiY();
        glyphsBySkia.addAndGet(n);
        drawGlyphs(dst, dst.pixelFormat(), dst.imageWidth(), dst.imageHeight(), strike.skiaScalerContext(), n, usePositions,
                   SKEnvironment.textSmoothing(), gl.getX(), gl.getY(), gl.getGlyphCodes(), gl.getImages(), usePositions ? gl.getPositions() : null,
                   sg2d.eargb, rule, cx1, cy1, cx2, cy2, SKRasterShapePipe.spansOf(sg2d.getCompClip(), cx1, cy1, cx2, cy2));
    }

    /**
     * Draws the glyph codes at Java's positions (or along the images' advances) from the
     * baseline origin through the strike's SkFont, with the colour (extra alpha included,
     * sg2d.eargb) and composite rule; the arrays are the GlyphList's own, read before it is reused.
     */
    private static native void drawGlyphs(SKRasterSurfaceData dst, int dstPixelFormat, int dstWidth, int dstHeight,
                                          long scalerContext, int numGlyphs, boolean usePositions, boolean smoothing,
                                          float originX, float originY, int[] codes, long[] images, float[] positions,
                                          int argb, int compositeRule, int clipX1, int clipY1, int clipX2, int clipY2, int[] clipSpans);
}
