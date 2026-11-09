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

import java.nio.file.Path;
import sun.awt.util.ThreadGroupUtils;

/**
 * The pipeline cache ({@code sun.java2d.skia.pipelineCache=<directory>|off}): where Dawn's shaders
 * and pipelines and Graphite's Vulkan pipeline data persist between runs. This side names the
 * directory, offers Graphite's data once more at exit and reads the counters; the native side
 * (SKPipelineCache.h) keeps the files.
 */
public final class SKPipelineCache {

    /** What {@link #counters} reports: lookups, lookups that found data, stores (SKPipelineCache.cpp). */
    private static final int COUNTERS = 3;

    private static String directory;   // null when off

    private SKPipelineCache() {}

    /**
     * Resolves the cache's directory and hands it to the native side; before the start, since an
     * API creates its device as it starts, and the device takes the pipeline cache then.
     */
    static void configure() {
        directory = resolveDirectory();
        setDirectoryNative(directory);
    }

    /** The cache's directory, or null when it is off. */
    static String directory() {
        return directory;
    }

    /**
     * The pipeline cache's counters, Dawn's blobs and Graphite's Vulkan pipeline data together:
     * lookups, lookups that found data, and stores (SKPipelineCache.cpp).
     */
    @SuppressWarnings("unused")
    public static long[] counters() {
        return SKEnvironment.isSkiaEnabled() ? countersNative() : new long[COUNTERS];
    }

    private static native long[] countersNative();

    /**
     * Windows' per-user cache location, %LOCALAPPDATA%/JetBrains/JBR/SkiaPipelineCache/&lt;Skia release&gt;
     * (~/.java/skia/&lt;release&gt; where LOCALAPPDATA is unset or empty): keyed by the pinned Skia, whose and Dawn's formats the
     * files hold, so a runtime update with the same Skia keeps them and directories add up only per Skia bump;
     * inside, Dawn's keys and Vulkan's pipeline cache header tell drivers and adapters apart, and a file over
     * its bound starts over (SKPipelineCache.cpp).
     */
    private static Path defaultDirectory() {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path root = localAppData != null && !localAppData.isEmpty()
                ? Path.of(localAppData, "JetBrains", "JBR", "SkiaPipelineCache")
                : Path.of(System.getProperty("user.home"), ".java", "skia");
        return root.resolve(skiaReleaseNative());
    }

    /**
     * The pipeline cache's directory, or null when it is off. Not created here: the native
     * side creates it when it stores the first pipeline (SKPipelineCache.cpp), so that nothing
     * is left in the user's profile when the backend does not start, and a directory that
     * cannot be created leaves the cache without files.
     */
    private static String resolveDirectory() {
        String requested = System.getProperty("sun.java2d.skia.pipelineCache");
        if ("off".equalsIgnoreCase(requested)) {
            return null;
        }
        try {
            Path resolved = requested != null && !requested.isEmpty() ? Path.of(requested) : defaultDirectory();
            return resolved.toAbsolutePath().toString();
        } catch (RuntimeException exception) {
            // a path Windows does not accept (sun.java2d.skia.pipelineCache, LOCALAPPDATA, user.home)
            SKEnvironment.warn("Skia pipeline cache off: " + exception.getMessage());
            return null;
        }
    }

    /** The Skia release libawt_skia was built against: the pin's SKIA_TAG (skia_prebuilt.conf). */
    private static native String skiaReleaseNative();

    private static native void setDirectoryNative(String directory);

    /**
     * Once the backend started with a cache: at exit, Graphite's Vulkan pipeline data compiled since
     * its last store goes to the cache (the native providers, SKPipelineDataProvider): a session offers it at
     * most every 10 s and its configurations are not disposed at exit, so without this a session that
     * exits soon after compiling loses what it compiled. The hook takes the native entry lock, which
     * keeps it out of a flush, and not the render queue's lock (the AWT lock), which the thread calling
     * System.exit may hold.
     */
    static void addShutdownHook() {
        if (directory == null) {
            return;
        }
        Thread hook = new Thread(ThreadGroupUtils.getRootThreadGroup(), SKPipelineCache::syncNative,
                                 "Skia Pipeline Cache", 0, false);
        hook.setContextClassLoader(null);
        try {
            Runtime.getRuntime().addShutdownHook(hook);
        } catch (IllegalStateException exception) {
            // started during shutdown: nothing more to store
        }
    }

    private static native void syncNative();
}
