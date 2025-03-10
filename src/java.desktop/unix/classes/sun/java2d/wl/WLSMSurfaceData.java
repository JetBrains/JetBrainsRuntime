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

import java.awt.Component;
import java.awt.Window;
import java.awt.GraphicsConfiguration;
import java.awt.Rectangle;
import java.awt.image.ColorModel;
import java.awt.image.Raster;

import sun.awt.AWTAccessor;
import sun.awt.wl.WLComponentPeer;
import sun.awt.wl.WLSMGraphicsConfig;
import sun.java2d.SurfaceData;
import sun.java2d.loops.SurfaceType;
import sun.util.logging.PlatformLogger;

/**
 * SurfaceData for shared memory buffers.
 */
public class WLSMSurfaceData extends SurfaceData implements WLSurfaceDataExt, WLPixelGrabberExt {
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.java2d.wl.WLSMSurfaceData");
    private final WLComponentPeer peer;

    public native void assignSurface(long surfacePtr);

    private native void initOps(int width, int height, int backgroundRGB, int wlShmFormat, boolean perfCountersEnabled);
    private static native void initIDs();

    static {
        initIDs();
    }

    private WLSMSurfaceData(WLComponentPeer peer, SurfaceType surfaceType, ColorModel colorModel, int wlShmFormat, boolean perfCountersEnabled) {
        super(surfaceType, colorModel);
        this.peer = peer;

        int backgroundRGB = peer.getBackground() != null
                ? peer.getBackground().getRGB()
                : 0;
        int backgroundPixel = surfaceType.pixelFor(backgroundRGB, colorModel);
        int width = peer.getBufferWidth();
        int height = peer.getBufferHeight();

        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine(String.format("Shared memory surface data %dx%d, format %d", width, height, wlShmFormat));
        }
        initOps(width, height, backgroundPixel, wlShmFormat, perfCountersEnabled);
    }

    /**
     * Method for instantiating a Window SurfaceData
     */
    public static WLSMSurfaceData createData(WLComponentPeer peer, WLSMGraphicsConfig graphicsConfig) {
        if (peer == null) {
            throw new UnsupportedOperationException("Surface without the corresponding window peer is not supported");
        }
        ColorModel cm = graphicsConfig.getColorModel();
        SurfaceType surfaceType = graphicsConfig.getSurfaceType();
        Window target = peer.getTarget() instanceof Window ? (Window)peer.getTarget() : null;
        boolean perfCountersEnabled = target != null && AWTAccessor.getWindowAccessor().countersEnabled(target);
        return new WLSMSurfaceData(peer, surfaceType, cm, graphicsConfig.getWlShmFormat(), perfCountersEnabled);
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
        return peer.getGraphicsConfiguration();
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
        Rectangle r = peer.getBufferBounds();
        r.x = r.y = 0;
        return r;
    }

    @Override
    public Object getDestination() {
        return peer.getTarget();
    }

    public native void revalidate(GraphicsConfiguration gc, int width, int height, int scale);

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
        // Called from the native code when a buffer has just been attached to this surface
        // but the surface has not been committed yet.
        peer.updateSurfaceSize();
    }

    private void countNewFrame() {
        // Called from the native code when this surface data has been sent to the Wayland server
        Component target = peer.getTarget();
        if (target instanceof Window window) {
            AWTAccessor.getWindowAccessor().bumpCounter(window, "java2d.native.frames");
        }
    }

    private void countDroppedFrame() {
        // Called from the native code when an attempt was made to send this surface data to
        // the Wayland server, but that attempt was not successful. This can happen, for example,
        // when those attempts are too frequent.
        Component target = peer.getTarget();
        if (target instanceof Window window) {
            AWTAccessor.getWindowAccessor().bumpCounter(window, "java2d.native.framesDropped");
        }
    }

    private native int pixelAt(int x, int y);
    private native int [] pixelsAt(int x, int y, int width, int height);
}
