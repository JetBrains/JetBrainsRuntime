/*
 * Copyright (c) 2011, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package sun.lwawt;

import sun.java2d.pipe.Region;

import java.awt.Color;
import java.awt.Container;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.peer.ContainerPeer;
import java.util.List;

import javax.swing.JComponent;

abstract class LWContainerPeer<T extends Container, D extends JComponent>
        extends LWCanvasPeer<T, D> implements ContainerPeer, LWContainerPeerAPI {

    /**
     * List of child peers sorted by z-order from bottom-most to top-most.
     */
    private final LWChildPeers childPeers = new LWChildPeers(getPeerTreeLock());

    LWContainerPeer(final T target, final PlatformComponent platformComponent, final ToolkitAPI toolkitApi) {
        super(target, platformComponent, toolkitApi);
    }

    @Override
    public final void addChildPeer(final LWComponentPeerAPI child) {
        childPeers.addChildPeer(child);
        // TODO: repaint
    }

    @Override
    public final void removeChildPeer(final LWComponentPeerAPI child) {
        childPeers.removeChildPeer(child);
        // TODO: repaint
    }

    // Used by LWComponentPeer.setZOrder()
    @Override
    public final void setChildPeerZOrder(final LWComponentPeerAPI peer, final LWComponentPeerAPI above) {
        childPeers.setChildPeerZOrder(peer, above);
        // TODO: repaint
    }

    // ---- PEER METHODS ---- //

    /*
     * Overridden in LWWindowPeer.
     */
    @Override
    public Insets getInsets() {
        return new Insets(0, 0, 0, 0);
    }

    @Override
    public final void beginValidate() {
        // TODO: it seems that begin/endValidate() is only useful
        // for heavyweight windows, when a batch movement for
        // child windows  occurs. That's why no-op
    }

    @Override
    public final void endValidate() {
        // TODO: it seems that begin/endValidate() is only useful
        // for heavyweight windows, when a batch movement for
        // child windows  occurs. That's why no-op
    }

    @Override
    public final void beginLayout() {
        // Skip all painting till endLayout()
        setLayouting(true);
    }

    @Override
    public final void endLayout() {
        setLayouting(false);

        // Post an empty event to flush all the pending target paints
        postPaintEvent(0, 0, 0, 0);
    }

    // ---- PEER NOTIFICATIONS ---- //

    /**
     * Returns a copy of the childPeer collection.
     */
    final List<LWComponentPeerAPI> getChildren() {
        return childPeers.getChildren();
    }

    @Override
    final Region getVisibleRegion() {
        return cutChildren(super.getVisibleRegion(), null);
    }

    /**
     * Removes bounds of children above specific child from the region. If above
     * is null removes all bounds of children.
     */
    @Override
    public final Region cutChildren(Region r, final LWComponentPeerAPI above) {
        return childPeers.cutChildren(r, above, getContentSize());
    }

    // ---- UTILITY METHODS ---- //

    /**
     * Finds a top-most visible component for the given point. The location is
     * specified relative to the peer's parent.
     */
    @Override
    public LWComponentPeerAPI findPeerAt(int x, int y) {
        LWComponentPeerAPI peer = super.findPeerAt(x, y);
        final Rectangle r = getBounds();
        // Translate to this container's coordinates to pass to children
        x -= r.x;
        y -= r.y;
        if (peer != null && getContentSize().contains(x, y)) {
            LWComponentPeerAPI p = childPeers.findChildPeerAt(x, y);
            if (p != null) {
                peer = p;
            }
        }
        return peer;
    }

    /*
    * Called by the container when any part of this peer or child
    * peers should be repainted
    */
    @Override
    public final void repaintPeer(final Rectangle r) {
        final Rectangle toPaint = getSize().intersection(r);
        if (!isShowing() || toPaint.isEmpty()) {
            return;
        }
        // First, post the PaintEvent for this peer
        super.repaintPeer(toPaint);
        // Second, handle all the children
        // Use the straight order of children, so the bottom
        // ones are painted first
        childPeers.repaintChildren(toPaint, getContentSize());
    }

    @Override
    public Rectangle getContentSize() {
        return getSize();
    }

    @Override
    public void setEnabled(final boolean e) {
        super.setEnabled(e);
        childPeers.setChildrenEnabled(e);
    }

    @Override
    public void setBackground(final Color c) {
        childPeers.setChildrenBackground(c);
        super.setBackground(c);
    }

    @Override
    public void setForeground(final Color c) {
        childPeers.setChildrenForeground(c);
        super.setForeground(c);
    }

    @Override
    public void setFont(final Font f) {
        childPeers.setChildrenFont(f);
        super.setFont(f);
    }

    @Override
    public final void paint(final Graphics g) {
        super.paint(g);
        LWChildPeers.paintChildren(getTarget().getComponents(), g);
    }

    @Override
    public final void print(final Graphics g) {
        super.print(g);
        LWChildPeers.printChildren(getTarget().getComponents(), g);
    }
}
