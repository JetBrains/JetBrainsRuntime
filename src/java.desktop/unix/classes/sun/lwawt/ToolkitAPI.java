/*
TODO copyright
 */

package sun.lwawt;

import java.awt.*;
import java.awt.dnd.DropTarget;

/**
 * Abstraction for toolkit operations used by LWComponentPeer.
 * Allows LWComponentPeer to be used with different toolkit implementations
 * (e.g., macOS LWToolkit, Wayland WLToolkit).
 */
public interface ToolkitAPI {

    Object peerForTarget(Object target);

    void peerDisposedForTarget(Object target, Object peer);

    void postAWTEvent(AWTEvent event);

    void flushOnscreenGraphics(Component context);

    boolean needUpdateWindowAfterPaint();

    void updateCursorImmediately(LWComponentPeerAPI peer);

    /**
     * Schedules a cursor update for the specified component.
     * Called when mouse events occur to update the cursor appropriately.
     */
    void updateCursorLater(Window target);

    /**
     * Returns the platform window currently under the mouse cursor.
     * Used for determining which window should receive mouse events.
     */
    PlatformWindow getPlatformWindowUnderMouse();

    PlatformDropTarget createDropTarget(DropTarget dropTarget, Component component, LWComponentPeerAPI peer);
}
