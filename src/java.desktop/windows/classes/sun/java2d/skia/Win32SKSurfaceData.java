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
 * {@code Win32SKPlatform.h}). Window and off-screen variants follow
 * {@code WGLSurfaceData}.
 */
public abstract class Win32SKSurfaceData extends SKSurfaceData {

    /** A window's texture has at least one texel a side, whatever the window's bounds. */
    private static final int MIN_WINDOW_TEXELS = 1;

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
    private static native long createPlatformSurface(long nativeConfigInfo, long hwnd, int width, int height,
                                                     long previousOps, boolean isOpaque);

    private Win32SKSurfaceData(WComponentPeer peer, Win32SKGraphicsConfig graphicsConfig, ColorModel colorModel,
                               int type, long platformOps) {
        super(graphicsConfig, colorModel, type, platformOps);
        this.peer = peer;
        this.graphicsConfig = graphicsConfig;
        Win32GraphicsDevice device = graphicsConfig.getDevice();
        this.scaleX = type == TEXTURE ? 1 : device.getDefaultScaleX();
        this.scaleY = type == TEXTURE ? 1 : device.getDefaultScaleY();
    }

    /**
     * The native ops of a new platform surface ({@link #createPlatformSurface}).
     *
     * @param peer     the window's peer, or {@code null} for an off-screen surface
     * @param previous the window surface this one replaces, or {@code null}
     */
    private static long platformSurface(Win32SKGraphicsConfig graphicsConfig, WComponentPeer peer, int width, int height,
                                        Win32SKSurfaceData previous, ColorModel colorModel) {
        long hwnd = peer != null ? peer.getHWnd() : 0L;
        long platformOps = createPlatformSurface(graphicsConfig.getNativeConfigInfo(), hwnd, width, height,
                                                 previous != null ? previous.getNativeOps() : 0L,
                                                 colorModel.getTransparency() == OPAQUE);
        if (platformOps == 0) {
            // what the volatile surface manager and the proxies expect on failure
            throw new OutOfMemoryError("cannot create Skia surface " + width + "x" + height);
        }
        return platformOps;
    }

    /**
     * Window surface for {@code peer}, taking over the window's swap chain from
     * {@code previous} (the surface it replaces, or null); null if the window
     * cannot be rendered by Skia. Called by {@link Win32SKScreenUpdateManager}.
     */
    static Win32SKSurfaceData createData(WComponentPeer peer, Win32SKGraphicsConfig graphicsConfig,
                                         Win32SKSurfaceData previous) {
        // like OpenGL: accelerated on-screen surfaces and layered (translucent/shaped)
        // windows do not mix
        if (!peer.isAccelCapable() || !SunToolkit.isContainingTopLevelOpaque((Component) peer.getTarget())) {
            return null;
        }
        // The flusher may still be presenting the previous surface; with the
        // queue flushed and locked it is idle while the swap chain changes hands.
        SKRenderQueue renderQueue = SKRenderQueue.getInstance();
        renderQueue.lock();
        try {
            renderQueue.flushNow();
            return new WindowSurfaceData(peer, graphicsConfig, previous);
        } catch (OutOfMemoryError error) {
            return null;
        } finally {
            renderQueue.unlock();
        }
    }

    /** Off-screen surface (VolatileImage back buffers, textures). */
    static Win32SKSurfaceData createData(Win32SKGraphicsConfig graphicsConfig, int width, int height,
                                         ColorModel colorModel, Image image, int type) {
        // the texture is created on the caller's thread; the flusher must not be
        // using the device meanwhile (Dawn's implicit device synchronization is
        // requested too, but not every adapter offers it)
        SKRenderQueue renderQueue = SKRenderQueue.getInstance();
        renderQueue.lock();
        try {
            return new OffScreenSurfaceData(graphicsConfig, width, height, image, colorModel, type);
        } finally {
            renderQueue.unlock();
        }
    }

    /** Whether the GPU device of this surface's configuration is lost; nothing renders to it any more. */
    boolean isDeviceLost() {
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

    private static final class WindowSurfaceData extends Win32SKSurfaceData {

        private WindowSurfaceData(WComponentPeer peer, Win32SKGraphicsConfig graphicsConfig, Win32SKSurfaceData previous) {
            this(peer, graphicsConfig, previous, peer.getColorModel(), peer.getBounds());
        }

        private WindowSurfaceData(WComponentPeer peer, Win32SKGraphicsConfig graphicsConfig, Win32SKSurfaceData previous,
                                  ColorModel colorModel, Rectangle bounds) {
            super(peer, graphicsConfig, colorModel, WINDOW,
                  platformSurface(graphicsConfig, peer,
                                  Math.max(MIN_WINDOW_TEXELS, Region.clipRound(bounds.width * graphicsConfig.getDevice().getDefaultScaleX())),
                                  Math.max(MIN_WINDOW_TEXELS, Region.clipRound(bounds.height * graphicsConfig.getDevice().getDefaultScaleY())),
                                  previous, colorModel));
            enableFrameCounters();
        }

        @Override
        boolean isOnScreen() {
            return true;
        }

        @Override
        public SurfaceData getReplacement() {
            return peer.getSurfaceData();
        }

        @Override
        public Rectangle getBounds() {
            Rectangle bounds = peer.getBounds();
            bounds.x = bounds.y = 0;
            bounds.width = Region.clipRound(bounds.width * scaleX);
            bounds.height = Region.clipRound(bounds.height * scaleY);
            return bounds;
        }

        @Override
        public Object getDestination() {
            return peer.getTarget();
        }
    }

    private static final class OffScreenSurfaceData extends Win32SKSurfaceData {

        private final Image offscreenImage;
        private final int width;
        private final int height;

        private OffScreenSurfaceData(Win32SKGraphicsConfig graphicsConfig, int width, int height, Image image,
                                     ColorModel colorModel, int type) {
            super(null, graphicsConfig, colorModel, type,
                  platformSurface(graphicsConfig, null,
                                  deviceSize(width, graphicsConfig.getDevice().getDefaultScaleX(), type),
                                  deviceSize(height, graphicsConfig.getDevice().getDefaultScaleY(), type), null, colorModel));
            this.width = deviceSize(width, scaleX, type);
            this.height = deviceSize(height, scaleY, type);
            this.offscreenImage = image;
        }

        private static int deviceSize(int size, double scale, int type) {
            // textures are not scaled; render targets follow the device scale like WGL
            return type == TEXTURE ? size : Region.clipRound(size * scale);
        }

        @Override
        boolean isOnScreen() {
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
