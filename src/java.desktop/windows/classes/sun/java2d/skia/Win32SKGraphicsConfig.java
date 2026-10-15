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

/**
 * Graphics configuration of the Skia backend on Windows. Unlike on macOS,
 * where Skia renders into the Metal pipeline's surfaces, there is no GPU
 * pipeline to wrap here: the Skia surfaces own their textures and the window
 * surfaces their swap chains (native side: {@code DawnSKPlatform.cpp},
 * Graphite on Dawn over Direct3D). Plugged into the Windows toolkit the way
 * {@code WGLGraphicsConfig} is; the window surfaces come from
 * {@link Win32SKScreenUpdateManager}.
 */
public final class Win32SKGraphicsConfig extends Win32GraphicsConfig
        implements SKGraphicsConfig, AccelGraphicsConfig, SurfaceManager.Factory {

    private static final ImageCapabilities imageCaps = new SKGraphicsConfig.ImageCaps();

    private final long pConfigInfo;
    private final ContextCapabilities contextCaps;
    private final Object disposerReferent = new Object();
    private final SurfaceManager.ProxyCache surfaceDataProxyCache = new SurfaceManager.ProxyCache();

    /** Creates the native configuration on the shared GPU device (created on first use); 0 if none is usable. */
    private static native long getConfigInfo(int screen);
    private static native String getAdapterDescription(long pConfigInfo);
    /** Whether the GPU reported the device lost; nothing renders or presents on it any more. */
    private static native boolean isDeviceLost(long pConfigInfo);
    private static native void disposeConfigInfo(long pConfigInfo);

    @SuppressWarnings("deprecation")
    private Win32SKGraphicsConfig(Win32GraphicsDevice device, long pConfigInfo) {
        super(device, 0);
        this.pConfigInfo = pConfigInfo;
        this.contextCaps = new SKGraphicsConfig.ContextCaps(getAdapterDescription(pConfigInfo));
        Disposer.addRecord(disposerReferent, new Win32SKGCDisposerRecord(pConfigInfo));
    }

    /** The configuration for {@code device}, or null if Skia cannot run on it. */
    public static Win32SKGraphicsConfig getConfig(Win32GraphicsDevice device) {
        long pConfigInfo = getConfigInfo(device.getScreen());
        if (pConfigInfo == 0) {
            return null;
        }
        return new Win32SKGraphicsConfig(device, pConfigInfo);
    }

    private static final class Win32SKGCDisposerRecord implements DisposerRecord {
        private long pCfgInfo;

        Win32SKGCDisposerRecord(long pCfgInfo) {
            this.pCfgInfo = pCfgInfo;
        }

        @Override
        public void dispose() {
            if (pCfgInfo != 0) {
                // the Graphite context on the flusher thread first, then the device
                SKRenderQueue.disposeGraphicsConfig(pCfgInfo);
                disposeConfigInfo(pCfgInfo);
                pCfgInfo = 0;
            }
        }
    }

    /**
     * Whether the GPU device behind this configuration is lost (a driver
     * reset, an adapter removal). Its surfaces stop rendering and presenting;
     * windows fall back to GDI until the display change recreates the
     * configuration.
     */
    public boolean isDeviceLost() {
        return isDeviceLost(pConfigInfo);
    }

    // ---- SKGraphicsConfig --------------------------------------------------

    @Override
    public long getNativeConfigInfo() {
        return pConfigInfo;
    }

    @Override
    public SurfaceManager.ProxyCache getSurfaceDataProxyCache() {
        return surfaceDataProxyCache;
    }

    @Override
    public SKSurfaceData createSKSurfaceData(int width, int height, ColorModel cm, Image image, int type) {
        return Win32SKSurfaceData.createData(this, width, height, cm, image, type);
    }

    // ---- AccelGraphicsConfig -----------------------------------------------

    @Override
    public BufferedContext getContext() {
        return SKContext.forConfig(this);
    }

    @Override
    public ContextCapabilities getContextCapabilities() {
        return contextCaps;
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
        return imageCaps;
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
