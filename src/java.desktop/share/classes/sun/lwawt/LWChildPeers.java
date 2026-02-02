/*
 * TODO copyright
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
