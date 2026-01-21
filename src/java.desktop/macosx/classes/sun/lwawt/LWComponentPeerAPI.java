/*
TODO copyright
 */

package sun.lwawt;

import java.awt.Rectangle;

import sun.java2d.pipe.Region;

/**
 * Interface defining the common API for component peers in lwawt.
 */
public interface LWComponentPeerAPI {

    /**
     * Returns the bounds of this component relative to its parent.
     */
    Rectangle getBounds();

    /**
     * Returns the region of this component.
     */
    Region getRegion();

    /**
     * Returns the container peer for this component, or null if this is a top-level container.
     */
    LWContainerPeerAPI getContainerPeer();

    /**
     * Returns the window peer for this component, or itself if this is a window peer.
     */
    LWWindowPeer getWindowPeerOrSelf();

    /**
     * Returns whether this component is visible.
     */
    boolean isVisible();

    /**
     * Returns whether this component is showing on screen.
     */
    boolean isShowing();

    /**
     * Returns whether this component is enabled.
     */
    boolean isEnabled();

    /**
     * Repaints the specified rectangle of this component.
     */
    void repaintPeer(Rectangle r);

    LWComponentPeerAPI findPeerAt(final int x, final int y);
}
