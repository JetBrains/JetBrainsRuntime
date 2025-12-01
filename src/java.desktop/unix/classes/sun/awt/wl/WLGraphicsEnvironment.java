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

package sun.awt.wl;

import java.awt.AWTError;
import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import sun.awt.HiDPIInfoProvider;
import sun.java2d.SunGraphicsEnvironment;
import sun.util.logging.PlatformLogger;
import sun.util.logging.PlatformLogger.Level;

public class WLGraphicsEnvironment extends SunGraphicsEnvironment implements HiDPIInfoProvider {
    public static final int WL_OUTPUT_TRANSFORM_NORMAL = 0;
    public static final int WL_OUTPUT_TRANSFORM_90 = 1;
    public static final int WL_OUTPUT_TRANSFORM_180 = 2;
    public static final int WL_OUTPUT_TRANSFORM_270 = 3;
    public static final int WL_OUTPUT_TRANSFORM_FLIPPED = 4;
    public static final int WL_OUTPUT_TRANSFORM_FLIPPED_90 = 5;
    public static final int WL_OUTPUT_TRANSFORM_FLIPPED_180 = 6;
    public static final int WL_OUTPUT_TRANSFORM_FLIPPED_270 = 7;

    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.wl.WLGraphicsEnvironment");

    private static final boolean debugScaleEnabled;
    private final Dimension totalDisplayBounds = new Dimension();

    private final List<WLGraphicsDevice> devices = new ArrayList<>(5);

    @SuppressWarnings("restricted")
    private static void loadAwt() {
        System.loadLibrary("awt");
    }

    static {
        loadAwt();

        debugScaleEnabled = SunGraphicsEnvironment.isUIScaleEnabled() && SunGraphicsEnvironment.getDebugScale() >= 1;

        // Make sure the toolkit is loaded because otherwise this GE is going to be empty
        WLToolkit.isInitialized();
    }

    private WLGraphicsEnvironment() {
    }

    private static class Holder {
        static final WLGraphicsEnvironment INSTANCE = new WLGraphicsEnvironment();
    }

    public static WLGraphicsEnvironment getSingleInstance() {
        return Holder.INSTANCE;
    }


    @Override
    protected int getNumScreens() {
        synchronized (devices) {
            return devices.size();
        }
    }

    @Override
    protected GraphicsDevice makeScreenDevice(int screenNum) {
        synchronized (devices) {
            return devices.get(screenNum);
        }
    }

    @Override
    public GraphicsDevice getDefaultScreenDevice() {
        synchronized (devices) {
            if (devices.isEmpty()) {
                throw new AWTError("no screen devices");
            }
            return devices.getFirst();
        }
    }

    @Override
    public synchronized GraphicsDevice[] getScreenDevices() {
        synchronized (devices) {
            return devices.toArray(new GraphicsDevice[0]);
        }
    }

    @Override
    public boolean isDisplayLocal() {
        return true;
    }

    private void notifyOutputConfigured(String name, String make, String model, int wlID,
                                        int x, int y,
                                        int width, int height,
                                        int widthLogical, int heightLogical,
                                        int widthMm, int heightMm,
                                        int subpixel, int transform, int scale) {
        // Called from native code whenever a new output appears or an existing one changes its properties
        // NB: initially called during WLToolkit.initIDs() on the main thread; later on EDT.
        if (log.isLoggable(Level.FINE)) {
            log.fine(String.format("Output configured id=%d at (%d, %d) %dx%d (%dx%d logical) %dx scale",
                    wlID, x, y, width, height, widthLogical, heightLogical, scale));
        }

        // Logical size comes from an optional protocol, so take the data from the main one, if absent
        if (widthLogical <= 0) widthLogical = width;
        if (heightLogical <= 0) heightLogical = height;

        // Physical size may be absent for virtual outputs.
        if (widthMm <= 0 || heightMm <= 0) {
            // Assume a 92 DPI display
            widthMm = (int) Math.ceil(25.4 * width / 92.0);
            heightMm = (int) Math.ceil(25.4 * height / 92.0);
        }

        String humanID = deviceNameFrom(name, make, model);

        WLGraphicsDevice gd = deviceWithID(wlID);
        if (gd != null) {
            // Some properties of an existing device have changed; update the existing device and
            // let all the windows it hosts know about the change.
            gd.updateConfiguration(humanID, x, y, width, height, widthLogical, heightLogical, widthMm, heightMm, scale);
        } else {
            WLGraphicsDevice newGD = WLGraphicsDevice.createWithConfiguration(wlID, humanID,
                    x, y, width, height, widthLogical, heightLogical,
                    widthMm, heightMm, scale);
            synchronized (devices) {
                devices.add(newGD);
            }
        }

        if (LogDisplay.ENABLED) {
            double effectiveScale = effectiveScaleFrom(scale);
            LogDisplay log = (gd == null) ? LogDisplay.ADDED : LogDisplay.CHANGED;
            log.log(wlID, (int) (width / effectiveScale) + "x" + (int) (height / effectiveScale), effectiveScale);
        }

        updateTotalDisplayBounds();

        // Skip notification during the initial configuration events
        if (WLToolkit.isInitialized()) {
            displayChanged();
        }
    }

    private static String deviceNameFrom(String name, String make, String model) {
        return (name != null ? name + " " : "")
                + (make != null ? make + " " : "")
                + (model != null ? model : "");
    }

    private WLGraphicsDevice getSimilarDevice(WLGraphicsDevice modelDevice) {
        WLGraphicsDevice similarDevice = devices.isEmpty() ? null : devices.getFirst();
        for (WLGraphicsDevice device : devices) {
            if (device.hasSameNameAs(modelDevice)) {
                similarDevice = device;
                break;
            } else if (device.hasSameSizeAs(modelDevice)) {
                similarDevice = device;
                break;
            }
        }

        return similarDevice;
    }

    private void notifyOutputDestroyed(int wlID) {
        // Called from native code whenever one of the outputs is no longer available.
        // All surfaces that were partly visible on that output should have
        // notifySurfaceLeftOutput().
        if (log.isLoggable(Level.FINE)) {
            log.fine(String.format("Output destroyed id=%d", wlID));
        }
        // NB: id may *not* be that of any output; if so, just ignore this event.
        WLGraphicsDevice gd = deviceWithID(wlID);
        if (gd != null) {
            if (LogDisplay.ENABLED) {
                WLGraphicsConfig config = (WLGraphicsConfig) gd.getDefaultConfiguration();
                Rectangle bounds = config.getBounds();
                LogDisplay.REMOVED.log(wlID, bounds.width + "x" + bounds.height, config.getEffectiveScale());
            }
            synchronized (devices) {
                devices.remove(gd);
            }
            final WLGraphicsDevice similarDevice = getSimilarDevice(gd);
            if (similarDevice != null) gd.invalidate(similarDevice);
        }

        updateTotalDisplayBounds();
        displayChanged();
    }

    WLGraphicsDevice deviceWithID(int wlOutputID) {
        synchronized (devices) {
            for (WLGraphicsDevice gd : devices) {
                if (gd.getID() == wlOutputID) {
                    return gd;
                }
            }
        }
        return null;
    }

    public Dimension getTotalDisplayBounds() {
        synchronized (totalDisplayBounds) {
            return totalDisplayBounds.getSize();
        }
    }

    private void updateTotalDisplayBounds() {
        Rectangle virtualBounds = new Rectangle();
        synchronized (devices) {
            for (var gd : devices) {
                virtualBounds = virtualBounds.union(gd.getBounds());
            }
        }
        synchronized (totalDisplayBounds) {
            totalDisplayBounds.setSize(virtualBounds.getSize());
        }
    }

    static double effectiveScaleFrom(int displayScale) {
        return debugScaleEnabled ? SunGraphicsEnvironment.getDebugScale() : displayScale;
    }

    static boolean isDebugScaleEnabled() {
        return debugScaleEnabled;
    }

    public String[][] getHiDPIInfo() {
        var devices = getSingleInstance().getScreenDevices();
        if (devices != null && devices.length > 0) {
            String[][] info = new String[devices.length * 3][3];

            int j = 0;
            for (int i = 0; i < devices.length; i++) {
                var gd = (WLGraphicsDevice) devices[i];
                var gc = (WLGraphicsConfig) gd.getDefaultConfiguration();

                var bounds = gc.getBounds();
                info[j][0] = String.format("Display #%d logical bounds", i);
                info[j][1] = String.format("%dx%d @(%d, %d)", bounds.width, bounds.height, bounds.x, bounds.y);
                info[j][2] = "Display size and offset in logical units";
                j++;

                info[j][0] = String.format("Display #%d logical scale", i);
                info[j][1] = String.format("%d (%.2f)", gd.getDisplayScale(), gc.getEffectiveScale());
                info[j][2] = "wl_output scale and effective scale factor";
                j++;

                info[j][0] = String.format("Display #%d real scale", i);
                info[j][1] = String.format("%.2f", gd.getPhysicalScale());
                info[j][2] = "Physical to logical diagonal length ratio";
                j++;
            }

            return info;
        }
        return null;
    }
}
