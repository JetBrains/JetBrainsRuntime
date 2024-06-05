/*
 * Copyright (c) 1999, 2014, Oracle and/or its affiliates. All rights reserved.
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

package sun.awt.windows;

import java.awt.Component;
import java.awt.Cursor;
import java.awt.Point;

import sun.awt.CachedCursorManager;

import static sun.awt.windows.WToolkit.getWToolkit;

final class WGlobalCursorManager extends CachedCursorManager {
    private Component lastMouseEventComponent;

    private WGlobalCursorManager() {
    }

    public void setMouseEventComponent(Component component) {
        lastMouseEventComponent = component;
    }

    public Component getMouseEventComponent() {
        return lastMouseEventComponent;
    }

    private static final WGlobalCursorManager theInstance = new WGlobalCursorManager();
    public static WGlobalCursorManager getInstance() {
        return theInstance;
    }

    private native void getCursorPos(Point p);
    private native void setCursor(Cursor cursor, boolean u);
    @Override
    protected native Point getLocationOnScreen(Component component);

    @Override
    protected Cursor getCursorByPosition(Point cursorPos, Component c) {
        final Object peer = WToolkit.targetToPeer(c);
        if (peer instanceof WComponentPeer && c.isShowing()) {
            final WComponentPeer wpeer = (WComponentPeer) peer;
            final Point p = getLocationOnScreen((Component) wpeer.getTarget());
            return wpeer.getCursor(new Point(cursorPos.x - p.x,
                    cursorPos.y - p.y));
        }
        return null;
    }

    public static void nativeUpdateCursor(Component component) {
        getInstance().updateCursorLater(component);
    }

    @Override
    protected Component getComponentUnderCursor() {
        return lastMouseEventComponent;
    }

    @Override
    public Point getCursorPosition() {
        Point cursorPos = new Point();
        getCursorPos(cursorPos);
        return cursorPos;
    }

    @Override
    protected void setCursor(Cursor cursor) {
        setCursor(cursor, true);
    }
}
