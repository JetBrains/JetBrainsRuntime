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
import java.awt.image.VolatileImage;

import sun.java2d.skia.SKEnvironment;

/**
 * @test
 * @summary Graphite on Dawn gets the device features it can use: a WebGPU device has only the
 *          features requested when it was created, not the adapter's, and Graphite enables what
 *          it finds on the device (dual-source blending among them, without which it blends
 *          coverage for Src or Clear in the shader from a copy of the destination). Every feature
 *          of the preferred set the adapter offers must be on the device, on D3D11 and on D3D12.
 *          When Skia is unavailable there is nothing to test.
 * @key headful
 * @requires (os.family == "windows")
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.uiScale=1 SkiaDawnFeaturesTest
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.dawn.backend=d3d12 -Dsun.java2d.uiScale=1 SkiaDawnFeaturesTest
 */
public class SkiaDawnFeaturesTest {
    public static void main(String[] args) {
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        if (!SKEnvironment.isSkiaEnabled()) {
            System.out.println("Skia is not available: nothing to test");
            return;
        }
        if (!SKEnvironment.getDescription().contains("Graphite/Dawn")) {
            System.out.println("not on Dawn (" + SKEnvironment.getDescription() + "): nothing to test");
            return;
        }
        // the device is created with the first context
        VolatileImage vi = gc.createCompatibleVolatileImage(16, 16, Transparency.OPAQUE);
        Graphics2D g = vi.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 16, 16);
        g.dispose();
        vi.getSnapshot();

        String report = SKEnvironment.deviceFeatures();
        System.out.println("device features (offered/granted): " + report);
        if (report.isEmpty()) {
            throw new RuntimeException("no device features reported on " + SKEnvironment.getDescription());
        }
        StringBuilder missing = new StringBuilder();
        for (String entry : report.split(" ")) {
            String[] nameFlags = entry.split("=");
            if (nameFlags.length != 2 || !nameFlags[1].matches("[01]/[01]")) {
                throw new RuntimeException("malformed entry " + entry);
            }
            if (nameFlags[1].equals("1/0")) {
                missing.append(' ').append(nameFlags[0]);
            }
        }
        if (missing.length() > 0) {
            throw new RuntimeException("the adapter offers, the device lacks:" + missing);
        }
        System.out.println("PASSED");
    }
}
