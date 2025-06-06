/*
 * Copyright 2025 JetBrains s.r.o.
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
import java.awt.dnd.DragGestureListener;
import java.awt.dnd.DragSource;
import java.awt.dnd.MouseDragGestureRecognizer;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.io.Serial;

public class WLMouseDragGestureRecognizer extends MouseDragGestureRecognizer {
    @Serial
    private static final long serialVersionUID = -175348046815052547L;

    private int motionThreshold;

    protected WLMouseDragGestureRecognizer(DragSource ds, Component c, int act, DragGestureListener dgl) {
        super(ds, c, act, dgl);
    }

    protected WLMouseDragGestureRecognizer(DragSource ds, Component c, int act) {
        super(ds, c, act);
    }

    protected WLMouseDragGestureRecognizer(DragSource ds, Component c) {
        super(ds, c);
    }

    protected WLMouseDragGestureRecognizer(DragSource ds) {
        super(ds);
    }

    private int getDropAction(MouseEvent e) {
        return SunDragSourceContextPeer.convertModifiersToDropAction(e.getModifiersEx(), getSourceActions());
    }

    @Override
    public void mousePressed(MouseEvent e) {
        events.clear();

        if (getDropAction(e) != 0) {
            motionThreshold = DragSource.getDragThreshold();
            appendEvent(e);
        }
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        events.clear();
    }

    @Override
    public void mouseEntered(MouseEvent e) {
        events.clear();
    }

    @Override
    public void mouseExited(MouseEvent e) {
        if (events.isEmpty()) {
            return;
        }

        if (getDropAction(e) == 0) {
            events.clear();
        }
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        if (events.isEmpty()) {
            return;
        }

        int dropAction = getDropAction(e);
        if (dropAction == 0) {
            return;
        }

        var trigger = (MouseEvent)getTriggerEvent();
        if (trigger.getPoint().distanceSq(e.getPoint()) >= motionThreshold * motionThreshold) {
            fireDragGestureRecognized(dropAction, trigger.getPoint());
        } else {
            appendEvent(e);
        }
    }
}
