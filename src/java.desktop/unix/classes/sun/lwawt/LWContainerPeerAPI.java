/*
TODO copyright
 */

package sun.lwawt;

import java.awt.Rectangle;

import sun.java2d.pipe.Region;

/**
 * Interface defining what {@link LWComponentPeer} needs from its container peer.
 * This abstraction allows lwawt component peers to work with different container
 * implementations (e.g., LWContainerPeer on macOS, WLWindowPeer on Wayland).
 */
public interface LWContainerPeerAPI extends LWComponentPeerAPI {

    /**
     * Adds a child peer to this container.
     */
    void addChildPeer(LWComponentPeerAPI child);

    /**
     * Removes a child peer from this container.
     */
    void removeChildPeer(LWComponentPeerAPI child);

    /**
     * Sets the z-order of a child peer relative to another peer.
     * @param peer the peer to reorder
     * @param above the peer that should be directly above, or null to place at top
     */
    void setChildPeerZOrder(LWComponentPeerAPI peer, LWComponentPeerAPI above);

    /**
     * Removes the bounds of children above the specified child from the region.
     * If above is null, removes all children's bounds.
     */
    Region cutChildren(Region r, LWComponentPeerAPI above);

    /**
     * Returns the content size (client area) of this container.
     */
    Rectangle getContentSize();
}
