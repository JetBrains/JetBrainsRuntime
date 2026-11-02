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

package sun.font;

import java.awt.geom.GeneralPath;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

import sun.java2d.Disposer;
import sun.java2d.skia.SKEnvironment;

/**
 * Font scaler on Skia's font host of the platform (JBR-10654, text track):
 * CoreText on macOS, DirectWrite on Windows, FreeType elsewhere, which is
 * what the platform's own text stack uses, so metrics are expected to agree
 * with it up to rounding. A typeface comes either from the font object the
 * platform's {@code Font2D} already holds (the {@code CFont}'s native font) or
 * from the font file; a scaler context is one {@code SkFont} per strike.
 * <p>
 * Lives in {@code libawt_skia}, which it loads itself
 * ({@link SKEnvironment#loadLibrary()}); the GPU backend need not be enabled:
 * the software loops and the platform's pipeline draw its images. Selected for the
 * platform's fonts with {@code -Dsun.java2d.font.scaler=skia}
 * ({@link FontUtilities#useSkiaScaler}): {@code CFont} strikes become
 * {@link SKFontStrike}s, file fonts get this scaler behind their
 * {@code FileFontStrike}.
 */
public final class SKFontScaler extends FontScaler {

    /**
     * The blend dimension of the text settings ({@code sun.java2d.skia.text.blend}, see
     * SKEnvironment): {@code BLEND_CORRECTED} — the host's antialiased coverage, the pipes
     * applying their contrast curve; {@code BLEND_PLAIN} — the platform engine's mask as
     * colour-independent coverage (DirectWrite's alpha texture; CoreGraphics renders none,
     * the host's coverage stands in); {@code BLEND_SYSTEM} — the platform engine's mask
     * rendered for the text's luminance class (CoreGraphics' smoothing, DirectWrite's
     * ClearType), the platform's own look for that colour.
     */
    static final int BLEND_CORRECTED = 0, BLEND_PLAIN = 1, BLEND_SYSTEM = 2;
    private static final int BLEND = switch (sun.java2d.skia.SKEnvironment.textBlend()) {
        case PLAIN -> BLEND_PLAIN;
        case CORRECTED -> BLEND_CORRECTED;
        default -> BLEND_SYSTEM;
    };
    /**
     * Blend system on macOS: the glyph rasterized by CoreGraphics
     * itself with font smoothing on, for the text luminance
     * {@code sun.java2d.skia.text.cgLuminance} (0..255; the platform darkens light
     * text more than dark text), the coverage recovered from the render. The
     * platform's own look by construction for that luminance. The graphics
     * state passes its colour's luminance class through the strike
     * (FontStrikeDesc.luminance); the property only decides for paints that are
     * not a colour, and lets a test pin the class; with
     * {@code sun.java2d.skia.text.luminanceClasses=1} it is the luminance of
     * every glyph mask. macOS only.
     */
    private static final int GRAY_LUMINANCE = Math.max(0, Math.min(255, Integer.getInteger("sun.java2d.skia.text.cgLuminance", 0)));

    /** The luminance the cg route rasterizes for when the strike carries no class ({@code sun.java2d.skia.text.cgLuminance}). */
    static int grayLuminance() {
        return GRAY_LUMINANCE;
    }
    /** Whether stems are darkened (the darkening dimension, resolved against the user's preference). */
    private static final boolean DARKENING = sun.java2d.skia.SKEnvironment.textDarkeningOn();
    /** The hinting request for the native context (the hinting dimension; −1 is the platform's own decision). */
    private static final int HINTING = sun.java2d.skia.SKEnvironment.textHintingRequest();

    private final Font2D font;
    private final long unitsPerEm;
    private final int numGlyphs;

    /** Over the platform's native font object (macOS: the CTFontRef behind a CFont). */
    public static SKFontScaler forNativeFont(Font2D font, long nativeFont) {
        requireAvailable();
        return new SKFontScaler(font, createNative(nativeFont, null, 0));
    }

    /** Over a font file (TrueType / OpenType, {@code index} within a collection). */
    public static SKFontScaler forFile(Font2D font, String path, int index) {
        requireAvailable();
        return new SKFontScaler(font, createNative(0, path, index));
    }

    /** The natives live in libawt_skia; the scaler needs the library, not the GPU backend. */
    private static void requireAvailable() {
        if (!isAvailable()) {
            throw new IllegalStateException("the Skia library is not available");
        }
    }

    private SKFontScaler(Font2D font, long pScaler) {
        if (pScaler == 0) {
            throw new IllegalArgumentException("no Skia typeface for " + font);
        }
        this.font = font;
        this.nativeScaler = pScaler;
        this.unitsPerEm = getUnitsPerEmNative(pScaler);
        this.numGlyphs = getNumGlyphsNative(pScaler);
        Disposer.addObjectRecord(this, this);
    }

    /** Whether a scaler can be made at all here (the platform has a font host). */
    public static boolean isAvailable() {
        return SKEnvironment.loadLibrary();
    }

    @Override
    StrikeMetrics getFontMetrics(long pScalerContext) {
        return getFontMetricsNative(pScalerContext);
    }

    @Override
    float getGlyphAdvance(long pScalerContext, int glyphCode) {
        return getGlyphAdvanceNative(pScalerContext, glyphCode);
    }

    @Override
    void getGlyphMetrics(long pScalerContext, int glyphCode, Point2D.Float metrics) {
        metrics.x = getGlyphAdvanceNative(pScalerContext, glyphCode);
        metrics.y = 0;
    }

    @Override
    long getGlyphImage(long pScalerContext, int glyphCode) {
        return getGlyphImageNative(pScalerContext, glyphCode,
                                   FontUtilities.subpixelResolution.width, FontUtilities.subpixelResolution.height);
    }

    /** The glyph's bounds from the font host, so bitmap glyphs (colour emoji) have them too. */
    @Override
    Rectangle2D.Float getGlyphOutlineBounds(long pScalerContext, int glyphCode) {
        Rectangle2D.Float bounds = getGlyphBoundsNative(pScalerContext, glyphCode);
        return bounds != null ? bounds : new Rectangle2D.Float();
    }

    @Override
    GeneralPath getGlyphOutline(long pScalerContext, int glyphCode, float x, float y) {
        GeneralPath path = getGlyphOutlineNative(pScalerContext, glyphCode, x, y);
        return path != null ? path : new GeneralPath();
    }

    @Override
    GlyphRenderData getGlyphRenderData(long pScalerContext, int glyphCode, float x, float y) {
        GlyphRenderData data = new GlyphRenderData();
        data.outline = getGlyphOutline(pScalerContext, glyphCode, x, y);
        return data;
    }

    @Override
    int getNumGlyphs() {
        return numGlyphs;
    }

    @Override
    int getMissingGlyphCode() {
        return 0;
    }

    @Override
    int getGlyphCode(char charCode) {
        return getGlyphCodeNative(nativeScaler, charCode);
    }

    @Override
    Point2D.Float getGlyphPoint(long pScalerContext, int glyphCode, int ptNumber) {
        return new Point2D.Float();   // outline control points are not exposed by SkFont
    }

    @Override
    long getUnitsPerEm() {
        return unitsPerEm;
    }

    /**
     * The scaler contract: whole-pixel advances are rounded in device space,
     * as the FreeType scaler behind {@code FileFontStrike} does.
     */
    @Override
    long createScalerContext(double[] matrix, int aa, int fm, float boldness, float italic) {
        return createScalerContext(matrix, aa, fm, boldness, italic, -1);
    }

    @Override
    long createScalerContext(double[] matrix, int aa, int fm, float boldness, float italic, int luminance) {
        return createScalerContext(matrix, aa, fm, boldness, italic, luminance, 1.0);
    }

    /**
     * With the display scale (the scale of the strike's device transform, 2 on a 200 % display):
     * the platform's glyph placement grid is per device-independent pixel.
     */
    @Override
    long createScalerContext(double[] matrix, int aa, int fm, float boldness, float italic, int luminance, double displayScale) {
        return createContextNative(nativeScaler, matrix, aa, fm, boldness, italic, 1.0, displayScale, BLEND, DARKENING, HINTING,
                                   luminance >= 0 ? luminance : GRAY_LUMINANCE);
    }

    /**
     * A context for a strike that rounds whole-pixel advances in user space,
     * as the CoreText strike ({@code CStrike}) does: {@code deviceScale} is the
     * scale of the device transform, which the glyph transform includes (and
     * so also the display scale).
     */
    long createScalerContext(double[] matrix, double deviceScale, int aa, int fm, float boldness, float italic) {
        return createScalerContext(matrix, deviceScale, aa, fm, boldness, italic, -1);
    }

    /** With the text luminance class (FontStrikeDesc.luminance) for the CoreGraphics route; -1 falls back to the property. */
    long createScalerContext(double[] matrix, double deviceScale, int aa, int fm, float boldness, float italic, int luminance) {
        return createContextNative(nativeScaler, matrix, aa, fm, boldness, italic, deviceScale, deviceScale, BLEND, DARKENING, HINTING,
                                   luminance >= 0 ? luminance : GRAY_LUMINANCE);
    }

    @Override
    void invalidateScalerContext(long pScalerContext) {
        disposeContextNative(pScalerContext);
    }

    @Override
    public void dispose() {
        disposeScaler();
    }

    @Override
    public synchronized void disposeScaler() {
        if (nativeScaler != 0) {
            disposeNative(nativeScaler);
            nativeScaler = 0;
        }
    }

    private static native long createNative(long nativeFont, String path, int index);
    private static native void disposeNative(long pScaler);
    private static native int getNumGlyphsNative(long pScaler);
    private static native int getGlyphCodeNative(long pScaler, char charCode);
    private static native long getUnitsPerEmNative(long pScaler);
    /** Whether the context's glyph transform is axis-aligned (size and optional synthetic italic only), so Skia's text path can draw it. */
    boolean contextIsAxisAligned(long pContext) {
        return contextIsAxisAlignedNative(pContext);
    }

    private static native boolean contextIsAxisAlignedNative(long pContext);

    private static native long createContextNative(long pScaler, double[] matrix, int aa, int fm, float boldness, float italic,
                                                   double deviceScale, double displayScale, int blend, boolean darkening, int hinting,
                                                   int grayLuminance);
    private static native void disposeContextNative(long pContext);
    private static native StrikeMetrics getFontMetricsNative(long pContext);
    private static native float getGlyphAdvanceNative(long pContext, int glyphCode);
    private static native GeneralPath getGlyphOutlineNative(long pContext, int glyphCode, float x, float y);
    private static native Rectangle2D.Float getGlyphBoundsNative(long pContext, int glyphCode);
    private static native long getGlyphImageNative(long pContext, int glyphCode, int subpixelResolutionX, int subpixelResolutionY);
}
