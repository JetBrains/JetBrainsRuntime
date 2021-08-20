package sun.awt.wl;

import sun.awt.KeyboardFocusManagerPeerImpl;
import sun.util.logging.PlatformLogger;

import java.awt.Component;
import java.awt.Window;

public class WLKeyboardFocusManagerPeer extends KeyboardFocusManagerPeerImpl {
    private static final PlatformLogger focusLog = PlatformLogger.getLogger("sun.awt.wl.focus.WLKeyboardFocusManagerPeer");

    private Window currentFocusedWindow;
    private static final WLKeyboardFocusManagerPeer instance = new WLKeyboardFocusManagerPeer();

    public static WLKeyboardFocusManagerPeer getInstance() {
        return instance;
    }

    @Override
    public void setCurrentFocusedWindow(Window win) {
        synchronized (this) {
            if (focusLog.isLoggable(PlatformLogger.Level.FINER)) {
                focusLog.finer("Current focused window -> " + win);
            }
            currentFocusedWindow = win;
        }
    }

    @Override
    public Window getCurrentFocusedWindow() {
        synchronized (this) {
            return currentFocusedWindow;
        }
    }

    @Override
    public void setCurrentFocusOwner(Component comp) {
        if (comp != currentFocusedWindow) {
            // In Wayland, only Window can be focused, not any widget in it.
            focusLog.severe("Unexpected focus owner set in a Window: " + comp);
        }
    }

    @Override
    public Component getCurrentFocusOwner() {
        synchronized (this) {
            return currentFocusedWindow;
        }
    }
}
