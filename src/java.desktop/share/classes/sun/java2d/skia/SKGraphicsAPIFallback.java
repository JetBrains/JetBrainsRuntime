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


package sun.java2d.skia;

import java.util.List;

import static sun.java2d.skia.SKEnvironment.report;

/**
 * The fallback between the graphics APIs Graphite renders through: the backend starts on the first
 * usable one (Dawn, then Vulkan; {@code sun.java2d.skia.graphite.api=vulkan}: the other way round),
 * and on none when no API is usable. An API is usable when it has an adapter, the GPU list
 * ({@link SKGPUList}, {@code sun.java2d.skia.gpuBlocklist}) does not block it for that adapter, and
 * its device starts; {@code verbose} names why an API was skipped.
 */
final class SKGraphicsAPIFallback {

    private SKGraphicsAPIFallback() {}

    /**
     * Starts Graphite on the first usable graphics API: Dawn, then Vulkan ({@code graphite.api=vulkan}:
     * the other way round). An API is usable when it has an adapter, the GPU list does not block
     * it for that adapter, and its device starts; otherwise the next one is tried, and when none
     * is usable the backend is off (the platform's default pipeline renders, as without
     * {@code sun.java2d.skia}).
     *
     * @param gpuList      the GPU list the APIs are checked against ({@link SKGPUList#load})
     * @param preferredAPI {@code vulkan} to try Vulkan first, anything else (or {@code null}) for Dawn
     *                     first ({@code sun.java2d.skia.graphite.api}, as SKEnvironment reads it)
     * @return what the backend runs on ({@link SKEnvironment#runningOn}), or {@code null}
     */
    static String startFirstUsableAPI(SKGPUList gpuList, String preferredAPI) {
        List<SKGPUList.GraphiteAPI> order = "vulkan".equals(preferredAPI)
                ? List.of(SKGPUList.GraphiteAPI.VULKAN, SKGPUList.GraphiteAPI.DAWN)
                : List.of(SKGPUList.GraphiteAPI.DAWN, SKGPUList.GraphiteAPI.VULKAN);
        for (SKGPUList.GraphiteAPI api : order) {
            String adapterLine = adapterNative(api.ordinal());
            if (adapterLine == null) {
                report("Skia on " + api.displayName() + ": no adapter");
                continue;
            }
            SKGPUList.Adapter adapter;
            try {
                adapter = SKGPUList.Adapter.parse(adapterLine);
            } catch (IllegalArgumentException exception) {
                report("Skia on " + api.displayName() + ": cannot read the adapter description '" + adapterLine
                       + "': " + exception.getMessage());
                continue;
            }
            SKGPUList.Entry blockingEntry = gpuList.blocking(api, adapter);
            if (blockingEntry != null) {
                report("Skia on " + api.displayName() + " not used on " + adapter + ": GPU list entry "
                       + blockingEntry + ", " + gpuList.source());
                continue;
            }
            String startedOn = startNative(api.ordinal());
            if (startedOn != null) {
                return startedOn;
            }
            report("Skia on " + api.displayName() + ": the device did not start on " + adapter);
        }
        return null;
    }

    /**
     * The adapter the API would use, for the GPU list ({@link SKGPUList.Adapter#parse}); {@code null} if none.
     *
     * @param api {@link SKGPUList.GraphiteAPI}'s ordinal
     */
    private static native String adapterNative(int api);

    /**
     * Starts the API; what the backend runs on then ({@link SKEnvironment#runningOn}), or {@code null} if its
     * device does not start.
     *
     * @param api {@link SKGPUList.GraphiteAPI}'s ordinal
     */
    private static native String startNative(int api);
}
