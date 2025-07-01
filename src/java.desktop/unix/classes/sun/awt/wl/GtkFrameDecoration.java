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
import sun.swing.ImageCache;

import java.awt.Color;
import java.awt.Dimension;
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
    private static final int CLOSE_BUTTON_ID = 1;
    private static final int MINIMIZE_BUTTON_ID = 2;
    private static final int MAXIMIZE_BUTTON_ID = 3;

    private long nativePtr;
    private final ImageCache cache = new ImageCache(16);
    private final int dndThreshold;
    private final int titleBarHeight;
    private final int titleBarMinWidth;

    public GtkFrameDecoration(WLDecoratedPeer peer, boolean showMinimize, boolean showMaximize) {
        super(peer, showMinimize, showMaximize);
        ((WLToolkit) WLToolkit.getDefaultToolkit()).checkGtkVersion(3, 0, 0);
        nativePtr = nativeCreateDecoration(showMinimize, showMaximize);

        assert nativePtr != 0;

        int t = nativeGetIntProperty(nativePtr, "gtk-dnd-drag-threshold");
        dndThreshold = t > 0 ? t : 4;

        t = nativeGetTitleBarHeight(nativePtr);
        titleBarHeight = t > 0 ? t : 37;

        t = nativeGetTitleBarMinimumWidth(nativePtr);
        titleBarMinWidth = t > 0 ? t : 180;

        // TODO: gtk-alternative-button-order Whether dialog buttons appear in an alternate order (Mac-like).
        // gtk-decoration-layout - This setting determines which buttons should be put in the titlebar of client-side decorated windows,
        // and whether they should be placed at the left of right.
        // For example, “menu:minimize,maximize,close” specifies a menu on the left, and minimize, maximize and close buttons on the right.
        // gtk-titlebar-double-click - This setting determines the action to take when a double-click occurs on the titlebar
        //                      of client-side decorated windows.
        // gtk-titlebar-middle-click
        // gtk-titlebar-right-click
        // see https://docs.gtk.org/gtk3/class.Settings.html
    }

    @Override
    protected void paintTitleBar(Graphics2D g2d) {
        int width = peer.getWidth();
        int height = titleBarHeight;
        // TODO: use the cache
        double scale = ((WLGraphicsConfig) peer.getGraphicsConfiguration()).getEffectiveScale();
        g2d.setBackground(new Color(0, true));
        g2d.clearRect(0, 0, width, height);
        int nativeW = (int) Math.ceil(width * scale);
        int nativeH = (int) Math.ceil(height * scale);
        DataBufferInt dataBuffer = new DataBufferInt(nativeW * nativeH);

        String title = peer.getTitle();
//        System.err.printf("width=%d, height=%d, scale=%f, nativeW=%d, nativeH=%d\n", width, height, scale, nativeW, nativeH);
        nativePaintTitleBar(nativePtr, SunWritableRaster.stealData(dataBuffer, 0), nativeW, nativeH, (int)scale, title);
        SunWritableRaster.markDirty(dataBuffer);

        int[] bands = { 0x00ff0000, 0x0000ff00, 0x000000ff, 0xff000000 };
        WritableRaster raster = Raster.createPackedRaster(dataBuffer, nativeW, nativeH, nativeW, bands, null);

        ColorModel cm = peer.getGraphicsConfiguration().getColorModel(Transparency.TRANSLUCENT);
        BufferedImage img = new BufferedImage(cm, raster, true, null);
        cache.setImage(getClass(), null, nativeW, nativeH, null, img);

        if (scale > 1 && g2d instanceof SunGraphics2D sg2d) {
            sg2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            sg2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            sg2d.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
        }
        g2d.drawImage(img, 0, 0, width, height, 0, 0, nativeW, nativeH, null);
    }

    @Override
    protected void paintBorder(Graphics2D g2d) {
        // TODO
    }

    @Override
    public void dispose() {
        nativeDestroyDecoration(nativePtr);
        nativePtr = 0;
        cache.flush();
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
    public Insets getContentInsets() {
        // TODO: proper border width
        return new Insets(titleBarHeight + 1, 1, 1, 1);
    }

    @Override
    public Rectangle getTitleBarBounds() {
        return new Rectangle(0, 0, peer.getWidth(), titleBarHeight);
    }

    @Override
    public Dimension getMinimumSize() {
        // TODO: proper border width
        return new Dimension(titleBarMinWidth + 1 * 2, titleBarHeight + 1);
    }

    @Override
    public void notifyConfigured(boolean active, boolean maximized, boolean fullscreen) {
        nativeNotifyConfigured(nativePtr, active, maximized, fullscreen);
        super.notifyConfigured(active, maximized, fullscreen);
    }

    @Override
    protected Rectangle getMinimizeButtonBounds() {
        if (!hasMinimizeButton()) return null;

        return nativeGetButtonBounds(nativePtr, MINIMIZE_BUTTON_ID);
    }

    @Override
    protected Rectangle getMaximizeButtonBounds() {
        if (!hasMaximizeButton()) return null;

        return nativeGetButtonBounds(nativePtr, MAXIMIZE_BUTTON_ID);
    }

    @Override
    protected Rectangle getCloseButtonBounds() {
        return nativeGetButtonBounds(nativePtr, CLOSE_BUTTON_ID);
    }

    @Override
    public void notifyThemeChanged() {
        cache.flush();
        nativeSwitchTheme();
    }

    private native long nativeCreateDecoration(boolean showMinimize, boolean showMaximize);
    private native void nativeDestroyDecoration(long nativePtr);
    private native void nativeSwitchTheme();
    private native void nativePaintTitleBar(long nativePtr, int[] buffer, int width, int height, int scale, String title);
    private native int nativeGetIntProperty(long nativePtr, String name);
    private native int nativeGetTitleBarHeight(long nativePtr);
    private native int nativeGetTitleBarMinimumWidth(long nativePtr);
    private native Rectangle nativeGetButtonBounds(long nativePtr, int buttonID);
    private native void nativeNotifyConfigured(long nativePtr, boolean active, boolean maximized, boolean fullscreen);
}
