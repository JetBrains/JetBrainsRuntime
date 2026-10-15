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

import sun.awt.SunToolkit;
import sun.awt.Win32GraphicsDevice;
import sun.awt.windows.WComponentPeer;
import sun.java2d.SurfaceData;
import sun.java2d.pipe.Region;

import java.awt.Component;
import java.awt.GraphicsConfiguration;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.image.ColorModel;

/**
 * Skia surface on Windows: owns its GPU texture, and for windows the swap
 * chain of the peer's HWND that the texture is presented through (native side:
 * {@code DawnSKPlatform.cpp}). Window and off-screen variants follow
 * {@code WGLSurfaceData}.
 */
public abstract class Win32SKSurfaceData extends SKSurfaceData {

    protected final WComponentPeer peer;
    protected final Win32SKGraphicsConfig graphicsConfig;
    protected final double scaleX;
    protected final double scaleY;

    /**
     * Creates the native surface: a texture of the given size and, for a
     * window ({@code hwnd} non-zero), the swap chain of its client area,
     * taken over from {@code previousOps}, the native ops of the window
     * surface this one replaces, when there is one (DXGI allows one flip-model
     * swap chain per HWND). The surface spans the whole window like every AWT
     * window surface and Java paints at the inset offset; the native side
     * takes the insets from the window (same as {@code xoff/yoff} of the D3D9
     * and WGL window surfaces). 0 on failure.
     */
    private static native long createPlatformSurface(long pConfigInfo, long hwnd, int width, int height,
                                                     long previousOps, boolean isOpaque);

    private Win32SKSurfaceData(WComponentPeer peer, Win32SKGraphicsConfig gc, ColorModel cm, int type,
                               long platformOps) {
        super(gc, cm, type, platformOps);
        this.peer = peer;
        this.graphicsConfig = gc;
        Win32GraphicsDevice device = gc.getDevice();
        this.scaleX = type == TEXTURE ? 1 : device.getDefaultScaleX();
        this.scaleY = type == TEXTURE ? 1 : device.getDefaultScaleY();
    }

    private static long platformSurface(Win32SKGraphicsConfig gc, WComponentPeer peer, int width, int height,
                                        Win32SKSurfaceData previous, ColorModel cm) {
        long hwnd = peer != null ? peer.getHWnd() : 0L;
        long ops = createPlatformSurface(gc.getNativeConfigInfo(), hwnd, width, height,
                                         previous != null ? previous.getNativeOps() : 0L,
                                         cm.getTransparency() == OPAQUE);
        if (ops == 0) {
            // what the volatile surface manager and the proxies expect on failure
            throw new OutOfMemoryError("cannot create Skia surface " + width + "x" + height);
        }
        return ops;
    }

    /**
     * Window surface for {@code peer}, taking over the window's swap chain from
     * {@code previous} (the surface it replaces, or null); null if the window
     * cannot be rendered by Skia. Called by {@link Win32SKScreenUpdateManager}.
     */
    static Win32SKSurfaceData createData(WComponentPeer peer, Win32SKGraphicsConfig gc, Win32SKSurfaceData previous) {
        // like OpenGL: accelerated on-screen surfaces and layered (translucent/shaped)
        // windows do not mix
        if (!peer.isAccelCapable() || !SunToolkit.isContainingTopLevelOpaque((Component) peer.getTarget())) {
            return null;
        }
        // The flusher may still be presenting the previous surface; with the
        // queue flushed and locked it is idle while the swap chain changes hands.
        SKRenderQueue rq = SKRenderQueue.getInstance();
        rq.lock();
        try {
            rq.flushNow();
            return new Window(peer, gc, previous);
        } catch (OutOfMemoryError e) {
            return null;
        } finally {
            rq.unlock();
        }
    }

    /** Off-screen surface (VolatileImage back buffers, textures). */
    public static Win32SKSurfaceData createData(Win32SKGraphicsConfig gc, int width, int height,
                                                ColorModel cm, Image image, int type) {
        // the texture is created on the caller's thread; the flusher must not be
        // using the device meanwhile (Dawn's implicit device synchronization is
        // requested too, but not every adapter offers it)
        SKRenderQueue rq = SKRenderQueue.getInstance();
        rq.lock();
        try {
            return new OffScreen(gc, width, height, image, cm, type);
        } finally {
            rq.unlock();
        }
    }

    /** Whether the GPU device of this surface's configuration is lost; nothing renders to it any more. */
    public boolean isDeviceLost() {
        return graphicsConfig.isDeviceLost();
    }

    /**
     * A lost device loses every surface on it: volatile images report it to
     * their manager, proxies stop handing out the texture, and windows go to
     * GDI until the configuration is recreated.
     */
    @Override
    public boolean isSurfaceLost() {
        return super.isSurfaceLost() || isDeviceLost();
    }

    @Override
    public GraphicsConfiguration getDeviceConfiguration() {
        return graphicsConfig;
    }

    @Override
    public double getDefaultScaleX() {
        return scaleX;
    }

    @Override
    public double getDefaultScaleY() {
        return scaleY;
    }

    @Override
    public Rectangle getNativeBounds() {
        return getBounds();
    }

    @Override
    public long getNativeResource(int resType) {
        return 0L;
    }

    private static final class Window extends Win32SKSurfaceData {

        private Window(WComponentPeer peer, Win32SKGraphicsConfig gc, Win32SKSurfaceData previous) {
            this(peer, gc, previous, peer.getColorModel(), peer.getBounds());
        }

        private Window(WComponentPeer peer, Win32SKGraphicsConfig gc, Win32SKSurfaceData previous,
                       ColorModel cm, Rectangle bounds) {
            super(peer, gc, cm, WINDOW,
                  platformSurface(gc, peer,
                                  Math.max(1, Region.clipRound(bounds.width * gc.getDevice().getDefaultScaleX())),
                                  Math.max(1, Region.clipRound(bounds.height * gc.getDevice().getDefaultScaleY())),
                                  previous, cm));
        }

        @Override
        public boolean isOnScreen() {
            return true;
        }

        @Override
        public SurfaceData getReplacement() {
            return peer.getSurfaceData();
        }

        @Override
        public Rectangle getBounds() {
            Rectangle r = peer.getBounds();
            r.x = r.y = 0;
            r.width = Region.clipRound(r.width * scaleX);
            r.height = Region.clipRound(r.height * scaleY);
            return r;
        }

        @Override
        public Object getDestination() {
            return peer.getTarget();
        }
    }

    private static final class OffScreen extends Win32SKSurfaceData {

        private final Image offscreenImage;
        private final int width;
        private final int height;

        private OffScreen(Win32SKGraphicsConfig gc, int width, int height, Image image, ColorModel cm, int type) {
            super(null, gc, cm, type,
                  platformSurface(gc, null, deviceSize(width, gc.getDevice().getDefaultScaleX(), type),
                                  deviceSize(height, gc.getDevice().getDefaultScaleY(), type), null, cm));
            this.width = deviceSize(width, scaleX, type);
            this.height = deviceSize(height, scaleY, type);
            this.offscreenImage = image;
        }

        private static int deviceSize(int size, double scale, int type) {
            // textures are not scaled; render targets follow the device scale like WGL
            return type == TEXTURE ? size : Region.clipRound(size * scale);
        }

        @Override
        public boolean isOnScreen() {
            return false;
        }

        @Override
        public SurfaceData getReplacement() {
            return restoreContents(offscreenImage);
        }

        @Override
        public Rectangle getBounds() {
            return new Rectangle(width, height);
        }

        @Override
        public Object getDestination() {
            return offscreenImage;
        }
    }
}
