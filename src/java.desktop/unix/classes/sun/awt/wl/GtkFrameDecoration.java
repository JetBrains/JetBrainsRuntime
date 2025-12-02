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

import sun.awt.image.SunWritableRaster;
import sun.java2d.SunGraphics2D;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBufferInt;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;

public class GtkFrameDecoration extends FullFrameDecorationHelper {
    private static final int MIN_BUTTON_STATE_HOVERED = 1;
    private static final int MIN_BUTTON_STATE_PRESSED = 1 << 1;
    private static final int MAX_BUTTON_STATE_HOVERED = 1 << 2;
    private static final int MAX_BUTTON_STATE_PRESSED = 1 << 3;
    private static final int CLOSE_BUTTON_STATE_HOVERED = 1 << 4;
    private static final int CLOSE_BUTTON_STATE_PRESSED = 1 << 5;

    private long nativePtr;
    private Rectangle minimizeButtonBounds; // set by the native code
    private Rectangle maximizeButtonBounds; // set by the native code
    private Rectangle closeButtonBounds; // set by the native code
    private int titleBarHeight = 37; // set by the native code
    private int titleBarMinWidth = 180; // set by the native code

    private final int dndThreshold;

    static {
        initIDs();
        nativeLoadGTK();
    }

    public GtkFrameDecoration(WLDecoratedPeer peer, boolean showMinimize, boolean showMaximize) {
        super(peer, showMinimize, showMaximize);
        nativePtr = nativeCreateDecoration(showMinimize, showMaximize, isDarkTheme());
        assert nativePtr != 0 : "Failed to create the native part of the decoration";
        int t = nativeGetIntProperty(nativePtr, "gtk-dnd-drag-threshold");
        dndThreshold = t > 0 ? t : 4;
    }

    @Override
    public void paint(Graphics g) {
        // Determine buttons' bounds, etc.
        nativePrePaint(nativePtr, peer.getWidth(), peer.getHeight());
        if (peer.getWidth() >= titleBarMinWidth && peer.getHeight() >= titleBarHeight) {
            super.paint(g);
        }
    }

    @Override
    protected void paintTitleBar(Graphics2D g2d) {
        int width = peer.getWidth();
        int height = titleBarHeight;

        assert width >= titleBarMinWidth : "The frame width is too small to display the title bar";
        assert peer.getHeight() >= titleBarHeight : "The frame height is too small to display the title bar";

        double scale = ((WLGraphicsConfig) peer.getGraphicsConfiguration()).getEffectiveScale();
        g2d.setBackground(new Color(0, true));
        g2d.clearRect(0, 0, width, height);
        int nativeW = (int) Math.ceil(width * scale);
        int nativeH = (int) Math.ceil(height * scale);
        DataBufferInt dataBuffer = new DataBufferInt(nativeW * nativeH);
        nativePaintTitleBar(
                nativePtr,
                SunWritableRaster.stealData(dataBuffer, 0),
                width, height, scale, peer.getTitle(), getButtonsState());
        SunWritableRaster.markDirty(dataBuffer);
        int[] bands = {0x00ff0000, 0x0000ff00, 0x000000ff, 0xff000000};
        WritableRaster raster = Raster.createPackedRaster(dataBuffer, nativeW, nativeH, nativeW, bands, null);
        ColorModel cm = peer.getGraphicsConfiguration().getColorModel(Transparency.TRANSLUCENT);
        BufferedImage img = new BufferedImage(cm, raster, true, null);
        if (scale != 1.0 && g2d instanceof SunGraphics2D sg2d) {
            sg2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            sg2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            sg2d.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
        }
        g2d.drawImage(img, 0, 0, width, height, 0, 0, nativeW, nativeH, null);
    }

    @Override
    protected void paintBorder(Graphics2D g2d) {
        // No border in this decoration style
    }

    @Override
    public void dispose() {
        nativeDestroyDecoration(nativePtr);
        nativePtr = 0;
        super.dispose();
    }

    @Override
    protected boolean pressedInDragStartArea(Point p) {
        if (p.x <= 0 || p.x >= peer.getWidth() || p.y <= 0 || p.y >= titleBarHeight) return false;

        var b1 = getMinimizeButtonBounds();
        if (b1 != null && b1.contains(p)) return false;

        var b2 = getMaximizeButtonBounds();
        if (b2 != null && b2.contains(p)) return false;

        var b3 = getCloseButtonBounds();
        if (b3 != null && b3.contains(p)) return false;

        return true;
    }

    @Override
    protected boolean isSignificantDragDistance(Point p1, Point p2) {
        return p1.distance(p2) > dndThreshold;
    }

    @Override
    public int getResizeEdgeThickness() {
        return 10;
    }

    @Override
    public Insets getContentInsets() {
        return new Insets(titleBarHeight, 0, 0, 0);
    }

    @Override
    public Rectangle getTitleBarBounds() {
        return new Rectangle(0, 0, peer.getWidth(), titleBarHeight);
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(titleBarMinWidth, titleBarHeight);
    }

    @Override
    public void notifyConfigured(boolean active, boolean maximized, boolean fullscreen) {
        nativeNotifyConfigured(nativePtr, active, maximized, fullscreen);
        super.notifyConfigured(active, maximized, fullscreen);
    }

    @Override
    protected Rectangle getMinimizeButtonBounds() {
        if (!hasMinimizeButton()) return null;
        return minimizeButtonBounds;
    }

    @Override
    protected Rectangle getMaximizeButtonBounds() {
        if (!hasMaximizeButton()) return null;
        return maximizeButtonBounds;
    }

    @Override
    protected Rectangle getCloseButtonBounds() {
        return closeButtonBounds;
    }

    @Override
    public void notifyThemeChanged() {
        nativeSwitchTheme(isDarkTheme());
    }

    private int getButtonsState() {
        int state = 0;
        if (closeButton.hovered) state |= CLOSE_BUTTON_STATE_HOVERED;
        if (closeButton.pressed) state |= CLOSE_BUTTON_STATE_PRESSED;

        if (hasMinimizeButton()) {
            if (minimizeButton.hovered) state |= MIN_BUTTON_STATE_HOVERED;
            if (minimizeButton.pressed) state |= MIN_BUTTON_STATE_PRESSED;
        }

        if (hasMaximizeButton()) {
            if (maximizeButton.hovered) state |= MAX_BUTTON_STATE_HOVERED;
            if (maximizeButton.pressed) state |= MAX_BUTTON_STATE_PRESSED;
        }

        return state;
    }

    private static native void initIDs();
    private static native boolean nativeLoadGTK();

    private native long nativeCreateDecoration(boolean showMinimize, boolean showMaximize, boolean isDarkTheme);
    private native void nativeDestroyDecoration(long nativePtr);
    private native void nativeSwitchTheme(boolean isDarkTheme);
    private native void nativePaintTitleBar(long nativePtr, int[] buffer, int width, int height, double scale,
                                            String title, int buttonsState);
    private native int nativeGetIntProperty(long nativePtr, String name);
    private native void nativeNotifyConfigured(long nativePtr, boolean active, boolean maximized, boolean fullscreen);
    private native void nativePrePaint(long nativePtr, int width, int height);
}
