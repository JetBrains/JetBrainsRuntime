/*
 * Copyright (c) 2022-2025, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2022-2025, JetBrains s.r.o.. All rights reserved.
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

import sun.java2d.SurfaceData;
import sun.java2d.wl.WLSurfaceDataExt;

import java.util.ArrayList;
import java.util.Objects;

public class WLSurface {
    private final long nativePtr;
    private final WLComponentPeer peer;

    // Graphics devices this top-level component is visible on
    protected final java.util.List<WLGraphicsDevice> devices = new ArrayList<>();

    private SurfaceData surfaceData;

    static {
        initIDs();
    }

    public WLSurface(WLComponentPeer peer) {
        this.peer = peer;
        nativePtr = nativeCreateWlSurface();
        if (nativePtr == 0) {
            throw new RuntimeException("Failed to create WLSurface");
        }
        surfaceData = null; // having surface data means that the surface is visible
    }

    public void dispose() {
        synchronized (this) {
            // TODO: introduce "isValid"?
            hide();
            nativeDestroyWlSurface(nativePtr);
        }
    }

    public boolean isVisible() {
        synchronized (this) {
            return surfaceData != null;
        }
    }

    public void showWithData(SurfaceData data) {
        Objects.requireNonNull(data);

        synchronized (this) {
            if (surfaceData != null) return;

            surfaceData = data;
            SurfaceData.convertTo(WLSurfaceDataExt.class, data).assignSurface(getWlSurfacePtr());
            WLToolkit.registerWLSurface(getWlSurfacePtr(), peer);
        }

    }

    public void hide() {
        synchronized (this) {
            WLToolkit.unregisterWLSurface(getWlSurfacePtr());

            if (surfaceData == null) return;
            SurfaceData.convertTo(WLSurfaceDataExt.class, surfaceData).assignSurface(0);
            surfaceData = null;
        }

        nativeHideWlSurface(nativePtr);
    }

    public void commit() {
        nativeCommitWlSurface(nativePtr);
    }

    public void moveTo(int x, int y) {
        nativeMoveSurface(nativePtr, x, y);
    }

    public long getWlSurfacePtr() {
        // TODO: this should be constant throughout the lifetime of this object
        return wlSurfacePtr(nativePtr);
    }

    public void setOpaqueRegion(int x, int y, int width, int height) {
        nativeSetOpaqueRegion(nativePtr, x, y, width, height);
    }

    WLGraphicsDevice getGraphicsDevice() {
        int scale = 0;
        WLGraphicsDevice theDevice = null;
        // AFAIK there's no way of knowing which WLGraphicsDevice is displaying
        // the largest portion of this component, so choose the first in the ordered list
        // of devices with the maximum scale simply to be deterministic.
        // NB: devices are added to the end of the list when we enter the corresponding
        // Wayland's output and are removed as soon as we have left.
        synchronized (devices) {
            for (WLGraphicsDevice gd : devices) {
                if (gd.getDisplayScale() > scale) {
                    scale = gd.getDisplayScale();
                    theDevice = gd;
                }
            }
        }

        return theDevice;
    }

    void notifyEnteredOutput(int wlOutputID) {
        // Called from native code whenever the corresponding wl_surface enters an output (monitor)
        synchronized (devices) {
            final WLGraphicsEnvironment ge = (WLGraphicsEnvironment)WLGraphicsEnvironment.getLocalGraphicsEnvironment();
            final WLGraphicsDevice gd = ge.notifySurfaceEnteredOutput(peer, wlOutputID);
            if (gd != null) {
                devices.add(gd);
            }
        }

        peer.checkIfOnNewScreen();
    }

    void notifyLeftOutput(int wlOutputID) {
        // Called from native code whenever the corresponding wl_surface leaves an output (monitor)
        synchronized (devices) {
            final WLGraphicsEnvironment ge = (WLGraphicsEnvironment)WLGraphicsEnvironment.getLocalGraphicsEnvironment();
            final WLGraphicsDevice gd = ge.notifySurfaceLeftOutput(peer, wlOutputID);
            if (gd != null) {
                devices.remove(gd);
            }
        }

        peer.checkIfOnNewScreen();
    }

    public void activateByAnotherSurface(long serial, long activatingSurfacePtr) {
        nativeActivate(nativePtr, serial, activatingSurfacePtr);
    }

    private static native void initIDs();

    private native long wlSurfacePtr(long ptr);
    private native void nativeCommitWlSurface(long ptr);
    private native long nativeCreateWlSurface();
    private native void nativeDestroyWlSurface(long ptr);
    private native void nativeHideWlSurface(long ptr);
    private native void nativeSetOpaqueRegion(long ptr, int x, int y, int width, int height);
    private native void nativeMoveSurface(long ptr, int x, int y);
    private native void nativeActivate(long ptr, long serial, long activatingSurfacePtr);
}
