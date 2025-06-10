/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
 * Copyright 2023 JetBrains s.r.o.
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

package sun.java2d.wl;

import java.awt.Color;
import java.awt.Component;
import java.awt.Window;
import java.awt.GraphicsConfiguration;
import java.awt.Rectangle;
import java.awt.image.Raster;
import java.util.Objects;

import sun.awt.AWTAccessor;
import sun.awt.wl.WLComponentPeer;
import sun.awt.wl.WLGraphicsConfig;
import sun.awt.wl.WLSMGraphicsConfig;
import sun.java2d.SurfaceData;
import sun.java2d.loops.SurfaceType;
import sun.util.logging.PlatformLogger;

/**
 * SurfaceData for shared memory buffers.
 */
public class WLSMSurfaceData extends SurfaceData implements WLSurfaceDataExt, WLPixelGrabberExt {
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.java2d.wl.WLSMSurfaceData");
    private final Component target; // optional
    private final WLSurfaceSizeListener sizeListener;
    private WLSMGraphicsConfig gc;
    private int width; // in pixels
    private int height; // in pixels

    public native void assignSurface(long surfacePtr);

    private native void initOps(int width, int height, int backgroundRGB, int wlShmFormat, boolean perfCountersEnabled);
    private static native void initIDs();

    static {
        initIDs();
    }

    private WLSMSurfaceData(Component target, WLSurfaceSizeListener sl, WLSMGraphicsConfig gc,
                            Color background, int width, int height, int wlShmFormat, boolean perfCountersEnabled) {
        super(gc.getSurfaceType(), gc.getColorModel());
        this.target = target;
        this.sizeListener = sl;
        this.width = width;
        this.height = height;
        this.gc = gc;

        Objects.requireNonNull(sl);
        Objects.requireNonNull(gc);

        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid surface data size: " + width + "x" + height);
        }

        int backgroundRGB = background == null ? 0 : background.getRGB();
        int backgroundPixel = getSurfaceType().pixelFor(backgroundRGB, getColorModel());

        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine(String.format("Shared memory surface data %dx%d, format %d", width, height, wlShmFormat));
        }
        initOps(width, height, backgroundPixel, wlShmFormat, perfCountersEnabled);
    }

    /**
     * Method for instantiating a Window SurfaceData
     */
    public static WLSMSurfaceData createData(WLComponentPeer peer, WLSMGraphicsConfig graphicsConfig) {
        Objects.requireNonNull(peer);
        Objects.requireNonNull(graphicsConfig);

        Window target = peer.getTarget() instanceof Window ? (Window) peer.getTarget() : null;
        boolean perfCountersEnabled = target instanceof Window window && AWTAccessor.getWindowAccessor().countersEnabled(window);
        return new WLSMSurfaceData(target, peer, graphicsConfig,
                peer.getBackground(), peer.getBufferWidth(), peer.getBufferHeight(),
                graphicsConfig.getWlShmFormat(), perfCountersEnabled);
    }

    public static WLSMSurfaceData createData(WLSurfaceSizeListener sizeListener, int width, int height, WLSMGraphicsConfig graphicsConfig) {
        Objects.requireNonNull(graphicsConfig);

        return new WLSMSurfaceData(null, sizeListener, graphicsConfig,
                null, width, height,
                graphicsConfig.getWlShmFormat(), false);
    }

    @Override
    public SurfaceData getReplacement() {
        // It does not seem possible in Wayland to get your window's surface
        // change mid-flight such that it needs a replacement.
        // The only scenario when a surface gets forcibly invalidated is
        // after WLComponentPeer.dispose(), at which point the surface
        // is no longer needed anyway.
        return null;
    }

    @Override
    public GraphicsConfiguration getDeviceConfiguration() {
        return gc;
    }

    @Override
    public Raster getRaster(int x, int y, int w, int h) {
        // Can do something like the following:
        // Raster r = getColorModel().createCompatibleWritableRaster(w, h);
        // copy surface data to this raster
        // save a reference to this raster
        // return r;
        // then in flush() check if raster was modified and take pixels from there
        // This is obviously suboptimal and shouldn't be used in performance-critical situations.
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public Rectangle getBounds() {
        return new Rectangle(width, height);
    }

    @Override
    public Object getDestination() {
        // NB: optional; could be null
        return target;
    }

    public void revalidate(GraphicsConfiguration gc, int width, int height, int scale) {
        Objects.requireNonNull(gc);

        WLSMGraphicsConfig wlgc = (WLSMGraphicsConfig) gc;
        this.gc = wlgc;
        this.width = width;
        this.height = height;

        nativeRevalidate(width, height, scale);
    }

    @Override
    public native void commit();

    public int getRGBPixelAt(int x, int y) {
        int pixel = pixelAt(x, y);
        return getSurfaceType().rgbFor(pixel, getColorModel());
    }

    public int [] getRGBPixelsAt(Rectangle bounds) {
        int [] pixels = pixelsAt(bounds.x, bounds.y, bounds.width, bounds.height);
        var surfaceType = getSurfaceType();
        if (surfaceType.equals(SurfaceType.IntArgbPre) ||  surfaceType.equals(SurfaceType.IntRgb)) {
            // No conversion is necessary, can return raw pixels
        } else {
            var cm = getColorModel();
            for (int i = 0; i < pixels.length; i++) {
                pixels[i] = surfaceType.rgbFor(pixels[i], cm);
            }
        }
        return pixels;
    }

    private void bufferAttached() {
        // Called from the native code when a buffer has just been attached to this surface,
        // but the surface has not been committed yet.
        sizeListener.updateSurfaceSize();
    }

    private void countNewFrame() {
        // Called from the native code when this surface data has been sent to the Wayland server
        if (target instanceof Window window) {
            AWTAccessor.getWindowAccessor().bumpCounter(window, "java2d.native.frames");
        }
    }

    private void countDroppedFrame() {
        // Called from the native code when an attempt was made to send this surface data to
        // the Wayland server, but that attempt was not successful. This can happen, for example,
        // when those attempts are too frequent.
        if (target instanceof Window window) {
            AWTAccessor.getWindowAccessor().bumpCounter(window, "java2d.native.framesDropped");
        }
    }

    private native void nativeRevalidate(int width, int height, int scale);
    private native int pixelAt(int x, int y);
    private native int [] pixelsAt(int x, int y, int width, int height);
}
