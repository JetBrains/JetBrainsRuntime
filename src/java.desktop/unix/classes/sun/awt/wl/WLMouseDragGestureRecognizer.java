/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2023, JetBrains s.r.o.. All rights reserved.
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

import sun.awt.dnd.SunDragSourceContextPeer;

import java.awt.Component;
import java.awt.Point;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DragGestureListener;
import java.awt.dnd.DragSource;
import java.awt.dnd.MouseDragGestureRecognizer;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.io.Serial;


public class WLMouseDragGestureRecognizer extends MouseDragGestureRecognizer {
    protected static int motionThreshold;

    @Serial
    private static final long serialVersionUID = -981648340947118946L;

    protected static final int BUTTON_MASK = InputEvent.BUTTON1_DOWN_MASK |
            InputEvent.BUTTON2_DOWN_MASK |
            InputEvent.BUTTON3_DOWN_MASK;

    protected WLMouseDragGestureRecognizer(DragSource ds, Component c, int act, DragGestureListener dgl) {
        super(ds, c, act, dgl);
    }

    protected WLMouseDragGestureRecognizer(DragSource ds, Component c, int act) {
        this(ds, c, act, null);
    }

    /**
     * determine the drop action from the event
     */

    protected int mapDragOperationFromModifiers(MouseEvent e) {
        int mods = e.getModifiersEx();
        int btns = mods & BUTTON_MASK;

        // Do not allow right mouse button drag since Motif DnD does not
        // terminate drag operation on right mouse button release.
        if (!(btns == InputEvent.BUTTON1_DOWN_MASK ||
                btns == InputEvent.BUTTON2_DOWN_MASK)) {
            return DnDConstants.ACTION_NONE;
        }

        return SunDragSourceContextPeer.convertModifiersToDropAction(
                mods,
                getSourceActions());
    }

    public void mouseClicked(MouseEvent e) {
        // do nothing
    }

    public void mousePressed(MouseEvent e) {
        events.clear();

        if (mapDragOperationFromModifiers(e) != DnDConstants.ACTION_NONE) {
            try {
                motionThreshold = DragSource.getDragThreshold();
            } catch (Exception exc) {
                motionThreshold = 5;
            }
            appendEvent(e);
        }
    }

    /**
     * Invoked when a mouse button has been released on a component.
     */

    public void mouseReleased(MouseEvent e) {
        events.clear();
    }

    /**
     * Invoked when the mouse enters a component.
     */

    public void mouseEntered(MouseEvent e) {
        events.clear();
    }

    /**
     * Invoked when the mouse exits a component.
     */

    public void mouseExited(MouseEvent e) {
        if (!events.isEmpty()) { // gesture pending
            int dragAction = mapDragOperationFromModifiers(e);

            if (dragAction == DnDConstants.ACTION_NONE) {
                events.clear();
            }
        }
    }

    /**
     * Invoked when a mouse button is pressed on a component.
     */

    public void mouseDragged(MouseEvent e) {
        if (!events.isEmpty()) { // gesture pending
            int dop = mapDragOperationFromModifiers(e);


            if (dop == DnDConstants.ACTION_NONE) {
                return;
            }

            MouseEvent trigger = (MouseEvent)events.get(0);

            Point origin = trigger.getPoint();
            Point current = e.getPoint();

            int dx = Math.abs(origin.x - current.x);
            int dy = Math.abs(origin.y - current.y);

            if (dx > motionThreshold || dy > motionThreshold) {
                fireDragGestureRecognized(dop, ((MouseEvent)getTriggerEvent()).getPoint());
            } else
                appendEvent(e);
        }
    }

    /**
     * Invoked when the mouse button has been moved on a component
     * (with no buttons no down).
     */

    public void mouseMoved(MouseEvent e) {
        // do nothing
    }
}
