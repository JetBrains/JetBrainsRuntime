/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2024, JetBrains s.r.o.. All rights reserved.
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

package sun.java2d.vulkan;

import sun.util.logging.PlatformLogger;

import java.security.AccessController;
import java.security.PrivilegedAction;

public class VKInstance {

    private static final PlatformLogger log = PlatformLogger.getLogger("sun.java2d.vulkan.VKInstance");
    private static Boolean initialized;
    private static Boolean sdAccelerated;

    private static native boolean initNative(long nativePtr, boolean verbose, int deviceNumber);

    @SuppressWarnings({"removal", "restricted"})
    public static void init(long nativePtr) {
        String vulkanOption = AccessController.doPrivileged(
                        (PrivilegedAction<String>) () -> System.getProperty("sun.java2d.vulkan", ""));
        if ("true".equalsIgnoreCase(vulkanOption)) {
            String deviceNumberOption = AccessController.doPrivileged(
                    (PrivilegedAction<String>) () -> System.getProperty("sun.java2d.vulkan.deviceNumber", "0"));
            int parsedDeviceNumber = 0;
            try {
                parsedDeviceNumber = Integer.parseInt(deviceNumberOption);
            } catch (NumberFormatException e) {
                log.warning("Invalid Vulkan device number:" + deviceNumberOption);
            }
            final int deviceNumber = parsedDeviceNumber;
            final boolean verbose = "True".equals(vulkanOption);
            System.loadLibrary("awt");
            String sdOption = AccessController.doPrivileged(
                    (PrivilegedAction<String>) () -> System.getProperty("sun.java2d.vulkan.accelsd", ""));
            initialized = initNative(nativePtr, verbose, deviceNumber);
            sdAccelerated = initialized && "true".equalsIgnoreCase(sdOption);
        } else initialized = false;

        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Vulkan rendering enabled: " + (initialized ? "YES" : "NO"));
            log.fine("Vulkan accelerated surface data enabled: " + (sdAccelerated ? "YES" : "NO"));
        }
    }

    public static boolean isVulkanEnabled() {
        if (initialized == null) throw new RuntimeException("Vulkan not initialized");
        return initialized;
    }

    public static boolean isSurfaceDataAccelerated() {
        if (initialized == null) throw new RuntimeException("Vulkan not initialized");
        return sdAccelerated;
    }
}
