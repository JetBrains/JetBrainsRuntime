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

import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import sun.java2d.SunGraphicsEnvironment;
import sun.util.logging.PlatformLogger;
import sun.util.logging.PlatformLogger.Level;

public class WLGraphicsEnvironment extends SunGraphicsEnvironment {
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
    public boolean isDisplayLocal() {
        return true;
    }

    private final List<WLGraphicsDevice> devices = new ArrayList<>(5);

    private void notifyOutputConfigured(String name, String make, String model, int wlID,
                                        int x, int y, int xLogical, int yLogical,
                                        int width, int height,
                                        int widthLogical, int heightLogical,
                                        int widthMm, int heightMm,
                                        int subpixel, int transform, int scale) {
        // Called from native code whenever a new output appears or an existing one changes its properties
        // NB: initially called during WLToolkit.initIDs() on the main thread; later on EDT.
        if (log.isLoggable(Level.FINE)) {
            log.fine(String.format("Output configured id=%d at (%d, %d) (%d, %d logical) %dx%d (%dx%d logical) %dx scale",
                    wlID, x, y, xLogical, yLogical, width, height, widthLogical, heightLogical, scale));
        }

        String humanID =
                (name != null ? name + " " : "")
                + (make != null ? make + " " : "")
                + (model != null ? model : "");
        synchronized (devices) {
            boolean newOutput = true;
            for (int i = 0; i < devices.size(); i++) {
                final WLGraphicsDevice gd = devices.get(i);
                if (gd.getID() == wlID) {
                    newOutput = false;
                    if (gd.isSameDeviceAs(wlID, x, y, xLogical, yLogical)) {
                        // These coordinates and the size are not scaled.
                        gd.updateConfiguration(humanID, width, height, widthLogical, heightLogical, scale);
                    } else {
                        final WLGraphicsDevice updatedDevice = WLGraphicsDevice.createWithConfiguration(wlID, humanID,
                                x, y, xLogical, yLogical, width, height, widthLogical, heightLogical,
                                widthMm, heightMm, scale);
                        devices.set(i, updatedDevice);
                        gd.invalidate(updatedDevice);
                    }
                    break;
                }
            }
            if (newOutput) {
                final WLGraphicsDevice gd = WLGraphicsDevice.createWithConfiguration(wlID, humanID,
                        x, y, xLogical, yLogical, width, height, widthLogical, heightLogical,
                        widthMm, heightMm, scale);
                devices.add(gd);
            }
            if (LogDisplay.ENABLED) {
                double effectiveScale = effectiveScaleFrom(scale);
                LogDisplay log = newOutput ? LogDisplay.ADDED : LogDisplay.CHANGED;
                log.log(wlID, (int) (width / effectiveScale) + "x" +  (int) (height / effectiveScale), effectiveScale);
            }
        }

        updateTotalDisplayBounds();

        // Skip notification during the initial configuration events
        if (WLToolkit.isInitialized()) {
            displayChanged();
        }
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
        synchronized (devices) {
            final Optional<WLGraphicsDevice> deviceOptional = devices.stream()
                    .filter(device -> device.getID() == wlID)
                    .findFirst();
            if (deviceOptional.isPresent()) {
                final WLGraphicsDevice destroyedDevice = deviceOptional.get();
                if (LogDisplay.ENABLED) {
                    WLGraphicsConfig config = (WLGraphicsConfig) destroyedDevice.getDefaultConfiguration();
                    Rectangle bounds = config.getBounds();
                    LogDisplay.REMOVED.log(wlID, bounds.width + "x" +  bounds.height, config.getEffectiveScale());
                }
                devices.remove(destroyedDevice);
                final WLGraphicsDevice similarDevice = getSimilarDevice(destroyedDevice);
                if (similarDevice != null) destroyedDevice.invalidate(similarDevice);
            }
        }

        updateTotalDisplayBounds();
        displayChanged();
    }

    WLGraphicsDevice notifySurfaceEnteredOutput(WLComponentPeer wlComponentPeer, int wlOutputID) {
        synchronized (devices) {
            for (WLGraphicsDevice gd : devices) {
                if (gd.getID() == wlOutputID) {
                    return gd;
                }
            }
            return null;
        }
    }

    WLGraphicsDevice notifySurfaceLeftOutput(WLComponentPeer wlComponentPeer, int wlOutputID) {
        synchronized (devices) {
            for (WLGraphicsDevice gd : devices) {
                if (gd.getID() == wlOutputID) {
                    return gd;
                }
            }
            return null;
        }
    }

    public Dimension getTotalDisplayBounds() {
        synchronized (totalDisplayBounds) {
            return totalDisplayBounds.getSize();
        }
    }

    private void updateTotalDisplayBounds() {
        synchronized (devices) {
            Rectangle virtualBounds = new Rectangle();
            for (GraphicsDevice gd : devices) {
                for (GraphicsConfiguration gc : gd.getConfigurations()) {
                    virtualBounds = virtualBounds.union(gc.getBounds());
                }
            }
            synchronized (totalDisplayBounds) {
                totalDisplayBounds.setSize(virtualBounds.getSize());
            }
        }
    }

    static double effectiveScaleFrom(int displayScale) {
        return debugScaleEnabled ? SunGraphicsEnvironment.getDebugScale() : displayScale;
    }

    static boolean isDebugScaleEnabled() {
        return debugScaleEnabled;
    }
}
