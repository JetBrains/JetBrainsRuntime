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
import sun.java2d.SurfaceData;
import sun.java2d.loops.SurfaceType;
import sun.java2d.pipe.BufferedContext;
import sun.java2d.pipe.RenderBuffer;
import sun.java2d.wl.WLSurfaceDataExt;
import sun.util.logging.PlatformLogger;

import static sun.java2d.pipe.BufferedOpCodes.FLUSH_BUFFER;
import static sun.java2d.pipe.BufferedOpCodes.CONFIGURE_SURFACE;

public abstract class WLVKSurfaceData extends VKSurfaceData implements WLSurfaceDataExt {
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.java2d.vulkan.WLVKSurfaceData");

    protected WLComponentPeer peer;

    private native void initOps(int backgroundRGB);

    private native void assignWlSurface(long surfacePtr);

    protected WLVKSurfaceData(WLComponentPeer peer, WLVKGraphicsConfig gc,
                              SurfaceType sType, ColorModel cm, int type)
    {
        super(gc, cm, type, 0, 0);
        this.peer = peer;
        final int backgroundRGB = peer.getBackground() != null
                ? peer.getBackground().getRGB()
                : 0;
        initOps(backgroundRGB);
    }

    private void bufferAttached() {
        // Called from the native code when a buffer has just been attached to this surface
        // but the surface has not been committed yet.
        peer.updateSurfaceSize();
    }

    @Override
    public void assignSurface(long surfacePtr) {
        assignWlSurface(surfacePtr);
        if (surfacePtr != 0) configure();
    }
    @Override
    public void revalidate(int width, int height, int scale) {
        this.width = width;
        this.height = height;
        this.scale = scale;
        configure();
    }

    private synchronized void configure() {
        VKRenderQueue rq = VKRenderQueue.getInstance();
        rq.lock();
        try {
            RenderBuffer buf = rq.getBuffer();
            rq.ensureCapacityAndAlignment(20, 4);
            buf.putInt(CONFIGURE_SURFACE);
            buf.putLong(getNativeOps());
            buf.putInt(width);
            buf.putInt(height);

            rq.flushNow();
        } finally {
            rq.unlock();
        }
    }

    @Override
    public synchronized void commit() {
        VKRenderQueue rq = VKRenderQueue.getInstance();
        rq.lock();
        try {
            RenderBuffer buf = rq.getBuffer();
            rq.ensureCapacityAndAlignment(12, 4);
            buf.putInt(FLUSH_BUFFER);
            buf.putLong(getNativeOps());

            rq.flushNow();
        } finally {
            rq.unlock();
        }
    }

    @Override
    public boolean isOnScreen() {
        return false;
    }

    @Override
    public GraphicsConfiguration getDeviceConfiguration() {
        return peer.getGraphicsConfiguration();
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
            Rectangle r = peer.getBufferBounds();
            r.x = r.y = 0;
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
            return ((WLVKGraphicsConfig) getDeviceConfiguration()).getContext();
        }

        @Override
        public boolean isOnScreen() {
            return true;
        }
    }
}
