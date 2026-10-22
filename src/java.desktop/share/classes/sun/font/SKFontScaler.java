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
 * Lives in {@code libawt_skia}; only created when the Skia backend is
 * available ({@link SKEnvironment#isSkiaEnabled()}). Selected for the
 * platform's fonts with {@code -Dsun.java2d.font.scaler=skia}
 * ({@link FontUtilities#useSkiaScaler}): {@code CFont} strikes become
 * {@link SKFontStrike}s, file fonts get this scaler behind their
 * {@code FileFontStrike}.
 */
public final class SKFontScaler extends FontScaler {

    /**
     * How grayscale (non-LCD) antialiased glyphs are rasterized, since the
     * JDK's CoreGraphics renderer applies font smoothing without sub-pixels,
     * which Skia's font host does not expose ({@code sun.java2d.skia.text.gray}):
     * {@code coverage} (the default: the host's plain antialiased coverage,
     * about 15 % less ink than the platform's text on macOS), {@code smoothed}
     * (the host's sub-pixel render with the channels averaged, as the JDK does
     * with CoreGraphics; about 13 % more ink) or {@code darkened} (coverage of
     * the outline widened by {@code sun.java2d.skia.text.grayDarkening} of the
     * em, default 0.013, rasterized by Skia; CoreGraphics' smoothing measured
     * as a widening proportional to the size, 0.17 px at 13 px, 0.35 px at 26).
     */
    static final int GRAY_COVERAGE = 0, GRAY_SMOOTHED = 1, GRAY_DARKENED = 2;
    private static final int GRAY_MODE = grayModeProperty();
    private static final float GRAY_DARKENING = Float.parseFloat(System.getProperty("sun.java2d.skia.text.grayDarkening", "0.013"));

    private static int grayModeProperty() {
        return switch (System.getProperty("sun.java2d.skia.text.gray", "coverage").toLowerCase(java.util.Locale.ROOT)) {
            case "smoothed" -> GRAY_SMOOTHED;
            case "darkened" -> GRAY_DARKENED;
            default -> GRAY_COVERAGE;
        };
    }

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

    /** The natives live in libawt_skia, which SKEnvironment loads on its first query. */
    private static void requireAvailable() {
        if (!isAvailable()) {
            throw new IllegalStateException("the Skia backend is not available");
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
        return SKEnvironment.isSkiaEnabled();
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
        return createContextNative(nativeScaler, matrix, aa, fm, boldness, italic, 1.0, GRAY_MODE, GRAY_DARKENING);
    }

    /**
     * A context for a strike that rounds whole-pixel advances in user space,
     * as the CoreText strike ({@code CStrike}) does: {@code deviceScale} is the
     * scale of the device transform, which the glyph transform includes.
     */
    long createScalerContext(double[] matrix, double deviceScale, int aa, int fm, float boldness, float italic) {
        return createContextNative(nativeScaler, matrix, aa, fm, boldness, italic, deviceScale, GRAY_MODE, GRAY_DARKENING);
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
    private static native long createContextNative(long pScaler, double[] matrix, int aa, int fm, float boldness, float italic,
                                                   double deviceScale, int grayMode, float grayDarkening);
    private static native void disposeContextNative(long pContext);
    private static native StrikeMetrics getFontMetricsNative(long pContext);
    private static native float getGlyphAdvanceNative(long pContext, int glyphCode);
    private static native GeneralPath getGlyphOutlineNative(long pContext, int glyphCode, float x, float y);
    private static native Rectangle2D.Float getGlyphBoundsNative(long pContext, int glyphCode);
    private static native long getGlyphImageNative(long pContext, int glyphCode, int subpixelResolutionX, int subpixelResolutionY);
}
