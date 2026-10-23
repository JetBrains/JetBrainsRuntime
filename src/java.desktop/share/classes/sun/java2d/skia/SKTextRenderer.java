/*
 * Copyright 2026 JetBrains s.r.o.
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
package sun.java2d.skia;

import sun.java2d.SunGraphics2D;
import sun.java2d.SurfaceData;
import sun.java2d.loops.FontInfo;
import sun.java2d.pipe.BufferedTextPipe;
import sun.java2d.pipe.OutlineTextRenderer;
import sun.java2d.pipe.RenderQueue;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.font.TextAttribute;
import java.text.AttributedCharacterIterator;
import java.text.AttributedCharacterIterator.Attribute;
import java.text.CharacterIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import sun.java2d.pipe.AttributedTextPipe;
import sun.font.GlyphList;
import sun.font.SkiaDrawable;

/**
 * Text pipe of the Skia backend. Java computes the glyph images and positions
 * ({@code sun.font}); Skia only composites them. Mirrors {@code MTLTextRenderer}.
 * With {@code sun.java2d.skia.text=skia} and a strike on the Skia scaler, the glyph
 * codes and positions go to Skia's own text path instead (Skia's strike, atlas
 * and blending; {@link SKEnvironment#textBySkia()}). With {@code =shaper}, the
 * characters of {@code drawString} and {@code drawChars} are shaped by Skia's
 * shaper too, instead of Java's layout ({@link SKEnvironment#textShapedBySkia()});
 * with {@code =paragraph}, attributed strings are laid out by Skia's paragraph
 * engine ({@link SKEnvironment#textParagraphBySkia()}).
 */
final class SKTextRenderer extends BufferedTextPipe implements AttributedTextPipe {

    SKTextRenderer(RenderQueue rq) {
        super(rq);
    }

    @Override
    protected native void drawGlyphList(int numGlyphs, boolean usePositions,
                                        boolean subPixPos, boolean rgbOrder,
                                        int lcdContrast,
                                        float glOrigX, float glOrigY,
                                        long[] images, float[] positions);

    @Override
    public void drawString(SunGraphics2D sg2d, String s, double x, double y) {
        if (!drawShaped(sg2d, s, x, y)) {
            super.drawString(sg2d, s, x, y);
        }
    }

    @Override
    public void drawChars(SunGraphics2D sg2d, char[] data, int offset, int length, int x, int y) {
        if (!drawShaped(sg2d, new String(data, offset, length), x, y)) {
            super.drawChars(sg2d, data, offset, length, x, y);
        }
    }

    /**
     * Shapes and draws the text through Skia's shaper when the strike is on the Skia
     * scaler with an axis-aligned glyph transform and the text is below the outline
     * threshold; false leaves it to Java's layout.
     */
    private boolean drawShaped(SunGraphics2D sg2d, String s, double x, double y) {
        if (!SKEnvironment.textShapedBySkia() || s.isEmpty()) {
            return false;
        }
        FontInfo info = sg2d.getFontInfo();
        if (info.nonInvertibleTx || info.pixelHeight > OutlineTextRenderer.THRESHHOLD
                || !(info.fontStrike instanceof SkiaDrawable strike) || strike.skiaScalerContext() == 0 || !strike.drawsBySkia()) {
            return false;
        }
        // The baseline origin in device space. Whole-pixel positioning is the SkFont's:
        // the shaper rounds the advances when sub-pixel positioning is off, which lands
        // the glyphs where Java's rounded advances do.
        final float devx, devy;
        if (sg2d.transformState >= SunGraphics2D.TRANSFORM_TRANSLATESCALE) {
            double[] origin = {x, y};
            sg2d.transform.transform(origin, 0, origin, 0, 1);
            devx = (float) origin[0];
            devy = (float) origin[1];
        } else {
            devx = (float) (x + sg2d.transX);
            devy = (float) (y + sg2d.transY);
        }
        Composite comp = sg2d.composite;
        if (comp == AlphaComposite.Src) {
            comp = AlphaComposite.SrcOver;
        }
        final long context = strike.skiaScalerContext();
        rq.lock();
        try {
            validateContext(sg2d, comp);
            rq.addReference(strike);
            final boolean smoothing = SKEnvironment.textSmoothing();
            rq.flushAndInvokeNow(() -> drawShapedBySkia(context, smoothing, devx, devy, s));
        } finally {
            rq.unlock();
        }
        return true;
    }

    /** Skia's shaper: the characters shaped by HarfBuzz with Skia's fallback and drawn as one text blob. */
    private static native void drawShapedBySkia(long pScalerContext, boolean smoothing, float originX, float originY, String text);

    /**
     * Lays out and draws the attributed string with Skia's paragraph engine when
     * every run is a font (FONT, or FAMILY/SIZE/WEIGHT/POSTURE), a foreground colour,
     * an underline or a strikethrough, and every run's strike is on the Skia scaler
     * with an axis-aligned transform; false leaves it to Java's TextLayout.
     */
    @Override
    public boolean drawString(SunGraphics2D sg2d, AttributedCharacterIterator it, float x, float y) {
        if (!SKEnvironment.textParagraphBySkia()) {
            return false;
        }
        final int begin = it.getBeginIndex(), end = it.getEndIndex();
        StringBuilder text = new StringBuilder(end - begin);
        for (char c = it.first(); c != CharacterIterator.DONE; c = it.next()) {
            text.append(c);
        }
        List<Integer> ends = new ArrayList<>();
        List<Long> contexts = new ArrayList<>();
        List<Integer> colours = new ArrayList<>();
        List<Integer> flags = new ArrayList<>();
        List<SkiaDrawable> strikes = new ArrayList<>();
        for (int pos = begin; pos < end; ) {
            it.setIndex(pos);
            final int limit = it.getRunLimit();
            Map<Attribute, Object> attrs = it.getAttributes();
            Font font = null;
            boolean fontAttrs = false;
            Color colour = null;
            int runFlags = 0;
            for (Map.Entry<Attribute, Object> e : attrs.entrySet()) {
                Attribute a = e.getKey();
                Object v = e.getValue();
                if (v == null) {
                    continue;
                }
                if (a == TextAttribute.FONT) {
                    if (!(v instanceof Font f)) {
                        return false;
                    }
                    font = f;
                } else if (a == TextAttribute.FAMILY || a == TextAttribute.SIZE || a == TextAttribute.WEIGHT || a == TextAttribute.POSTURE) {
                    fontAttrs = true;
                } else if (a == TextAttribute.FOREGROUND) {
                    if (!(v instanceof Color c)) {
                        return false;
                    }
                    colour = c;
                } else if (a == TextAttribute.UNDERLINE) {
                    if (!TextAttribute.UNDERLINE_ON.equals(v)) {
                        return false;
                    }
                    runFlags |= 1;
                } else if (a == TextAttribute.STRIKETHROUGH) {
                    if (!TextAttribute.STRIKETHROUGH_ON.equals(v)) {
                        return false;
                    }
                    runFlags |= 2;
                } else {
                    return false;
                }
            }
            if (font == null) {
                if (!fontAttrs) {
                    return false;   // TextLayout's default fonts: left to it
                }
                font = Font.getFont(attrs);
            }
            FontInfo info = sg2d.checkFontInfo(null, font, sg2d.getFontRenderContext());
            if (info.nonInvertibleTx || info.pixelHeight > OutlineTextRenderer.THRESHHOLD
                    || !(info.fontStrike instanceof SkiaDrawable strike) || strike.skiaScalerContext() == 0 || !strike.drawsBySkia()) {
                return false;
            }
            ends.add(limit - begin);
            contexts.add(strike.skiaScalerContext());
            colours.add(colour != null ? colour.getRGB() : 0);
            flags.add(runFlags | (colour != null ? 4 : 0));
            strikes.add(strike);
            pos = limit;
        }
        final float devx, devy;
        if (sg2d.transformState >= SunGraphics2D.TRANSFORM_TRANSLATESCALE) {
            double[] origin = {x, y};
            sg2d.transform.transform(origin, 0, origin, 0, 1);
            devx = (float) origin[0];
            devy = (float) origin[1];
        } else {
            devx = x + sg2d.transX;
            devy = y + sg2d.transY;
        }
        Composite comp = sg2d.composite;
        if (comp == AlphaComposite.Src) {
            comp = AlphaComposite.SrcOver;
        }
        final String string = text.toString();
        final int[] endArray = ends.stream().mapToInt(Integer::intValue).toArray();
        final long[] contextArray = contexts.stream().mapToLong(Long::longValue).toArray();
        final int[] colourArray = colours.stream().mapToInt(Integer::intValue).toArray();
        final int[] flagArray = flags.stream().mapToInt(Integer::intValue).toArray();
        rq.lock();
        try {
            validateContext(sg2d, comp);
            for (SkiaDrawable strike : strikes) {
                rq.addReference(strike);
            }
            final boolean smoothing = SKEnvironment.textSmoothing();
            rq.flushAndInvokeNow(() -> drawParagraphBySkia(smoothing, devx, devy, string, endArray, contextArray, colourArray, flagArray));
        } finally {
            rq.unlock();
        }
        return true;
    }

    /** Skia's paragraph engine: runs of (strike, colour, decorations) laid out on one line and painted at the baseline origin. */
    private static native void drawParagraphBySkia(boolean smoothing, float originX, float originY, String text,
                                                   int[] runEnds, long[] contexts, int[] colours, int[] flags);

    @Override
    protected void drawGlyphList(SunGraphics2D sg2d, GlyphList gl) {
        if (SKEnvironment.textBySkia() && gl.getStrike() instanceof SkiaDrawable strike && strike.skiaScalerContext() != 0 && strike.drawsBySkia()) {
            Composite comp = sg2d.composite;
            if (comp == AlphaComposite.Src) {
                comp = AlphaComposite.SrcOver;   // as BufferedTextPipe: opaque colour, SrcNoEa equals SrcOverNoEa
            }
            final int n = gl.getNumGlyphs();
            final boolean usePositions = gl.usePositions();
            final float x = gl.getX(), y = gl.getY();
            final int[] codes = gl.getGlyphCodes();
            final long[] images = gl.getImages();
            final float[] positions = usePositions ? gl.getPositions() : null;
            final long context = strike.skiaScalerContext();
            rq.lock();
            try {
                validateContext(sg2d, comp);
                rq.addReference(strike);
                final boolean smoothing = SKEnvironment.textSmoothing();
                rq.flushAndInvokeNow(() -> drawGlyphsBySkia(context, n, usePositions, smoothing, x, y, codes, images, positions));
            } finally {
                rq.unlock();
            }
            return;
        }
        super.drawGlyphList(sg2d, gl);
    }

    /** Skia's text path: glyph codes with Java's positions (or the images' advances) through the strike's SkFont. */
    private static native void drawGlyphsBySkia(long pScalerContext, int numGlyphs, boolean usePositions, boolean smoothing,
                                                float originX, float originY, int[] codes, long[] images, float[] positions);

    @Override
    protected void validateContext(SunGraphics2D sg2d, Composite comp) {
        SKSurfaceData dstData = SurfaceData.convertTo(SKSurfaceData.class, sg2d.surfaceData);
        SKContext.validateContext(dstData, dstData,
                sg2d.getCompClip(), comp,
                null, sg2d.paint, sg2d,
                SKContext.NO_CONTEXT_FLAGS);
    }
}
