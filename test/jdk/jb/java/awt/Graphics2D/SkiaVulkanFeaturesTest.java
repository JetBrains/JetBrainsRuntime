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

/*
 * @test
 * @key headful
 * @summary Regression test for JBR-10654: on Vulkan no device feature Graphite reads that Skia's
 *          VulkanPreferredFeatures queried and the adapter offers is dropped when the device is created;
 *          and when the device with those features cannot be created, the minimal device of the retry
 *          renders and reports only what it was created with.
 * @requires (os.family == "windows")
 * @library /test/lib
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.graphite.api=vulkan -Dsun.java2d.uiScale=1 SkiaVulkanFeaturesTest
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.pipelineCache=off -Dsun.java2d.skia.graphite.api=vulkan -Dsun.java2d.uiScale=1
 *                   -Dsun.java2d.skia.test.rejectVulkanPreferredDevice=true SkiaVulkanFeaturesTest minimal
 */

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.Set;

import jtreg.SkippedException;
import sun.java2d.skia.SKEnvironment;

/**
 * A Vulkan device has only the extensions and features enabled when it was created, not the
 * adapter's, and Graphite uses what it finds in the device's feature chain (dynamic state,
 * graphics pipeline libraries, host image copies among them). The device reports each feature
 * Graphite reads as {@code name=offered/granted} with 0 or 1 each
 * ({@link SKEnvironment#deviceFeatures}, VKSKPlatform.cpp), "offered" as the chain Skia's
 * VulkanPreferredFeatures queried holds it.
 * <p>
 * By default one offered but not granted fails: nothing queried was dropped by the enable step or
 * by the backend's edits. That is not "Graphite gets everything it reads": a feature the helper
 * does not query reports 0/0 and passes; the count of the features Graphite reads is checked with
 * every Skia bump instead (jb/project/tools/common/SKIA.md).
 * <p>
 * With {@code minimal} the first device creation is rejected
 * (sun.java2d.skia.test.rejectVulkanPreferredDevice), so the device is the retry's: the swap chain,
 * the device fault report and the core features. It must render, keep the core features and the
 * device fault report, and grant nothing else.
 */
public class SkiaVulkanFeaturesTest {

    /** The side of the image whose draw needs the device. */
    private static final int PROBE_SIZE = 16;
    /** The color channels of a pixel, without alpha. */
    private static final int RGB_MASK = 0xFFFFFF;

    /** A feature entry's flags: offered by the adapter / granted to the device. */
    private static final String FLAGS_PATTERN = "[01]/[01]";
    private static final String OFFERED_NOT_GRANTED = "1/0";
    private static final char GRANTED = '1';
    /** The index of the granted flag in an entry's flags. */
    private static final int GRANTED_INDEX = 2;

    /** The features of the minimal device: the core features and the device fault report. */
    private static final Set<String> MINIMAL_FEATURES = Set.of("dualSrcBlend", "occlusionQueryPrecise", "deviceFault");
    /** Core in Vulkan 1.3 without a feature flag: granted with the API version, whatever the chain holds. */
    private static final Set<String> CORE_IN_VULKAN_1_3 = Set.of("extendedDynamicState", "extendedDynamicState2");

    public static void main(String[] args) {
        boolean minimal = args.length > 0 && args[0].equals("minimal");
        PipelineComparison.requireRequestedAPI();
        if (!PipelineComparison.runsOn("Vulkan")) {
            throw new SkippedException("not on Vulkan (" + SKEnvironment.runningOn() + "): nothing to test");
        }
        BufferedImage probe = PipelineComparison.rendered(PipelineComparison.defaultConfiguration(), PROBE_SIZE,
                PROBE_SIZE, graphics -> {
                    graphics.setColor(Color.RED);
                    graphics.fillRect(0, 0, PROBE_SIZE, PROBE_SIZE);
                });
        int center = probe.getRGB(PROBE_SIZE / 2, PROBE_SIZE / 2);
        if ((center & RGB_MASK) != (Color.RED.getRGB() & RGB_MASK)) {
            throw new RuntimeException("the device does not render: " + Integer.toHexString(center));
        }

        String featureReport = SKEnvironment.deviceFeatures();
        System.out.println("device features (offered/granted): " + featureReport);
        if (featureReport.isEmpty()) {
            throw new RuntimeException("no device features reported on " + SKEnvironment.runningOn());
        }
        StringBuilder missing = new StringBuilder();
        StringBuilder grantedBeyondMinimal = new StringBuilder();
        boolean offeredBeyondMinimal = false;
        for (String entry : featureReport.split(" ")) {
            String[] nameAndFlags = entry.split("=");
            if (nameAndFlags.length != 2 || !nameAndFlags[1].matches(FLAGS_PATTERN)) {
                throw new RuntimeException("malformed entry " + entry);
            }
            String name = nameAndFlags[0];
            String flags = nameAndFlags[1];
            if (!minimal || MINIMAL_FEATURES.contains(name)) {
                if (flags.equals(OFFERED_NOT_GRANTED)) {
                    missing.append(' ').append(name);
                }
            } else if (!CORE_IN_VULKAN_1_3.contains(name)) {
                offeredBeyondMinimal |= flags.equals(OFFERED_NOT_GRANTED);
                if (flags.charAt(GRANTED_INDEX) == GRANTED) {
                    grantedBeyondMinimal.append(' ').append(name);
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new RuntimeException("the adapter offers, the device lacks:" + missing);
        }
        if (!grantedBeyondMinimal.isEmpty()) {
            throw new RuntimeException("the minimal device has more than the minimal set:" + grantedBeyondMinimal);
        }
        if (minimal && !offeredBeyondMinimal) {
            throw new SkippedException("the adapter offers nothing beyond the minimal set: "
                                       + "the minimal device is indistinguishable from the preferred one");
        }
        System.out.println("PASSED");
    }
}
