/*
 * Copyright 2025 JetBrains s.r.o.
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

package com.jetbrains.desktop;

import com.jetbrains.exported.JBRApi;
import sun.java2d.vulkan.VKEnv;
import sun.java2d.vulkan.VKGPU;

@JBRApi.Service
@JBRApi.Provides("Vulkan")
public class Vulkan {

    Vulkan() {
        if (!VKEnv.isVulkanEnabled()) throw new JBRApi.ServiceNotAvailableException("Vulkan is not enabled");
    }

    boolean isPresentationEnabled() {
        return VKEnv.isPresentationEnabled();
    }

    Device[] getDevices() {
        return VKEnv.getDevices().map(Device::new).toArray(Device[]::new);
    }

    @JBRApi.Provides("Vulkan.Device")
    static class Device {
        private final VKGPU device;

        Device(VKGPU device) {
            this.device = device;
        }

        String getName() {
            return device.getName();
        }

        String getTypeString() {
            return device.getType().toString();
        }

        int getCapabilities() {
            return device.getCaps();
        }
    }
}
