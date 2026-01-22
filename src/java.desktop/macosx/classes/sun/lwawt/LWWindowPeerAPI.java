/*
TODO copyright
 */

package sun.lwawt;

import java.awt.*;
import java.awt.event.FocusEvent;

/**
 * Interface defining the window peer API needed by component peers.
 * This abstraction allows lwawt component peers to work with different
 * window peer implementations (e.g., LWWindowPeer on macOS, WLWindowPeer on Wayland).
 */
public interface LWWindowPeerAPI extends LWContainerPeerAPI {

    /**
     * Returns graphics for on-screen rendering with the specified colors and font.
     */
    Graphics getOnscreenGraphics(Color fg, Color bg, Font f);

    // TODO this method comes from WindowPeer actually, should extend it instead?
    /**
     * Updates the window, typically to flush rendering.
     */
    void updateWindow();

    boolean requestWindowFocus(FocusEvent.Cause cause);
}
