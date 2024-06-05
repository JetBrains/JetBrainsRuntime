/*
 * Copyright (c) 1996, 2024, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2024, JetBrains s.r.o.. All rights reserved.
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

package sun.awt;

import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Point;

import java.util.concurrent.atomic.AtomicBoolean;

public abstract class CachedCursorManager {

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
    public final void updateCursor() {
        updatePending.set(false);
        updateCursorImpl();
    }

    /**
     * Schedules updating the cursor on the corresponding event dispatch
     * thread for the given window.
     *
     * This method is called on the toolkit thread as a result of a
     * native update cursor request (e.g. WM_SETCURSOR on Windows).
     */
    public final void updateCursorLater(final Component window) {
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

    protected abstract Cursor getCursorByPosition(final Point cursorPos, Component c);

    private void updateCursorImpl() {
        final Point cursorPos = getCursorPosition();
        final Component c = findComponent(cursorPos);
        Cursor cursor = getCursorByPosition(cursorPos, c);

        if (cursor == null) {
            cursor = (c != null) ? c.getCursor() : null;
        }

        if (cursor != null) {
            setCursor(cursor);
        }
    }

    protected abstract Component getComponentUnderCursor();
    protected abstract Point getLocationOnScreen(Component component);

    /**
     * Returns the first visible, enabled and showing component under cursor.
     * Returns null for modal blocked windows.
     *
     * @param cursorPos Current cursor position.
     * @return Component or null.
     */
    protected Component findComponent(final Point cursorPos) {
        Component component = getComponentUnderCursor();
        if (component != null) {
            if (component instanceof Container && component.isShowing()) {
                final Point p = getLocationOnScreen(component);
                component = AWTAccessor.getContainerAccessor().findComponentAt(
                        (Container) component, cursorPos.x - p.x, cursorPos.y - p.y, false);

            }
            while (component != null) {
                final Object p = AWTAccessor.getComponentAccessor().getPeer(component);
                if (component.isVisible() && component.isEnabled() && p != null) {
                    break;
                }
                component = component.getParent();
            }
        }
        return component;
    }

    /**
     * Returns the current cursor position.
     */
    public abstract Point getCursorPosition();

    /**
     * Sets a cursor. The cursor can be null if the mouse is not over a Java
     * window.
     * @param cursor the new {@code Cursor}.
     */
    protected abstract void setCursor(Cursor cursor);
}
