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

import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.concurrent.ConcurrentHashMap;

import sun.java2d.Disposer;

/**
 * A strike of a platform font ({@code CFont}) on the {@link SKFontScaler}:
 * what {@code CStrike} is on CoreText, with the scaler answering in device
 * space and this class mapping back to user space through the inverse device
 * transform, as {@code FileFontStrike} does. Whole-pixel advances are rounded
 * in user space, {@code CStrike}'s rule, so text keeps its metrics when the
 * scaler is switched.
 */
public final class SKFontStrike extends PhysicalStrike implements SKDrawableStrike {

    private static final int INVISIBLE_GLYPHS = CharToGlyphMapper.INVISIBLE_GLYPHS;

    private final SKFontScaler scaler;
    private final AffineTransform invertDevTx;
    private final long[] glyphImages;
    private final FontStrikeDisposer disposer;
    private ConcurrentHashMap<Integer, Rectangle2D.Float> boundsMap;
    /**
     * Above the outline threshold (FontInfo's pixel height) a strike Skia's text path draws hands out records with
     * the advance only: every pipe draws such text as outlines or through Skia's text path, which draws large glyphs
     * itself, so their images are never drawn; pixel bounds come from the outlines (as FileFontStrike's above it).
     */
    private final boolean advancesOnly;
    private static final int ADVANCES_ONLY_PAD = 2;
    /** Images of fallback characters (negative codes), freed with the strike. */
    private final ConcurrentHashMap<Integer, Long> fallbackImages = new ConcurrentHashMap<>();

    SKFontStrike(PhysicalFont font, FontStrikeDesc desc, SKFontScaler scaler) {
        super(font, desc);
        this.scaler = scaler;
        this.mapper = font.getMapper();
        AffineTransform inverse = null;
        double deviceScale = 1.0;
        if (!desc.devTx.isIdentity() && desc.devTx.getType() != AffineTransform.TYPE_TRANSLATION) {
            try {
                inverse = desc.devTx.createInverse();
                deviceScale = Math.sqrt(Math.abs(desc.devTx.getDeterminant()));
            } catch (NoninvertibleTransformException e) {
                // a singular device transform draws nothing; keep device space
            }
        }
        this.invertDevTx = inverse;
        // the glyph transform, then the device transform: whole-pixel advances are rounded per component in user
        // space (the font's own transform included, the graphics' not), CStrike's rule under any transform
        double[] matrix = new double[8];
        desc.glyphTx.getMatrix(matrix);
        double[] device = new double[4];
        desc.devTx.getMatrix(device);
        System.arraycopy(device, 0, matrix, 4, 4);
        this.pScalerContext = scaler.createScalerContext(matrix, deviceScale, desc.aaHint, desc.fmHint, 1f, 0f, desc.luminance);
        this.glyphImages = new long[Math.max(1, mapper.getNumGlyphs())];
        double pixelHeight = Math.hypot(matrix[2], matrix[3]);   // SunGraphics2D's FontInfo.pixelHeight: m01, m11 of the glyph transform
        this.advancesOnly = (int) (pixelHeight + 0.5) > sun.java2d.pipe.OutlineTextRenderer.THRESHHOLD
                && pScalerContext != 0 && scaler.contextIsInvertible(pScalerContext);
        this.disposer = new FontStrikeDisposer(font, desc, pScalerContext, glyphImages);
        Disposer.addObjectRecord(this, disposer);
        ConcurrentHashMap<Integer, Long> fallbacks = fallbackImages;
        Disposer.addObjectRecord(this, () -> {
            for (long ptr : fallbacks.values()) {
                StrikeCache.freeLongPointer(ptr);
            }
            fallbacks.clear();
        });
        // the images carry the advance, so advances never need a second scaler call
        this.getImageWithAdvance = true;
    }

    @Override
    public int getNumGlyphs() {
        return mapper.getNumGlyphs();
    }

    @Override
    StrikeMetrics getFontMetrics() {
        if (strikeMetrics == null) {
            StrikeMetrics metrics = scaler.getFontMetrics(pScalerContext);
            if (invertDevTx != null) {
                metrics.convertToUserSpace(invertDevTx);
            }
            strikeMetrics = metrics;
        }
        return strikeMetrics;
    }

    @Override
    public long skiaScalerContext() {
        return pScalerContext;
    }

    @Override
    public boolean drawsBySkia() {
        return scaler.contextIsInvertible(pScalerContext);
    }

    @Override
    public double layoutScale() {
        return scaler.contextLayoutScale(pScalerContext);
    }

    @Override
    public boolean axisAligned() {
        return scaler.contextIsAxisAligned(pScalerContext);
    }

    @Override
    long getGlyphImagePtr(int glyphCode) {
        if (glyphCode >= INVISIBLE_GLYPHS) {
            return StrikeCache.invisibleGlyphPtr;
        }
        if (glyphCode < 0) {
            // CFont's mapper hands a character the font lacks to the platform's
            // fallback as -codePoint; the scaler resolves it the same way
            return fallbackImages.computeIfAbsent(glyphCode, this::createGlyphRecord);
        }
        if (glyphCode >= glyphImages.length) {
            return StrikeCache.invisibleGlyphPtr;
        }
        long ptr = glyphImages[glyphCode];
        if (ptr != 0L) {
            return ptr;
        }
        ptr = createGlyphRecord(glyphCode);
        synchronized (this) {
            if (glyphImages[glyphCode] == 0L) {
                glyphImages[glyphCode] = ptr;
            } else if (glyphImages[glyphCode] != ptr) {
                StrikeCache.freeLongPointer(ptr);
                ptr = glyphImages[glyphCode];
            }
        }
        return ptr;
    }

    @Override
    void getGlyphImagePtrs(int[] glyphCodes, long[] images, int len) {
        for (int i = 0; i < len; i++) {
            images[i] = getGlyphImagePtr(glyphCodes[i]);
        }
    }

    private long createGlyphRecord(int glyphCode) {
        return advancesOnly ? scaler.getGlyphAdvanceRecord(pScalerContext, glyphCode) : scaler.getGlyphImage(pScalerContext, glyphCode);
    }

    @Override
    void getGlyphImageBounds(int glyphCode, Point2D.Float pt, Rectangle result) {
        if (advancesOnly) {
            // the outline's bounds with the images' padding: the platform's masks below Skia's atlas limit spread
            // up to two pixels beyond the outline (antialiasing and smoothing), as the images did
            Rectangle2D.Float bounds = getGlyphOutlineBounds(glyphCode);
            if (bounds.isEmpty()) {
                result.setBounds((int) Math.floor(pt.x + 0.5f), (int) Math.floor(pt.y + 0.5f), 0, 0);
                return;
            }
            result.x = (int) Math.floor(pt.x + bounds.x) - ADVANCES_ONLY_PAD;
            result.y = (int) Math.floor(pt.y + bounds.y) - ADVANCES_ONLY_PAD;
            result.width = (int) Math.ceil(pt.x + bounds.x + bounds.width) + ADVANCES_ONLY_PAD - result.x;
            result.height = (int) Math.ceil(pt.y + bounds.y + bounds.height) + ADVANCES_ONLY_PAD - result.y;
            return;
        }
        long ptr = getGlyphImagePtr(glyphCode);
        if (ptr == 0L) {
            result.setBounds((int) Math.floor(pt.x + 0.5f), (int) Math.floor(pt.y + 0.5f), 0, 0);
            return;
        }
        result.x = (int) Math.floor(pt.x + StrikeCache.getGlyphTopLeftX(ptr) + 0.5f);
        result.y = (int) Math.floor(pt.y + StrikeCache.getGlyphTopLeftY(ptr) + 0.5f);
        result.width = StrikeCache.getGlyphWidth(ptr) & 0xffff;
        result.height = StrikeCache.getGlyphHeight(ptr) & 0xffff;
    }

    @Override
    Point2D.Float getGlyphMetrics(int glyphCode) {
        Point2D.Float metrics = new Point2D.Float();
        if (glyphCode < glyphImages.length) {
            long ptr = getGlyphImagePtr(glyphCode);
            metrics.x = StrikeCache.getGlyphXAdvance(ptr);
            metrics.y = StrikeCache.getGlyphYAdvance(ptr);
            if (invertDevTx != null) {
                invertDevTx.deltaTransform(metrics, metrics);
            }
        }
        return metrics;
    }

    @Override
    Point2D.Float getCharMetrics(char ch) {
        return getGlyphMetrics(mapper.charToGlyph(ch));
    }

    @Override
    float getGlyphAdvance(int glyphCode) {
        return getGlyphMetrics(glyphCode).x;
    }

    @Override
    float getCodePointAdvance(int cp) {
        return getGlyphAdvance(mapper.charToGlyph(cp));
    }

    /** In device space, like the outline: the glyph vector maps them back itself. */
    @Override
    Rectangle2D.Float getGlyphOutlineBounds(int glyphCode) {
        if (boundsMap == null) {
            boundsMap = new ConcurrentHashMap<>();
        }
        return boundsMap.computeIfAbsent(glyphCode, code -> scaler.getGlyphOutlineBounds(pScalerContext, code));
    }

    /** The outline in device space at {@code x}, {@code y}, as the layout code expects it. */
    @Override
    GeneralPath getGlyphOutline(int glyphCode, float x, float y) {
        if (glyphCode >= INVISIBLE_GLYPHS) {
            return new GeneralPath();
        }
        return scaler.getGlyphOutline(pScalerContext, glyphCode, x, y);
    }

    @Override
    GlyphRenderData getGlyphRenderData(int glyphCode, float x, float y) {
        GlyphRenderData data = new GlyphRenderData();
        data.outline = getGlyphOutline(glyphCode, x, y);
        return data;
    }
}
