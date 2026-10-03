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
import sun.util.logging.PlatformLogger;

import java.util.ArrayList;
import java.util.List;

public class WLMainSurface extends WLSurface {
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.wl.WLMainSurface");

    private final WLWindowPeer peer;

    // Graphics devices this top-level component is visible on
    private final List<WLGraphicsDevice> devices = new ArrayList<>();

    // The numerator (in 1/120ths) of the scale the compositor has most recently announced for this surface
    // with wp_fractional_scale_v1.preferred_scale, or 0 if none has been received (yet). Protected by AWT lock.
    private int preferredScaleNumerator;

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
        WLGraphicsDevice gd;
        synchronized (devices) {
            final WLGraphicsEnvironment ge = (WLGraphicsEnvironment)WLGraphicsEnvironment.getLocalGraphicsEnvironment();
            gd = ge.deviceWithID(wlOutputID);
            if (gd != null) {
                devices.add(gd);
            }
        }

        if (gd != null) {
            peer.notifyOutputChanged(gd, WLGraphicsDevice.OutputChange.ENTERED, 0, 0, 0);
            // The compositor may have announced the scale of this surface before the surface entered any output
            applyPreferredScale();
        }
    }

    @Override
    void notifyLeftOutput(int wlOutputID) {
        // Called from native code whenever the corresponding wl_surface leaves an output (monitor)
        WLGraphicsDevice gd;
        synchronized (devices) {
            final WLGraphicsEnvironment ge = (WLGraphicsEnvironment)WLGraphicsEnvironment.getLocalGraphicsEnvironment();
            gd = ge.deviceWithID(wlOutputID);
            if (gd != null) {
                devices.remove(gd);
            }
        }

        if (gd != null) {
            peer.notifyOutputChanged(gd, WLGraphicsDevice.OutputChange.LEFT, 0, 0, 0);
        }
    }

    @Override
    void notifyPreferredScale(int scaleNumerator) {
        // Called from native code whenever the compositor announces the scale it applies to this surface
        assert SunToolkit.isAWTLockHeldByCurrentThread() : "This method must be invoked while holding the AWT lock";

        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine(String.format("%s: compositor prefers scale %d/%d for %s", this, scaleNumerator,
                    WLGraphicsDevice.FRACTIONAL_SCALE_DENOMINATOR, peer));
        }
        preferredScaleNumerator = scaleNumerator;
        applyPreferredScale();
    }

    /**
     * Makes sure the device the peer renders for has the scale the compositor applies to this surface, if known.
     * The two normally agree as the device's scale is estimated from its output's physical and logical sizes
     * (the compositor's value is exact, though, and replaces the estimate). When they don't, the compositor
     * is showing this surface on another output than the one the peer renders for, so the peer switches to
     * the output with that scale if this surface is on one; otherwise the announcement is left for the
     * 'enter' event of that output to act upon (see notifyEnteredOutput()), as the compositors tend to
     * announce the new scale before the surface enters the output, and changing the scale of the wrong
     * device would affect all the windows on it.
     */
    private void applyPreferredScale() {
        assert SunToolkit.isAWTLockHeldByCurrentThread() : "This method must be invoked while holding the AWT lock";

        if (preferredScaleNumerator == 0) return;
        double scale = (double) preferredScaleNumerator / WLGraphicsDevice.FRACTIONAL_SCALE_DENOMINATOR;

        WLGraphicsDevice currentDevice = ((WLGraphicsConfig) peer.getGraphicsConfiguration()).getDevice();
        WLGraphicsDevice deviceWithScale = null;
        synchronized (devices) {
            if (devices.isEmpty()) return; // Not on any output yet; the scale gets applied upon entering one
            if (devices.contains(currentDevice) && currentDevice.hasSimilarSurfaceScale(scale)) {
                deviceWithScale = currentDevice;
            } else {
                for (WLGraphicsDevice gd : devices) {
                    if (gd.hasSimilarSurfaceScale(scale)) {
                        deviceWithScale = gd;
                        break;
                    }
                }
            }
        }

        if (deviceWithScale == currentDevice) {
            currentDevice.setSurfaceScale(scale); // Confirms or refines the estimated scale
        } else if (deviceWithScale != null) {
            // The compositor considers another one of the outputs this surface is on to be its main output;
            // follow suit as the buffers must be scaled for the output the compositor shows this surface at.
            peer.notifyOutputChanged(deviceWithScale, WLGraphicsDevice.OutputChange.ENTERED, 0, 0, 0);
            deviceWithScale.setSurfaceScale(scale);
        } else if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine(String.format("%s: no output of %s has the scale %f preferred by the compositor, ignoring",
                    this, peer, scale));
        }
    }

    public void activateByAnotherSurface(long serial, long activatingSurfacePtr) {
        assert SunToolkit.isAWTLockHeldByCurrentThread() : "This method must be invoked while holding the AWT lock";
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
