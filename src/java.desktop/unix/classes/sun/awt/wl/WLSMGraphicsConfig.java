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

package sun.awt.wl;

import sun.awt.image.SunVolatileImage;
import sun.awt.image.SurfaceManager;
import sun.awt.image.VolatileSurfaceManager;
import sun.java2d.SurfaceData;
import sun.java2d.loops.SurfaceType;
import sun.java2d.wl.WLSMSurfaceData;
import sun.java2d.wl.WLVolatileSurfaceManager;
import sun.util.logging.PlatformLogger;

import java.awt.Transparency;
import java.awt.image.ColorModel;
import java.awt.image.DirectColorModel;

/**
 * Graphics configuration for shared memory buffers (the wl_shm Wayland protocol).
 */
public class WLSMGraphicsConfig extends WLGraphicsConfig
        implements SurfaceManager.Factory {
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.wl.WLSMGraphicsConfig");

    // The memory layout of an individual pixel corresponding exactly to
    // the values of wl_shm::format
    public static final int WL_SHM_FORMAT_ARGB8888 = 0;
    public static final int WL_SHM_FORMAT_XRGB8888 = 1;

    private final boolean translucencyCapable;
    private final ColorModel colorModel;
    private final SurfaceType surfaceType;

    private WLSMGraphicsConfig(WLGraphicsDevice device,
                               int width,
                               int height,
                               int scale,
                               boolean translucencyCapable) {
        super(device, width, height, scale);
        this.translucencyCapable = translucencyCapable;
        this.colorModel
                = translucencyCapable
                ? new DirectColorModel(32, 0xff0000, 0xff00, 0xff, 0xff000000)
                : new DirectColorModel(24, 0xff0000, 0xff00, 0xff);
        this.surfaceType = translucencyCapable ? SurfaceType.IntArgb : SurfaceType.IntRgb;
    }

    public static WLSMGraphicsConfig getConfig(WLGraphicsDevice device,
                                               int width,
                                               int height,
                                               int scale,
                                               boolean translucencyCapable) {
        var newConfig = new WLSMGraphicsConfig(device, width, height, scale, translucencyCapable);
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("New shared memory config " + newConfig);
        }
        return newConfig;
    }

    public SurfaceType getSurfaceType() {
        return surfaceType;
    }

    @Override
    public ColorModel getColorModel() {
        return colorModel;
    }

    @Override
    public ColorModel getColorModel(int transparency) {
        return switch (transparency) {
            case Transparency.OPAQUE -> new DirectColorModel(24, 0xff0000, 0xff00, 0xff);
            case Transparency.BITMASK ->  new DirectColorModel(25, 0xff0000, 0xff00, 0xff, 0x1000000);
            case Transparency.TRANSLUCENT -> new DirectColorModel(32, 0xff0000, 0xff00, 0xff, 0xff000000);
            default -> null;
        };
    }

    public SurfaceData createSurfaceData(WLComponentPeer peer) {
        return WLSMSurfaceData.createData(peer, this);
    }

    public int getWlShmFormat() {
        // The value is one of enum wl_shm_format from wayland-client-protocol.h
        return translucencyCapable ? WL_SHM_FORMAT_ARGB8888 : WL_SHM_FORMAT_XRGB8888;
    }

    @Override
    public boolean isTranslucencyCapable() {
        return translucencyCapable;
    }

    @Override
    public VolatileSurfaceManager createVolatileManager(SunVolatileImage image,
                                                        Object context) {
        return new WLVolatileSurfaceManager(image, context);
    }

    @Override
    public String toString() {
        return "WLSMGraphicsConfig[" + (translucencyCapable ? "translucent" : "opaque") + "] " + super.toString();
    }
}
