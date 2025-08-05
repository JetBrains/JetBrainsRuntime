/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2023, JetBrains s.r.o.. All rights reserved.
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

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.image.ColorModel;
import sun.awt.wl.WLComponentPeer;
import sun.awt.wl.WLGraphicsConfig;
import sun.java2d.SurfaceData;
import sun.java2d.loops.SurfaceType;
import sun.java2d.pipe.BufferedContext;
import sun.java2d.wl.WLSurfaceDataExt;
import sun.util.logging.PlatformLogger;

public abstract class WLVKSurfaceData extends VKSurfaceData implements WLSurfaceDataExt {
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.java2d.vulkan.WLVKSurfaceData");

    protected WLComponentPeer peer;
    protected WLVKGraphicsConfig graphicsConfig;

    @Override
    public native void assignSurface(long surfacePtr);
    @Override
    public native void revalidate(int width, int height, int scale);

    protected native void initOps(int width, int height, int scale, int backgroundRGB);
    protected WLVKSurfaceData(WLComponentPeer peer, WLVKGraphicsConfig gc,
                              SurfaceType sType, ColorModel cm, int type)
    {
        super(gc, cm, type, 0, 0);
        this.peer = peer;
        this.graphicsConfig = gc;
        final int backgroundRGB = peer.getBackground() != null
                ? peer.getBackground().getRGB()
                : 0;
        int scale = ((WLGraphicsConfig)peer.getGraphicsConfiguration()).getWlScale();
        initOps(peer.getBufferWidth(), peer.getBufferHeight(), scale, backgroundRGB);
    }

    @Override
    public boolean isOnScreen() {
        return false;
    }

    public GraphicsConfiguration getDeviceConfiguration() {
        return graphicsConfig;
    }

    /**
     * Creates a SurfaceData object representing surface of on-screen Window.
     */
    public static WLVKWindowSurfaceData createData(WLComponentPeer peer) {
        WLVKGraphicsConfig gc = getGC(peer);
        return new WLVKWindowSurfaceData(peer, gc);
    }

    public static WLVKGraphicsConfig getGC(WLComponentPeer peer) {
        if (peer != null) {
            return (WLVKGraphicsConfig) peer.getGraphicsConfiguration();
        } else {
            // REMIND: this should rarely (never?) happen, but what if
            //         default config is not WLVK?
            GraphicsEnvironment env =
                GraphicsEnvironment.getLocalGraphicsEnvironment();
            GraphicsDevice gd = env.getDefaultScreenDevice();
            return (WLVKGraphicsConfig)gd.getDefaultConfiguration();
        }
    }

    public static class WLVKWindowSurfaceData extends WLVKSurfaceData {
        public WLVKWindowSurfaceData(WLComponentPeer peer, WLVKGraphicsConfig gc)
        {
            super(peer, gc, gc.getSurfaceType(), peer.getColorModel(), WINDOW);
        }

        public SurfaceData getReplacement() {
            return null;
        }

        @Override
        public long getNativeResource(int resType) {
            return 0;
        }

        public Rectangle getBounds() {
            Rectangle r = peer.getVisibleBounds();
            r.x = r.y = 0;
            r.width = (int) Math.ceil(r.width * scale);
            r.height = (int) Math.ceil(r.height * scale);
            return r;
        }

        /**
         * Returns destination Component associated with this SurfaceData.
         */
        public Object getDestination() {
            return peer.getTarget();
        }

        @Override
        public double getDefaultScaleX() {
            return scale;
        }

        @Override
        public double getDefaultScaleY() {
            return scale;
        }

        @Override
        public BufferedContext getContext() {
            return graphicsConfig.getContext();
        }

        @Override
        public boolean isOnScreen() {
            return true;
        }
    }
}
