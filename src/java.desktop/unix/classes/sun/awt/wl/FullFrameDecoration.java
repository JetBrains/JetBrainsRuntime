/*
 * Copyright 2022, 2025, JetBrains s.r.o.
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
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.Objects;
import java.util.function.Supplier;

public abstract class FullFrameDecoration extends FrameDecoration {
    private volatile boolean isDarkTheme;

    private PropertyChangeListener pcl;

    protected final ButtonState closeButton;
    protected final ButtonState maximizeButton;
    protected final ButtonState minimizeButton;

    private boolean pointerInside;
    private boolean pressedInside;
    private Point pressedLocation;

    public FullFrameDecoration(WLDecoratedPeer peer, boolean showMinimize, boolean showMaximize) {
        super(peer);

        closeButton = new ButtonState(this::getCloseButtonBounds, peer::postWindowClosing);
        maximizeButton = showMaximize ? new ButtonState(this::getMaximizeButtonBounds, this::toggleMaximizedState) : null;
        minimizeButton = showMinimize ? new ButtonState(this::getMinimizeButtonBounds, this::minimizeWindow) : null;
        pcl = new PropertyChangeListener() {
            @Override
            public void propertyChange(PropertyChangeEvent evt) {
                if ("awt.os.theme.isDark".equals(evt.getPropertyName())) {
                    Object newValue = evt.getNewValue();
                    if (newValue != null) {
                        isDarkTheme = (Boolean) newValue;
                        notifyThemeChanged();
                        peer.notifyClientDecorationsChanged();
                    }
                }
            }
        };
        WLToolkit.getDefaultToolkit().addPropertyChangeListener("awt.os.theme.isDark", pcl);
    }

    public abstract void notifyThemeChanged();

    protected final boolean hasMinimizeButton() {
        return minimizeButton != null;
    }

    protected final boolean hasMaximizeButton() {
        return maximizeButton != null && peer.isResizable();
    }

    protected abstract Rectangle getCloseButtonBounds();

    protected abstract Rectangle getMaximizeButtonBounds();

    protected abstract Rectangle getMinimizeButtonBounds();

    public final boolean isDarkTheme() {
        return isDarkTheme;
    }

    @Override
    public void paint(Graphics g) {
        assert isRepaintNeeded();

        int width = peer.getWidth();
        int height = peer.getHeight();
        if (width <= 0 || height <= 0) return;

        Graphics2D g2d = (Graphics2D) g;
        paintBorder(g2d);
        paintTitleBar(g2d);

        markRepaintNeeded(false);
    }

    protected abstract void paintBorder(Graphics2D g2d);
    protected abstract void paintTitleBar(Graphics2D g2d);

    @SuppressWarnings("deprecation")
    private static MouseEvent convertEvent(MouseEvent e, int newId) {
        return new MouseEvent(e.getComponent(),
                newId,
                e.getWhen(),
                e.getModifiers() | e.getModifiersEx(),
                e.getX(),
                e.getY(),
                e.getXOnScreen(),
                e.getYOnScreen(),
                e.getClickCount(),
                e.isPopupTrigger(),
                e.getButton());
    }

    private boolean isSignificantDrag(Point p) {
        Objects.requireNonNull(p);
        return pressedLocation != null && isSignificantDragDistance(pressedLocation, p);
    }

    protected abstract boolean isSignificantDragDistance(Point p1, Point p2);

    protected abstract boolean pressedInDragStartArea(Point p);

    @Override
    boolean processMouseEvent(MouseEvent e) {
        if (super.processMouseEvent(e)) return true;

        final boolean isLMB = e.getButton() == MouseEvent.BUTTON1;
        final boolean isRMB = e.getButton() == MouseEvent.BUTTON3;
        final boolean isPressed = e.getID() == MouseEvent.MOUSE_PRESSED;
        final boolean isRMBPressed = isRMB && isPressed;

        Point point = e.getPoint();
        if (isRMBPressed && getTitleBarBounds().contains(point.x, point.y)) {
            peer.showWindowMenu(WLToolkit.getInputState().pointerButtonSerial(), point.x, point.y);
            return true;
        }

        Rectangle bounds = getTitleBarBounds();
        boolean pointerInside = point.y >= bounds.height && e.getID() != MouseEvent.MOUSE_EXITED ||
                pressedInside && e.getID() == MouseEvent.MOUSE_DRAGGED;
        if (pointerInside && !this.pointerInside && e.getID() != MouseEvent.MOUSE_ENTERED) {
            WLToolkit.postEvent(convertEvent(e, MouseEvent.MOUSE_ENTERED));
        }
        if (pointerInside || this.pointerInside && e.getID() == MouseEvent.MOUSE_EXITED) {
            WLToolkit.postEvent(e);
        }
        if (!pointerInside && this.pointerInside && e.getID() != MouseEvent.MOUSE_EXITED) {
            WLToolkit.postEvent(convertEvent(e, MouseEvent.MOUSE_EXITED));
        }
        this.pointerInside = pointerInside;
        if (e.getID() == MouseEvent.MOUSE_DRAGGED) {
            pressedInside = pointerInside;
        }
        if ((closeButton != null && closeButton.processMouseEvent(e)) |
                (maximizeButton != null && maximizeButton.processMouseEvent(e)) |
                (minimizeButton != null && minimizeButton.processMouseEvent(e))) {
            peer.notifyClientDecorationsChanged();
        }
        if (e.getID() == MouseEvent.MOUSE_PRESSED) {
            pressedLocation = point;
        } else if (e.getID() == MouseEvent.MOUSE_DRAGGED && pressedInDragStartArea(point) && isSignificantDrag(point)) {
            peer.startDrag(WLToolkit.getInputState().pointerButtonSerial());

        } else if (e.getID() == MouseEvent.MOUSE_CLICKED && e.getClickCount() == 2 && pressedInDragStartArea(point)
                && peer.isFrameStateSupported(Frame.MAXIMIZED_BOTH)) {
            toggleMaximizedState();
        } else if (e.getID() == MouseEvent.MOUSE_MOVED && !pointerInside) {
            peer.updateCursorImmediately();
        }

        return true;
    }

    private void toggleMaximizedState() {
        peer.setExtendedState(peer.getState() == Frame.NORMAL ? Frame.MAXIMIZED_BOTH : Frame.NORMAL);
    }

    private void minimizeWindow() {
        peer.setState(Frame.ICONIFIED);
    }

    @Override
    public Cursor cursorAt(int x, int y) {
        var superCursor = super.cursorAt(x, y);
        if (superCursor != null) return superCursor;

        Rectangle bounds = getTitleBarBounds();
        if (y < bounds.height) {
            return Cursor.getDefaultCursor();
        }
        return null;
    }

    @Override
    public void dispose() {
        WLToolkit.getDefaultToolkit().removePropertyChangeListener("awt.os.theme.isDark", pcl);
    }

    protected static class ButtonState {
        private final Supplier<Rectangle> bounds;
        private final Runnable action;
        boolean hovered;
        boolean pressed;

        private ButtonState(Supplier<Rectangle> bounds, Runnable action) {
            this.bounds = bounds;
            this.action = action;
        }

        private boolean processMouseEvent(MouseEvent e) {
            Rectangle buttonBounds = bounds.get();
            boolean ourLocation = buttonBounds != null && e.getID() != MouseEvent.MOUSE_EXITED &&
                    buttonBounds.contains(e.getPoint());
            boolean oldHovered = hovered;
            boolean oldPressed = pressed;
            hovered = ourLocation;
            if (ourLocation && e.getID() == MouseEvent.MOUSE_PRESSED) {
                pressed = true;
            } else if (e.getID() == MouseEvent.MOUSE_RELEASED) {
                pressed = false;
            } else if (ourLocation && e.getID() == MouseEvent.MOUSE_CLICKED) {
                action.run();
            }
            return oldPressed != pressed || !pressed && oldHovered != hovered;
        }
    }
}
