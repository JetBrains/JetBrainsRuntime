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
    private static final int BORDER_SIZE = 1;
    private static final int HEIGHT = 28 + BORDER_SIZE;
    private static final int BUTTON_ICON_SIZE = 4;
    private static final int BUTTON_SIZE = 16;
    private static final int BUTTONS_RIGHT_PADDING = 7;
    private static final int BUTTONS_PADDING = 8;
    private static final Font FONT = new Font(Font.DIALOG, Font.BOLD, 12);
    private static final Color ACTIVE_BACKGROUND = new Color(0xedeeef);
    private static final Color ACTIVE_BACKGROUND_DARK = new Color(0x31363b);
    private static final Color INACTIVE_BACKGROUND = new Color(0xdcddde);
    private static final Color INACTIVE_BACKGROUND_DARK = new Color(0x292d31);
    private static final Color ICON_BACKGROUND = ACTIVE_BACKGROUND;
    private static final Color ICON_BACKGROUND_DARK = ACTIVE_BACKGROUND_DARK;
    private static final Color ICON_HOVERED_BACKGROUND = new Color(0x232629);
    private static final Color ICON_HOVERED_BACKGROUND_DARK = new Color(0xfcfcfc);
    private static final Color ICON_HOVERED_FOREGROUND = new Color(0xcacdcf);
    private static final Color ICON_HOVERED_FOREGROUND_DARK = new Color(0x43484c);
    private static final Color ICON_PRESSED_BACKGROUND = new Color(0xa6a8ab);
    private static final Color ICON_PRESSED_BACKGROUND_DARK = new Color(0x6e7175);
    private static final Color CLOSE_ICON_PRESSED_BACKGROUND = new Color(0x6d2229);
    private static final Color CLOSE_ICON_PRESSED_BACKGROUND_DARK = new Color(0x6d2229);
    private static final Color CLOSE_ICON_HOVERED_BACKGROUND = new Color(0xff98a2);
    private static final Color CLOSE_ICON_HOVERED_INACTIVE_BACKGROUND = new Color(0xda4453);
    private static final Color CLOSE_ICON_HOVERED_INACTIVE_BACKGROUND_DARK = new Color(0xda4453);
    private static final Color CLOSE_ICON_HOVERED_BACKGROUND_DARK = new Color(0xff98a2);
    private static final Color ACTIVE_FOREGROUND = new Color(0x2d3033);
    private static final Color ACTIVE_FOREGROUND_DARK = new Color(0xf1f1f1);
    private static final Color INACTIVE_FOREGROUND = ACTIVE_FOREGROUND;
    private static final Color INACTIVE_FOREGROUND_DARK = ACTIVE_FOREGROUND_DARK;

    private static final Color ACTIVE_BACKGROUND_TOP = new Color(0xa9abac);
    private static final Color ACTIVE_BACKGROUND_TOP_DARK = new Color(0x4c565f);
    private static final Color INACTIVE_BACKGROUND_TOP = new Color(0xb7b8b9);
    private static final Color INACTIVE_BACKGROUND_TOP_DARK = new Color(0x424952);
    private static final Color ACTIVE_BORDER = ACTIVE_BACKGROUND_TOP;
    private static final Color ACTIVE_BORDER_DARK = ACTIVE_BACKGROUND_TOP_DARK;
    private static final Color INACTIVE_BORDER = INACTIVE_BACKGROUND_TOP;
    private static final Color INACTIVE_BORDER_DARK = INACTIVE_BACKGROUND_TOP_DARK;
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

    @Override
    protected Rectangle getCloseButtonBounds() {
        int x = peer.getWidth() - BUTTON_SIZE - BUTTONS_RIGHT_PADDING - BORDER_SIZE;
        int y = (int) Math.floor((HEIGHT - BUTTON_SIZE + 1f) / 2);
        return new Rectangle(x, y, BUTTON_SIZE, BUTTON_SIZE);
    }

    @Override
    protected Rectangle getMaximizeButtonBounds() {
        if (!hasMaximizeButton()) return null;

        int x = peer.getWidth() - BUTTON_SIZE * 2 - BUTTONS_RIGHT_PADDING
                - BUTTONS_PADDING - BORDER_SIZE;
        int y = (int) Math.floor((HEIGHT - BUTTON_SIZE + 1f) / 2);
        return x > 0 ? new Rectangle(x, y, BUTTON_SIZE, BUTTON_SIZE) : null;
    }

    @Override
    protected Rectangle getMinimizeButtonBounds() {
        if (!hasMinimizeButton()) return null;

        int x = peer.getWidth() - BUTTON_SIZE * 3 - BUTTONS_RIGHT_PADDING
                - BUTTONS_PADDING * 2 - BORDER_SIZE;
        int y = (int) Math.floor((HEIGHT - BUTTON_SIZE + 1f) / 2);
        return x > 0 ? new Rectangle(x, y, BUTTON_SIZE, BUTTON_SIZE) : null;
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
        return numButtons * BUTTON_SIZE + (numButtons - 1) * BUTTONS_PADDING + BUTTONS_RIGHT_PADDING;
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

    private Color getButtonForeground(boolean isHovered) {
        if (isHovered) {
            return isDarkTheme() ? ICON_HOVERED_FOREGROUND_DARK : ICON_HOVERED_FOREGROUND;
        } else {
            return isDarkTheme() ? ACTIVE_FOREGROUND_DARK : ACTIVE_FOREGROUND;
        }
    }

    private Color getClosePressedBackground() {
        return isDarkTheme() ? CLOSE_ICON_PRESSED_BACKGROUND_DARK : CLOSE_ICON_PRESSED_BACKGROUND;
    }

    private Color getCloseHoveredBackground(boolean isActive) {
        if (isActive) {
            return isDarkTheme() ? CLOSE_ICON_HOVERED_BACKGROUND_DARK : CLOSE_ICON_HOVERED_BACKGROUND;
        } else {
            return isDarkTheme() ? CLOSE_ICON_HOVERED_INACTIVE_BACKGROUND_DARK : CLOSE_ICON_HOVERED_INACTIVE_BACKGROUND;
        }
    }

    @Override
    protected void paintBorder(Graphics2D g2d) {
        int width = peer.getWidth();
        int height = peer.getHeight();
        g2d.setColor(getBorderColor(isActive()));
        g2d.setStroke(new BasicStroke(BORDER_SIZE));
        g2d.drawRect(0, 0, width, height);
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

            // The border
            var oldStroke = g.getStroke();
            g.setColor(getBorderColor(active));
            g.setStroke(new BasicStroke(BORDER_SIZE));
            g.drawRoundRect(0, 0, width, HEIGHT + radius + 1, radius, radius);
            g.setStroke(oldStroke);
            g.drawLine(0, HEIGHT - 1, width, HEIGHT - 1);
        } else {
            // The title bar
            g.setColor(getBackgroundColor(active));
            g.fillRect(0, 0, width, HEIGHT);

            // The top bevel of the title bar
            g.setColor(getBackgroundTopColor(active));
            g.drawLine(BORDER_SIZE, BORDER_SIZE, width, BORDER_SIZE);
            g.drawLine(BORDER_SIZE, BORDER_SIZE, BORDER_SIZE, HEIGHT);

            // The border
            var oldStroke = g.getStroke();
            g.setColor(getBorderColor(active));
            g.setStroke(new BasicStroke(BORDER_SIZE));
            g.drawRect(0, 0, width, HEIGHT);
            g.setStroke(oldStroke);
        }
        paintTitle(g, title, foregroundColor, width);

        Rectangle closeButtonBounds = getCloseButtonBounds();
        if (closeButtonBounds != null) {
            paintCloseButtonBackground(g, closeButtonBounds, closeButton);
            Color buttonColor = getButtonForeground(closeButton.hovered);
            paintCloseButton(g, closeButtonBounds, buttonColor);
        }
        Rectangle maximizedButtonBounds = getMaximizeButtonBounds();
        if (maximizedButtonBounds != null) {
            paintButtonBackground(g, maximizedButtonBounds, maximizeButton);
            Color buttonColor = getButtonForeground(maximizeButton.hovered);
            paintMaximizeButton(g, maximizedButtonBounds, buttonColor);
        }
        Rectangle minimizedButtonBounds = getMinimizeButtonBounds();
        if (minimizedButtonBounds != null) {
            paintButtonBackground(g, minimizedButtonBounds, minimizeButton);
            Color buttonColor = getButtonForeground(minimizeButton.hovered);
            paintMinimizeButton(g, minimizedButtonBounds, buttonColor);
        }
        g.setClip(null);
    }

    private void paintTitle(Graphics2D g, String title, Color foregroundColor, int width) {
        g.setColor(foregroundColor);
        g.setFont(FONT);
        FontMetrics fm = g.getFontMetrics();
        int leftMargin = HEIGHT / 2 - BUTTON_SIZE; // same as space between close button and right window edge
        int availableWidth = width - getButtonSpaceWidth() - leftMargin;
        String text = SwingUtilities2.clipStringIfNecessary(null, fm, title, availableWidth);
        int textWidth = fm.stringWidth(text);
        g.drawString(text,
                Math.min((width - textWidth) / 2, availableWidth - textWidth),
                (HEIGHT - fm.getHeight()) / 2 + fm.getAscent());
    }

    private void paintCloseButtonBackground(Graphics2D g, Rectangle bounds, ButtonState state) {
        if (!isActive() && !state.hovered && !state.pressed) return;

        g.setColor(state.pressed ? getClosePressedBackground() :
                   state.hovered ? getCloseHoveredBackground(isActive()) : getIconBackground());
        g.fill(new Ellipse2D.Float(bounds.x, bounds.y, bounds.width, bounds.height));
    }

    private void paintButtonBackground(Graphics2D g, Rectangle bounds, ButtonState state) {
        if (state.hovered || state.pressed) {
            g.setColor(state.pressed ? getIconPressedBackground() :
                    state.hovered ? getIconHoveredBackground() : getIconBackground());
            g.fill(new Ellipse2D.Float(bounds.x, bounds.y, bounds.width, bounds.height));
        }
    }

    private static Point centerOf(Rectangle rect) {
        return new Point((int) Math.floor(rect.x + rect.width / 2f),
                (int) Math.floor(rect.y + rect.height / 2f));
    }

    private void paintCloseButton(Graphics2D g, Rectangle bounds, Color foregroundColor) {
        g.setColor(foregroundColor);
        Point center = centerOf(bounds);
        g.drawLine(center.x - BUTTON_ICON_SIZE, center.y - BUTTON_ICON_SIZE,
                center.x + BUTTON_ICON_SIZE, center.y + BUTTON_ICON_SIZE);
        g.drawLine(center.x - BUTTON_ICON_SIZE, center.y + BUTTON_ICON_SIZE,
                center.x + BUTTON_ICON_SIZE, center.y - BUTTON_ICON_SIZE);
    }

    private void paintMaximizeButton(Graphics2D g, Rectangle bounds, Color foregroundColor) {
        g.setColor(foregroundColor);
        Point center = centerOf(bounds);
        int size = BUTTON_ICON_SIZE + 1;
        if (peer.getState() == Frame.MAXIMIZED_BOTH) {
            g.drawLine(center.x - size, center.y, center.x, center.y - size);
            g.drawLine(center.x, center.y - size, center.x + size, center.y);
            g.drawLine(center.x - size, center.y, center.x, center.y + size);
            g.drawLine(center.x, center.y + size, center.x + size, center.y);
        } else {
            g.drawLine(center.x - size, (int) (center.y + size / 2f),
                    center.x, (int) (center.y - size / 2f));
            g.drawLine(center.x, (int) (center.y - size / 2f),
                    center.x + size, (int) (center.y + size / 2f));
        }
    }

    private void paintMinimizeButton(Graphics2D g, Rectangle bounds, Color foregroundColor) {
        g.setColor(foregroundColor);
        Point center = centerOf(bounds);
        int size = BUTTON_ICON_SIZE + 1;
        g.drawLine(center.x - size, (int) (center.y - size / 2f),
                center.x, (int) (center.y + size / 2f));
        g.drawLine(center.x, (int) (center.y + size / 2f),
                center.x + size, (int) (center.y - size / 2f));
    }
}
