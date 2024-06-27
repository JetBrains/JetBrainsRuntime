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

import java.awt.*;
import java.util.concurrent.atomic.AtomicBoolean;

import sun.awt.AWTAccessor;
import sun.awt.SunToolkit;

import static sun.awt.windows.WToolkit.getWToolkit;

final class WGlobalCursorManager {
    /**
     * A flag to indicate if the update is scheduled, so we don't process it
     * twice.
     */
    private final AtomicBoolean updatePending = new AtomicBoolean(false);

    /**
     * Sets the cursor to correspond the component currently under mouse.
     *
     * This method should not be executed on the toolkit thread as it
     * calls to user code (e.g. Container.findComponentAt).
     */
    public void updateCursor() {
        updatePending.set(false);
        updateCursorImpl();
    }

    protected native void getCursorPos(Point p);

    protected native void setCursor(Cursor cursor, boolean u);

    protected native Point getLocationOnScreen(Component com);

    /**
     * Schedules updating the cursor on the corresponding event dispatch
     * thread for the given window.
     * This method is called on the toolkit thread as a result of a
     * native update cursor request (e.g. WM_SETCURSOR on Windows).
     */
    public void updateCursorLater(final Component window) {
        if (updatePending.compareAndSet(false, true)) {
            Runnable r = new Runnable() {
                @Override
                public void run() {
                    updateCursor();
                }
            };
            SunToolkit.executeOnEventHandlerThread(window, r);
        }
    }

    public static void nativeUpdateCursor(Component component) {
        getWToolkit().getCursorManager().updateCursorLater(component);
    }

    private void updateCursorImpl() {
        Point cursorPos = new Point();
        getCursorPos(cursorPos);
        final Component c = findComponent(cursorPos);
        if (c == null) {
            return;
        }
        final Cursor cursor;
        final Object peer = WToolkit.targetToPeer(c);
        if (peer instanceof WComponentPeer && c.isShowing()) {
            final WComponentPeer wpeer = (WComponentPeer) peer;
            final Point p = getLocationOnScreen((Component) wpeer.getTarget());
            cursor = wpeer.getCursor(new Point(cursorPos.x - p.x,
                    cursorPos.y - p.y));
        } else {
            cursor = c.getCursor();
        }
        if (cursor != null) {
            setCursor(cursor, true);
        }
    }

    /**
     * Returns the first visible, enabled and showing component under cursor.
     * Returns null for modal blocked windows.
     *
     * @param cursorPos Current cursor position.
     * @return Component or null.
     */
    private Component findComponent(final Point cursorPos) {
        Component c = Component.lastCommonMouseEventComponent;
        if (c != null) {
            if (c instanceof Container && c.isShowing()) {
                final Point p = getLocationOnScreen(c);
                c = AWTAccessor.getContainerAccessor().findComponentAt(
                        (Container) c, cursorPos.x - p.x, cursorPos.y - p.y, false);

            }
            while (c != null) {
                final Object p = AWTAccessor.getComponentAccessor().getPeer(c);
                if (c.isVisible() && c.isEnabled() && p != null) {
                    break;
                }
                c = c.getParent();
            }
        }
        return c;
    }
}
