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

import sun.awt.Win32GraphicsConfig;
import sun.awt.Win32GraphicsDevice;
import sun.awt.image.SunVolatileImage;
import sun.awt.image.SurfaceManager;
import sun.awt.image.VolatileSurfaceManager;
import sun.awt.windows.WComponentPeer;
import sun.java2d.Disposer;
import sun.java2d.DisposerRecord;
import sun.java2d.SurfaceData;
import sun.java2d.pipe.BufferedContext;
import sun.java2d.pipe.hw.AccelGraphicsConfig;
import sun.java2d.pipe.hw.ContextCapabilities;
import sun.java2d.windows.GDIWindowSurfaceData;

import java.awt.Image;
import java.awt.ImageCapabilities;
import java.awt.image.ColorModel;
import java.awt.image.VolatileImage;
import java.lang.ref.Reference;

/**
 * Graphics configuration of the Skia backend on Windows. There is no GPU
 * pipeline to wrap: the Skia surfaces own their textures and the window
 * surfaces their swap chains (native side: {@code Win32SKPlatform.h},
 * Graphite on Dawn over Direct3D by default). Plugged into the Windows toolkit the way
 * {@code WGLGraphicsConfig} is; the window surfaces come from
 * {@link Win32SKScreenUpdateManager}.
 */
public final class Win32SKGraphicsConfig extends Win32GraphicsConfig
        implements SKGraphicsConfig, AccelGraphicsConfig, SurfaceManager.Factory {

    /** Win32GraphicsConfig's visual: the device's default GDI pixel format, as D3DGraphicsConfig passes it. */
    private static final int DEFAULT_VISUAL = 0;

    private static final ImageCapabilities imageCapabilities = new SKGraphicsConfig.SKImageCapabilities();

    private final long nativeConfigInfo;
    private final ContextCapabilities contextCapabilities;
    private final Object disposerReferent = new Object();
    private final SurfaceManager.ProxyCache surfaceDataProxyCache = new SurfaceManager.ProxyCache();

    /** Creates the native configuration on the shared GPU device (created on first use); 0 if none is usable. */
    private static native long getConfigInfo(int screen);
    private static native String getAdapterDescription(long nativeConfigInfo);
    /** Whether the GPU reported the device lost; nothing renders or presents on it any more. */
    private static native boolean isDeviceLost(long nativeConfigInfo);
    private static native void disposeConfigInfo(long nativeConfigInfo);

    @SuppressWarnings("deprecation")   // the constructor every Win32GraphicsConfig subclass uses
    private Win32SKGraphicsConfig(Win32GraphicsDevice device, long nativeConfigInfo) {
        super(device, DEFAULT_VISUAL);
        this.nativeConfigInfo = nativeConfigInfo;
        this.contextCapabilities = new SKGraphicsConfig.SKContextCapabilities(getAdapterDescription(nativeConfigInfo));
        Disposer.addRecord(disposerReferent, new Win32SKGraphicsConfigDisposerRecord(nativeConfigInfo));
    }

    /** The configuration for {@code device}, or null if Skia cannot run on it. */
    public static Win32SKGraphicsConfig getConfig(Win32GraphicsDevice device) {
        long nativeConfigInfo = getConfigInfo(device.getScreen());
        if (nativeConfigInfo == 0) {
            return null;
        }
        return new Win32SKGraphicsConfig(device, nativeConfigInfo);
    }

    private static final class Win32SKGraphicsConfigDisposerRecord implements DisposerRecord {
        private long nativeConfigInfo;

        Win32SKGraphicsConfigDisposerRecord(long nativeConfigInfo) {
            this.nativeConfigInfo = nativeConfigInfo;
        }

        @Override
        public void dispose() {
            if (nativeConfigInfo != 0) {
                // the Graphite context on the flusher thread first, then the device
                SKRenderQueue.disposeGraphicsConfig(nativeConfigInfo);
                disposeConfigInfo(nativeConfigInfo);
                nativeConfigInfo = 0;
            }
        }
    }

    /**
     * Whether the GPU device behind this configuration is lost (a driver
     * reset, an adapter removal). Its surfaces stop rendering and presenting;
     * windows fall back to GDI until the display change recreates the
     * configuration.
     */
    boolean isDeviceLost() {
        try {
            return isDeviceLost(nativeConfigInfo);
        } finally {
            // the native configuration is freed once this one is unreachable, and the native
            // side reads it without the render queue's lock
            Reference.reachabilityFence(disposerReferent);
        }
    }

    /**
     * For tests: every GPU device counts as lost, as after a driver reset (TDR); with
     * {@code persistent} also every device created afterwards, once its first window
     * surface exists, as on a driver that keeps failing. A real TDR needs an elevated
     * shell ({@code dxcap -forcetdr}).
     */
    public static void simulateDeviceLoss(boolean persistent) {
        simulateDeviceLossNative(persistent);
    }

    private static native void simulateDeviceLossNative(boolean persistent);

    // ---- SKGraphicsConfig --------------------------------------------------

    @Override
    public long getNativeConfigInfo() {
        return nativeConfigInfo;
    }

    @Override
    public SurfaceManager.ProxyCache getSurfaceDataProxyCache() {
        return surfaceDataProxyCache;
    }

    @Override
    @SuppressWarnings("ClassEscapesDefinedScope")   // SKGraphicsConfig is the package's; this class is public for Win32GraphicsDevice
    public SKSurfaceData createSKSurfaceData(int width, int height, ColorModel colorModel, Image image, int type) {
        return Win32SKSurfaceData.createData(this, width, height, colorModel, image, type);
    }

    // ---- AccelGraphicsConfig -----------------------------------------------

    @Override
    public BufferedContext getContext() {
        return SKContext.forConfig(this);
    }

    @Override
    public ContextCapabilities getContextCapabilities() {
        return contextCapabilities;
    }

    @Override
    public VolatileImage createCompatibleVolatileImage(int width, int height, int transparency, int type) {
        return createSKVolatileImage(width, height, transparency, type);
    }

    // ---- Win32GraphicsConfig -----------------------------------------------

    @Override
    public ColorModel getColorModel(int transparency) {
        return SKGraphicsConfig.colorModelFor(transparency);
    }

    /**
     * Window surfaces are created by {@link Win32SKScreenUpdateManager}, which
     * installs itself whenever Skia is enabled; a window the manager did not
     * take (flip chains, failures) stays on GDI.
     */
    @Override
    public SurfaceData createSurfaceData(WComponentPeer peer, int numBackBuffers) {
        return GDIWindowSurfaceData.createData(peer);
    }

    @Override
    public ImageCapabilities getImageCapabilities() {
        return imageCapabilities;
    }

    @Override
    public VolatileSurfaceManager createVolatileManager(SunVolatileImage image, Object context) {
        return new SKVolatileSurfaceManager(image, context);
    }

    @Override
    public String toString() {
        return "Win32SKGraphicsConfig[dev=" + getDevice() + "]";
    }
}
