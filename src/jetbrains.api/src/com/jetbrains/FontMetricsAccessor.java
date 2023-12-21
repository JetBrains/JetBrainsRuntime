/*
 * Copyright 2023 JetBrains s.r.o.
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

package com.jetbrains;

import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.image.BufferedImage;

/**
 * Provides convenience methods to access {@link FontMetrics} instances, and obtain character advances from them without
 * rounding. Also provides an (unsafe) way to override character advances in those instances with arbitrary specified
 * values.
 */
public interface FontMetricsAccessor {
    /**
     * Returns a {@link FontMetrics} instance for the given {@link Font} and {@link FontRenderContext}. This is supposed
     * to be the same instance as returned by the public API methods ({@link Graphics#getFontMetrics()},
     * {@link Graphics#getFontMetrics(Font)} and {@link Component#getFontMetrics(Font)}) in the same context.
     */
    FontMetrics getMetrics(Font font, FontRenderContext context);

    /**
     * Returns not rounded value for the character's advance. It's not accessible directly via public
     * {@link FontMetrics} API, one can only extract it from one of the {@code getStringBounds} methods' output.
     */
    float codePointWidth(FontMetrics metrics, int codePoint);

    /**
     * Allows to override advance values returned by the specified {@link FontMetrics} instance. It's not generally
     * guaranteed the invocation of this method actually has the desired effect. One can verify whether it's the case
     * using {@link #hasOverride(FontMetrics)} method.
     * <p>
     * A subsequent invocation of this method will override any previous invocations. Passing {@code null} as the
     * {@code overrider} value will remove any override, if it was set up previously.
     * <p>
     * While this method operates on a specific {@link FontMetrics} instance, it's expected that overriding will have
     * effect regardless of the method font metrics are accessed, for all future character advance requests. This is
     * feasible, as JDK implementation generally uses the same cached {@link FontMetrics} instance in identical
     * contexts.
     * <p>
     * The method doesn't provides any concurrency guarantees, i.e. the override isn't guaranteed to be immediately
     * visible for font metrics readers in other threads.
     * <p>
     * WARNING. This method can break the consistency of a UI application, as previously calculated/returned advance
     * values can already be used/cached by some UI components. It's the calling code's responsibility to remediate such
     * consequences (e.g. re-validating all components which use the relevant font might be required).
     */
    void setOverride(FontMetrics metrics, Overrider overrider);

    /**
     * Tells whether character advances returned by the specified {@link FontMetrics} instance are overridden using the
     * previous {@link #setOverride(FontMetrics, Overrider)} call.
     */
    boolean hasOverride(FontMetrics metrics);

    /**
     * Removes all overrides set previously by {@link #setOverride(FontMetrics, Overrider)} invocations.
     */
    void removeAllOverrides();

    /**
     * @see #setOverride(FontMetrics, Overrider)
     */
    interface Overrider {
        /**
         * Returning {@code NaN} means the default (not overridden) value should be used.
         */
        float charWidth(int codePoint);
    }

    class __Fallback implements FontMetricsAccessor {
        private final Graphics2D g;

        __Fallback() {
            g = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
        }

        @Override
        public FontMetrics getMetrics(Font font, FontRenderContext context) {
            synchronized (g) {
                g.setTransform(context.getTransform());
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, context.getAntiAliasingHint());
                g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, context.getFractionalMetricsHint());
                return g.getFontMetrics(font);
            }
        }

        @Override
        public float codePointWidth(FontMetrics metrics, int codePoint) {
            String s = new String(new int[]{codePoint}, 0, 1);
            return (float) metrics.getFont().getStringBounds(s, metrics.getFontRenderContext()).getWidth();
        }

        @Override
        public void setOverride(FontMetrics metrics, Overrider overrider) {}

        @Override
        public boolean hasOverride(FontMetrics metrics) {
            return false;
        }

        @Override
        public void removeAllOverrides() {}
    }
}
