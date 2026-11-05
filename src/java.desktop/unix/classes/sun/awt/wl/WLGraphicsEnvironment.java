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
import java.awt.GraphicsDevice;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import sun.awt.HiDPIInfoProvider;
import sun.java2d.SunGraphicsEnvironment;
import sun.util.logging.PlatformLogger;
import sun.util.logging.PlatformLogger.Level;

public class WLGraphicsEnvironment extends SunGraphicsEnvironment implements HiDPIInfoProvider {
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.wl.WLGraphicsEnvironment");

    private static final boolean debugScaleEnabled;
    private final Dimension totalDisplayBounds = new Dimension();

    private final List<WLGraphicsDevice> devices = new ArrayList<>();
    private WLGraphicsDevice lastDeviceStanding = null; // guarded by devices

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

    private sealed interface OutputEvent permits
            OutputEvent.OutputConfigured,
            OutputEvent.OutputDisconnected,
            OutputEvent.SurfaceEntered,
            OutputEvent.SurfaceLeft {
        record SurfaceEntered (WLMainSurface surface, WLOutput output) implements OutputEvent {}
        record SurfaceLeft (WLMainSurface surface, WLOutput output) implements OutputEvent {}
        record OutputConfigured(WLOutput output, WLOutputState state, WLOutputState prevState) implements OutputEvent {}
        record OutputDisconnected (WLOutput output) implements OutputEvent {}
    }

    // these fields are only accessed on Wayland dispatch thread (main thread during init, EDT later)
    private final List<OutputEvent> outputEvents = new ArrayList<>();
    private boolean outputEventsFlushPending = false;

    public void notifyOutputConfigured(WLOutput output, WLOutputState state, WLOutputState prevState) {
        postOutputEvent(new OutputEvent.OutputConfigured(output, state, prevState));
    }

    public void notifyOutputDisconnected(WLOutput output) {
        postOutputEvent(new OutputEvent.OutputDisconnected(output));
    }

    public void notifySurfaceEntered(WLSurface surface, WLOutput output) {
        if (surface instanceof WLMainSurface mainSurface) {
            postOutputEvent(new OutputEvent.SurfaceEntered(mainSurface, output));
        }
    }

    public void notifySurfaceLeft(WLSurface surface, WLOutput output) {
        if (surface instanceof WLMainSurface mainSurface) {
            postOutputEvent(new OutputEvent.SurfaceLeft(mainSurface, output));
        }
    }

    private void postOutputEvent(OutputEvent event) {
        if (log.isLoggable(Level.FINE)) {
            log.fine("Adding pending configuration event: " + event);
        }
        outputEvents.add(event);

        if (!outputEventsFlushPending) {
            outputEventsFlushPending = true;
            WLToolkit.syncCallback(() -> {
                List<OutputEvent> events = new ArrayList<>(outputEvents);
                outputEvents.clear();
                outputEventsFlushPending = false;
                flushOutputEvents(events);
            });
        }
    }

    private void updateConfigForPeer(WLComponentPeer peer) {
        WLGraphicsConfig oldGC = (WLGraphicsConfig) peer.getGraphicsConfiguration();
        WLGraphicsConfig newGC = computeConfigFor(peer);
        if (oldGC != newGC) {
            peer.notifyGraphicsConfigurationChanged(newGC);
        }
    }

    private void flushOutputEvents(List<OutputEvent> events) {
        List<OutputEvent> configureEvents = events.stream().filter(e ->
                e instanceof OutputEvent.OutputConfigured ||
                e instanceof OutputEvent.OutputDisconnected).toList();

        boolean outputsChanged = !configureEvents.isEmpty();

        for (OutputEvent event : configureEvents) {
            switch (event) {
                case OutputEvent.OutputConfigured e -> processOutputConfigured(e.output(), e.state(), e.prevState());
                case OutputEvent.OutputDisconnected e -> processOutputDisconnected(e.output());
                default -> {}
            }
        }

        List<WLWindowPeer> allPeers = WLToolkit.getLiveWindowPeers();
        List<WLWindowPeer> toplevels = allPeers.stream().filter(peer -> !peer.targetIsWlPopup()).toList();
        List<WLWindowPeer> popups = allPeers.stream().filter(peer -> peer.targetIsWlPopup()).toList();

        // First compute GC for toplevels, and only then for popups
        for (WLWindowPeer peer : toplevels) {
            updateConfigForPeer(peer);
        }
        for (WLWindowPeer peer : popups) {
            updateConfigForPeer(peer);
        }

        // Recompute positions of popups
        for (WLWindowPeer peer : popups) {
            peer.updatePopupLocationFromParent();
        }

        if (outputsChanged) {
            updateTotalDisplayBounds();
            if (WLToolkit.isInitialized()) {
                WLComponentPeer.performUnlocked(this::displayChanged);
            }
        }
    }

    private WLGraphicsDevice computePrimaryDevice(List<WLOutput> outputs) {
        int scale = 0;
        WLGraphicsDevice result = null;
        for (WLOutput output : outputs) {
            WLGraphicsDevice gd = deviceForOutput(output);
            if (gd == null) {
                continue;
            }
            if (gd.getDisplayScale() > scale) {
                scale = gd.getDisplayScale();
                result = gd;
            }
        }
        return result;
    }

    WLGraphicsDevice computeDeviceFor(WLComponentPeer peer) {
        WLMainSurface surface = peer.getSurface();
        WLGraphicsDevice oldGD = (WLGraphicsDevice) peer.getGraphicsConfiguration().getDevice();

        WLGraphicsDevice gd = null;
        if (surface != null) {
            gd = computePrimaryDevice(surface.getOutputs());
        }
        if (gd == null) {
            synchronized (devices) {
                if (devices.contains(oldGD)) {
                    gd = oldGD;
                }
            }
        }
        if (gd == null) {
            gd = (WLGraphicsDevice) getDefaultScreenDevice();
        }
        return gd;
    }

    WLGraphicsConfig computeConfigFor(WLComponentPeer peer) {
        WLGraphicsDevice gd = computeDeviceFor(peer);
        // TODO: get an equivalent GC, instead of using the default one
        return (WLGraphicsConfig) gd.getDefaultConfiguration();
    }

    @Override
    protected int getNumScreens() {
        synchronized (devices) {
            if (devices.isEmpty()) return 1;
            return devices.size();
        }
    }

    @Override
    protected GraphicsDevice makeScreenDevice(int screenNum) {
        synchronized (devices) {
            if (devices.isEmpty()) return lastDeviceStanding;
            return devices.get(screenNum);
        }
    }

    @Override
    public GraphicsDevice getDefaultScreenDevice() {
        synchronized (devices) {
            if (devices.isEmpty()) {
                return lastDeviceStanding;
            }
            return devices.getFirst();
        }
    }

    @Override
    public GraphicsDevice[] getScreenDevices() {
        synchronized (devices) {
            if (devices.isEmpty()) {
                return new GraphicsDevice[]{lastDeviceStanding};
            }
            return devices.toArray(new GraphicsDevice[0]);
        }
    }

    /**
     * Returns the index of the given device in the GraphicsEnvironment.getScreenDevices() array.
     * If the device is not found, returns the index of a similar device
     * (see {@link #getSimilarDevice(WLGraphicsDevice)}). Returns 0 if all else fails.
     */
    public int deviceNumberOf(GraphicsDevice device) {
        synchronized (devices) {
            int i = 0;
            for (var d: devices) {
                if (d == device) return i;
                i++;
            }
            i = 0;
            var similarDevice = getSimilarDevice((WLGraphicsDevice) device);
            for (var d: devices) {
                if (d == similarDevice) return i;
                i++;
            }
            return 0;
        }
    }

    @Override
    public boolean isDisplayLocal() {
        return true;
    }

    private void processOutputConfigured(WLOutput output, WLOutputState state, WLOutputState prevState) {
        if (log.isLoggable(Level.FINE)) {
            log.fine(String.format("Output configured: %s with %s, previous state was %s", output, state, prevState));
        }
        WLGraphicsDevice gd = deviceForOutput(output);
        if (gd != null) {
            gd.updateConfiguration(state);
            if (LogDisplay.ENABLED) {
                LogDisplay.CHANGED.log(output.getUniqueId(), gd.getBounds(), gd.getEffectiveScale());
            }
        } else {
            gd = WLGraphicsDevice.createWithOutputState(output, state);
            if (LogDisplay.ENABLED) {
                LogDisplay.ADDED.log(output.getUniqueId(), gd.getBounds(), gd.getEffectiveScale());
            }
            synchronized (devices) {
                devices.add(gd);
                lastDeviceStanding = null;
            }
        }
    }

    private void processOutputDisconnected(WLOutput output) {
        if (log.isLoggable(Level.FINE)) {
            log.fine(String.format("Output disconnected: %s, state was %s", output, output.getState()));
        }
        try {
            WLGraphicsDevice gd = deviceForOutput(output);
            if (gd != null) {
                if (LogDisplay.ENABLED) {
                    LogDisplay.REMOVED.log(output.getUniqueId(), gd.getBounds(), gd.getEffectiveScale());
                }
                synchronized (devices) {
                    devices.remove(gd);
                    if (devices.isEmpty()) {
                        lastDeviceStanding = gd;
                    }
                }
                final WLGraphicsDevice similarDevice = getSimilarDevice(gd);
                if (similarDevice != null) {
                    gd.invalidate(similarDevice);
                }
            }
        } finally {
            output.destroy();
        }
    }

    private WLGraphicsDevice getSimilarDevice(WLGraphicsDevice modelDevice) {
        WLGraphicsDevice similarDevice = devices.isEmpty() ? null : devices.getFirst();
        String modelDeviceName = modelDevice.getOutputState().name();
        if (modelDeviceName == null) {
            return similarDevice;
        }

        for (WLGraphicsDevice device : devices) {
            if (modelDeviceName.equals(device.getOutputState().name())) {
                similarDevice = device;
                break;
            }
        }

        return similarDevice;
    }

    WLGraphicsDevice deviceForOutput(WLOutput output) {
        synchronized (devices) {
            for (WLGraphicsDevice gd : devices) {
                if (gd.getOutput() == output) {
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
