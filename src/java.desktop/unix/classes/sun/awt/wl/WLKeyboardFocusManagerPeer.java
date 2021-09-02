package sun.awt.wl;

import java.awt.Component;
import java.awt.Window;
import java.awt.peer.KeyboardFocusManagerPeer;

public class WLKeyboardFocusManagerPeer implements KeyboardFocusManagerPeer {
    private static final WLKeyboardFocusManagerPeer instance = new WLKeyboardFocusManagerPeer();

    public static WLKeyboardFocusManagerPeer getInstance() {
        return instance;
    }

    @Override
    public void setCurrentFocusedWindow(Window win) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Window getCurrentFocusedWindow() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setCurrentFocusOwner(Component comp) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Component getCurrentFocusOwner() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clearGlobalFocusOwner(Window activeWindow) {
        throw new UnsupportedOperationException();
    }
}
