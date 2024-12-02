package sun.awt.wl;

import sun.awt.AWTAccessor;

import java.awt.*;

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

    private volatile WLGraphicsConfig config = null;

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
        if (config == null || config.differsFrom(width, height, scale)) {
            // It is necessary to create a new object whenever config changes as its
            // identity is used to detect changes in scale, among other things.
            config = new WLGraphicsConfig(this, width, height, scale);
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
        final Rectangle newBounds = similarDevice.config.getBounds();
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
    public boolean isSameDeviceAs(int wlID, int x, int y) {
        return this.wlID == wlID && this.x == x && this.y == y;
    }

    public boolean hasSameNameAs(WLGraphicsDevice otherDevice) {
        return name != null && otherDevice.name != null && name.equals(otherDevice.name);
    }

    public boolean hasSameSizeAs(WLGraphicsDevice modelDevice) {
        return config != null && modelDevice.config != null && config.getBounds().equals(modelDevice.config.getBounds());
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
        // So there is just one config, always.
        return new GraphicsConfiguration[] {config};
    }

    @Override
    public GraphicsConfiguration getDefaultConfiguration() {
        return config;
    }

    int getScale() {
        return config.getScale();
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
        return String.format("WLGraphicsDevice: id=%d at (%d, %d) with %s", wlID, x, y, config);
    }
}
