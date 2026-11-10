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

import sun.awt.SunHints;
import sun.java2d.SunGraphics2D;
import sun.java2d.SurfaceData;
import sun.java2d.loops.FontInfo;
import sun.java2d.pipe.BufferedTextPipe;
import sun.java2d.pipe.OutlineTextRenderer;
import sun.java2d.pipe.RenderBuffer;
import sun.java2d.pipe.RenderQueue;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.font.GlyphVector;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.lang.annotation.Native;
import java.text.AttributedCharacterIterator;
import java.text.AttributedCharacterIterator.Attribute;
import java.text.CharacterIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import sun.java2d.pipe.AttributedTextPipe;
import sun.font.GlyphList;
import sun.font.SKDrawableStrike;

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
 * <p>
 * The three Skia text-path entries are render-queue operations carrying the
 * glyph codes or the UTF-16 text (opcodes {@link #DRAW_GLYPHS_BY_SKIA} and the
 * two others, dispatched by {@code SKRenderQueue.cpp}); only an operation larger
 * than the render buffer runs through {@link RenderQueue#flushAndInvokeNow}, as
 * {@code BufferedTextPipe} does with an oversized glyph list.
 */
final class SKTextRenderer extends BufferedTextPipe implements AttributedTextPipe {

    SKTextRenderer(RenderQueue rq) {
        super(rq);
    }

    /** Paragraph run flags, as SKBackend.h's SK_PARAGRAPH_*: the colour flag says colours[r] applies. */
    private static final int PARAGRAPH_UNDERLINE = 1, PARAGRAPH_STRIKETHROUGH = 2, PARAGRAPH_COLOUR = 4,
            PARAGRAPH_SYSTEM_DECORATIONS = 8,   // decorations drawn by our rule after the paragraph, not by SkParagraph (SKEnvironment.decorationsSnapped)
            PARAGRAPH_KERNING = 16, PARAGRAPH_LIGATURES = 32,   // the run's font kerns / ligates: Java's kern, and liga with calt
            PARAGRAPH_BACKGROUND = 64, PARAGRAPH_BACKGROUND_AA = 128,   // a box behind the run, antialiased as the graphics fills shapes
            PARAGRAPH_SHAPES_AA = 256;   // the graphics antialiases shapes: the system decorations under a transform, Java2D's rule

    /** The attributes a run's font is made of when there is no FONT attribute (TextLayout's Font.getFont). */
    private static final java.util.Set<Attribute> FONT_ATTRIBUTES = java.util.Set.of(TextAttribute.FAMILY, TextAttribute.SIZE,
            TextAttribute.WEIGHT, TextAttribute.POSTURE, TextAttribute.KERNING, TextAttribute.LIGATURES, TextAttribute.TRACKING,
            TextAttribute.SUPERSCRIPT, TextAttribute.WIDTH, TextAttribute.TRANSFORM);

    /**
     * Layout attributes a run's font may carry for Skia's paragraph engine: kerning, ligatures, tracking and
     * superscript, which it applies as Java's layout does. Any other of Font's layout attributes (Font.LAYOUT_MASK), and width (part
     * of its extra transform), leave the paragraph to TextLayout. Colours, background, underline, strikethrough,
     * swapped colours and the bidi attributes (run direction, embedding, numeric shaping, justification) a font carries
     * are not refused: TextLayout takes them from the iterator's attributes only, never from a FONT attribute's font
     * (measured), and so does the route.
     */
    private static final int UNSUPPORTED_FONT_ATTRIBUTES = sun.font.AttributeValues.getMask(
            sun.font.EAttribute.ECHAR_REPLACEMENT, sun.font.EAttribute.EINPUT_METHOD_HIGHLIGHT,
            sun.font.EAttribute.EINPUT_METHOD_UNDERLINE, sun.font.EAttribute.EWIDTH);

    /** Render-queue opcodes of the Skia text path: the backend's own range above {@code BufferedOpCodes}, after {@code SKShapePipe}'s. */
    @Native static final int DRAW_GLYPHS_BY_SKIA = 210, DRAW_SHAPED_BY_SKIA = 211, DRAW_PARAGRAPH_BY_SKIA = 212;
    /** Bits of the opcodes' packed parameters. */
    @Native static final int OFFSET_USE_POSITIONS = 0, OFFSET_SMOOTHING = 1, OFFSET_RIGHT_TO_LEFT = 2;

    /** Bytes a string's UTF-16 units take on the queue: padded so the next operation stays 4-byte aligned. */
    private static int paddedChars(int length) {
        return (2 * length + 3) & ~3;
    }

    private static void putChars(RenderBuffer buf, String s) {
        final int n = s.length();
        for (int i = 0; i < n; i++) {
            buf.putShort((short) s.charAt(i));
        }
        if ((n & 1) != 0) {
            buf.putShort((short) 0);
        }
    }

    /**
     * The strike when Skia's shaper or paragraph route can draw it (on the Skia scaler, any invertible glyph
     * transform: the text is shaped in the SkFont's space and drawn under the transform beyond its size, as the text
     * path draws), else null.
     */
    private static SKDrawableStrike skiaStrike(Object strike) {   // a FontStrike; GlyphList hands it out as Object
        return strike instanceof SKDrawableStrike s && s.skiaScalerContext() != 0 && s.drawsBySkia() ? s : null;
    }

    /** A glyph transform without its size (the scaler context's remainder), to compare the runs of a paragraph. */
    private static double[] remainder(FontInfo info) {
        double[] m = info.glyphTx;
        double size = Math.sqrt(Math.abs(m[0] * m[3] - m[1] * m[2]));
        return new double[] {m[0] / size, m[1] / size, m[2] / size, m[3] / size};
    }

    /**
     * A superscript's or subscript's baseline shift in the paragraph's layout space: Font.getTransform's translation
     * (a user-space offset perpendicular to the user's baseline, outside any TRANSFORM of the font) mapped to the
     * device and back through the paragraph's paint transform (the remainder and the layout scale). SkParagraph
     * shifts along its own y axis only; under a rotating TRANSFORM of the font the offset's other component is lost.
     */
    private static float baselineShift(Font font, sun.font.AttributeValues values, FontInfo info, double[] remainder, double layoutScale) {
        double shift = font.getTransform().getTranslateY();
        if (values.isNonDefault(sun.font.EAttribute.ETRANSFORM)) {
            shift -= values.getTransform().getTranslateY();
        }
        double[] d = info.devTx;   // AffineTransform order: m00 m10 m01 m11
        double dx = d[2] * shift, dy = d[3] * shift;
        double[] r = remainder;
        double det = r[0] * r[3] - r[1] * r[2];
        return (float) ((r[0] * dy - r[1] * dx) / det / layoutScale);
    }

    /** Decoration's brightness of a swapped background (30/59/11 weights, "tweaked a bit"). */
    private static int brightness(Color c) {
        return 33 * c.getRed() + 53 * c.getGreen() + 14 * c.getBlue();
    }

    /** The baseline origin in device space, as GlyphListPipe computes it (without the strike's rounding offset). */
    static float[] deviceOrigin(SunGraphics2D sg2d, double x, double y) {
        if (sg2d.transformState >= SunGraphics2D.TRANSFORM_TRANSLATESCALE) {
            double[] origin = {x, y};
            sg2d.transform.transform(origin, 0, origin, 0, 1);
            return new float[] {(float) origin[0], (float) origin[1]};
        }
        return new float[] {(float) (x + sg2d.transX), (float) (y + sg2d.transY)};
    }

    /**
     * The composite Skia's text routes draw with: Src as SrcOver only under an opaque paint, where the two are equal.
     * BufferedTextPipe turns every Src into SrcOver (its glyph quads cannot take another blend, and OGL/D3D admit Src
     * only with an opaque colour), but Src is COMP_ISCOPY whatever the paint's alpha, so a translucent colour arrives
     * too; Skia's coverage gives Src its MaskFill meaning (dst lerped towards the colour by the glyph's coverage), as
     * the software loops, the raster surfaces and TextLayout's decorations (shape fills) have it.
     */
    private static Composite textComposite(SunGraphics2D sg2d) {
        boolean opaque = sg2d.paintState == SunGraphics2D.PAINT_OPAQUECOLOR
                || (sg2d.paintState > SunGraphics2D.PAINT_ALPHACOLOR && sg2d.paint.getTransparency() == java.awt.Transparency.OPAQUE);
        return sg2d.composite == AlphaComposite.Src && opaque ? AlphaComposite.SrcOver : sg2d.composite;
    }

    @Override
    protected native void drawGlyphList(int numGlyphs, boolean usePositions,
                                        boolean subPixPos, boolean rgbOrder,
                                        int lcdContrast,
                                        float glOrigX, float glOrigY,
                                        long[] images, float[] positions);

    @Override
    public void drawString(SunGraphics2D sg2d, String s, double x, double y) {
        if (drawShaped(sg2d, s, x, y)) {
            return;
        }
        FontInfo info = sg2d.getFontInfo();
        if (!largeBySkia(info)) {
            super.drawString(sg2d, s, x, y);
            return;
        }
        float[] origin = glyphListOrigin(sg2d, x + info.originX, y + info.originY);
        GlyphList gl = GlyphList.getInstance();
        if (!gl.setFromString(info, s, origin[0], origin[1])) {
            gl.dispose();
            new TextLayout(s, sg2d.getFont(), sg2d.getFontRenderContext()).draw(sg2d, (float) x, (float) y);   // needs shaping
            return;
        }
        boolean drawn = drawBySkia(sg2d, gl);
        gl.dispose();
        if (!drawn) {
            SurfaceData.outlineTextRenderer.drawString(sg2d, s, x, y);
        }
    }

    @Override
    public void drawChars(SunGraphics2D sg2d, char[] data, int offset, int length, int x, int y) {
        if (drawShaped(sg2d, new String(data, offset, length), x, y)) {
            return;
        }
        FontInfo info = sg2d.getFontInfo();
        if (!largeBySkia(info)) {
            super.drawChars(sg2d, data, offset, length, x, y);
            return;
        }
        float[] origin = glyphListOrigin(sg2d, x + info.originX, y + info.originY);
        GlyphList gl = GlyphList.getInstance();
        if (!gl.setFromChars(info, data, offset, length, origin[0], origin[1])) {
            gl.dispose();
            new TextLayout(new String(data, offset, length), sg2d.getFont(), sg2d.getFontRenderContext()).draw(sg2d, x, y);
            return;
        }
        boolean drawn = drawBySkia(sg2d, gl);
        gl.dispose();
        if (!drawn) {
            SurfaceData.outlineTextRenderer.drawChars(sg2d, data, offset, length, x, y);
        }
    }

    @Override
    public void drawGlyphVector(SunGraphics2D sg2d, GlyphVector gv, float x, float y) {
        FontInfo info = sg2d.getGVFontInfo(gv.getFont(), gv.getFontRenderContext());
        if (!largeBySkia(info)) {
            super.drawGlyphVector(sg2d, gv, x, y);
            return;
        }
        float[] origin = glyphListOrigin(sg2d, x, y);   // the glyph vector carries the font's origin itself
        GlyphList gl = GlyphList.getInstance();
        gl.setFromGlyphVector(info, gv, origin[0], origin[1]);
        boolean drawn = drawBySkia(sg2d, gl);
        gl.dispose();
        if (!drawn) {
            SurfaceData.outlineTextRenderer.drawGlyphVector(sg2d, gv, x, y);
        }
    }

    /**
     * Text above the outline threshold that Skia's text path may draw: GlyphListPipe would fill Java's outlines of
     * it; Skia draws large glyphs itself (as paths beyond its glyph atlas), as the shaper and the paragraph engine
     * do, so every route renders such text alike. The strike's records then carry advances only (SKFontStrike);
     * a list Skia cannot draw after all (a composite font's slot off the Skia scaler) goes to the outline renderer.
     */
    static boolean largeBySkia(FontInfo info) {
        return info.pixelHeight > OutlineTextRenderer.THRESHHOLD && !info.nonInvertibleTx
                && SKEnvironment.textBySkia() && SKGlyphContexts.mayDraw(info.fontStrike);
    }

    /** A glyph list's device origin from a user-space position, as GlyphListPipe computes it. */
    static float[] glyphListOrigin(SunGraphics2D sg2d, double x, double y) {
        if (sg2d.transformState >= SunGraphics2D.TRANSFORM_TRANSLATESCALE) {
            double[] origin = {x, y};
            sg2d.transform.transform(origin, 0, origin, 0, 1);
            return new float[] {(float) origin[0], (float) origin[1]};
        }
        return new float[] {(float) (x + sg2d.transX), (float) (y + sg2d.transY)};
    }

    /** The strike Skia's shaper draws the string in, or null when the string is the text path's or TextLayout's (see drawShaped). */
    static SKDrawableStrike shapedStrike(SunGraphics2D sg2d, String s) {
        if (!SKEnvironment.textShapedBySkia() || s.isEmpty()) {
            return null;
        }
        FontInfo info = sg2d.getFontInfo();
        SKDrawableStrike strike = skiaStrike(info.fontStrike);
        return info.nonInvertibleTx || strike == null ? null : strike;
    }

    /**
     * Shapes and draws the text through Skia's shaper when the strike is on the Skia
     * scaler (any invertible glyph transform, drawn under its part beyond the size, at any
     * size: Skia draws large glyphs itself); false leaves it to Java's layout.
     */
    private boolean drawShaped(SunGraphics2D sg2d, String s, double x, double y) {
        SKDrawableStrike strike = shapedStrike(sg2d, s);
        if (strike == null) {
            return false;
        }
        // Whole-pixel positioning is the SkFont's: the shaper rounds the advances when
        // sub-pixel positioning is off, which lands the glyphs where Java's rounded advances do.
        final float[] origin = deviceOrigin(sg2d, x, y);
        final long context = strike.skiaScalerContext();
        final boolean rightToLeft = rightToLeft(s);
        rq.lock();
        try {
            validateContext(sg2d, textComposite(sg2d));
            rq.addReference(strike);
            final boolean smoothing = false   /* the strike's hinting carries the darkening (SKFontScaler) */;
            final int length = s.length(), total = 28 + paddedChars(length);
            RenderBuffer buf = rq.getBuffer();
            if (total <= buf.capacity()) {
                rq.ensureCapacityAndAlignment(total, 20);
                buf.putInt(DRAW_SHAPED_BY_SKIA);
                buf.putInt(length);
                buf.putInt((smoothing ? 1 << OFFSET_SMOOTHING : 0) | (rightToLeft ? 1 << OFFSET_RIGHT_TO_LEFT : 0));
                buf.putFloat(origin[0]);
                buf.putFloat(origin[1]);
                buf.putLong(context);
                putChars(buf, s);
            } else {
                rq.flushAndInvokeNow(() -> drawShapedBySkia(context, smoothing, rightToLeft, origin[0], origin[1], s));
            }
        } finally {
            rq.unlock();
        }
        return true;
    }

    /** Skia's shaper: the characters shaped by HarfBuzz with Skia's fallback and drawn as one text blob (oversized operations). */
    private static native void drawShapedBySkia(long pScalerContext, boolean smoothing, boolean rightToLeft, float originX, float originY, String text);

    /**
     * A plain string's base direction as Java lays it out: TextLayout(String, Font) takes it from the first strong
     * character (Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT); left to right for text that needs no bidi.
     */
    static boolean rightToLeft(String s) {
        char[] chars = s.toCharArray();
        return java.text.Bidi.requiresBidi(chars, 0, chars.length)
                && !new java.text.Bidi(s, java.text.Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).baseIsLeftToRight();
    }

    /** The attributed string as Skia's paragraph engine draws it, or null when it is TextLayout's (see drawString). */
    static Paragraph paragraphOf(SunGraphics2D sg2d, AttributedCharacterIterator it) {
        if (!SKEnvironment.textParagraphBySkia()) {
            return null;
        }
        final int begin = it.getBeginIndex(), end = it.getEndIndex();
        StringBuilder text = new StringBuilder(end - begin);
        for (char c = it.first(); c != CharacterIterator.DONE; c = it.next()) {
            text.append(c);
        }
        final char[] chars = text.toString().toCharArray();
        List<Integer> ends = new ArrayList<>();
        List<Long> contexts = new ArrayList<>();
        List<Integer> colours = new ArrayList<>();
        List<Integer> flags = new ArrayList<>();
        List<SKDrawableStrike> strikes = new ArrayList<>();
        List<Integer> trackings = new ArrayList<>();
        List<Integer> shifts = new ArrayList<>();
        List<Integer> backgrounds = new ArrayList<>();
        List<Integer> embeddings = new ArrayList<>();
        double[] paragraphRemainder = null;
        for (int pos = begin; pos < end; ) {
            it.setIndex(pos);
            final int limit = it.getRunLimit();
            Map<Attribute, Object> attrs = it.getAttributes();
            Font font = null;
            Color colour = null, background = null;
            boolean swapColors = false;
            int runFlags = 0, embedding = 0;
            for (Map.Entry<Attribute, Object> e : attrs.entrySet()) {
                Attribute a = e.getKey();
                Object v = e.getValue();
                if (v == null) {
                    continue;
                }
                if (a == TextAttribute.FONT) {
                    if (!(v instanceof Font f)) {
                        return null;
                    }
                    font = f;
                } else if (a == TextAttribute.FAMILY || a == TextAttribute.SIZE || a == TextAttribute.WEIGHT || a == TextAttribute.POSTURE
                        || a == TextAttribute.KERNING || a == TextAttribute.LIGATURES || a == TextAttribute.TRACKING
                        || a == TextAttribute.SUPERSCRIPT || a == TextAttribute.WIDTH || a == TextAttribute.TRANSFORM) {
                    // the run's font (resolved below as TextLayout does: a FONT attribute outranks them)
                } else if (a == TextAttribute.FOREGROUND) {
                    if (!(v instanceof Color c)) {
                        return null;
                    }
                    colour = c;
                } else if (a == TextAttribute.BACKGROUND) {
                    if (!(v instanceof Color c)) {
                        return null;
                    }
                    background = c;
                } else if (a == TextAttribute.SWAP_COLORS) {
                    swapColors = TextAttribute.SWAP_COLORS_ON.equals(v);
                } else if (a == TextAttribute.UNDERLINE) {
                    if (Integer.valueOf(-1).equals(v)) {
                        continue;   // AttributeValues' "no underline"
                    }
                    if (!TextAttribute.UNDERLINE_ON.equals(v)) {
                        return null;   // the dotted, dashed and gray styles (UNDERLINE_LOW_*) are TextLayout's
                    }
                    runFlags |= PARAGRAPH_UNDERLINE;
                    if (SKEnvironment.decorationsSnapped()) {
                        runFlags |= PARAGRAPH_SYSTEM_DECORATIONS;
                    }
                } else if (a == TextAttribute.STRIKETHROUGH) {
                    if (Boolean.FALSE.equals(v)) {
                        continue;
                    }
                    if (!TextAttribute.STRIKETHROUGH_ON.equals(v)) {
                        return null;
                    }
                    runFlags |= PARAGRAPH_STRIKETHROUGH;
                    if (SKEnvironment.decorationsSnapped()) {
                        runFlags |= PARAGRAPH_SYSTEM_DECORATIONS;
                    }
                } else if (a == TextAttribute.BIDI_EMBEDDING) {
                    if (!(v instanceof Integer level) || level < -61 || level > 61) {
                        return null;
                    }
                    embedding = level;
                } else if (a == TextAttribute.RUN_DIRECTION || a == TextAttribute.NUMERIC_SHAPING || a == TextAttribute.JUSTIFICATION) {
                    // the paragraph's, from its first character (below), as TextLayout and java.text.Bidi read them;
                    // justification only serves TextLayout.getJustifiedLayout
                } else if (!(a instanceof TextAttribute)) {
                    // LANGUAGE, READING, INPUT_METHOD_SEGMENT and an application's own keys: TextLayout reads only
                    // TextAttributes (its fonts, decorations and bidi), so they change nothing it draws
                } else {
                    return null;
                }
            }
            if (!(sg2d.paint instanceof Color) && colour == null && (runFlags & (PARAGRAPH_UNDERLINE | PARAGRAPH_STRIKETHROUGH)) != 0
                    && (runFlags & PARAGRAPH_SYSTEM_DECORATIONS) == 0) {
                return null;   // SkParagraph's own decorations take a colour, not the graphics' gradient or texture
            }
            // the run's fonts as TextLayout's StyledParagraph resolves them: a FONT as it is; with a FAMILY, the font of the
            // font attributes; without either, FontResolver's per character (Dialog 12, else the first of all fonts that
            // displays it) derived with the font attributes. Underline, strikethrough and colours are the route's own
            // (Font.getFont and deriveFont would fold them into the font, whose layout attributes the route refuses below)
            Font[] runFonts;
            int[] runLimits;
            if (font != null) {
                runFonts = new Font[] {font};
                runLimits = new int[] {limit};
            } else {
                Map<Attribute, Object> fontOnly = new java.util.HashMap<>(attrs);
                fontOnly.keySet().retainAll(FONT_ATTRIBUTES);
                if (attrs.get(TextAttribute.FAMILY) != null) {
                    runFonts = new Font[] {Font.getFont(fontOnly)};
                    runLimits = new int[] {limit};
                } else {
                    List<Font> resolved = new ArrayList<>(1);
                    List<Integer> resolvedLimits = new ArrayList<>(1);
                    sun.font.FontResolver resolver = sun.font.FontResolver.getInstance();
                    sun.font.CodePointIterator cps = sun.font.CodePointIterator.create(chars, pos - begin, limit - begin);
                    while (cps.charIndex() < limit - begin) {
                        int index = resolver.nextFontRunIndex(cps);
                        resolved.add(resolver.getFont(index, fontOnly));
                        resolvedLimits.add(cps.charIndex() + begin);
                    }
                    runFonts = resolved.toArray(new Font[0]);
                    runLimits = resolvedLimits.stream().mapToInt(Integer::intValue).toArray();
                }
            }
            // the run's colours as Decoration.drawTextAndDecorations resolves them: swapped, the foreground (the
            // graphics' colour without one) is the background and the text is the background colour, or black or
            // white by the foreground's brightness
            if (swapColors) {
                Color foreground = colour != null ? colour : sg2d.paint instanceof Color c ? c : null;
                if (foreground == null) {
                    return null;   // a gradient or texture paint behind the text: TextLayout's
                }
                colour = background != null ? background : brightness(foreground) > 18500 ? Color.BLACK : Color.WHITE;
                background = foreground;
            }
            if (sg2d.imageComp == sun.java2d.loops.CompositeType.Clear) {
                colour = null;   // SunGraphics2D.validateColor: no colour under Clear (the glyphs' luminance class is black's)
            }
            if (background != null) {
                runFlags |= PARAGRAPH_BACKGROUND | (sg2d.antialiasHint == SunHints.INTVAL_ANTIALIAS_ON ? PARAGRAPH_BACKGROUND_AA : 0);
            }
            for (int k = 0; k < runFonts.length; k++) {
                font = runFonts[k];
                int subFlags = runFlags;
                // the run's font as TextLayout lays it out: kerning and ligatures are Java's features (FontAccess.getFeatures:
                // kern, and liga with calt), anything else that changes the layout is TextLayout's
                sun.font.AttributeValues values = sun.font.AttributeValues.fromMap(font.getAttributes());
                if (values.anyNonDefault(UNSUPPORTED_FONT_ATTRIBUTES) || sun.font.FontAccess.getFontAccess().getFeatures(font).length > 3) {
                    return null;   // the font's own layout attributes or JBR font features
                }
                subFlags |= (values.getKerning() != 0 ? PARAGRAPH_KERNING : 0) | (values.getLigatures() != 0 ? PARAGRAPH_LIGATURES : 0);
                // tracking: em of the run's font added to each glyph's advance (StandardGlyphVector: tracking x the font's size)
                trackings.add(Float.floatToRawIntBits(values.getTracking()));
                FontInfo info = sg2d.checkFontInfo(null, font, sg2d.getFontRenderContext());
                SKDrawableStrike strike = skiaStrike(info.fontStrike);
                if (info.nonInvertibleTx || strike == null) {
                    return null;
                }
                double[] r = remainder(info);
                if (paragraphRemainder == null) {
                    paragraphRemainder = r;
                } else {
                    for (int i = 0; i < 4; i++) {
                        if (Math.abs(r[i] - paragraphRemainder[i]) > 1e-9) {
                            return null;   // runs under different transforms (a font with its own): one paint cannot place them
                        }
                    }
                }
                if (sg2d.antialiasHint == SunHints.INTVAL_ANTIALIAS_ON) {
                    subFlags |= PARAGRAPH_SHAPES_AA;
                }
                shifts.add(Float.floatToRawIntBits(values.getSuperscript() != 0 ? baselineShift(font, values, info, r, strike.layoutScale()) : 0));
                backgrounds.add(background != null ? background.getRGB() : 0);
                ends.add(runLimits[k] - begin);
                embeddings.add(embedding);
                contexts.add(strike.skiaScalerContext());
                colours.add(colour != null ? colour.getRGB() : 0);
                flags.add(subFlags | (colour != null ? PARAGRAPH_COLOUR : 0));
                strikes.add(strike);
            }
            pos = limit;
        }
        // bidi as TextLayout's TextLine runs it: java.text.Bidi over the iterator (RUN_DIRECTION at the first character,
        // else the first strong one; BIDI_EMBEDDING's explicit levels), the paragraph laid out in its base direction
        it.first();
        boolean embedded = embeddings.stream().anyMatch(e -> e != 0);
        boolean rightToLeft = false;
        if (embedded || it.getAttribute(TextAttribute.RUN_DIRECTION) != null || java.text.Bidi.requiresBidi(text.toString().toCharArray(), 0, text.length())) {
            rightToLeft = !new java.text.Bidi(it).baseIsLeftToRight();
        }
        // numeric shaping from the first character for the whole text, after the fonts were resolved (TextLayout's paragraphInit)
        it.first();
        if (it.getAttribute(TextAttribute.NUMERIC_SHAPING) instanceof java.awt.font.NumericShaper shaper) {
            char[] shaped = text.toString().toCharArray();
            shaper.shape(shaped, 0, shaped.length);
            text = new StringBuilder().append(shaped);
        }
        int[] endArray = ends.stream().mapToInt(Integer::intValue).toArray();
        String string = text.toString();
        if (embedded) {
            // explicit levels through the Unicode embedding controls, inside each run's own text: one push per level
            // from the base level (RLE raises an even level by one, LRE an odd one; RLO / LRO for the last push of an
            // override), closed with as many PDFs; the controls are default-ignorable and shape to nothing
            StringBuilder controlled = new StringBuilder(string.length() + 8);
            int start = 0, base = rightToLeft ? 1 : 0;
            for (int r = 0; r < endArray.length; r++) {
                int e = embeddings.get(r), level = Math.abs(e);
                int pushes = level > base ? level - base : 0;
                if (e < 0 && pushes == 0) {
                    return null;   // an override at the base level: no control reaches it, TextLayout's
                }
                for (int l = base; l < level; l++) {
                    boolean last = l == level - 1;
                    controlled.append(l % 2 == 0 ? (last && e < 0 ? '\u202E' : '\u202B') : (last && e < 0 ? '\u202D' : '\u202A'));
                }
                controlled.append(string, start, endArray[r]);
                for (int i = 0; i < pushes; i++) {
                    controlled.append('\u202C');
                }
                start = endArray[r];
                endArray[r] = controlled.length();
            }
            string = controlled.toString();
        }
        final long[] contextArray = contexts.stream().mapToLong(Long::longValue).toArray();
        final int[] colourArray = colours.stream().mapToInt(Integer::intValue).toArray();
        final int[] flagArray = flags.stream().mapToInt(Integer::intValue).toArray();
        final int[] trackingArray = trackings.stream().mapToInt(Integer::intValue).toArray();
        final int[] shiftArray = shifts.stream().mapToInt(Integer::intValue).toArray();
        final int[] backgroundArray = backgrounds.stream().mapToInt(Integer::intValue).toArray();
        return new Paragraph(string, endArray, contextArray, colourArray, flagArray, trackingArray, shiftArray, backgroundArray, strikes, rightToLeft);
    }

    /** A paragraph's runs: their ends in the text, scaler contexts, colours, flags, trackings, baseline shifts, backgrounds. */
    record Paragraph(String string, int[] endArray, long[] contextArray, int[] colourArray, int[] flagArray, int[] trackingArray,
                     int[] shiftArray, int[] backgroundArray, List<SKDrawableStrike> strikes, boolean rightToLeft) {}

    /**
     * Lays out and draws the attributed string with Skia's paragraph engine when
     * every run is a font (FONT, or FAMILY/SIZE/WEIGHT/POSTURE with KERNING/LIGATURES; the
     * font's own kerning and ligatures applied as Java's layout applies them), a foreground
     * colour, an underline or a strikethrough, and every run's strike is on the Skia scaler with
     * one glyph transform beyond its size for the whole paragraph (laid out once, painted
     * under it; the system decorations' device rows only when it is the identity); false
     * leaves it to Java's TextLayout.
     */
    @Override
    public boolean drawString(SunGraphics2D sg2d, AttributedCharacterIterator it, float x, float y) {
        final Paragraph paragraph = paragraphOf(sg2d, it);
        if (paragraph == null) {
            return false;
        }
        final float[] origin = deviceOrigin(sg2d, x, y);
        final String string = paragraph.string();
        final boolean rightToLeft = paragraph.rightToLeft();
        final int[] endArray = paragraph.endArray(), colourArray = paragraph.colourArray(), flagArray = paragraph.flagArray(),
                trackingArray = paragraph.trackingArray(), shiftArray = paragraph.shiftArray(), backgroundArray = paragraph.backgroundArray();
        final long[] contextArray = paragraph.contextArray();
        final List<SKDrawableStrike> strikes = paragraph.strikes();
        rq.lock();
        try {
            validateContext(sg2d, textComposite(sg2d));
            for (SKDrawableStrike strike : strikes) {
                rq.addReference(strike);
            }
            final boolean smoothing = false   /* the strike's hinting carries the darkening (SKFontScaler) */;
            final int length = string.length(), runs = endArray.length;
            final int total = 24 + runs * (8 + 6 * 4) + paddedChars(length);
            RenderBuffer buf = rq.getBuffer();
            if (total <= buf.capacity()) {
                rq.ensureCapacityAndAlignment(total, 24);
                buf.putInt(DRAW_PARAGRAPH_BY_SKIA);
                buf.putInt(length);
                buf.putInt(runs);
                buf.putInt((smoothing ? 1 << OFFSET_SMOOTHING : 0) | (rightToLeft ? 1 << OFFSET_RIGHT_TO_LEFT : 0));
                buf.putFloat(origin[0]);
                buf.putFloat(origin[1]);
                buf.put(contextArray, 0, runs);
                buf.put(endArray, 0, runs);
                buf.put(colourArray, 0, runs);
                buf.put(flagArray, 0, runs);
                buf.put(trackingArray, 0, runs);
                buf.put(shiftArray, 0, runs);
                buf.put(backgroundArray, 0, runs);
                putChars(buf, string);
            } else {
                rq.flushAndInvokeNow(() -> drawParagraphBySkia(smoothing, rightToLeft, origin[0], origin[1], string, endArray, contextArray, colourArray, flagArray, trackingArray, shiftArray, backgroundArray));
            }
        } finally {
            rq.unlock();
        }
        return true;
    }

    /** Skia's paragraph engine: runs of (strike, colour, decorations) laid out on one line and painted at the baseline origin (oversized operations). */
    private static native void drawParagraphBySkia(boolean smoothing, boolean rightToLeft, float originX, float originY, String text,
                                                   int[] runEnds, long[] contexts, int[] colours, int[] flags, int[] trackingBits, int[] shiftBits,
                                                   int[] backgrounds);

    @Override
    protected void drawGlyphList(SunGraphics2D sg2d, GlyphList gl) {
        if (!drawBySkia(sg2d, gl)) {
            super.drawGlyphList(sg2d, gl);
        }
    }

    /** The list through Skia's text path; false when a strike of it is not Skia's (the glyph images' pipe then). */
    private boolean drawBySkia(SunGraphics2D sg2d, GlyphList gl) {
        // a physical strike's context and codes, or a composite strike's contexts by slot with the codes re-encoded
        SKGlyphContexts.Runs runs = SKEnvironment.textBySkia() ? SKGlyphContexts.of(gl) : null;
        if (runs != null) {
            final int n = gl.getNumGlyphs();
            final boolean usePositions = gl.usePositions();
            final float x = gl.getX(), y = gl.getY();
            // the GlyphList singleton's own arrays: copied onto the queue, or drawn, under the lock before the GlyphList is reused
            final int[] codes = runs.codes();
            final long[] images = gl.getImages();
            final float[] positions = usePositions ? gl.getPositions() : null;
            final long[] contexts = runs.contexts();
            rq.lock();
            try {
                validateContext(sg2d, textComposite(sg2d));
                rq.addReference(gl.getFontStrike());   // a composite strike holds its slots' strikes
                final boolean smoothing = false   /* the strike's hinting carries the darkening (SKFontScaler) */;
                final int total = 24 + 8 * contexts.length + n * (8 + 4 + (usePositions ? 8 : 0));
                RenderBuffer buf = rq.getBuffer();
                if (total <= buf.capacity()) {
                    rq.ensureCapacityAndAlignment(total, 24);
                    buf.putInt(DRAW_GLYPHS_BY_SKIA);
                    buf.putInt(n);
                    buf.putInt((usePositions ? 1 << OFFSET_USE_POSITIONS : 0) | (smoothing ? 1 << OFFSET_SMOOTHING : 0));
                    buf.putFloat(x);
                    buf.putFloat(y);
                    buf.putInt(contexts.length);
                    buf.put(contexts, 0, contexts.length);
                    buf.put(images, 0, n);
                    buf.put(codes, 0, n);
                    if (usePositions) {
                        buf.put(positions, 0, 2 * n);
                    }
                } else {
                    rq.flushAndInvokeNow(() -> drawGlyphsBySkia(contexts, n, usePositions, smoothing, x, y, codes, images, positions));
                }
            } finally {
                rq.unlock();
            }
            return true;
        }
        return false;
    }

    /** Skia's text path: glyph codes (the slot in bits 24–31 selecting the context) with Java's positions (or the images' advances) through the strikes' SkFonts (oversized operations). */
    private static native void drawGlyphsBySkia(long[] contexts, int numGlyphs, boolean usePositions, boolean smoothing,
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
