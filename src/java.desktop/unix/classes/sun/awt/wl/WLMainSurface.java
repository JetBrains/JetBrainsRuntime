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

import sun.awt.SunToolkit;
import sun.java2d.SurfaceData;

import java.util.ArrayList;
import java.util.List;

public class WLMainSurface extends WLSurface {
    private final WLWindowPeer peer;

    // Graphics devices this top-level component is visible on
    private final List<WLGraphicsDevice> devices = new ArrayList<>();

    public WLMainSurface(WLWindowPeer peer) {
        this.peer = peer;
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

    @Override
    void notifyEnteredOutput(int wlOutputID) {
        // Called from native code whenever the corresponding wl_surface enters an output (monitor)
        synchronized (devices) {
            final WLGraphicsEnvironment ge = (WLGraphicsEnvironment)WLGraphicsEnvironment.getLocalGraphicsEnvironment();
            final WLGraphicsDevice gd = ge.deviceWithID(wlOutputID);
            if (gd != null) {
                devices.add(gd);
            }
        }

        peer.checkIfOnNewScreen();
    }

    @Override
    void notifyLeftOutput(int wlOutputID) {
        // Called from native code whenever the corresponding wl_surface leaves an output (monitor)
        synchronized (devices) {
            final WLGraphicsEnvironment ge = (WLGraphicsEnvironment)WLGraphicsEnvironment.getLocalGraphicsEnvironment();
            final WLGraphicsDevice gd = ge.deviceWithID(wlOutputID);
            if (gd != null) {
                devices.remove(gd);
            }
        }

        peer.checkIfOnNewScreen();
    }

    public void activateByAnotherSurface(long serial, long activatingSurfacePtr) {
        assert SunToolkit.isAWTLockHeldByCurrentThread();
        assertIsValid();

        nativeActivate(getNativePtr(), serial, activatingSurfacePtr);
    }

    @Override
    public void associateWithSurfaceData(SurfaceData data) {
        super.associateWithSurfaceData(data);
        WLToolkit.registerWLSurface(getWlSurfacePtr(), peer);
    }

    @Override
    public void dispose() {
        if (isValid) {
            WLToolkit.unregisterWLSurface(getWlSurfacePtr());
            super.dispose();
        }
    }

    private native void nativeActivate(long ptr, long serial, long activatingSurfacePtr);
}
