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
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class VKEnv {

    private static final PlatformLogger log = PlatformLogger.getLogger("sun.java2d.vulkan.VKInstance");
    private static Boolean enabled;
    private static Boolean sdAccelerated;
    private static VKGPU[] devices;
    private static VKGPU defaultDevice;

    private static native VKGPU[] initNative(long nativePtr, int deviceNumber);

    @SuppressWarnings({"removal", "restricted"})
    public static void init(long nativePtr) {
        String vulkanOption = AccessController.doPrivileged(
                        (PrivilegedAction<String>) () -> System.getProperty("sun.java2d.vulkan", ""));
        if ("true".equalsIgnoreCase(vulkanOption)) {
            System.loadLibrary("awt");
            String sdOption = AccessController.doPrivileged(
                    (PrivilegedAction<String>) () -> System.getProperty("sun.java2d.vulkan.accelsd", ""));
            int deviceNumber = 0;
            String deviceNumberOption = AccessController.doPrivileged(
                    (PrivilegedAction<String>) () -> System.getProperty("sun.java2d.vulkan.deviceNumber"));
            if (deviceNumberOption != null) {
                try {
                    deviceNumber = Integer.parseInt(deviceNumberOption);
                } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                    log.warning("Invalid Vulkan device number:" + deviceNumberOption);
                }
            }
            devices = initNative(nativePtr, deviceNumber);
            enabled = devices != null;
            sdAccelerated = enabled && "true".equalsIgnoreCase(sdOption);
            if (enabled) {
                defaultDevice = devices[deviceNumber];
                defaultDevice.getNativeHandle(); // Force init default device.
            }
        } else enabled = false;

        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            if (enabled) {
                log.fine("Vulkan rendering enabled: YES" +
                         "\n  accelerated surface data enabled: " + (sdAccelerated ? "YES" : "NO") +
                         "\n  devices:" + Stream.of(devices).map(d -> (d == defaultDevice ?
                         "\n    *" : "\n     ") + d.getName()).collect(Collectors.joining()));
            } else log.fine("Vulkan rendering enabled: NO");
        }
    }

    public static boolean isVulkanEnabled() {
        if (enabled == null) throw new RuntimeException("Vulkan not initialized");
        return enabled;
    }

    public static boolean isSurfaceDataAccelerated() {
        if (enabled == null) throw new RuntimeException("Vulkan not initialized");
        return sdAccelerated;
    }

    public static Stream<VKGPU> getDevices() {
        if (enabled == null) throw new RuntimeException("Vulkan not initialized");
        final VKGPU first = defaultDevice;
        return Stream.concat(Stream.of(first), Stream.of(devices).filter(d -> d != first));
    }
}
