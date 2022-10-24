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

import sun.swing.SwingUtilities2;

import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Ellipse2D;
import java.awt.image.VolatileImage;
import java.util.function.Supplier;

public class WLFrameDecoration {
    private static final int HEIGHT = 30;
    private static final int BUTTON_ICON_SIZE = 3;
    private static final int BUTTON_CIRCLE_SIZE = 9;
    private static final int BUTTON_MAXIMIZED_LINE_GAP = 2;
    private static final Font FONT = new Font(Font.DIALOG, Font.BOLD, 12);
    private static final Color BACKGROUND = Color.white;
    private static final Color ICON_BACKGROUND = new Color(0xe8e8e8);
    private static final Color ICON_HOVERED_BACKGROUND = new Color(0xe0e0e0);
    private static final Color ICON_PRESSED_BACKGROUND = Color.lightGray;
    private static final Color ACTIVE_FOREGROUND = Color.darkGray;
    private static final Color INACTIVE_FOREGROUND = Color.gray;
    private static final int SIGNIFICANT_DRAG_DISTANCE = 4;
    private static final int RESIZE_EDGE_THICKNESS = 5;

    private static final int XDG_TOPLEVEL_RESIZE_EDGE_TOP = 1;
    private static final int XDG_TOPLEVEL_RESIZE_EDGE_BOTTOM = 2;
    private static final int XDG_TOPLEVEL_RESIZE_EDGE_LEFT = 4;
    private static final int XDG_TOPLEVEL_RESIZE_EDGE_RIGHT = 8;

    private final WLFramePeer peer;
    private final ButtonState closeButton;
    private final ButtonState maximizeButton;
    private final ButtonState minimizeButton;

    private VolatileImage buffer;
    private boolean active;
    private boolean pointerInside;
    private boolean pressedInside;
    private Point pressedLocation;

    public WLFrameDecoration(WLFramePeer peer) {
        this.peer = peer;
        closeButton = new ButtonState(this::getCloseButtonCenter, peer::postWindowClosing);
        maximizeButton = new ButtonState(this::getMaximizeButtonCenter, this::toggleMaximizedState);
        minimizeButton = new ButtonState(this::getMinimizeButtonCenter, this::minimizeWindow);
    }

    private Frame getFrame() {
        return (Frame) peer.target;
    }

    public Insets getInsets() {
        return new Insets(HEIGHT, 0, 0, 0);
    }

    public Rectangle getBounds() {
        return new Rectangle(0, 0, peer.getWidth(), HEIGHT);
    }

    private Point getCloseButtonCenter() {
        int width = peer.getWidth();
        return width >= HEIGHT ? new Point(width - HEIGHT / 2, HEIGHT / 2) : null;
    }

    private Point getMaximizeButtonCenter() {
        if (!getFrame().isResizable()) return null;
        int width = peer.getWidth();
        return width >= 2 * HEIGHT ? new Point(width - HEIGHT * 3 / 2, HEIGHT / 2) : null;
    }

    private Point getMinimizeButtonCenter() {
        int width = peer.getWidth();
        int buttonSpaceWidth = getButtonSpaceWidth();
        return width >= buttonSpaceWidth ? new Point(width - buttonSpaceWidth + HEIGHT / 2, HEIGHT / 2) : null;
    }

    private int getButtonSpaceWidth() {
        return (getFrame().isResizable() ? 3 : 2) * HEIGHT;
    }

    public void paint(final Graphics g) {
        int width = peer.getWidth();
        int height = peer.getHeight();
        if (width <= 0 || height <= 0) return;
        Graphics2D g2d = (Graphics2D) g.create(0, 0, width, HEIGHT);
        try {
            doPaint(g2d);
        } finally {
            g2d.dispose();
        }
    }

    private void doPaint(Graphics2D g) {
        int width = peer.getWidth();
        String title = getFrame().getTitle();
        Color foregroundColor = active ? ACTIVE_FOREGROUND : INACTIVE_FOREGROUND;

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g.setColor(BACKGROUND);
        g.fillRect(0, 0, width, HEIGHT);

        paintTitle(g, title, foregroundColor, width);

        Point closeButtonCenter = getCloseButtonCenter();
        if (closeButtonCenter != null) {
            paintButtonBackground(g, closeButtonCenter, closeButton);
            paintCloseButton(g, closeButtonCenter, foregroundColor);
        }
        Point maximizedButtonCenter = getMaximizeButtonCenter();
        if (maximizedButtonCenter != null) {
            paintButtonBackground(g, maximizedButtonCenter, maximizeButton);
            paintMaximizeButton(g, maximizedButtonCenter, foregroundColor);
        }
        Point minimizedButtonCenter = getMinimizeButtonCenter();
        if (minimizedButtonCenter != null) {
            paintButtonBackground(g, minimizedButtonCenter, minimizeButton);
            paintMinimizeButton(g, minimizedButtonCenter, foregroundColor);
        }
    }

    private void paintTitle(Graphics2D g, String title, Color foregroundColor, int width) {
        g.setColor(foregroundColor);
        g.setFont(FONT);
        FontMetrics fm = g.getFontMetrics();
        int leftMargin = HEIGHT / 2 - BUTTON_CIRCLE_SIZE; // same as space between close button and right window edge
        int availableWidth = width - getButtonSpaceWidth() - leftMargin;
        String text = SwingUtilities2.clipStringIfNecessary(null, fm, title, availableWidth);
        int textWidth = fm.stringWidth(text);
        g.drawString(text,
                Math.min((width - textWidth) / 2, availableWidth - textWidth),
                (HEIGHT - fm.getHeight()) / 2 + fm.getAscent());
    }

    private void paintButtonBackground(Graphics2D g, Point center, ButtonState state) {
        if (active) {
            g.setColor(state.pressed ? ICON_PRESSED_BACKGROUND :
                    state.hovered ? ICON_HOVERED_BACKGROUND : ICON_BACKGROUND);
            g.fill(new Ellipse2D.Float(center.x - BUTTON_CIRCLE_SIZE + .5f,
                    center.y - BUTTON_CIRCLE_SIZE + .5f,
                    2 * BUTTON_CIRCLE_SIZE, 2 * BUTTON_CIRCLE_SIZE));
        }
    }

    private void paintCloseButton(Graphics2D g, Point center, Color foregroundColor) {
        g.setColor(foregroundColor);
        g.drawLine(center.x - BUTTON_ICON_SIZE, center.y - BUTTON_ICON_SIZE,
                center.x + BUTTON_ICON_SIZE, center.y + BUTTON_ICON_SIZE);
        g.drawLine(center.x - BUTTON_ICON_SIZE, center.y + BUTTON_ICON_SIZE,
                center.x + BUTTON_ICON_SIZE, center.y - BUTTON_ICON_SIZE);
    }

    private void paintMaximizeButton(Graphics2D g, Point center, Color foregroundColor) {
        g.setColor(foregroundColor);
        if (peer.getState() == Frame.MAXIMIZED_BOTH) {
            g.drawRect(center.x - BUTTON_ICON_SIZE, center.y - BUTTON_ICON_SIZE + BUTTON_MAXIMIZED_LINE_GAP,
                    2 * BUTTON_ICON_SIZE - BUTTON_MAXIMIZED_LINE_GAP, 2 * BUTTON_ICON_SIZE - BUTTON_MAXIMIZED_LINE_GAP);
            g.drawLine(center.x - BUTTON_ICON_SIZE + BUTTON_MAXIMIZED_LINE_GAP, center.y - BUTTON_ICON_SIZE,
                    center.x + BUTTON_ICON_SIZE, center.y - BUTTON_ICON_SIZE);
            g.drawLine(center.x + BUTTON_ICON_SIZE, center.y - BUTTON_ICON_SIZE,
                    center.x + BUTTON_ICON_SIZE, center.y + BUTTON_ICON_SIZE - BUTTON_MAXIMIZED_LINE_GAP);
        } else {
            g.drawRect(center.x - BUTTON_ICON_SIZE, center.y - BUTTON_ICON_SIZE,
                    2 * BUTTON_ICON_SIZE, 2 * BUTTON_ICON_SIZE);
        }
    }

    private void paintMinimizeButton(Graphics2D g, Point center, Color foregroundColor) {
        g.setColor(foregroundColor);
        g.drawLine(center.x - BUTTON_ICON_SIZE, center.y + BUTTON_ICON_SIZE,
                center.x + BUTTON_ICON_SIZE, center.y + BUTTON_ICON_SIZE);
    }

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
        return pressedLocation != null && pressedLocation.distance(p) > SIGNIFICANT_DRAG_DISTANCE;
    }

    private boolean pressedInDragStartArea() {
        return pressedLocation != null &&
                pressedLocation.y >= 0 &&
                pressedLocation.y < HEIGHT &&
                pressedLocation.x >= 0 &&
                pressedLocation.x < peer.getWidth() - getButtonSpaceWidth();
    }

    void processMouseEvent(MouseEvent e) {
        Point point = e.getPoint();
        if (e.getID() == MouseEvent.MOUSE_PRESSED && getFrame().isResizable()) {
            int resizeSide = getResizeEdges(point);
            if (resizeSide != 0) {
                peer.startResize(resizeSide);
                return;
            }
        }
        boolean pointerInside = e.getY() >= HEIGHT && e.getID() != MouseEvent.MOUSE_EXITED ||
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
        if (closeButton.processMouseEvent(e) |
            maximizeButton.processMouseEvent(e) |
            minimizeButton.processMouseEvent(e)) {
            peer.postPaintEventForClientDecorations();
        }
        if (e.getID() == MouseEvent.MOUSE_PRESSED) {
            pressedLocation = point;
        } else if (e.getID() == MouseEvent.MOUSE_DRAGGED && pressedInDragStartArea() && isSignificantDrag(point)) {
            peer.startDrag();
        } else if (e.getID() == MouseEvent.MOUSE_CLICKED && e.getClickCount() == 2 && pressedInDragStartArea()
                && getFrame().isResizable()) {
            toggleMaximizedState();
        }
    }

    private int getResizeEdges(Point p) {
        if (!getFrame().isResizable()) return 0;
        int edges = 0;
        if (p.x < RESIZE_EDGE_THICKNESS) {
            edges |= XDG_TOPLEVEL_RESIZE_EDGE_LEFT;
        } else if (p.x > peer.getWidth() - RESIZE_EDGE_THICKNESS) {
            edges |= XDG_TOPLEVEL_RESIZE_EDGE_RIGHT;
        }
        if (p.y < RESIZE_EDGE_THICKNESS) {
            edges |= XDG_TOPLEVEL_RESIZE_EDGE_TOP;
        } else if (p.y > peer.getHeight() - RESIZE_EDGE_THICKNESS) {
            edges |= XDG_TOPLEVEL_RESIZE_EDGE_BOTTOM;
        }
        return edges;
    }

    void setActive(boolean active) {
        if (active != this.active) {
            this.active = active;
            peer.postPaintEventForClientDecorations();
        }
    }

    private void toggleMaximizedState() {
        getFrame().setExtendedState(peer.getState() == Frame.NORMAL ? Frame.MAXIMIZED_BOTH : Frame.NORMAL);
    }

    private void minimizeWindow() {
        getFrame().setState(Frame.ICONIFIED);
    }

    private static class ButtonState {
        private final Supplier<Point> location;
        private final Runnable action;
        private boolean hovered;
        private boolean pressed;

        private ButtonState(Supplier<Point> location, Runnable action) {
            this.location = location;
            this.action = action;
        }

        private boolean processMouseEvent(MouseEvent e) {
            Point buttonCenter = location.get();
            boolean ourLocation = buttonCenter != null && e.getID() != MouseEvent.MOUSE_EXITED &&
                    Math.abs(buttonCenter.x - e.getX()) <= BUTTON_CIRCLE_SIZE &&
                    Math.abs(buttonCenter.y - e.getY()) <= BUTTON_CIRCLE_SIZE;
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
