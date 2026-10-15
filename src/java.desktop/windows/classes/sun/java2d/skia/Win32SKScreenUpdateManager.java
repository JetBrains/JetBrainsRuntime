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

import sun.awt.AWTAccessor;
import sun.awt.Win32GraphicsConfig;
import sun.awt.Win32GraphicsEnvironment;
import sun.awt.windows.WComponentPeer;
import sun.java2d.ScreenUpdateManager;
import sun.java2d.SurfaceData;
import sun.java2d.windows.GDIWindowSurfaceData;

import java.awt.Color;
import java.awt.Component;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Screen surfaces of the Skia backend on Windows, after
 * {@code D3DScreenUpdateManager}: a replacement window surface (every bounds
 * change replaces the window surface) takes over the window's swap chain from
 * the surface the peer still holds, since DXGI allows one flip-model swap chain
 * per HWND; a window that leaves Skia (flip chain requested, surface creation
 * failed, device lost) releases its swap chain at once and paints with GDI; the
 * component is repainted when the texture behind it is new.
 * <p>
 * A lost device (a driver reset, an adapter removal) is left behind the way
 * {@code D3DScreenUpdateManager} leaves a lost Direct3D device: the windows
 * paint with GDI, and the device's display is told that it changed, so that
 * its configurations are created anew (with a new device) and the windows get
 * Skia surfaces back on their next surface replacement.
 */
public final class Win32SKScreenUpdateManager extends ScreenUpdateManager {

    /** GDI stand-ins of the Skia window surfaces whose device is lost. */
    private final Map<Win32SKSurfaceData, GDIWindowSurfaceData> gdiSurfaces = new HashMap<>();

    /** Lost configurations whose recreation has been requested (weak: a lost configuration must be collectable). */
    private final Set<Win32SKGraphicsConfig> recovering = Collections.newSetFromMap(new WeakHashMap<>());
    /** Lost configurations in a row; after a few the process stays on GDI. */
    private int recoveryAttempts;
    private static final int MAX_RECOVERY_ATTEMPTS = 3;

    @Override
    public synchronized SurfaceData createScreenSurface(Win32GraphicsConfig gc, WComponentPeer peer,
                                                        int bbNum, boolean isResize) {
        // the peer still refers to the surface being replaced
        Win32SKSurfaceData previous = peer.getSurfaceData() instanceof Win32SKSurfaceData w ? w : null;
        Win32SKSurfaceData sd = null;
        if (previous != null) {
            dropGdiSurface(previous);   // a new surface of either kind supersedes it
        }
        if (gc instanceof Win32SKGraphicsConfig skgc && bbNum == 0) {
            if (skgc.isDeviceLost()) {
                recover(skgc);
            } else {
                sd = Win32SKSurfaceData.createData(peer, skgc, previous);
                if (sd != null) {
                    recoveryAttempts = 0;
                }
            }
        }
        if (sd == null) {
            // flip chains (BufferStrategy with back buffers), a lost device or
            // a failure: GDI, and the window's swap chain must not stay behind
            if (previous != null) {
                previous.flush();
            }
            return super.createScreenSurface(gc, peer, bbNum, isResize);
        }
        if (isResize) {
            // the new surface's texture is empty; the previous contents are gone
            // with the old one (as with a replaced D3D back buffer)
            Component target = (Component) peer.getTarget();
            Rectangle bounds = AWTAccessor.getComponentAccessor().getBounds(target);
            peer.handlePaint(0, 0, bounds.width, bounds.height);
        }
        return sd;
    }

    /**
     * A Skia window surface whose device is lost paints nothing; this
     * {@code Graphics} draws with GDI instead (on one GDI surface per window surface,
     * kept until the surface is replaced, as
     * {@code D3DScreenUpdateManager.getGdiSurface} keeps it), and the surface
     * is replaced (by a GDI one while the device stays lost).
     */
    @Override
    public synchronized Graphics2D createGraphics(SurfaceData sd, WComponentPeer peer,
                                                  Color fgColor, Color bgColor, Font font) {
        if (sd instanceof Win32SKSurfaceData skia && skia.isDeviceLost()) {
            recover((Win32SKGraphicsConfig) skia.getDeviceConfiguration());
            peer.replaceSurfaceDataLater();
            GDIWindowSurfaceData gdi = gdiSurfaces.get(skia);
            if (gdi == null || gdi.isSurfaceLost()) {
                gdi = GDIWindowSurfaceData.createData(peer);
                gdiSurfaces.put(skia, gdi);
            }
            sd = gdi;
        }
        return super.createGraphics(sd, peer, fgColor, bgColor, font);
    }

    @Override
    public synchronized void dropScreenSurface(SurfaceData sd) {
        // the surface is owned by the peer and disposed with it; only the GDI
        // stand-in kept for a lost device goes
        if (sd instanceof Win32SKSurfaceData skia) {
            dropGdiSurface(skia);
        }
    }

    /** An outstanding Graphics on the stand-in takes the replacement path instead of drawing next to the new surface. */
    private void dropGdiSurface(Win32SKSurfaceData skia) {
        GDIWindowSurfaceData gdi = gdiSurfaces.remove(skia);
        if (gdi != null) {
            gdi.invalidate();
        }
    }

    /**
     * Has the configurations of a lost device recreated, once per lost
     * configuration and a few times in a row at most. The environment's
     * {@code displayChanged} runs later on the event thread, as it does for a
     * real display change: it recreates the device objects, notifies the
     * top-level windows (new surfaces on the new device) and the volatile
     * images (moved to the new configuration). This manager is entered under
     * the peers' locks.
     */
    private void recover(Win32SKGraphicsConfig gc) {
        if (gc == null || !recovering.add(gc)) {
            return;
        }
        if (++recoveryAttempts > MAX_RECOVERY_ATTEMPTS) {
            return;   // the device keeps getting lost: GDI from here on
        }
        GraphicsEnvironment environment = GraphicsEnvironment.getLocalGraphicsEnvironment();
        if (environment instanceof Win32GraphicsEnvironment win32) {
            EventQueue.invokeLater(win32::displayChanged);
        }
    }
}
