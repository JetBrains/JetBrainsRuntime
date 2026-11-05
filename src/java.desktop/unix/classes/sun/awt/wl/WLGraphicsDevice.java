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

import sun.awt.AWTAccessor;
import sun.java2d.vulkan.VKEnv;
import sun.java2d.vulkan.WLVKGraphicsConfig;

import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * TODO: update this comment!!!
 * Corresponds to a Wayland output and is identified by its wlID.
 * Owns all graphics configurations associated with this device.
 * Encapsulates all the other properties of the output, such as its size
 * and location in a multi-monitor configuration.
 * Whenever any of these properties change, they are updated and
 * the GraphicsConfiguration objects get re-created to let referents know of the change.
 */
public class WLGraphicsDevice extends GraphicsDevice {
    public enum OutputChange {
        CHANGED, ENTERED, LEFT
    }

    public static final int WL_OUTPUT_CHANGED_ORIGIN = 1;

    private final WLOutput output;
    private final String idString;
    private volatile WLOutputState outputState;

    private volatile GraphicsConfiguration[] configs;
    private volatile WLGraphicsConfig defaultConfig; // A reference to the configs array

    /**
     * Top-level window peers that consider this device as their primary one
     * and get their graphics configuration from it.
     * Stores weak references to WLComponentPeers
     */
    private final Set<WLComponentPeer> toplevels = Collections.newSetFromMap(new WeakHashMap<>()); // guarded by 'this'

    private WLGraphicsDevice(WLOutput output, WLOutputState state) {
        this.output = output;
        this.outputState = state;
        if (state.name() != null) {
            this.idString = String.format("%s (#%d)", state.name(), output.getUniqueId());
        } else {
            this.idString = String.format("%s %s (#%d)", state.make(), state.model(), output.getUniqueId());
        }

        makeGC();
    }

    private void makeGC() {
        GraphicsConfiguration[] newConfigs;
        WLGraphicsConfig newDefaultConfig;
        if (VKEnv.isPresentationEnabled()) {
            newConfigs = VKEnv.getDevices().flatMap(d -> d.getPresentableGraphicsConfigs().map(
                            gc -> WLVKGraphicsConfig.getConfig(gc, this)))
                    .toArray(GraphicsConfiguration[]::new);
            newDefaultConfig = (WLGraphicsConfig) newConfigs[0];
        } else {
            // TODO: Actually, Wayland may support a lot more shared memory buffer configurations, need to
            //   subscribe to the wl_shm:format event and get the list from there.
            newDefaultConfig = WLSMGraphicsConfig.getConfig(this, true);
            newConfigs = new GraphicsConfiguration[2];
            newConfigs[0] = newDefaultConfig;
            newConfigs[1] = WLSMGraphicsConfig.getConfig(this, false);
        }

        configs = newConfigs;
        defaultConfig = newDefaultConfig;
    }

    void updateConfiguration(WLOutputState state) {
        WLOutputState prevState = this.outputState;
        this.outputState = state;
        int dx = x - this.x;
        int dy = y - this.y;

        // It is necessary to create new config objects whenever this device changes
        // as GraphicsConfiguration identity is used to detect changes in scale, among other things.
        makeGC();

        // It is important that by the time displayChanged() events are delivered,
        // all the peers on this device had their graphics configuration updated
        // to refer to the new ones with, perhaps, a different scale or resolution.
        // This affects various BufferStrategy that use volatile images as their buffers.

        List<WLComponentPeer> toplevelsCopy;
        synchronized (this) {
            toplevelsCopy = new ArrayList<>(toplevels);
        }

        toplevelsCopy.forEach(
                wlComponentPeer ->
                        wlComponentPeer.notifyOutputChanged(
                                this, OutputChange.CHANGED,
                                dx != 0 || dy != 0 ? WL_OUTPUT_CHANGED_ORIGIN : 0,
                                dx, dy));

    }

    void invalidate(WLGraphicsDevice similarDevice) {
        updateConfiguration(similarDevice.getOutputState());
    }

    public static WLGraphicsDevice createWithConfiguration(WLOutput output, WLOutputState state) {
        return new WLGraphicsDevice(output, state);
    }

    @Override
    public int getType() {
        return TYPE_RASTER_SCREEN;
    }

    @Override
    public String getIDstring() {
        return idString;
    }

    @Override
    public GraphicsConfiguration[] getConfigurations() {
        // From wayland.xml, wl_output.mode event:
        // "Non-current modes are deprecated. A compositor can decide to only
        // advertise the current mode and never send other modes. Clients
        // should not rely on non-current modes."
        // So there's always the same set of configs.
        return configs.clone();
    }

    @Override
    public GraphicsConfiguration getDefaultConfiguration() {
        return defaultConfig;
    }

    public WLOutput getOutput() {
        return output;
    }

    public WLOutputState getOutputState() {
        return outputState;
    }

    Rectangle getBounds() {
        return outputState.logicalBounds();
    }

    Dimension getPhysicalSizeMm() {
        return outputState.physicalSizeMm();
    }

    int getDisplayScale() {
        return outputState.scale();
    }

    double getEffectiveScale() {
        return outputState.effectiveScale();
    }

    int getResolution() {
        // must match the horizontal resolution to pass tests
        return outputState.dpi().width;
    }

    double getPhysicalScale() {
        Dimension sizePixels = outputState.pixelSize();
        double diagPixels = Math.hypot(sizePixels.width, sizePixels.height);
        Dimension sizeUnits = outputState.logicalBounds().getSize();
        double diagUnits = Math.hypot(sizeUnits.width, sizeUnits.height);
        return diagPixels / diagUnits;
    }

    @Override
    public boolean isFullScreenSupported() {
        return true;
    }

    @Override
    public void setFullScreenWindow(Window w) {
        Window old = getFullScreenWindow();
        if (w == old) {
            return;
        }

        boolean fsSupported = isFullScreenSupported();
        if (fsSupported && old != null) {
            // enter windowed mode and restore the original display mode
            exitFullScreenExclusive(old);
        }

        super.setFullScreenWindow(w);

        if (fsSupported) {
            if (w != null) {
                enterFullScreenExclusive(w);
            } else {
                exitFullScreenExclusive(old);
            }
        }
    }

    private void enterFullScreenExclusive(Window w) {
        WLComponentPeer peer = AWTAccessor.getComponentAccessor().getPeer(w);
        if (peer != null) {
            peer.requestFullScreen(wlID);
        }
    }

    private void exitFullScreenExclusive(Window w) {
        WLComponentPeer peer = AWTAccessor.getComponentAccessor().getPeer(w);
        if (peer != null) {
            peer.requestUnsetFullScreen();
        }
    }

    public void addWindow(WLComponentPeer peer) {
        synchronized (this) {
            toplevels.add(peer);
        }
    }

    public void removeWindow(WLComponentPeer peer) {
        synchronized (this) {
            toplevels.remove(peer);
        }
    }

    @Override
    public String toString() {
        var config = defaultConfig;
        return String.format("WLGraphicsDevice: '%s' outputState=%s with %s",
                idString, outputState, config != null ? config : "<no configs>");
    }

    public Insets getInsets() {
        return new Insets(0, 0, 0, 0);
    }
}
