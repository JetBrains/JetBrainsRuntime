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

package sun.java2d.vulkan;

import sun.awt.image.SurfaceManager;

import java.awt.Transparency;
import java.lang.annotation.Native;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * VKDevice wrapper.
 */
public class VKGPU {

    @Native public static final int FORMAT_PRESENTABLE_BIT = 0x80000000; // Considered always supported.
    @Native public static final int SAMPLED_CAP_4BYTE_BIT = 0; // Considered always supported.
    @Native public static final int SAMPLED_CAP_3BYTE_BIT = 1;
    @Native public static final int SAMPLED_CAP_565_BIT   = 2;
    @Native public static final int SAMPLED_CAP_555_BIT   = 4;

    private boolean initialized;

    private final SurfaceManager.ProxyCache surfaceDataProxyCache = new SurfaceManager.ProxyCache();
    private final long nativeHandle;
    private final String name;
    private final Type type;
    private final int sampledCaps;
    private final List<VKGraphicsConfig> offscreenGraphicsConfigs, presentableGraphicsConfigs;

    private static native void init(long nativeHandle);
    private static native void reset(long nativeHandle);

    /**
     * Instantiated from native code, see createJavaDevices in VKInstance.c
     * Fresh devices are created in uninitialized state. They can be queried for their properties
     * but cannot be used for rendering until initialized via getNativeHandle().
     */
    private VKGPU(long nativeHandle, String name, int type, int sampledCaps, int[] supportedFormats) {
        this.nativeHandle = nativeHandle;
        this.name = name;
        this.type = Type.VALUES[type];
        this.sampledCaps = sampledCaps;
        offscreenGraphicsConfigs = new ArrayList<>();
        presentableGraphicsConfigs = new ArrayList<>();
        VKFormat[] allFormats = VKFormat.values();
        for (int supportedFormat : supportedFormats) {
            int formatValue = supportedFormat & ~FORMAT_PRESENTABLE_BIT;
            for (VKFormat format : allFormats) {
                if (formatValue == format.getValue(Transparency.TRANSLUCENT)) {
                    VKOffscreenGraphicsConfig gc = new VKOffscreenGraphicsConfig(this, format);
                    offscreenGraphicsConfigs.add(gc);
                    if ((supportedFormat & FORMAT_PRESENTABLE_BIT) != 0) presentableGraphicsConfigs.add(gc);
                    break;
                }
            }
        }
    }

    public SurfaceManager.ProxyCache getSurfaceDataProxyCache() { return surfaceDataProxyCache; }
    public String getName() { return name; }
    public Type getType() { return type; }

    public Stream<VKGraphicsConfig> getOffscreenGraphicsConfigs() {
        return offscreenGraphicsConfigs.stream();
    }

    public Stream<VKGraphicsConfig> getPresentableGraphicsConfigs() {
        return presentableGraphicsConfigs.stream();
    }

    public int getSampledCaps() {
        return sampledCaps;
    }

    /**
     * Initialize the device and return its native handle.
     */
    public synchronized long getNativeHandle() {
        if (!initialized) {
            try {
                init(nativeHandle);
            } catch (RuntimeException e) {
                throw new RuntimeException("Failed to initialize Vulkan device: " + name, e);
            }
            initialized = true;
        }
        return nativeHandle;
    }

    @Override
    public String toString() {
        return name + " (" + type + ")";
    }

    public enum Type {
        OTHER,
        INTEGRATED_GPU,
        DISCRETE_GPU,
        VIRTUAL_GPU,
        CPU;
        private static final Type[] VALUES = values();
    }

}
