/*
TODO copyright
 */

package sun.lwawt;

import java.awt.GraphicsConfiguration;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.dnd.peer.DropTargetPeer;

import sun.java2d.pipe.Region;

/**
 * Interface defining the common API for component peers in lwawt.
 */
public interface LWComponentPeerAPI extends DropTargetPeer {

    // TODO these 2 methods come from ComponentPeer actually, should extend it instead?
    /**
     * Returns the location of this component on the screen.
     */
    Point getLocationOnScreen();

    /**
     * Returns the graphics configuration for this component.
     */
    GraphicsConfiguration getGraphicsConfiguration();

    /**
     * Returns the platform window for this component.
     */
    PlatformWindow getPlatformWindow();

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
    LWWindowPeerAPI getWindowPeerOrSelf();

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
