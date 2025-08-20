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

import java.awt.Toolkit;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.stream.Stream;

public final class VKEnv {

    private static final PlatformLogger log = PlatformLogger.getLogger("sun.java2d.vulkan.VKEnv");

    private static final class Options {

        @SuppressWarnings("removal")
        private static final String vulkanProperty = AccessController.doPrivileged(
                (PrivilegedAction<String>) () -> System.getProperty("sun.java2d.vulkan", ""));

        private static final boolean vulkan = "true".equalsIgnoreCase(vulkanProperty);
        private static final boolean verbose = "True".equals(vulkanProperty);

        @SuppressWarnings("removal")
        private static final boolean accelsd = vulkan && "true".equalsIgnoreCase(AccessController.doPrivileged(
                (PrivilegedAction<String>) () -> System.getProperty("sun.java2d.vulkan.accelsd", "")));

        @SuppressWarnings("removal")
        private static final int deviceNumber = !vulkan ? 0 : AccessController.doPrivileged(
                (PrivilegedAction<Integer>) () -> Integer.getInteger("sun.java2d.vulkan.deviceNumber", 0));
    }

    private static final int UNINITIALIZED = 0;
    private static final int INITIALIZING  = 1;
    private static final int DISABLED      = 2;
    private static final int ENABLED       = 3;
    private static final int ACCELSD_BIT   = 4;
    private static final int PRESENT_BIT   = 8;

    private static int state = UNINITIALIZED;
    private static VKGPU[] devices;
    private static VKGPU defaultDevice;

    private static native long initPlatform(long nativePtr);
    private static native VKGPU[] initNative(long platformData);

    public static synchronized void init(long nativePtr) {
        if (state > INITIALIZING) return;
        int newState = DISABLED;
        if (Options.vulkan) {
            try {
                long platformData = nativePtr == 0 ? 0 : initPlatform(nativePtr);
                devices = initNative(platformData);
                if (devices != null) {
                    newState = ENABLED;
                    if (Options.accelsd) newState |= ACCELSD_BIT;
                    defaultDevice = devices[Options.deviceNumber >= 0 && Options.deviceNumber < devices.length ?
                            Options.deviceNumber : 0];
                    // Check whether the presentation is supported.
                    for (VKGPU device : devices) {
                        if (device.hasCap(VKGPU.CAP_PRESENTABLE_BIT) &&
                                device.getPresentableGraphicsConfigs().findAny().isPresent()) {
                            newState |= PRESENT_BIT;
                            break;
                        }
                    }

                    VKBlitLoops.register();
                    VKMaskFill.register();
                    VKMaskBlit.register();
                }
            } catch (UnsatisfiedLinkError e) {
                newState = DISABLED;
                if (Options.verbose) {
                    System.err.println("Vulkan backend is not available");
                    e.printStackTrace(System.err);
                }
                if (log.isLoggable(PlatformLogger.Level.WARNING)) {
                    log.warning("Vulkan backend is not available", e);
                }
            }
        }
        state = newState;

        if (Options.verbose || log.isLoggable(PlatformLogger.Level.FINE)) {
            StringBuilder msg = new StringBuilder("Vulkan rendering enabled: ");
            if (isVulkanEnabled()) {
                msg.append("YES")
                        .append("\n  Presentation enabled: ").append(isPresentationEnabled() ? "YES" : "NO")
                        .append("\n  Accelerated surface data enabled: ").append(isSurfaceDataAccelerated() ? "YES" : "NO")
                        .append("\n  Devices:");
                for (int i = 0; i < devices.length; i++) {
                    VKGPU d = devices[i];
                    msg.append(d == defaultDevice ? "\n    *" : "\n     ").append(i).append(": ").append(d.getName());
                }
            } else {
                msg.append("NO");
            }
            String message = msg.toString();
            if (Options.verbose) {
                System.err.println(message);
            }
            if (log.isLoggable(PlatformLogger.Level.FINE)) {
                log.fine(message);
            }
        }
    }

    private static void checkInit() {
        if (state > INITIALIZING) return;
        synchronized (VKEnv.class) {
            if (state == UNINITIALIZED) {
                // Try initializing the Toolkit first to give it a chance to init Vulkan with proper platform data.
                state = INITIALIZING;
                Toolkit.getDefaultToolkit();
            }
            // Still not initialized? Init without platform data.
            if (state == INITIALIZING) init(0);
        }
    }

    public static boolean isVulkanEnabled() {
        checkInit();
        return (state & ENABLED) == ENABLED;
    }

    public static boolean isPresentationEnabled() {
        checkInit();
        return (state & PRESENT_BIT) != 0;
    }

    public static boolean isSurfaceDataAccelerated() {
        checkInit();
        return (state & ACCELSD_BIT) != 0;
    }

    public static Stream<VKGPU> getDevices() {
        checkInit();
        final VKGPU first = defaultDevice;
        return Stream.concat(Stream.of(first), Stream.of(devices).filter(d -> d != first));
    }
}
