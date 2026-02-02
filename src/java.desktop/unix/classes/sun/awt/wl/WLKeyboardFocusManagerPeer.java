package sun.awt.wl;

import sun.awt.AWTAccessor;
import sun.awt.KeyboardFocusManagerPeerImpl;
import sun.util.logging.PlatformLogger;

import java.awt.Component;
import java.awt.Window;
import java.lang.ref.WeakReference;

public class WLKeyboardFocusManagerPeer extends KeyboardFocusManagerPeerImpl {
    private static final PlatformLogger focusLog = PlatformLogger.getLogger("sun.awt.wl.focus.WLKeyboardFocusManagerPeer");

    private WeakReference<Window> currentFocusedWindow = new WeakReference<>(null);
    private WeakReference<Component> currentFocusOwner = new WeakReference<>(null);

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
            currentFocusedWindow = new WeakReference<>(win);
        }
    }

    @Override
    public Window getCurrentFocusedWindow() {
        synchronized (this) {
            return currentFocusedWindow.get();
        }
    }

    @Override
    public void setCurrentFocusOwner(Component comp) {
        synchronized (this) {
            currentFocusOwner = new WeakReference<>(comp);
        }
        if (comp == null) {
            return;
        }
        Window nativeFocusable = WLComponentPeer.getNativelyFocusableOwnerOrSelf(comp);
        if (nativeFocusable != getCurrentFocusedWindow()) {
            // The component's natively focusable window is not the current focused window.
            // This can happen for popups that aren't natively focusable under Wayland.
            focusLog.severe("Unexpected focus owner set outside of focused window: " + comp);
            return;
        }
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

    @Override
    public Component getCurrentFocusOwner() {
        synchronized (this) {
            return currentFocusOwner.get();
        }
    }
}
