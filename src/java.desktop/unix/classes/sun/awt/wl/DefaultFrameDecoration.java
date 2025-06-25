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

import sun.swing.SwingUtilities2;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;

public class DefaultFrameDecoration extends FullFrameDecorationHelper {
    private static final int HEIGHT = 30;
    private static final int BUTTON_ICON_SIZE = 4;
    private static final int BUTTON_CIRCLE_RADIUS = 10;
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

    private static final int BORDER_SIZE = 1;
    private static final int SIGNIFICANT_DRAG_DISTANCE = 4;

    public DefaultFrameDecoration(WLDecoratedPeer peer, boolean showMinimize, boolean showMaximize) {
        super(peer, showMinimize, showMaximize);
    }

    @Override
    public void notifyThemeChanged() {
        // Nothing to do as we repaint everything in full each time so the new theme
        // gets "applied" automatically as long as isDarkTheme() returns the correct value.
    }

    @Override
    public Insets getContentInsets() {
        return new Insets(HEIGHT + BORDER_SIZE, BORDER_SIZE, BORDER_SIZE, BORDER_SIZE);
    }

    @Override
    public Rectangle getTitleBarBounds() {
        return new Rectangle(0, 0, peer.getWidth(), HEIGHT);
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(getButtonSpaceWidth(), HEIGHT);
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

    @Override
    protected Rectangle getCloseButtonBounds() {
        int width = peer.getWidth();
        if (width >= HEIGHT) {
            return new Rectangle(width - HEIGHT / 2 - BUTTON_CIRCLE_RADIUS,
                    HEIGHT / 2 - BUTTON_CIRCLE_RADIUS,
                    BUTTON_CIRCLE_RADIUS * 2,
                    BUTTON_CIRCLE_RADIUS * 2);
        } else {
            return null;
        }
    }

    @Override
    protected Rectangle getMaximizeButtonBounds() {
        if (!hasMaximizeButton()) return null;
        int width = peer.getWidth();
        if (width >= 2 * HEIGHT) {
            return new Rectangle(width - HEIGHT * 3 / 2 - BUTTON_CIRCLE_RADIUS,
                    HEIGHT / 2 - BUTTON_CIRCLE_RADIUS,
                    BUTTON_CIRCLE_RADIUS * 2,
                    BUTTON_CIRCLE_RADIUS * 2);
        } else {
            return null;
        }
    }

    @Override
    protected Rectangle getMinimizeButtonBounds() {
        if (!hasMinimizeButton()) return null;
        int width = peer.getWidth();
        int buttonSpaceWidth = getButtonSpaceWidth();
        if (width >= buttonSpaceWidth) {
            return new Rectangle(width - buttonSpaceWidth + HEIGHT / 2 - BUTTON_CIRCLE_RADIUS,
                    HEIGHT / 2 - BUTTON_CIRCLE_RADIUS,
                    BUTTON_CIRCLE_RADIUS * 2,
                    BUTTON_CIRCLE_RADIUS * 2);
        } else {
            return null;
        }
    }

    @Override
    protected boolean isSignificantDragDistance(Point p1, Point p2) {
        return p1.distance(p2) > SIGNIFICANT_DRAG_DISTANCE;
    }

    @Override
    protected boolean pressedInDragStartArea(Point p) {
        return p != null && p.y >= 0 && p.y < HEIGHT && p.x >= 0 && p.x < peer.getWidth() - getButtonSpaceWidth();
    }

    private int getButtonSpaceWidth() {
        final int numButtons = 1
                + (hasMaximizeButton() ? 1 : 0)
                + (hasMinimizeButton() ? 1 : 0);
        return numButtons * HEIGHT;
    }

    private Color getBackgroundColor(boolean isActive) {
        if (isActive) {
            return isDarkTheme() ? ACTIVE_BACKGROUND_DARK : ACTIVE_BACKGROUND;
        } else {
            return isDarkTheme() ? INACTIVE_BACKGROUND_DARK : INACTIVE_BACKGROUND;
        }
    }

    private Color getBackgroundTopColor(boolean isActive) {
        if (isActive) {
            return isDarkTheme() ? ACTIVE_BACKGROUND_TOP_DARK : ACTIVE_BACKGROUND_TOP;
        } else {
            return isDarkTheme() ? INACTIVE_BACKGROUND_TOP_DARK : INACTIVE_BACKGROUND_TOP;
        }
    }

    private Color getBorderColor(boolean isActive) {
        if (isActive) {
            return isDarkTheme() ? ACTIVE_BORDER_DARK : ACTIVE_BORDER;
        } else {
            return isDarkTheme() ? INACTIVE_BORDER_DARK : INACTIVE_BORDER;
        }
    }

    private Color getIconBackground() {
        return isDarkTheme() ? ICON_BACKGROUND_DARK : ICON_BACKGROUND;
    }

    private Color getIconHoveredBackground() {
        return isDarkTheme() ? ICON_HOVERED_BACKGROUND_DARK : ICON_HOVERED_BACKGROUND;
    }

    private Color getIconPressedBackground() {
        return isDarkTheme() ? ICON_PRESSED_BACKGROUND_DARK : ICON_PRESSED_BACKGROUND;
    }

    private Color getForeground(boolean isActive) {
        if (isActive) {
            return isDarkTheme() ? ACTIVE_FOREGROUND_DARK : ACTIVE_FOREGROUND;
        } else {
            return isDarkTheme() ? INACTIVE_FOREGROUND_DARK : INACTIVE_FOREGROUND;
        }
    }

    @Override
    protected void paintBorder(Graphics2D g2d) {
        int width = peer.getWidth();
        int height = peer.getHeight();
        g2d.setColor(getBorderColor(isActive()));
        g2d.setStroke(new BasicStroke(BORDER_SIZE));
        g2d.drawRect(0, 0, width - BORDER_SIZE, height - BORDER_SIZE);
    }

    @Override
    protected void paintTitleBar(Graphics2D g) {
        boolean active = isActive();
        int width = peer.getWidth();
        String title = peer.getTitle();
        Color foregroundColor = getForeground(active);

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g.setColor(getBackgroundColor(active));
        g.clipRect(0, 0, width, HEIGHT);
        if (g.getDeviceConfiguration().isTranslucencyCapable()
                && peer.getRoundedCornerKind() == WLRoundedCornersManager.RoundedCornerKind.DEFAULT
                && !isMaximized()
                && !isFullscreen()) {
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
            g.setStroke(new BasicStroke(BORDER_SIZE));
            g.drawRoundRect(0, 0, width - BORDER_SIZE, HEIGHT + radius + 1, radius, radius);
            g.setStroke(oldStroke);
            g.drawLine(0, HEIGHT - 1, width, HEIGHT - 1);
        } else {
            // The title bar
            g.setColor(getBackgroundColor(active));
            g.fillRect(0, 0, width, HEIGHT);

            // The top bevel of the title bar
            g.setColor(getBackgroundTopColor(active));
            g.drawLine(BORDER_SIZE, BORDER_SIZE, width - BORDER_SIZE, BORDER_SIZE);
            g.drawLine(BORDER_SIZE, BORDER_SIZE, BORDER_SIZE, HEIGHT - BORDER_SIZE);

            // The border
            var oldStroke = g.getStroke();
            g.setColor(getBorderColor(active));
            g.setStroke(new BasicStroke(BORDER_SIZE));
            g.drawRect(0, 0, width - BORDER_SIZE, HEIGHT - BORDER_SIZE);
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
        int leftMargin = HEIGHT / 2 - BUTTON_CIRCLE_RADIUS; // same as space between close button and right window edge
        int availableWidth = width - getButtonSpaceWidth() - leftMargin;
        String text = SwingUtilities2.clipStringIfNecessary(null, fm, title, availableWidth);
        int textWidth = fm.stringWidth(text);
        g.drawString(text,
                Math.min((width - textWidth) / 2, availableWidth - textWidth),
                (HEIGHT - fm.getHeight()) / 2 + fm.getAscent());
    }

    private void paintButtonBackground(Graphics2D g, Point center, ButtonState state) {
        if (isActive()) {
            g.setColor(state.pressed ? getIconPressedBackground() :
                    state.hovered ? getIconHoveredBackground() : getIconBackground());
            g.fill(new Ellipse2D.Float(center.x - BUTTON_CIRCLE_RADIUS + .5f,
                    center.y - BUTTON_CIRCLE_RADIUS + .5f,
                    2 * BUTTON_CIRCLE_RADIUS, 2 * BUTTON_CIRCLE_RADIUS));
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
}
