package sun.awt.wl;

import sun.awt.AWTAccessor;
import sun.awt.DisplayChangedListener;

import java.awt.*;
import java.util.ArrayList;

public class WLGraphicsDevice extends GraphicsDevice implements DisplayChangedListener {
    private final int wlID; // ID of wl_output object received from Wayland
    private String name;
    private int x;
    private int y;

    private final java.util.List<WLComponentPeer> peers = new ArrayList<>();
    private final WLGraphicsConfig config = new WLGraphicsConfig(this);

    public WLGraphicsDevice(int id) {
        this.wlID = id;
    }

    int getWLID() {
        return wlID;
    }

    void updateConfiguration(String name, int x, int y, int width, int height, int scale) {
        this.name = name == null ? "wl_output." + wlID : name;
        this.x = x;
        this.y = y;
        config.update(width, height, scale);
    }

    void updateConfiguration(WLGraphicsDevice gd) {
        final Rectangle bounds = config.getBounds();
        updateConfiguration(gd.name, gd.x, gd.y, bounds.width, bounds.height, config.getScale());
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

    @Override
    public void displayChanged() {
       synchronized (peers) {
           peers.forEach(WLComponentPeer::displayChanged);
       }
    }

    @Override
    public void paletteChanged() {
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
        synchronized (peers) {
            peers.add(peer);
        }
    }

    public void removeWindow(WLComponentPeer peer) {
        synchronized (peers) {
            peers.remove(peer);
        }
    }
}
