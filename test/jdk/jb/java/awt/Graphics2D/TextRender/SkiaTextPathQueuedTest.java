/*
 * Copyright 2026 JetBrains s.r.o.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
 */

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.lang.reflect.Method;
import java.text.AttributedString;

/**
 * @test
 * @summary The three Skia text-path entries (sun.java2d.skia.text=skia, =shaper,
 *          =paragraph) are render-queue operations, not a flush and GPU submit
 *          per string: a paint of many strings reaches the backend in a handful
 *          of flushes, and the strings still all go through the Skia path (its
 *          counters advance by the whole paint). Rendering is covered by the
 *          other Skia text tests; this one counts flushes.
 * @requires os.family == "mac"
 * @key headful
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text=skia SkiaTextPathQueuedTest
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text=shaper SkiaTextPathQueuedTest
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text=paragraph SkiaTextPathQueuedTest
 */
public class SkiaTextPathQueuedTest {
    static final String TEXT = "Hamburgefonts Quick fox jumps 1234567890";
    static final int STRINGS = 300, W = 400, H = 300;
    /** Flushes a paint of STRINGS strings may take: the paint's own, the snapshot's, the flusher thread's. */
    static final int FLUSH_BUDGET = 20;

    public static void main(String[] args) throws Exception {
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        if (!gc.getClass().getName().contains("MTL")) {
            throw new RuntimeException("not an accelerated configuration: " + gc.getClass().getName());
        }
        final String mode = System.getProperty("sun.java2d.skia.text");
        final String counterName = switch (mode) {
            case "skia" -> "glyphsDrawnBySkia";
            case "shaper" -> "glyphsShapedBySkia";
            case "paragraph" -> "paragraphsBySkia";
            default -> throw new RuntimeException("unexpected text route " + mode);
        };
        final long pathBefore = counter(counterName), flushesBefore = counter("flushes");
        Font font = new Font("Helvetica Neue", Font.PLAIN, 11);
        VolatileImage vi = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
        BufferedImage out;
        do {
            vi.validate(gc);
            Graphics2D g = vi.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, W, H);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setColor(Color.BLACK);
            g.setFont(font);
            for (int i = 0; i < STRINGS; i++) {
                float x = 4 + (i % 3) * 2, y = 12 + (i % 24) * 12;
                if (mode.equals("paragraph")) {
                    AttributedString as = new AttributedString(TEXT);
                    as.addAttribute(TextAttribute.FONT, font);
                    g.drawString(as.getIterator(), x, y);
                } else {
                    g.drawString(TEXT, x, y);
                }
            }
            g.dispose();
            out = vi.getSnapshot();
        } while (vi.contentsLost());
        final long path = counter(counterName) - pathBefore, flushes = counter("flushes") - flushesBefore;
        final long expectedPath = mode.equals("paragraph") ? STRINGS : (long) STRINGS * TEXT.replace(" ", "").length();
        System.out.println(mode + ": " + STRINGS + " strings, " + counterName + " +" + path + ", flushes +" + flushes);
        if (path < expectedPath) {
            throw new RuntimeException("the Skia path drew " + path + ", expected at least " + expectedPath + " (route not taken)");
        }
        if (flushes > FLUSH_BUDGET) {
            throw new RuntimeException(flushes + " flushes for " + STRINGS + " strings: the Skia text path flushes per call instead of queueing");
        }
        if ((out.getRGB(0, 0) & 0xffffff) != 0xffffff) {
            throw new RuntimeException("the snapshot has no white background: " + Integer.toHexString(out.getRGB(0, 0)));
        }
    }

    static long counter(String name) throws Exception {
        try {
            Method m = Class.forName("sun.java2d.skia.SKEnvironment").getMethod(name);
            return ((Number) m.invoke(null)).longValue();
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("the backend has no counter " + name, e);
        }
    }
}
