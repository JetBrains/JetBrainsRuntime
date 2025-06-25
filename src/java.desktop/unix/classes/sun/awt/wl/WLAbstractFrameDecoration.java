/*
 * Copyright 2022 JetBrains s.r.o.
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
package sun.awt.wl;

import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;

public abstract class WLAbstractFrameDecoration {
    private static final int RESIZE_EDGE_THICKNESS = 5;

    private static final int XDG_TOPLEVEL_RESIZE_EDGE_TOP = 1;
    private static final int XDG_TOPLEVEL_RESIZE_EDGE_BOTTOM = 2;
    private static final int XDG_TOPLEVEL_RESIZE_EDGE_LEFT = 4;
    private static final int XDG_TOPLEVEL_RESIZE_EDGE_RIGHT = 8;

    private static final int[] RESIZE_CURSOR_TYPES = {
            -1, // not used
            Cursor.N_RESIZE_CURSOR,
            Cursor.S_RESIZE_CURSOR,
            -1, // not used
            Cursor.W_RESIZE_CURSOR,
            Cursor.NW_RESIZE_CURSOR,
            Cursor.SW_RESIZE_CURSOR,
            -1, // not used
            Cursor.E_RESIZE_CURSOR,
            Cursor.NE_RESIZE_CURSOR,
            Cursor.SE_RESIZE_CURSOR
    };

    // TODO: no 'protected', please
    protected final WLDecoratedPeer peer;
    protected boolean active; // TODO: synchronization
    protected volatile boolean needRepaint = true;

    public WLAbstractFrameDecoration(WLDecoratedPeer peer) {
        this.peer = peer;
    }

    public abstract Insets getInsets();
    public abstract Rectangle getBounds();
    public abstract Dimension getMinimumSize();

    public abstract void paint(Graphics g);

    public void setActive(boolean active) {
        if (active != this.active) {
            this.active = active;
            peer.notifyClientDecorationsChanged();
        }
    }

    public final boolean isRepaintNeeded() {
        return needRepaint;
    }

    public final void markRepaintNeeded() {
        needRepaint = true;
    }

    boolean processMouseEvent(MouseEvent e) {
        if (!peer.isResizable()) return false;

        boolean isLMB = e.getButton() == MouseEvent.BUTTON1;
        boolean isPressed = e.getID() == MouseEvent.MOUSE_PRESSED;
        boolean isLMBPressed = isLMB && isPressed;

        if (isLMBPressed && peer.isInteractivelyResizable()) {
            Point point = e.getPoint();
            int resizeSide = getResizeEdges(point.x, point.y);
            if (resizeSide != 0) {
                peer.startResize(WLToolkit.getInputState().pointerButtonSerial(), resizeSide);
                // workaround for https://gitlab.gnome.org/GNOME/mutter/-/issues/2523
                WLToolkit.resetPointerInputState();
                return true;
            }
        }

        return false;
    }

    private int getResizeEdges(int x, int y) {
        if (!peer.isInteractivelyResizable()) return 0;
        int edges = 0;
        if (x < RESIZE_EDGE_THICKNESS) {
            edges |= XDG_TOPLEVEL_RESIZE_EDGE_LEFT;
        } else if (x > peer.getWidth() - RESIZE_EDGE_THICKNESS) {
            edges |= XDG_TOPLEVEL_RESIZE_EDGE_RIGHT;
        }
        if (y < RESIZE_EDGE_THICKNESS) {
            edges |= XDG_TOPLEVEL_RESIZE_EDGE_TOP;
        } else if (y > peer.getHeight() - RESIZE_EDGE_THICKNESS) {
            edges |= XDG_TOPLEVEL_RESIZE_EDGE_BOTTOM;
        }
        return edges;
    }

    public Cursor getCursor(int x, int y) {
        int edges = getResizeEdges(x, y);
        if (edges != 0) {
            return Cursor.getPredefinedCursor(RESIZE_CURSOR_TYPES[edges]);
        }

        return null;
    }

    public abstract void dispose();
}
