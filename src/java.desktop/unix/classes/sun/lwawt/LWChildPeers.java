/*
 * Copyright 2026 JetBrains s.r.o.
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

import sun.awt.SunGraphicsCallback;
import sun.java2d.pipe.Region;

import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.util.LinkedList;
import java.util.List;

public class LWChildPeers {
    private final List<LWComponentPeerAPI> childPeers = new LinkedList<>();

    private final Object peerTreeLock;

    public LWChildPeers(Object peerTreeLock) {
        this.peerTreeLock = peerTreeLock;
    }

    public void addChildPeer(LWComponentPeerAPI child) {
        synchronized (peerTreeLock) {
            childPeers.add(childPeers.size(), child);
        }
    }

    public void removeChildPeer(LWComponentPeerAPI child) {
        synchronized (peerTreeLock) {
            childPeers.remove(child);
        }
    }

    public void setChildPeerZOrder(LWComponentPeerAPI peer, LWComponentPeerAPI above) {
        synchronized (peerTreeLock) {
            childPeers.remove(peer);
            int index = (above != null) ? childPeers.indexOf(above) : childPeers.size();
            if (index >= 0) {
                childPeers.add(index, peer);
            } else {
                // TODO: log
            }
        }
    }

    @SuppressWarnings("unchecked")
    public List<LWComponentPeerAPI> getChildren() {
        synchronized (peerTreeLock) {
            Object copy = ((LinkedList<?>) childPeers).clone();
            return (List<LWComponentPeerAPI>) copy;
        }
    }

    /**
     * Removes bounds of children above specific child from the region. If above
     * is null removes all bounds of children.
     */
    public Region cutChildren(Region r, LWComponentPeerAPI above, Rectangle contentSize) {
        boolean aboveFound = above == null;
        for (final LWComponentPeerAPI child : getChildren()) {
            if (!aboveFound && child == above) {
                aboveFound = true;
                continue;
            }
            if (aboveFound) {
                if(child.isVisible()){
                    final Rectangle cb = child.getBounds();
                    final Region cr = child.getRegion();
                    final Region tr = cr.getTranslatedRegion(cb.x, cb.y);
                    r = r.getDifference(tr.getIntersection(contentSize));
                }
            }
        }
        return r;
    }

    public LWComponentPeerAPI findChildPeerAt(int x, int y) {
        synchronized (peerTreeLock) {
            for (int i = childPeers.size() - 1; i >= 0; --i) {
                LWComponentPeerAPI p = childPeers.get(i).findPeerAt(x, y);
                if (p != null) {
                    return p;
                }
            }
        }
        return null;
    }

    /**
     * Repaints all the child peers in the straight z-order, so the
     * bottom-most ones are painted first.
     */
    public void repaintChildren(final Rectangle r, final Rectangle containerSize) {
        for (final LWComponentPeerAPI child : getChildren()) {
            final Rectangle childBounds = child.getBounds();
            Rectangle toPaint = r.intersection(childBounds);
            toPaint = toPaint.intersection(containerSize);
            toPaint.translate(-childBounds.x, -childBounds.y);
            child.repaintPeer(toPaint);
        }
    }

    public void setChildrenEnabled(boolean e) {
        for (final LWComponentPeerAPI child : getChildren()) {
            child.setEnabled(e && child.getTarget().isEnabled());
        }
    }

    public void setChildrenBackground(Color c) {
        for (final LWComponentPeerAPI child : getChildren()) {
            if (!child.getTarget().isBackgroundSet()) {
                child.setBackground(c);
            }
        }
    }

    public void setChildrenForeground(Color c) {
        for (final LWComponentPeerAPI child : getChildren()) {
            if (!child.getTarget().isForegroundSet()) {
                child.setForeground(c);
            }
        }
    }

    public void setChildrenFont(Font f) {
        for (final LWComponentPeerAPI child : getChildren()) {
            if (!child.getTarget().isFontSet()) {
                child.setFont(f);
            }
        }
    }

    public static void paintChildren(Component[] components, Graphics g) {
        SunGraphicsCallback.PaintHeavyweightComponentsCallback.getInstance()
                .runComponents(components, g,
                        SunGraphicsCallback.LIGHTWEIGHTS | SunGraphicsCallback.HEAVYWEIGHTS);
    }

    public static void printChildren(Component[] components, Graphics g) {
        SunGraphicsCallback.PrintHeavyweightComponentsCallback.getInstance()
                .runComponents(components, g,
                        SunGraphicsCallback.LIGHTWEIGHTS | SunGraphicsCallback.HEAVYWEIGHTS);
    }
}
