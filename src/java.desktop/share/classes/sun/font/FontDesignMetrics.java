/*
 * Copyright (c) 1997, 2021, Oracle and/or its affiliates. All rights reserved.
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

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GraphicsEnvironment;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.jetbrains.desktop.FontExtensions;
import sun.java2d.Disposer;
import sun.java2d.DisposerRecord;

/*
 * This class provides a summary of the glyph measurements  for a Font
 * and a set of hints that guide their display.  It provides more metrics
 * information for the Font than the java.awt.FontMetrics class. There
 * is also some redundancy with that class.
 * <p>
 * The design metrics for a Font are obtained from Font.getDesignMetrics().
 * The FontDesignMetrics object returned will be independent of the
 * point size of the Font.
 * Most users are familiar with the idea of using <i>point size</i> to
 * specify the size of glyphs in a font. This point size defines a
 * measurement between the baseline of one line to the baseline of the
 * following line in a single spaced text document. The point size is
 * based on <i>typographic points</i>, approximately 1/72 of an inch.
 * <p>
 * The Java2D API adopts the convention that one point is equivalent
 * to one unit in user coordinates.  When using a normalized transform
 * for converting user space coordinates to device space coordinates (see
 * GraphicsConfiguration.getDefaultTransform() and
 * GraphicsConfiguration.getNormalizingTransform()), 72 user space units
 * equal 1 inch in device space.  In this case one point is 1/72 of an inch.
 * <p>
 * The FontDesignMetrics class expresses font metrics in terms of arbitrary
 * <i>typographic units</i> (not points) chosen by the font supplier
 * and used in the underlying platform font representations.  These units are
 * defined by dividing the em-square into a grid.  The em-sqaure is the
 * theoretical square whose dimensions are the full body height of the
 * font.  A typographic unit is the smallest measurable unit in the
 * em-square.  The number of units-per-em is determined by the font
 * designer.  The greater the units-per-em, the greater the precision
 * in metrics.  For example, Type 1 fonts divide the em-square into a
 * 1000 x 1000 grid, while TrueType fonts typically use a 2048 x 2048
 * grid.  The scale of these units can be obtained by calling
 * getUnitsPerEm().
 * <p>
 * Typographic units are relative -- their absolute size changes as the
 * size of the of the em-square changes.  An em-square is 9 points high
 * in a 9-point font.  Because typographic units are relative to the
 * em-square, a given location on a glyph will have the same coordinates
 * in typographic units regardless of the point size.
 * <p>
 * Converting typographic units to pixels requires computing pixels-per-em
 * (ppem).  This can be computed as:
 * <pre>
         ppem = device_resolution * (inches-per-point) * pointSize
 * </pre>
 * where device resolution could be measured in pixels/inch and the point
 * size of a font is effectively points/em.  Using a normalized transform
 * from user space to device space (see above), results in 1/72 inch/point.
 * In this case, ppem is equal to the point size on a 72 dpi monitor, so
 * that an N point font displays N pixels high.  In general,
 * <pre>
        pixel_units = typographic_units * (ppem / units_per_em)
 * </pre>
 * @see java.awt.Font
 * @see java.awt.GraphicsConfiguration#getDefaultTransform
 * @see java.awt.GraphicsConfiguration#getNormalizingTransform
 */

public final class FontDesignMetrics extends FontMetrics {

    /**
     * Use serialVersionUID from JDK 1.3 for interoperability.
     */
    @Serial
    private static final long serialVersionUID = 4480069578560887773L;

    private static final float UNKNOWN_WIDTH = -1;
    private static final int CURRENT_VERSION = 1;

    // height, ascent, descent, leading are reported to the client
    // as an integer this value is added to the true fp value to
    // obtain a value which is usually going to result in a round up
    // to the next integer except for very marginal cases.
    private static float roundingUpValue = 0.95f;

    // These fields are all part of the old serialization representation
    private Font  font;
    private float ascent;
    private float descent;
    private float leading;
    private float maxAdvance;
    private double[] matrix;
    private int[] cache; // now unused, still here only for serialization
    // End legacy serialization fields

    private int serVersion = 0;  // If 1 in readObject, these fields are on the input stream:
    private boolean isAntiAliased;
    private boolean usesFractionalMetrics;
    private AffineTransform frcTx;

    private transient float[] advCache; // transient since values could change across runtimes
    private transient int height = -1;

    private transient FontRenderContext frc;

    private transient double[] devmatrix = null;

    private transient FontStrike fontStrike;

    private transient Overrider overrider;

    private static FontRenderContext DEFAULT_FRC = null;

    private static FontRenderContext getDefaultFrc() {

        if (DEFAULT_FRC == null) {
            AffineTransform tx;
            if (GraphicsEnvironment.isHeadless()) {
                tx = new AffineTransform();
            } else {
                tx =  GraphicsEnvironment
                    .getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice()
                    .getDefaultConfiguration()
                    .getDefaultTransform();
            }
            DEFAULT_FRC = new FontRenderContext(tx, false, false);
        }
        return DEFAULT_FRC;
    }

    /* Strongly cache up to 5 most recently requested FontMetrics objects,
     * and softly cache as many as GC allows. In practice this means we
     * should keep references around until memory gets low.
     * We key the cache either by a Font or a combination of the Font and
     * and FRC. A lot of callers use only the font so although there's code
     * duplication, we allow just a font to be a key implying a default FRC.
     * Also we put the references on a queue so that if they do get nulled
     * out we can clear the keys from the table.
     */
    private static class KeyReference extends SoftReference<Object>
        implements DisposerRecord, Disposer.PollDisposable {

        static ReferenceQueue<Object> queue = Disposer.getQueue();

        Object key;

        KeyReference(Object key, Object value) {
            super(value, queue);
            this.key = key;
            Disposer.addReference(this, this);
        }

        /* It is possible that since this reference object has been
         * enqueued, that a new metrics has been put into the table
         * for the same key value. So we'll test to see if the table maps
         * to THIS reference. If it's a new one, we'll leave it alone.
         */
        public void dispose() {
            metricsCache.remove(key, this);
        }
    }

    private static class MetricsKey {
        Font font;
        FontRenderContext frc;
        int hash;

        MetricsKey() {
        }

        MetricsKey(Font font, FontRenderContext frc) {
            init(font, frc);
        }

        void init(Font font, FontRenderContext frc) {
            this.font = font;
            this.frc = frc;
            this.hash = font.hashCode() + frc.hashCode();
        }

        public boolean equals(Object key) {
            if (!(key instanceof MetricsKey)) {
                return false;
            }
            return
                font.equals(((MetricsKey)key).font) &&
                frc.equals(((MetricsKey)key).frc);
        }

        public int hashCode() {
            return hash;
        }

        /* Synchronize access to this on the class */
        static final MetricsKey key = new MetricsKey();
    }

    /* All accesses to a CHM do not in general need to be synchronized,
     * as incomplete operations on another thread would just lead to
     * harmless cache misses.
     */
    private static final ConcurrentHashMap<Object, KeyReference>
        metricsCache = new ConcurrentHashMap<Object, KeyReference>();

    private static final int MAXRECENT = 5;
    private static final FontDesignMetrics[]
        recentMetrics = new FontDesignMetrics[MAXRECENT];
    private static int recentIndex = 0;

    public static FontDesignMetrics getMetrics(Font font) {
        return getMetrics(font, getDefaultFrc());
     }

    public static FontDesignMetrics getMetrics(Font font,
                                               FontRenderContext frc) {


        /* When using alternate composites, can't cache based just on
         * the java.awt.Font. Since this is rarely used and we can still
         * cache the physical fonts, its not a problem to just return a
         * new instance in this case.
         * Note that currently Swing native L&F composites are not handled
         * by this code as they use the metrics of the physical anyway.
         */
        SunFontManager fm = SunFontManager.getInstance();
        if (fm.usingAlternateCompositeFonts() &&
            FontUtilities.getFont2D(font) instanceof CompositeFont) {
            return new FontDesignMetrics(font, frc);
        }

        FontDesignMetrics m = null;
        KeyReference r;

        /* There are 2 possible keys used to perform lookups in metricsCache.
         * If the FRC is set to all defaults, we just use the font as the key.
         * If the FRC is non-default in any way, we construct a hybrid key
         * that combines the font and FRC.
         */
        boolean usefontkey = frc.equals(getDefaultFrc());

        if (usefontkey) {
            r = metricsCache.get(font);
        } else /* use hybrid key */ {
            // NB synchronization is not needed here because of updates to
            // the metrics cache but is needed for the shared key.
            synchronized (MetricsKey.class) {
                MetricsKey.key.init(font, frc);
                r = metricsCache.get(MetricsKey.key);
            }
        }

        if (r != null) {
            m = (FontDesignMetrics)r.get();
        }

        if (m == null) {
            /* either there was no reference, or it was cleared. Need a new
             * metrics instance. The key to use in the map is a new
             * MetricsKey instance when we've determined the FRC is
             * non-default. Its constructed from local vars so we are
             * thread-safe - no need to worry about the shared key changing.
             */
            m = new FontDesignMetrics(font, frc);
            if (usefontkey) {
                metricsCache.put(font, new KeyReference(font, m));
            } else /* use hybrid key */ {
                MetricsKey newKey = new MetricsKey(font, frc);
                metricsCache.put(newKey, new KeyReference(newKey, m));
            }
        }

        /* Here's where we keep the recent metrics */
        for (int i=0; i<recentMetrics.length; i++) {
            if (recentMetrics[i]==m) {
                return m;
            }
        }

        synchronized (recentMetrics) {
            recentMetrics[recentIndex++] = m;
            if (recentIndex == MAXRECENT) {
                recentIndex = 0;
            }
        }
        return m;
    }

  /*
   * Constructs a new FontDesignMetrics object for the given Font.
   * Its private to enable caching - call getMetrics() instead.
   * @param font a Font object.
   */

    private FontDesignMetrics(Font font) {

        this(font, getDefaultFrc());
    }

    /* private to enable caching - call getMetrics() instead. */
    private FontDesignMetrics(Font font, FontRenderContext frc) {
      super(font);
      this.font = font;
      this.frc = frc;

      this.isAntiAliased = frc.isAntiAliased();
      this.usesFractionalMetrics = frc.usesFractionalMetrics();

      frcTx = frc.getTransform();

      matrix = new double[4];
      initMatrixAndMetrics();

      initAdvCache();
    }

    private void initMatrixAndMetrics() {

        Font2D font2D = FontUtilities.getFont2D(font);
        fontStrike = font2D.getStrike(font, frc);
        StrikeMetrics metrics = fontStrike.getFontMetrics();
        this.ascent = metrics.getAscent();
        this.descent = metrics.getDescent();
        this.leading = metrics.getLeading();
        this.maxAdvance = metrics.getMaxAdvance();

        devmatrix = new double[4];
        frcTx.getMatrix(devmatrix);
    }

    private void initAdvCache() {
        advCache = new float[256];
        // 0 is a valid metric so force it to -1
        for (int i = 0; i < 256; i++) {
            advCache[i] = UNKNOWN_WIDTH;
        }
    }

    @Serial
    private void readObject(ObjectInputStream in) throws IOException,
                                                  ClassNotFoundException {

        in.defaultReadObject();
        if (serVersion != CURRENT_VERSION) {
            frc = getDefaultFrc();
            isAntiAliased = frc.isAntiAliased();
            usesFractionalMetrics = frc.usesFractionalMetrics();
            frcTx = frc.getTransform();
        }
        else {
            frc = new FontRenderContext(frcTx, isAntiAliased, usesFractionalMetrics);
        }

        // when deserialized, members are set to their default values for their type--
        // not to the values assigned during initialization before the constructor
        // body!
        height = -1;

        cache = null;

        initMatrixAndMetrics();
        initAdvCache();
    }

    @Serial
    private void writeObject(ObjectOutputStream out) throws IOException {

        cache = new int[256];
        for (int i=0; i < 256; i++) {
            cache[i] = -1;
        }
        serVersion = CURRENT_VERSION;

        out.defaultWriteObject();

        cache = null;
    }

    private float handleCharWidth(int ch) {
        return fontStrike.getCodePointAdvance(ch); // x-component of result only
    }

    // Uses advCache to get character width
    // It is incorrect to call this method for ch > 255
    private float getLatinCharWidth(char ch) {

        float w = advCache[ch];
        if (w == UNKNOWN_WIDTH) {
            w = handleCharWidth(ch);
            advCache[ch] = w;
        }
        return w;
    }


    /* Override of FontMetrics.getFontRenderContext() */
    public FontRenderContext getFontRenderContext() {
        return frc;
    }

    private float preciseCharWidth(int ch) {
        if (!Character.isValidCodePoint(ch)) {
            ch = 0xffff;
        }
        Overrider o = overrider;
        if (o != null) {
            float override = o.charWidth(ch);
            if (!Float.isNaN(override)) {
                return override;
            }
        }
        return (ch < 0x100) ? getLatinCharWidth((char) ch) : handleCharWidth(ch);
    }

    public int charWidth(char ch) {
        return charWidth((int) ch);
    }

    public int charWidth(int ch) {
        return Math.round(preciseCharWidth(ch));
    }

    private Rectangle2D.Float textLayoutBounds(Object data, int off, int len) {
        String str = data instanceof String ? ((String) data).substring(off, off  + len) :
                new String((char[]) data, off, len);
        TextLayout tl = new TextLayout(str, font, frc);
        return new Rectangle2D.Float(0, -tl.getAscent(),
                tl.getAdvance(), tl.getAscent() + tl.getDescent() + tl.getLeading());
    }

    private char getChar(Object data, int i) {
        return data instanceof String ? ((String) data).charAt(i) : ((char[]) data)[i];
    }

    final int ADVANCES_FAST_KEY_LIMIT = 0x100;
    private HashMap<Integer, Float> advances = new HashMap<>();
    private float[] advancesFast = new float[ADVANCES_FAST_KEY_LIMIT * ADVANCES_FAST_KEY_LIMIT];

    private Rectangle2D.Float dataBounds(Object data, int off, int len) {

        assert (data instanceof String || data instanceof char[]);
        float width = 0;

        if (overrider == null && FontExtensions.isComplexRendering(font) && len > 0) {
            return textLayoutBounds(data, off, len);
        }

        float height = ascent + descent + leading;

        if (len == 0 || len == 1 && !FontUtilities.isNonSimpleChar(getChar(data, off))) {
            width = len > 0 ? preciseCharWidth(getChar(data, off)) : 0f;
            return new Rectangle2D.Float(0f, -ascent, width, height);
        }

        boolean isKerning = FontExtensions.isKerning(font);
        float consecutiveDoubleCharacterWidth = 0f;
        char prev = 0;
        for (int i = off; i < off + len; i++) {
            char cur = getChar(data, i);
            if (FontUtilities.isNonSimpleChar(cur)) {
                return textLayoutBounds(data, off, len);
            } else {
                // Correct width of string with kerning calculates by text LayoutBounds but it could be slow.
                // Below is description of optimized algorithm for correct calculating width of text with kerning:
                // proof:
                // base: kerningWidth('c1') = plainWidth('c1')
                // induction: kerningWidth('c1..cN-1cN') =
                //                                 kerningWidth('c1..cN-1') + kerningWidth('cN-1cN') - plainWidth('cN-1')
                // final: kerningWidth('c1..cN') = kerningWidth('c1c2') + ... + kerningWidth('cN-1cN')
                //                                                      - (plainWidth('c2') + ... + plainWidth('cN-1'))
                // remark: for reducing calculation of kerningWidth('c1c2') using caching
                if (isKerning && i > off) {
                    // fast path
                    if (prev < ADVANCES_FAST_KEY_LIMIT && cur < ADVANCES_FAST_KEY_LIMIT) {
                        int key = (prev << 8) | cur;
                        if (advancesFast[key] == 0.0f) {
                            advancesFast[key] = textLayoutBounds(data, i - 1, 2).width;
                        }
                        consecutiveDoubleCharacterWidth += advancesFast[key];
                    // common solution
                    } else {
                        int key = (prev << 16) | (0xffff & cur);
                        advances.putIfAbsent(key, textLayoutBounds(data, i - 1, 2).width);
                        consecutiveDoubleCharacterWidth += advances.get(key);
                    }
                }
                width += preciseCharWidth(cur);
            }
            prev = cur;
        }

        if (isKerning) {
            width = consecutiveDoubleCharacterWidth -
                    (width - preciseCharWidth(getChar(data, off)) - preciseCharWidth(getChar(data, off + len - 1)));
        }

        return new Rectangle2D.Float(0f, -ascent, width, height);
    }

    public Rectangle2D.Float charsBounds(char[] data, int off, int len) {
        return dataBounds(data, off, len);
    }

    private int dataWidth(Object data, int off, int len) {
        return Math.round((float) dataBounds(data, off, len).getWidth());
    }

    public int stringWidth(String str) {

        if (str == null) {
            throw new NullPointerException("str is null");
        }
        return dataWidth(str, 0, str.length());
    }

    public int charsWidth(char[] data, int off, int len) {

        /* Explicit test needed to satisfy superclass spec */
        if (len < 0) {
            throw new IndexOutOfBoundsException("len="+len);
        }
        return dataWidth(data, off, len);
    }

    public int getMaxAdvance() {
        return (int)(0.99f + this.maxAdvance);
    }

  /*
   * Returns the typographic ascent of the font. This is the maximum distance
   * glyphs in this font extend above the base line (measured in typographic
   * units).
   */
    public int getAscent() {
        return (int)(roundingUpValue + this.ascent);
    }

  /*
   * Returns the typographic descent of the font. This is the maximum distance
   * glyphs in this font extend below the base line.
   */
    public int getDescent() {
        return (int)(roundingUpValue + this.descent);
    }

    public int getLeading() {
        // nb this ensures the sum of the results of the public methods
        // for leading, ascent & descent sum to height.
        // if the calculations in any other methods change this needs
        // to be changed too.
        // the 0.95 value used here and in the other methods allows some
        // tiny fraction of leeway before rounding up. A higher value (0.99)
        // caused some excessive rounding up.
        return
            (int)(roundingUpValue + descent + leading) -
            (int)(roundingUpValue + descent);
    }

    // height is calculated as the sum of two separately rounded up values
    // because typically clients use ascent to determine the y location to
    // pass to drawString etc and we need to ensure that the height has enough
    // space below the baseline to fully contain any descender.
    public int getHeight() {

        if (height < 0) {
            height = getAscent() + (int)(roundingUpValue + descent + leading);
        }
        return height;
    }

    private static class Accessor { // used by JBR API
        // Keeping metrics instances here prevents them from being garbage collected
        // and being re-created by FontDesignMetrics.getMetrics method
        private final Set<FontDesignMetrics> PINNED_METRICS = new HashSet<>();

        FontMetrics getMetrics(Font font, FontRenderContext context) {
            return FontDesignMetrics.getMetrics(font, context);
        }

        float codePointWidth(FontMetrics metrics, int codePoint) {
            return metrics instanceof FontDesignMetrics fdm ? fdm.preciseCharWidth(codePoint)
                                                            : metrics.charWidth(codePoint);
        }

        void setOverride(FontMetrics metrics, Overrider overrider) {
            if (metrics instanceof FontDesignMetrics fdm) {
                synchronized (PINNED_METRICS) {
                    if (overrider == null) {
                        PINNED_METRICS.remove(fdm);
                    } else {
                        PINNED_METRICS.add(fdm);
                    }
                    fdm.overrider = overrider;
                }
            }
        }

        boolean hasOverride(FontMetrics metrics) {
            return metrics instanceof FontDesignMetrics fdm && fdm.overrider != null;
        }

        void removeAllOverrides() {
            synchronized (PINNED_METRICS) {
                PINNED_METRICS.forEach(fdm -> fdm.overrider = null);
                PINNED_METRICS.clear();
            }
        }
    }

    private interface Overrider { // used by JBR API
        float charWidth(int codePoint);
    }
}
