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

import java.awt.GraphicsConfiguration;
import java.awt.Rectangle;
import java.awt.image.ColorModel;
import java.awt.image.Raster;

import sun.awt.wl.WLComponentPeer;
import sun.awt.wl.WLSMGraphicsConfig;
import sun.java2d.SurfaceData;
import sun.java2d.loops.SurfaceType;
import sun.util.logging.PlatformLogger;

/**
 * SurfaceData for shared memory buffers.
 */
public class WLSMSurfaceData extends SurfaceData implements WLSurfaceDataExt {
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.java2d.wl.WLSMSurfaceData");
    private final WLComponentPeer peer;

    public native void assignSurface(long surfacePtr);

    private native void initOps(int width, int height, int scale, int backgroundRGB, int wlShmFormat);

    private WLSMSurfaceData(WLComponentPeer peer, SurfaceType surfaceType, ColorModel colorModel, int scale, int wlShmFormat) {
        super(surfaceType, colorModel);
        this.peer = peer;

        int backgroundRGB = peer.getBackground() != null
                ? peer.getBackground().getRGB()
                : 0;
        int backgroundPixel = surfaceType.pixelFor(backgroundRGB, colorModel);
        int width = peer.getBufferWidth();
        int height = peer.getBufferHeight();

        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine(String.format("Shared memory surface data %dx%d x%d scale, format %d", width, height, scale, wlShmFormat));
        }

        initOps(width, height, scale, backgroundPixel, wlShmFormat);
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
        return new WLSMSurfaceData(peer, surfaceType, cm, graphicsConfig.getScale(), graphicsConfig.getWlShmFormat());
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

    public native void revalidate(int width, int height, int scale);

    @Override
    public native void flush();
}
