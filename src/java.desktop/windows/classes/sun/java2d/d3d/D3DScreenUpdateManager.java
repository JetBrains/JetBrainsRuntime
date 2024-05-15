/*
 * Copyright (c) 2007, 2024, Oracle and/or its affiliates. All rights reserved.
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

package sun.java2d.d3d;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Window;
import java.util.ArrayList;
import java.util.HashMap;

import sun.awt.AWTAccessor;
import sun.awt.AWTAccessor.ComponentAccessor;
import sun.awt.util.ThreadGroupUtils;
import sun.awt.Win32GraphicsConfig;
import sun.awt.windows.WComponentPeer;
import sun.java2d.InvalidPipeException;
import sun.java2d.ScreenUpdateManager;
import sun.java2d.SunGraphics2D;
import sun.java2d.SurfaceData;
import sun.java2d.windows.GDIWindowSurfaceData;
import sun.java2d.d3d.D3DSurfaceData.D3DWindowSurfaceData;
import sun.java2d.windows.WindowsFlags;

/**
 * This class handles rendering to the screen with the D3D pipeline.
 *
 * Since it is not possible to render directly to the front buffer
 * with D3D9, we create a swap chain surface (with COPY effect) in place of the
 * GDIWindowSurfaceData. A background thread handles the swap chain flips.
 *
 * There are some restrictions to which windows we would use this for.
 * @see #createScreenSurface
 */
public final class D3DScreenUpdateManager extends ScreenUpdateManager {
    /**
     * A window must be at least MIN_WIN_SIZE in one or both dimensions
     * to be considered for the update manager.
     */
    private static final int MIN_WIN_SIZE = 150;

    /**
     * List of D3DWindowSurfaceData surfaces. Surfaces are added to the
     * list when a graphics object is created, and removed when the surface
     * is invalidated.
     */
    private ArrayList<D3DWindowSurfaceData> d3dwSurfaces;
    /**
     * Cache of GDIWindowSurfaceData surfaces corresponding to the
     * D3DWindowSurfaceData surfaces. Surfaces are added to the list when
     * a d3dw surface is lost and could not be restored (due to lack of vram,
     * for example), and removed then the d3dw surface is invalidated.
     */
    private HashMap<D3DWindowSurfaceData, GDIWindowSurfaceData> gdiSurfaces;

    public D3DScreenUpdateManager() {
    }

    /**
     * If possible, creates a D3DWindowSurfaceData (which is actually
     * a back-buffer surface). If the creation fails, returns GDI
     * onscreen surface instead.
     *
     * Note that the created D3D surface does not initialize the native
     * resources (and is marked lost) to avoid wasting video memory. It is
     * restored when a graphics object is requested from the peer.
     *
     * Note that this method is called from a synchronized block in
     * WComponentPeer, so we don't need to synchronize
     *
     * Note that we only create a substitute d3dw surface if certain conditions
     * are met
     * <ul>
     *  <li>the fake d3d rendering on screen is not disabled via flag
     *  <li>d3d on the device is enabled
     *  <li>surface is larger than MIN_WIN_SIZE (don't bother for smaller ones)
     *  <li>it doesn't have a backBuffer for a BufferStrategy already
     *  <li>the peer is either Canvas, Panel, Window, Frame,
     *  Dialog or EmbeddedFrame
     * </ul>
     *
     * @param gc GraphicsConfiguration on associated with the surface
     * @param peer peer for which the surface is to be created
     * @param bbNum number of back-buffers requested. if this number is >0,
     * method returns GDI surface (we don't want to have two swap chains)
     * @param isResize whether this surface is being created in response to
     * a component resize event. This determines whether a repaint event will
     * be issued after a surface is created: it will be if {@code isResize}
     * is {@code true}.
     * @return surface data to be use for onscreen rendering
     */
    @Override
    public SurfaceData createScreenSurface(Win32GraphicsConfig gc,
                                           WComponentPeer peer,
                                           int bbNum, boolean isResize)
    {
        if (!(gc instanceof D3DGraphicsConfig)) {
            return super.createScreenSurface(gc, peer, bbNum, isResize);
        }

        SurfaceData sd = null;

        if (canUseD3DOnScreen(peer, gc, bbNum)) {
            try {
                // note that the created surface will be in the "lost"
                // state, it will be restored prior to rendering to it
                // for the first time. This is done so that vram is not
                // wasted for surfaces never rendered to
                sd = D3DSurfaceData.createData(peer);
            }  catch (InvalidPipeException ipe) {
                sd = null;
            }
        }
        if (sd == null) {
            sd = GDIWindowSurfaceData.createData(peer);
            // note that we do not add this surface to the list of cached gdi
            // surfaces as there's no d3dw surface to associate it with;
            // this peer will have a gdi surface until next time a surface
            // will need to be replaced
        }

        if (isResize) {
            // since we'd potentially replaced the back-buffer surface
            // (either with another bb, or a gdi one), the
            // component will need to be completely repainted;
            // this only need to be done when the surface is created in
            // response to a resize event since when a component is created it
            // will be repainted anyway
            repaintPeerTarget(peer);
        }

        return sd;
    }

    /**
     * Determines if we can use a d3d surface for onscreen rendering for this
     * peer.
     * We only create onscreen d3d surfaces if the following conditions are met:
     *  - d3d is enabled on this device and onscreen emulation is enabled
     *  - window is big enough to bother (either dimension > MIN_WIN_SIZE)
     *  - this heavyweight doesn't have a BufferStrategy
     *  - if we are in full-screen mode then it must be the peer of the
     *    full-screen window (since there could be only one SwapChain in fs)
     *    and it must not have any heavyweight children
     *    (as Present() doesn't respect component clipping in fullscreen mode)
     *  - it's one of the classes likely to have custom rendering worth
     *    accelerating
     *
     * @return true if we can use a d3d surface for this peer's onscreen
     *         rendering
     */
    public static boolean canUseD3DOnScreen(final WComponentPeer peer,
                                            final Win32GraphicsConfig gc,
                                            final int bbNum)
    {
        if (!(gc instanceof D3DGraphicsConfig)) {
            return false;
        }
        D3DGraphicsConfig d3dgc = (D3DGraphicsConfig)gc;
        D3DGraphicsDevice d3dgd = d3dgc.getD3DDevice();
        String peerName = peer.getClass().getName();
        Rectangle r = peer.getBounds();
        Component target = (Component)peer.getTarget();
        Window fsw = d3dgd.getFullScreenWindow();

        return
            WindowsFlags.isD3DOnScreenEnabled() &&
            d3dgd.isD3DEnabledOnDevice() &&
            peer.isAccelCapable() &&
            bbNum == 0 &&
            (fsw == null || (fsw == target && !hasHWChildren(target))) &&
            (peerName.equals("sun.awt.windows.WCanvasPeer") ||
             peerName.equals("sun.awt.windows.WDialogPeer") ||
             peerName.equals("sun.awt.windows.WPanelPeer")  ||
             peerName.equals("sun.awt.windows.WWindowPeer") ||
             peerName.equals("sun.awt.windows.WFramePeer")  ||
             peerName.equals("sun.awt.windows.WEmbeddedFramePeer"));
    }

    /**
     * Creates a graphics object for the passed in surface data. If
     * the surface is lost, it is restored.
     * If the surface wasn't lost or the restoration was successful
     * the surface is added to the list of maintained surfaces
     * (if it hasn't been already).
     *
     * If the updater thread hasn't been created yet , it will be created and
     * started.
     *
     * @param sd surface data for which to create SunGraphics2D
     * @param peer peer associated with the surface data
     * @param fgColor fg color to be used in graphics
     * @param bgColor bg color to be used in graphics
     * @param font font to be used in graphics
     * @return a SunGraphics2D object for the surface (or for temp GDI
     * surface data)
     */
    @Override
    public synchronized Graphics2D createGraphics(SurfaceData sd,
            WComponentPeer peer, Color fgColor, Color bgColor, Font font)
    {
        if (peer == null || peer.getSurfaceData() == null) {
            return null;
        }

        if (sd instanceof D3DWindowSurfaceData) {
            D3DWindowSurfaceData d3dw = (D3DWindowSurfaceData) sd;
            if (!d3dw.isSurfaceLost() || validate(d3dw)) {
                trackScreenSurface(d3dw);
                return new SunGraphics2D(sd, fgColor, bgColor, font);
            }
            // could not restore the d3dw surface, use the cached gdi surface
            // instead for this graphics object; note that we do not track
            // this new gdi surface, it is only used for this graphics
            // object
            sd = getGdiSurface(d3dw);
        }
        return super.createGraphics(sd, peer, fgColor, bgColor, font);
    }

    /**
     * Posts a repaint event for the peer's target to the EDT
     * @param peer for which target's the repaint should be issued
     */
    private void repaintPeerTarget(WComponentPeer peer) {
        Component target = (Component)peer.getTarget();
        Rectangle bounds = AWTAccessor.getComponentAccessor().getBounds(target);
        // the system-level painting operations should call the handlePaint()
        // method of the WComponentPeer class to repaint the component;
        // calling repaint() forces AWT to make call to update()
        peer.handlePaint(0, 0, bounds.width, bounds.height);
    }

    /**
     * Adds a surface to the list of tracked surfaces.
     *
     * @param sd the surface to be added
     */
    private void trackScreenSurface(SurfaceData sd) {
        if (sd instanceof D3DWindowSurfaceData) {
            synchronized (this) {
                if (d3dwSurfaces == null) {
                    d3dwSurfaces = new ArrayList<D3DWindowSurfaceData>();
                }
                D3DWindowSurfaceData d3dw = (D3DWindowSurfaceData)sd;
                if (!d3dwSurfaces.contains(d3dw)) {
                    d3dwSurfaces.add(d3dw);
                }
            }
        }
    }

    @Override
    public synchronized void dropScreenSurface(SurfaceData sd) {
        if (d3dwSurfaces != null && sd instanceof D3DWindowSurfaceData) {
            D3DWindowSurfaceData d3dw = (D3DWindowSurfaceData)sd;
            removeGdiSurface(d3dw);
            d3dwSurfaces.remove(d3dw);
        }
    }

    @Override
    public SurfaceData getReplacementScreenSurface(WComponentPeer peer,
                                                   SurfaceData sd)
    {
        SurfaceData newSurface = super.getReplacementScreenSurface(peer, sd);
        // if some outstanding graphics context wants to get a replacement we
        // need to make sure that the new surface (if it is accelerated) is
        // being tracked
        trackScreenSurface(newSurface);
        return newSurface;
    }

    /**
     * Remove the gdi surface corresponding to the passed d3dw surface
     * from list of the cached gdi surfaces.
     *
     * @param d3dw surface for which associated gdi surface is to be removed
     */
    private void removeGdiSurface(final D3DWindowSurfaceData d3dw) {
        if (gdiSurfaces != null) {
            GDIWindowSurfaceData gdisd = gdiSurfaces.get(d3dw);
            if (gdisd != null) {
                gdisd.invalidate();
                gdiSurfaces.remove(d3dw);
            }
        }
    }

    /**
     * Swaps the buffers of the D3D window surfaces within the specified region.
     * If a D3D window surface is dirty or marked as lost, it will be swapped.
     *
     * @param dx1 the x-coordinate of the starting point of the region
     * @param dy1 the y-coordinate of the starting point of the region
     * @param dx2 the x-coordinate of the ending point of the region
     * @param dy2 the y-coordinate of the ending point of the region
     */
    public void swapBuffers(D3DWindowSurfaceData sd, int dx1, int dy1, int dx2, int dy2) {
        // skip invalid surfaces (they could have become invalid
        // after we made a copy of the list) - just a precaution
        if (sd.isValid() && (sd.isDirty() || sd.isSurfaceLost())) {
            if (!sd.isSurfaceLost()) {
                // the flip and the clearing of the dirty state
                // must be done under the lock, otherwise it's
                // possible to miss an update to the surface
                D3DRenderQueue rq = D3DRenderQueue.getInstance();
                rq.lock();
                try {
                    Rectangle r = sd.getBounds();
                    if (dx1 == 0 && dy1 == 0 && dx2 == 0 && dy2 == 0) {
                        D3DSurfaceData.swapBuffers(sd, 0, 0,
                                r.width, r.height);
                    } else {
                        D3DSurfaceData.swapBuffers(sd, dx1, dy1, dx2, dy2);
                    }
                    sd.markClean();
                } finally {
                    rq.unlock();
                }
            } else if (!validate(sd)) {
                // it is possible that the validation may never
                // succeed, we need to detect this and replace
                // the d3dw surface with gdi; the replacement of
                // the surface will also trigger a repaint
                sd.getPeer().replaceSurfaceDataLater();
            }
        }
    }

    public void swapFullBuffers() {
        if (d3dwSurfaces == null) {
            return;
        }

        // make a copy to avoid synchronization during the loop
        for (int i = 0; i < d3dwSurfaces.size(); i++) {
            D3DWindowSurfaceData sd = d3dwSurfaces.get(i);
            swapBuffers(sd, 0, 0, 0, 0);
        }
    }

    /**
     * Restores the passed surface if it was lost, resets the lost status.
     * @param sd surface to be validated
     * @return true if surface wasn't lost or if restoration was successful,
     * false otherwise
     */
    private boolean validate(D3DWindowSurfaceData sd) {
        if (sd.isSurfaceLost()) {
            try {
                sd.restoreSurface();
                // if succeeded, first fill the surface with bg color
                // note: use the non-synch method to avoid incorrect lock order
                Color bg = sd.getPeer().getBackgroundNoSync();
                SunGraphics2D sg2d = new SunGraphics2D(sd, bg, bg, null);
                sg2d.fillRect(0, 0, sd.getBounds().width, sd.getBounds().height);
                sg2d.dispose();
                // now clean the dirty status so that we don't flip it
                // next time before it gets repainted; it is safe
                // to do without the lock because we will issue a
                // repaint anyway so we will not lose any rendering
                sd.markClean();
                // since the surface was successfully restored we need to
                // repaint whole window to repopulate the back-buffer
                repaintPeerTarget(sd.getPeer());
            } catch (InvalidPipeException ipe) {
                return false;
            }
        }
        return true;
    }

    /**
     * Creates (or returns a cached one) gdi surface for the same peer as
     * the passed d3dw surface has.
     *
     * @param d3dw surface used as key into the cache
     * @return gdi window surface associated with the d3d window surfaces' peer
     */
    private synchronized SurfaceData getGdiSurface(D3DWindowSurfaceData d3dw) {
        if (gdiSurfaces == null) {
            gdiSurfaces =
                new HashMap<D3DWindowSurfaceData, GDIWindowSurfaceData>();
        }
        GDIWindowSurfaceData gdisd = gdiSurfaces.get(d3dw);
        if (gdisd == null) {
            gdisd = GDIWindowSurfaceData.createData(d3dw.getPeer());
            gdiSurfaces.put(d3dw, gdisd);
        }
        return gdisd;
    }

    /**
     * Returns true if the component has heavyweight children.
     *
     * @param comp component to check for hw children
     * @return true if Component has heavyweight children
     */
    private static boolean hasHWChildren(Component comp) {
        final ComponentAccessor acc = AWTAccessor.getComponentAccessor();
        if (comp instanceof Container) {
            for (Component c : ((Container)comp).getComponents()) {
                if (acc.getPeer(c) instanceof WComponentPeer || hasHWChildren(c)) {
                    return true;
                }
            }
        }
        return false;
    }
}
