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
import sun.java2d.vulkan.WLVKGraphicsConfig;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.Rectangle;
import java.awt.Window;

/**
 * Corresponds to Wayland's output and is identified by its wlID and x, y coordinates
 * in the multi-monitor setup. Whenever those change, this device is re-created.
 */
public class WLGraphicsDevice extends GraphicsDevice {
    /**
     *  ID of the corresponding wl_output object received from Wayland.
     */
    private volatile int wlID; // only changes when the device gets invalidated

    /**
     * Some human-readable name of the device that came from Wayland.
     * NOT considered part of device's identity.
     */
    private volatile String name;

    /**
     * The horizontal location of this device in the multi-monitor configuration.
     */
    private volatile int x; // only changes when the device gets invalidated

    /**
     * The vertical location of this device in the multi-monitor configuration.
     */
    private volatile int y; // only changes when the device gets invalidated

    // Configs are always the same in size and scale
    private volatile WLGraphicsConfig[] configs = null;

    // The default config is an object from the configs array
    private volatile WLGraphicsConfig defaultConfig = null;

    private WLGraphicsDevice(int id, int x, int y) {
        this.wlID = id;
        this.x = x;
        this.y = y;
    }

    int getID() {
        return wlID;
    }

    void updateConfiguration(String name, int width, int height, int scale) {
        this.name = name == null ? "wl_output." + wlID : name;

        if (configs == null || configs[0].differsFrom(width, height, scale)) {
            // It is necessary to create a new object whenever config changes as its
            // identity is used to detect changes in scale, among other things.
            if (WLGraphicsEnvironment.isVulkanEnabled()) {
                defaultConfig = WLVKGraphicsConfig.getConfig(this, width, height, scale);
                configs = new WLGraphicsConfig[1];
                configs[0] = defaultConfig;
            } else {
                // TODO: Actually, Wayland may support a lot more shared memory buffer configurations, need to
                //   subscribe to the wl_shm:format event and get the list from there.
                defaultConfig = WLSMGraphicsConfig.getConfig(this, width, height, scale, false);
                configs = new WLGraphicsConfig[2];
                configs[0] = defaultConfig;
                configs[1] = WLSMGraphicsConfig.getConfig(this, width, height, scale, true);
            }
        }
    }

    /**
     * Changes all aspects of this device including its identity to be that of the given
     * device. Only used for devices that are no longer physically present, but references
     * to which may still exist in the program.
     */
    void invalidate(WLGraphicsDevice similarDevice) {
        this.wlID = similarDevice.wlID;
        this.x = similarDevice.x;
        this.y = similarDevice.y;

        final int newScale = similarDevice.getScale();
        final Rectangle newBounds = similarDevice.defaultConfig.getBounds();
        updateConfiguration(similarDevice.name, newBounds.width, newBounds.height, newScale);
    }

    public static WLGraphicsDevice createWithConfiguration(int id, String name, int x, int y, int width, int height, int scale) {
        final WLGraphicsDevice device = new WLGraphicsDevice(id, x, y);
        device.updateConfiguration(name, width, height, scale);
        return device;
    }

    /**
     * Compares the identity of this device with the given attributes
     * and returns true iff the attributes identify the same device.
     */
    boolean isSameDeviceAs(int wlID, int x, int y) {
        return this.wlID == wlID && this.x == x && this.y == y;
    }

    boolean hasSameNameAs(WLGraphicsDevice otherDevice) {
        return name != null && otherDevice.name != null && name.equals(otherDevice.name);
    }

    boolean hasSameSizeAs(WLGraphicsDevice modelDevice) {
        return defaultConfig != null && modelDevice.defaultConfig != null && defaultConfig.getBounds().equals(modelDevice.defaultConfig.getBounds());
    }

    @Override
    public int getType() {
        return TYPE_RASTER_SCREEN;
    }

    @Override
    public String getIDstring() {
        return name;
    }

    @Override
    public GraphicsConfiguration[] getConfigurations() {
        // From wayland.xml, wl_output.mode event:
        // "Non-current modes are deprecated. A compositor can decide to only
        //	advertise the current mode and never send other modes. Clients
        //	should not rely on non-current modes."
        // So there's always the same set of configs.
        return configs.clone();
    }

    @Override
    public GraphicsConfiguration getDefaultConfiguration() {
        return defaultConfig;
    }

    int getScale() {
        return defaultConfig.getScale();
    }

    @Override
    public boolean isFullScreenSupported() {
        return true;
    }

    public void setFullScreenWindow(Window w) {
        Window old = getFullScreenWindow();
        if (w == old) {
            return;
        }

        super.setFullScreenWindow(w);

        if (isFullScreenSupported()) {
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
        // TODO: may be needed to keep track of windows on the device to notify
        // them of display change events, perhaps.
    }

    public void removeWindow(WLComponentPeer peer) {
        // TODO: may be needed to keep track of windows on the device to notify
        // them of display change events, perhaps.
    }

    @Override
    public String toString() {
        return String.format("WLGraphicsDevice: id=%d at (%d, %d) with %s",
                wlID, x, y,
                defaultConfig != null ? defaultConfig : "<no configs>");
    }
}
