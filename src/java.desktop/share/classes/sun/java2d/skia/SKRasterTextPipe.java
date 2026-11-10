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
import java.awt.font.TextLayout;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import sun.font.GlyphList;
import sun.font.SKDrawableStrike;
import sun.java2d.SunGraphics2D;
import sun.java2d.loops.FontInfo;
import sun.java2d.pipe.GlyphListPipe;
import sun.java2d.pipe.OutlineTextRenderer;
import sun.java2d.pipe.AttributedTextPipe;
import sun.java2d.pipe.TextPipe;
import java.text.AttributedCharacterIterator;

/**
 * Skia's text path on a {@link SKRasterSurfaceData}, the raster twin of
 * {@link SKTextRenderer}'s glyph route: the glyph codes and positions Java laid
 * out go through the strike's SkFont and Skia's glyph masks and blitters, drawn
 * synchronously into the image; a composite strike slot by slot through each
 * physical strike's SkFont. Text whose strikes are not on the Skia scaler (or
 * carry a rotated glyph transform) goes to the loops' pipe this one wraps,
 * decided after the glyph list is built and before the loops see the call, so
 * they see it whole. Text above the outline threshold is Skia's too (the loops would fill
 * Java's outlines of it), as on the GPU pipe (SKTextRenderer.largeBySkia). One instance per wrapped pipe.
 */
final class SKRasterTextPipe extends GlyphListPipe implements AttributedTextPipe {

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

    /** As GlyphListPipe.drawString, deciding on the built list: Skia's path, or the loops' pipe with the whole call. */
    @Override
    public void drawString(SunGraphics2D sg2d, String s, double x, double y) {
        SKDrawableStrike shaped = SKTextRenderer.shapedStrike(sg2d, s);
        if (shaped != null) {
            drawShaped(sg2d, shaped, s, x, y);
            return;
        }
        FontInfo info = sg2d.getFontInfo();
        if (!SKGlyphContexts.mayDraw(info.fontStrike)) {
            loops.drawString(sg2d, s, x, y);
            return;
        }
        if (info.nonInvertibleTx) {
            return;
        }
        float devX, devY;
        if (sg2d.transformState >= SunGraphics2D.TRANSFORM_TRANSLATESCALE) {
            double[] origin = {x + info.originX, y + info.originY};
            sg2d.transform.transform(origin, 0, origin, 0, 1);
            devX = (float) origin[0];
            devY = (float) origin[1];
        } else {
            devX = (float) (x + info.originX + sg2d.transX);
            devY = (float) (y + info.originY + sg2d.transY);
        }
        GlyphList gl = GlyphList.getInstance();
        if (!gl.setFromString(info, s, devX, devY)) {
            gl.dispose();
            new TextLayout(s, sg2d.getFont(), sg2d.getFontRenderContext()).draw(sg2d, (float) x, (float) y);   // needs shaping
            return;
        }
        SKGlyphContexts.Runs runs = SKGlyphContexts.of(gl);
        if (runs != null) {
            draw(sg2d, gl, runs);
        }
        gl.dispose();
        if (runs == null) {
            loops.drawString(sg2d, s, x, y);
        }
    }

    @Override
    public void drawChars(SunGraphics2D sg2d, char[] data, int offset, int length, int ix, int iy) {
        String chars = SKEnvironment.textShapedBySkia() ? new String(data, offset, length) : "";
        SKDrawableStrike shaped = SKTextRenderer.shapedStrike(sg2d, chars);
        if (shaped != null) {
            drawShaped(sg2d, shaped, chars, ix, iy);
            return;
        }
        FontInfo info = sg2d.getFontInfo();
        if (!SKGlyphContexts.mayDraw(info.fontStrike)) {
            loops.drawChars(sg2d, data, offset, length, ix, iy);
            return;
        }
        if (info.nonInvertibleTx) {
            return;
        }
        float devX, devY;
        if (sg2d.transformState >= SunGraphics2D.TRANSFORM_TRANSLATESCALE) {
            double[] origin = {ix + info.originX, iy + info.originY};
            sg2d.transform.transform(origin, 0, origin, 0, 1);
            devX = (float) origin[0];
            devY = (float) origin[1];
        } else {
            devX = ix + info.originX + sg2d.transX;
            devY = iy + info.originY + sg2d.transY;
        }
        GlyphList gl = GlyphList.getInstance();
        if (!gl.setFromChars(info, data, offset, length, devX, devY)) {
            gl.dispose();
            new TextLayout(new String(data, offset, length), sg2d.getFont(), sg2d.getFontRenderContext()).draw(sg2d, ix, iy);
            return;
        }
        SKGlyphContexts.Runs runs = SKGlyphContexts.of(gl);
        if (runs != null) {
            draw(sg2d, gl, runs);
        }
        gl.dispose();
        if (runs == null) {
            loops.drawChars(sg2d, data, offset, length, ix, iy);
        }
    }

    /** As GlyphListPipe.drawGlyphVector; a glyph vector's strike is its own (a system font's is composite on macOS). */
    @Override
    public void drawGlyphVector(SunGraphics2D sg2d, GlyphVector gv, float x, float y) {
        FontInfo info = sg2d.getGVFontInfo(gv.getFont(), gv.getFontRenderContext());
        if (info.nonInvertibleTx) {
            return;
        }
        if (info.pixelHeight > OutlineTextRenderer.THRESHHOLD && !SKGlyphContexts.mayDraw(info.fontStrike)) {
            loops.drawGlyphVector(sg2d, gv, x, y);   // the loops' outlines, without building the list's large images
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
        SKGlyphContexts.Runs runs = SKGlyphContexts.of(gl);
        if (runs != null) {
            draw(sg2d, gl, runs);
        }
        gl.dispose();
        if (runs == null) {
            loops.drawGlyphVector(sg2d, gv, x, y);
        }
    }

    @Override
    protected void drawGlyphList(SunGraphics2D sg2d, GlyphList gl) {
        SKGlyphContexts.Runs runs = SKGlyphContexts.of(gl);
        if (runs == null) {
            // the entry points above decide on the list themselves; nothing else builds one for this pipe
            throw new IllegalStateException("a glyph list the raster text pipe cannot draw: " + gl.getFontStrike());
        }
        draw(sg2d, gl, runs);
    }

    /**
     * The attributed string through Skia's paragraph engine on the raster surface, in the cases the GPU pipe's
     * route takes (SKTextRenderer.paragraphOf); false leaves it to TextLayout.
     */
    @Override
    public boolean drawString(SunGraphics2D sg2d, AttributedCharacterIterator it, float x, float y) {
        SKTextRenderer.Paragraph p = SKTextRenderer.paragraphOf(sg2d, it);
        if (p == null) {
            return false;
        }
        float[] origin = SKTextRenderer.deviceOrigin(sg2d, x, y);
        SKRasterSurfaceData dst = (SKRasterSurfaceData) sg2d.surfaceData;
        int runs = p.endArray().length;
        int[] runInts = new int[5 * runs];   // colours, flags, trackings, shifts, backgrounds: one array (the natives' 14 slots)
        System.arraycopy(p.colourArray(), 0, runInts, 0, runs);
        System.arraycopy(p.flagArray(), 0, runInts, runs, runs);
        System.arraycopy(p.trackingArray(), 0, runInts, 2 * runs, runs);
        System.arraycopy(p.shiftArray(), 0, runInts, 3 * runs, runs);
        System.arraycopy(p.backgroundArray(), 0, runInts, 4 * runs, runs);
        int cx1 = sg2d.getCompClip().getLoX(), cy1 = sg2d.getCompClip().getLoY(), cx2 = sg2d.getCompClip().getHiX(), cy2 = sg2d.getCompClip().getHiY();
        long drawn = drawParagraph(dst, dst.pixelFormat(), opFlags(sg2d, dst) | (p.rightToLeft() ? 1 << 9 : 0), origin[0], origin[1], p.string(), p.endArray(), p.contextArray(), runInts,
                sg2d.eargb, extraAlpha(sg2d),
                SKRasterShapePipe.packed(cx1, cy1), SKRasterShapePipe.packed(cx2, cy2), SKRasterShapePipe.spansOf(sg2d.getCompClip(), cx1, cy1, cx2, cy2));
        glyphsBySkia.addAndGet(drawn);
        java.lang.ref.Reference.reachabilityFence(p.strikes());
        return true;
    }

    /** The string through Skia's shaper on the raster surface (SKTextRenderer.shapedStrike decided). */
    private static void drawShaped(SunGraphics2D sg2d, SKDrawableStrike strike, String s, double x, double y) {
        float[] origin = SKTextRenderer.deviceOrigin(sg2d, x, y);
        SKRasterSurfaceData dst = (SKRasterSurfaceData) sg2d.surfaceData;
        int cx1 = sg2d.getCompClip().getLoX(), cy1 = sg2d.getCompClip().getLoY(), cx2 = sg2d.getCompClip().getHiX(), cy2 = sg2d.getCompClip().getHiY();
        long drawn = drawShaped(dst, dst.pixelFormat(), strike.skiaScalerContext(), opFlags(sg2d, dst) | (SKTextRenderer.rightToLeft(s) ? 1 << 9 : 0), origin[0], origin[1], s,
                sg2d.eargb, extraAlpha(sg2d), SKRasterShapePipe.packed(cx1, cy1), SKRasterShapePipe.packed(cx2, cy2), SKRasterShapePipe.spansOf(sg2d.getCompClip(), cx1, cy1, cx2, cy2));
        glyphsBySkia.addAndGet(drawn);
        java.lang.ref.Reference.reachabilityFence(strike);
    }

    /** The composite rule in bits 0-3 (Src with an opaque colour as SrcOver, as for glyphs) and the mirror flag at 8 (the base direction goes at 9). */
    private static int opFlags(SunGraphics2D sg2d, SKRasterSurfaceData dst) {
        int rule = sg2d.composite instanceof AlphaComposite ac ? ac.getRule() : AlphaComposite.SRC_OVER;
        if (rule == AlphaComposite.SRC && (sg2d.eargb >>> 24) == 0xff) {
            rule = AlphaComposite.SRC_OVER;
        }
        return rule | (dst.untouchedByJava() ? 1 << 8 : 0);
    }

    private static native long drawShaped(SKRasterSurfaceData dst, int dstPixelFormat, long context, int flags, float originX, float originY,
                                          String text, int argb, float extraAlpha, int clipLeftTop, int clipRightBottom, int[] clipSpans);

    /** The composite's extra alpha: colour glyphs take it under Src (their own colours, not the text colour's alpha). */
    private static float extraAlpha(SunGraphics2D sg2d) {
        return sg2d.composite instanceof AlphaComposite ac ? ac.getAlpha() : 1f;
    }

    private static native long drawParagraph(SKRasterSurfaceData dst, int dstPixelFormat, int flags, float originX, float originY, String text,
                                             int[] runEnds, long[] contexts, int[] runInts, int argb, float extraAlpha, int clipLeftTop, int clipRightBottom, int[] clipSpans);

    private static void draw(SunGraphics2D sg2d, GlyphList gl, SKGlyphContexts.Runs runs) {
        SKRasterSurfaceData dst = (SKRasterSurfaceData) sg2d.surfaceData;
        final int n = gl.getNumGlyphs();
        final boolean usePositions = gl.usePositions();
        // with an opaque colour Src equals SrcOver, as BufferedTextPipe takes it
        int rule = sg2d.composite instanceof AlphaComposite ac ? ac.getRule() : AlphaComposite.SRC_OVER;
        if (rule == AlphaComposite.SRC && (sg2d.eargb >>> 24) == 0xff) {
            rule = AlphaComposite.SRC_OVER;
        }
        // The lock box: the glyph images' bounds grown by two pixels (Skia renders the same glyphs,
        // and may differ from the strike's image by a pixel), within the clip — not the clip itself:
        // an INT_ARGB image's mirror is written back for the box after every operation, and a line
        // of text under a 1200 x 900 clip wrote the whole image back per string, 86 times the
        // INT_ARGB_PRE time (SkiaRasterTextCostTest).
        int cx1 = sg2d.getCompClip().getLoX(), cy1 = sg2d.getCompClip().getLoY(), cx2 = sg2d.getCompClip().getHiX(), cy2 = sg2d.getCompClip().getHiY();
        gl.startGlyphIteration();   // the list is fresh: the bounds of all its glyph images, as the loops take them
        // records with advances only (text above the outline threshold, drawn by Skia at any size): the outlines' bounds
        int[] bounds = SKDrawableStrike.imageless(gl, n) ? SKDrawableStrike.outlineBounds(gl, n)
                : gl.getBounds(n);   // x0, y0, x1, y1 in device pixels; all zero for a list without images
        glyphsBySkia.addAndGet(n);
        if (bounds[2] <= bounds[0] && bounds[3] <= bounds[1]) {
            return;   // nothing visible (every glyph without an image)
        }
        cx1 = Math.max(cx1, bounds[0] - 2); cy1 = Math.max(cy1, bounds[1] - 2);
        cx2 = Math.min(cx2, bounds[2] + 2); cy2 = Math.min(cy2, bounds[3] + 2);
        if (cx1 >= cx2 || cy1 >= cy2) {
            return;   // no glyph pixel inside the clip
        }
        int flags = n | (usePositions ? 1 << 24 : 0) | 0 /* bit 25, once the smoothing request: the strike's hinting carries the darkening */ | rule << 26 | (dst.untouchedByJava() ? 1 << 30 : 0);
        drawGlyphs(dst, dst.pixelFormat(), runs.contexts(), flags, gl.getX(), gl.getY(), runs.codes(), gl.getImages(), usePositions ? gl.getPositions() : null,
                   sg2d.eargb, extraAlpha(sg2d), SKRasterShapePipe.packed(cx1, cy1), SKRasterShapePipe.packed(cx2, cy2), SKRasterShapePipe.spansOf(sg2d.getCompClip(), cx1, cy1, cx2, cy2));
    }

    /**
     * Draws the glyph codes at Java's positions (or along the images' advances) from the
     * baseline origin through the strikes' SkFonts (contexts by the slot in the codes' bits 24–31),
     * with the colour (extra alpha included, sg2d.eargb); the arrays are the GlyphList's own, read
     * before it is reused. countAndFlags: the glyph count in bits 0–23, usePositions at 24, smoothing
     * at 25, the composite rule from 26, the mirror flag at 30 (the natives take at most 14 parameter slots, see
     * SKRasterShapePipe.flags).
     */
    private static native void drawGlyphs(SKRasterSurfaceData dst, int dstPixelFormat, long[] contexts, int countAndFlags,
                                          float originX, float originY, int[] codes, long[] images, float[] positions,
                                          int argb, float extraAlpha, int clipLeftTop, int clipRightBottom, int[] clipSpans);
}
