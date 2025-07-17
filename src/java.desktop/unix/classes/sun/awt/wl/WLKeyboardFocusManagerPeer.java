package sun.awt.wl;

import sun.awt.AWTAccessor;
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
        Window cur = getCurrentFocusedWindow();
        if (comp != null && (!(comp instanceof Window window) ||
                WLComponentPeer.getNativelyFocusableOwnerOrSelf(window) != cur)) {
            // In Wayland, only Window can be focused, not any widget in it.
            focusLog.severe("Unexpected focus owner set in a Window: " + comp);
            return;
        }

        if (comp != null) {
            Window nativeFocusable = WLComponentPeer.getNativelyFocusableOwnerOrSelf(comp);
            AWTAccessor.ComponentAccessor acc = AWTAccessor.getComponentAccessor();
            WLComponentPeer nativeFocusablePeer = acc.getPeer(nativeFocusable);
            if (nativeFocusablePeer instanceof WLWindowPeer windowPeer) {
                // May have to transfer the keyboard focus to a child popup window
                // when this 'windowPeer' receives focus from Wayland again because popups
                // aren't natively focusable under Wayland.
                Component synthFocusOwner = nativeFocusable != comp ? comp : null;
                windowPeer.setSyntheticFocusOwner(synthFocusOwner);
            }
        }
    }

    @Override
    public Component getCurrentFocusOwner() {
        synchronized (this) {
            return currentFocusedWindow;
        }
    }
}
