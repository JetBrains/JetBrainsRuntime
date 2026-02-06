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

    Object targetToPeer(Object target);

    void targetDisposedPeer(Object target, Object peer);

    void postEvent(AWTEvent event);

    void flushOnscreenGraphics();

    boolean needUpdateWindowAfterPaint();

    void updateCursorImmediately();

    PlatformDropTarget createDropTarget(DropTarget dropTarget, Component component, LWComponentPeer<?, ?> peer);
}
