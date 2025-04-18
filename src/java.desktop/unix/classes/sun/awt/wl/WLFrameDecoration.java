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

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.function.Supplier;

public class WLFrameDecoration implements PropertyChangeListener {
    private static final int HEIGHT = 30;
    private static final int BUTTON_ICON_SIZE = 4;
    private static final int BUTTON_CIRCLE_SIZE = 10;
    private static final Font FONT = new Font(Font.DIALOG, Font.BOLD, 12);
    private static final Color ACTIVE_BACKGROUND = new Color(0xebebeb);
    private static final Color ACTIVE_BACKGROUND_DARK = new Color(0x222222);
    private static final Color INACTIVE_BACKGROUND = new Color(0xfafafa);
    private static final Color INACTIVE_BACKGROUND_DARK = new Color(0x2c2c2c);
    private static final Color ICON_BACKGROUND = ACTIVE_BACKGROUND;
    private static final Color ICON_BACKGROUND_DARK = ACTIVE_BACKGROUND_DARK;
    private static final Color ICON_HOVERED_BACKGROUND = new Color(0xd1d1d1);
    private static final Color ICON_HOVERED_BACKGROUND_DARK = new Color(0x373737);
    private static final Color ICON_PRESSED_BACKGROUND = new Color(0xc0c0c0);
    private static final Color ICON_PRESSED_BACKGROUND_DARK = new Color(0x565656);
    private static final Color ACTIVE_FOREGROUND = Color.darkGray;
    private static final Color ACTIVE_FOREGROUND_DARK = new Color(0xf7f7f7);
    private static final Color INACTIVE_FOREGROUND = Color.gray;
    private static final Color INACTIVE_FOREGROUND_DARK = new Color(0xb5b5b5);

    private static final Color ACTIVE_BACKGROUND_TOP = new Color(0xfbfbfb);
    private static final Color ACTIVE_BACKGROUND_TOP_DARK = new Color(0x313131);
    private static final Color INACTIVE_BACKGROUND_TOP = new Color(0xfefefe);
    private static final Color INACTIVE_BACKGROUND_TOP_DARK = new Color(0x3a3a3a);
    private static final Color ACTIVE_BORDER = new Color(0x9e9e9e);
    private static final Color ACTIVE_BORDER_DARK = new Color(0x080808);
    private static final Color INACTIVE_BORDER = new Color(0xbcbcbc);
    private static final Color INACTIVE_BORDER_DARK = new Color(0x121212);

    private static final int BORDER_WIDTH = 1;
    private static final int SIGNIFICANT_DRAG_DISTANCE = 4;
    private static final int RESIZE_EDGE_THICKNESS = 5;

    private static volatile boolean isDarkTheme;
    static {
        Object isDarkThemeProp = Toolkit.getDefaultToolkit().getDesktopProperty("awt.os.theme.isDark");
        isDarkTheme = isDarkThemeProp instanceof Boolean ? (Boolean) isDarkThemeProp : false;
    }

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

    private final WLDecoratedPeer peer;
    private final boolean isUndecorated;


    private final ButtonState closeButton;
    private final ButtonState maximizeButton;
    private final ButtonState minimizeButton;

    private boolean active;
    private boolean pointerInside;
    private boolean pressedInside;
    private Point pressedLocation;

    public WLFrameDecoration(WLDecoratedPeer peer, boolean isUndecorated, boolean showMinimize, boolean showMaximize) {
        this.peer = peer;
        this.isUndecorated = isUndecorated;

        if (isUndecorated) {
            closeButton = null;
            maximizeButton = null;
            minimizeButton = null;
        } else {
            closeButton = new ButtonState(this::getCloseButtonCenter, peer::postWindowClosing);
            maximizeButton = showMaximize ? new ButtonState(this::getMaximizeButtonCenter, this::toggleMaximizedState) : null;
            minimizeButton = showMinimize ? new ButtonState(this::getMinimizeButtonCenter, this::minimizeWindow) : null;
            WLToolkit.getDefaultToolkit().addPropertyChangeListener("awt.os.theme.isDark", this);
        }
    }

    public Insets getInsets() {
        return isUndecorated
                ? new Insets(0, 0, 0, 0)
                : new Insets(HEIGHT + BORDER_WIDTH, BORDER_WIDTH, BORDER_WIDTH, BORDER_WIDTH);
    }

    public Rectangle getBounds() {
        return isUndecorated
                ? new Rectangle(0, 0, 0, 0)
                : new Rectangle(0, 0, peer.getWidth(), HEIGHT);
    }

    public Dimension getMinimumSize() {
        return isUndecorated
                ? new Dimension(0, 0)
                : new Dimension(getButtonSpaceWidth(), HEIGHT);
    }

    private boolean hasMinimizeButton() {
        return minimizeButton != null;
    }

    private boolean hasMaximizeButton() {
        return maximizeButton != null && peer.isResizable();
    }

    private Point getCloseButtonCenter() {
        int width = peer.getWidth();
        return width >= HEIGHT ? new Point(width - HEIGHT / 2, HEIGHT / 2) : null;
    }

    private Point getMaximizeButtonCenter() {
        if (!hasMaximizeButton()) return null;
        int width = peer.getWidth();
        return width >= 2 * HEIGHT ? new Point(width - HEIGHT * 3 / 2, HEIGHT / 2) : null;
    }

    private Point getMinimizeButtonCenter() {
        if (!hasMinimizeButton()) return null;
        int width = peer.getWidth();
        int buttonSpaceWidth = getButtonSpaceWidth();
        return width >= buttonSpaceWidth ? new Point(width - buttonSpaceWidth + HEIGHT / 2, HEIGHT / 2) : null;
    }

    private int getButtonSpaceWidth() {
        final int numButtons = 1
                + (hasMaximizeButton() ? 1 : 0)
                + (hasMinimizeButton() ? 1 : 0);
        return numButtons * HEIGHT;
    }

    private static boolean isDarkTheme() {
        return isDarkTheme;
    }

    private static Color getBackgroundColor(boolean isActive) {
        if (isActive) {
            return isDarkTheme() ? ACTIVE_BACKGROUND_DARK : ACTIVE_BACKGROUND;
        } else {
            return isDarkTheme() ? INACTIVE_BACKGROUND_DARK : INACTIVE_BACKGROUND;
        }
    }

    private static Color getBackgroundTopColor(boolean isActive) {
        if (isActive) {
            return isDarkTheme() ? ACTIVE_BACKGROUND_TOP_DARK : ACTIVE_BACKGROUND_TOP;
        } else {
            return isDarkTheme() ? INACTIVE_BACKGROUND_TOP_DARK : INACTIVE_BACKGROUND_TOP;
        }
    }

    private static Color getBorderColor(boolean isActive) {
        if (isActive) {
            return isDarkTheme() ? ACTIVE_BORDER_DARK : ACTIVE_BORDER;
        } else {
            return isDarkTheme() ? INACTIVE_BORDER_DARK : INACTIVE_BORDER;
        }
    }

    private static Color getIconBackground() {
        return isDarkTheme() ? ICON_BACKGROUND_DARK : ICON_BACKGROUND;
    }

    private static Color getIconHoveredBackground() {
        return isDarkTheme() ? ICON_HOVERED_BACKGROUND_DARK : ICON_HOVERED_BACKGROUND;
    }

    private static Color getIconPressedBackground() {
        return isDarkTheme() ? ICON_PRESSED_BACKGROUND_DARK : ICON_PRESSED_BACKGROUND;
    }

    private static Color getForeground(boolean isActive) {
        if (isActive) {
            return isDarkTheme() ? ACTIVE_FOREGROUND_DARK : ACTIVE_FOREGROUND;
        } else {
            return isDarkTheme() ? INACTIVE_FOREGROUND_DARK : INACTIVE_FOREGROUND;
        }
    }

    public void paint(final Graphics g) {
        if (isUndecorated) return;

        int width = peer.getWidth();
        int height = peer.getHeight();
        if (width <= 0 || height <= 0) return;

        Graphics2D g2d = (Graphics2D) g;
        paintBorder(g2d);
        paintTitleBar(g2d);
        needRepaint = false;
    }

    private void paintBorder(Graphics2D g2d) {
        int width = peer.getWidth();
        int height = peer.getHeight();
        g2d.setColor(getBorderColor(active));
        g2d.setStroke(new BasicStroke(BORDER_WIDTH));
        g2d.drawRect(0, 0, width - BORDER_WIDTH, height - BORDER_WIDTH);
    }

    private void paintTitleBar(Graphics2D g) {
        int width = peer.getWidth();
        String title = peer.getTitle();
        Color foregroundColor = getForeground(active);

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g.setColor(getBackgroundColor(active));
        g.clipRect(0, 0, width, HEIGHT);
        if (g.getDeviceConfiguration().isTranslucencyCapable()
                && peer.getRoundedCornerKind() == WLRoundedCornersManager.RoundedCornerKind.DEFAULT
                && peer.getState() != Frame.MAXIMIZED_BOTH
                && !peer.isFullscreen()) {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
            g.setBackground(new Color(0, true));
            g.clearRect(0, 0, width, HEIGHT);
            int radius = WLRoundedCornersManager.roundCornerRadiusFor(WLRoundedCornersManager.RoundedCornerKind.DEFAULT);
            // The title bar
            g.fillRoundRect(0, 0, width, HEIGHT + radius + 1, radius, radius);

            // The top bevel of the title bar
            g.setColor(getBackgroundTopColor(active));
            g.drawLine(radius / 2, 1, width - radius / 2, 1);
            g.drawArc(1, 1, (radius - 1), (radius - 1), 90, 60);
            g.drawArc(width - radius, 1, (radius - 1), (radius - 1), 45, 45);

            // The border
            var oldStroke = g.getStroke();
            g.setColor(getBorderColor(active));
            g.setStroke(new BasicStroke(BORDER_WIDTH));
            g.drawRoundRect(0, 0, width - BORDER_WIDTH, HEIGHT + radius + 1, radius, radius);
            g.setStroke(oldStroke);
            g.drawLine(0, HEIGHT - 1, width, HEIGHT - 1);
        } else {
            // The title bar
            g.setColor(getBackgroundColor(active));
            g.fillRect(0, 0, width, HEIGHT);

            // The top bevel of the title bar
            g.setColor(getBackgroundTopColor(active));
            g.drawLine(BORDER_WIDTH, BORDER_WIDTH, width - BORDER_WIDTH, BORDER_WIDTH);
            g.drawLine(BORDER_WIDTH, BORDER_WIDTH, BORDER_WIDTH, HEIGHT - BORDER_WIDTH);

            // The border
            var oldStroke = g.getStroke();
            g.setColor(getBorderColor(active));
            g.setStroke(new BasicStroke(BORDER_WIDTH));
            g.drawRect(0, 0, width - BORDER_WIDTH, HEIGHT - BORDER_WIDTH);
            g.setStroke(oldStroke);
        }
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
        g.setClip(null);
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
            g.setColor(state.pressed ? getIconPressedBackground() :
                    state.hovered ? getIconHoveredBackground() : getIconBackground());
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
            g.drawLine(center.x - BUTTON_ICON_SIZE, center.y,
                    center.x, center.y - BUTTON_ICON_SIZE);
            g.drawLine(center.x, center.y - BUTTON_ICON_SIZE,
                    center.x + BUTTON_ICON_SIZE, center.y);
            g.drawLine(center.x - BUTTON_ICON_SIZE, center.y,
                    center.x, center.y + BUTTON_ICON_SIZE);
            g.drawLine(center.x, center.y + BUTTON_ICON_SIZE,
                    center.x + BUTTON_ICON_SIZE, center.y);
        } else {
            g.drawLine(center.x - BUTTON_ICON_SIZE, center.y + BUTTON_ICON_SIZE / 2,
                    center.x, center.y - BUTTON_ICON_SIZE / 2);
            g.drawLine(center.x, center.y - BUTTON_ICON_SIZE / 2,
                    center.x + BUTTON_ICON_SIZE, center.y + BUTTON_ICON_SIZE / 2);
        }
    }

    private void paintMinimizeButton(Graphics2D g, Point center, Color foregroundColor) {
        g.setColor(foregroundColor);
        g.drawLine(center.x - BUTTON_ICON_SIZE, center.y - BUTTON_ICON_SIZE / 2,
                center.x, center.y + BUTTON_ICON_SIZE / 2);
        g.drawLine(center.x, center.y + BUTTON_ICON_SIZE / 2,
                center.x + BUTTON_ICON_SIZE, center.y -  BUTTON_ICON_SIZE / 2);
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

    boolean processMouseEvent(MouseEvent e) {
        if (isUndecorated && !peer.isResizable()) return false;

        final boolean isLMB = e.getButton() == MouseEvent.BUTTON1;
        final boolean isRMB = e.getButton() == MouseEvent.BUTTON3;
        final boolean isPressed = e.getID() == MouseEvent.MOUSE_PRESSED;
        final boolean isLMBPressed = isLMB && isPressed;
        final boolean isRMBPressed = isRMB && isPressed;

        Point point = e.getPoint();
        if (isLMBPressed && peer.isInteractivelyResizable()) {
            int resizeSide = getResizeEdges(point.x, point.y);
            if (resizeSide != 0) {
                peer.startResize(WLToolkit.getInputState().pointerButtonSerial(), resizeSide);
                // workaround for https://gitlab.gnome.org/GNOME/mutter/-/issues/2523
                WLToolkit.resetPointerInputState();
                return true;
            }
        }

        if (isUndecorated) return false;

        if (isRMBPressed && getBounds().contains(e.getX(), e.getY())) {
            peer.showWindowMenu(WLToolkit.getInputState().pointerButtonSerial(), e.getX(), e.getY());
            return true;
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
        if ((closeButton != null && closeButton.processMouseEvent(e)) |
                (maximizeButton != null && maximizeButton.processMouseEvent(e)) |
                (minimizeButton != null && minimizeButton.processMouseEvent(e))) {
            peer.notifyClientDecorationsChanged();
        }
        if (e.getID() == MouseEvent.MOUSE_PRESSED) {
            pressedLocation = point;
        } else if (e.getID() == MouseEvent.MOUSE_DRAGGED && pressedInDragStartArea() && isSignificantDrag(point)) {
            peer.startDrag(WLToolkit.getInputState().pointerButtonSerial());

        } else if (e.getID() == MouseEvent.MOUSE_CLICKED && e.getClickCount() == 2 && pressedInDragStartArea()
                && peer.isFrameStateSupported(Frame.MAXIMIZED_BOTH)) {
            toggleMaximizedState();
        } else if (e.getID() == MouseEvent.MOUSE_MOVED && !pointerInside) {
            peer.updateCursorImmediately();
        }

        return true;
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

    void setActive(boolean active) {
        if (active != this.active) {
            this.active = active;
            peer.notifyClientDecorationsChanged();
        }
    }

    private void toggleMaximizedState() {
        peer.setExtendedState(peer.getState() == Frame.NORMAL ? Frame.MAXIMIZED_BOTH : Frame.NORMAL);
    }

    private void minimizeWindow() {
        peer.setState(Frame.ICONIFIED);
    }

    private volatile boolean needRepaint = true;

    boolean isRepaintNeeded() {
        return !isUndecorated && needRepaint;
    }

    void markRepaintNeeded() {
        needRepaint = true;
    }

    Cursor getCursor(int x, int y) {
        int edges = getResizeEdges(x, y);
        if (edges != 0) {
            return Cursor.getPredefinedCursor(RESIZE_CURSOR_TYPES[edges]);
        }
        if (!isUndecorated && y < HEIGHT) {
            return Cursor.getDefaultCursor();
        }
        return null;
    }

    @Override
    public void propertyChange(PropertyChangeEvent evt) {
        if ("awt.os.theme.isDark".equals(evt.getPropertyName())) {
            Object newValue = evt.getNewValue();
            if (newValue != null) {
                isDarkTheme = (Boolean) newValue;
                peer.notifyClientDecorationsChanged();
            }
        }
    }

    public void dispose() {
        WLToolkit.getDefaultToolkit().removePropertyChangeListener("awt.os.theme.isDark", this);
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
