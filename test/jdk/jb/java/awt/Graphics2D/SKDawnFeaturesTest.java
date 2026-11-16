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
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

import java.awt.Color;

import jtreg.SkippedException;
import sun.java2d.skia.SKEnvironment;

/*
 * @test
 * @key headful
 * @summary Regression test for JBR-10654: Graphite on Dawn gets every device feature of its
 *          preferred set that the adapter offers.
 * @requires os.family == "windows"
 * @library /test/lib
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.uiScale=1 SKDawnFeaturesTest
 */

/*
 * A WebGPU device has only the features requested when it was created, not the adapter's, and
 * Graphite enables what it finds on the device (dual-source blending among them, without which it
 * blends coverage for Src or Clear in the shader from a copy of the destination). The device
 * reports each feature as "name=offered/granted" with 0 or 1 each
 * (SKEnvironment.deviceFeatures, DawnSKPlatform.cpp); one offered but not granted fails.
 */

/** The side of the image whose first draw creates the device (with its first context). */
private static final int PROBE_SIZE = 16;

// a feature entry's flags: offered by the adapter / granted to the device
private static final String FLAGS_PATTERN = "[01]/[01]";
private static final String OFFERED_NOT_GRANTED = "1/0";

void main() {
    PipelineComparison.requireRequestedAPI();
    if (!PipelineComparison.runsOn("Dawn")) {
        throw new SkippedException("not on Dawn (" + SKEnvironment.runningOn() + "): nothing to test");
    }
    // the device is created with the first context
    PipelineComparison.rendered(PipelineComparison.defaultConfiguration(), PROBE_SIZE, PROBE_SIZE, graphics -> {
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, PROBE_SIZE, PROBE_SIZE);
    });

    String featureReport = SKEnvironment.deviceFeatures();
    System.out.println("device features (offered/granted): " + featureReport);
    if (featureReport.isEmpty()) {
        throw new RuntimeException("no device features reported on " + SKEnvironment.runningOn());
    }
    StringBuilder missing = new StringBuilder();
    for (String entry : featureReport.split(" ")) {
        String[] nameAndFlags = entry.split("=");
        if (nameAndFlags.length != 2 || !nameAndFlags[1].matches(FLAGS_PATTERN)) {
            throw new RuntimeException("malformed entry " + entry);
        }
        if (nameAndFlags[1].equals(OFFERED_NOT_GRANTED)) {
            missing.append(' ').append(nameAndFlags[0]);
        }
    }
    if (!missing.isEmpty()) {
        throw new RuntimeException("the adapter offers, the device lacks:" + missing);
    }
    System.out.println("PASSED");
}
