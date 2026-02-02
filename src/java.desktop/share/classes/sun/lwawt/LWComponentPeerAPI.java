/*
TODO copyright
 */

package sun.lwawt;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsConfiguration;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.dnd.peer.DropTargetPeer;

import sun.java2d.pipe.Region;

/**
 * Interface defining the common API for component peers in lwawt.
 */
public interface LWComponentPeerAPI extends DropTargetPeer {
    /**
     * Returns the AWT component associated with this peer.
     */
    Component getTarget();

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
     * Sets whether this component is enabled.
     */
    void setEnabled(boolean e);

    /**
     * Sets the background color for this component.
     */
    void setBackground(Color c);

    /**
     * Sets the foreground color for this component.
     */
    void setForeground(Color c);

    /**
     * Sets the font for this component.
     */
    void setFont(Font f);

    /**
     * Repaints the specified rectangle of this component.
     */
    void repaintPeer(Rectangle r);

    /**
     * Sets the bounds of this component.
     */
    void setBounds(Rectangle r);

    /**
     * Sets the bounds of this component with additional parameters.
     *
     * @param x the x coordinate
     * @param y the y coordinate
     * @param w the width
     * @param h the height
     * @param op the operation (SET_LOCATION, SET_SIZE, SET_BOUNDS, etc.)
     * @param notify whether to notify of the change
     * @param updateTarget whether to update the target component
     */
    void setBounds(int x, int y, int w, int h, int op, boolean notify, boolean updateTarget);

    /**
     * Returns the preferred size of this component.
     */
    Dimension getPreferredSize();

    /**
     * Returns the minimum size of this component.
     */
    Dimension getMinimumSize();

    LWComponentPeerAPI findPeerAt(final int x, final int y);
}
