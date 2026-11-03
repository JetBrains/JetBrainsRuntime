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
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;

import sun.java2d.skia.SKEnvironment;
import sun.java2d.skia.SKRenderQueue;

/**
 * @test
 * @summary Render-queue flushes that present nothing and wait for nothing continue one Graphite
 *          recording instead of ending one each: 64 flushes of off-screen drawing take a few
 *          recordings, not 64, and the image holds every flush's drawing when it is read back.
 *          When Skia is unavailable there is nothing to test.
 * @requires (os.family == "mac" | os.family == "windows")
 * @key headful
 * @modules java.desktop/sun.java2d.skia java.desktop/sun.java2d.pipe
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true SkiaHeldRecordingTest
 */
public class SkiaHeldRecordingTest {
    static final int FLUSHES = 64;

    public static void main(String[] args) {
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        if (!SKEnvironment.isSkiaEnabled()) {
            System.out.println("Skia is not available: nothing to test");
            return;
        }
        VolatileImage vi = gc.createCompatibleVolatileImage(FLUSHES, 16, Transparency.OPAQUE);
        for (int attempt = 0; attempt < 5; attempt++) {
            vi.validate(gc);
            Graphics2D g = vi.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, FLUSHES, 16);
            g.dispose();
            vi.getSnapshot();   // a sync: starts from an ended recording
            long flushes = SKEnvironment.flushes(), recordings = SKEnvironment.recordings();
            for (int i = 0; i < FLUSHES; i++) {
                g = vi.createGraphics();
                g.setColor(new Color(i * 4, 0, 255 - i * 4));
                g.fillRect(i, 0, 1, 16);
                g.dispose();
                flush();
            }
            flushes = SKEnvironment.flushes() - flushes;
            recordings = SKEnvironment.recordings() - recordings;
            BufferedImage out = vi.getSnapshot();
            if (vi.contentsLost()) {
                continue;
            }
            for (int i = 0; i < FLUSHES; i++) {
                int rgb = out.getRGB(i, 8) & 0xffffff, expected = new Color(i * 4, 0, 255 - i * 4).getRGB() & 0xffffff;
                if (rgb != expected) {
                    throw new RuntimeException("column " + i + ": " + Integer.toHexString(rgb) + ", expected " + Integer.toHexString(expected));
                }
            }
            System.out.println(flushes + " flushes, " + recordings + " recordings");
            if (flushes < FLUSHES) {
                throw new RuntimeException("only " + flushes + " flushes for " + FLUSHES + " flushNow calls");
            }
            if (recordings > FLUSHES / 4) {
                throw new RuntimeException(recordings + " recordings for " + flushes + " flushes that presented nothing: each flush ended its recording");
            }
            System.out.println("PASSED");
            return;
        }
        throw new RuntimeException("the image kept losing its contents");
    }

    static void flush() {
        SKRenderQueue rq = SKRenderQueue.getInstance();
        rq.lock();
        try {
            rq.flushNow();
        } finally {
            rq.unlock();
        }
    }
}
