/*
 * Copyright (c) 2022, JetBrains s.r.o.. All rights reserved.
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

import sun.awt.AWTAccessor;
import sun.awt.SurfacePixelGrabber;
import sun.awt.UngrabEvent;
import sun.java2d.SunGraphics2D;
import sun.java2d.vulkan.VKSurfaceData;
import sun.java2d.wl.WLSMSurfaceData;

import javax.swing.JRootPane;
import javax.swing.RootPaneContainer;
import java.awt.AWTEvent;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Font;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.SystemColor;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.awt.peer.ComponentPeer;
import java.awt.peer.WindowPeer;
import java.lang.ref.WeakReference;

public class WLWindowPeer extends WLComponentPeer implements WindowPeer, SurfacePixelGrabber {
    private static Font defaultFont;
    private Dialog blocker;
    private static WLWindowPeer grabbingWindow; // fake, kept for UngrabEvent only

    // If this window gets focus from Wayland, we need to transfer focus synthFocusOwner, if any
    private WeakReference<Component> synthFocusOwner = new WeakReference<>(null);

    public static final String WINDOW_CORNER_RADIUS = "apple.awt.windowCornerRadius";

    private WLRoundedCornersManager.RoundedCornerKind roundedCornerKind = WLRoundedCornersManager.RoundedCornerKind.DEFAULT; // guarded by stateLock
    private Path2D.Double topLeftMask;      // guarded by stateLock
    private Path2D.Double topRightMask;     // guarded by stateLock
    private Path2D.Double bottomLeftMask;   // guarded by stateLock
    private Path2D.Double bottomRightMask;  // guarded by stateLock
    private SunGraphics2D graphics;         // guarded by stateLock

    static synchronized Font getDefaultFont() {
        if (null == defaultFont) {
            defaultFont = new Font(Font.DIALOG, Font.PLAIN, 12);
        }
        return defaultFont;
    }

    public WLWindowPeer(Window target) {
        this(target, true);
    }

    public WLWindowPeer(Window target, boolean dropShadow) {
        super(target, dropShadow);

        if (!target.isFontSet()) {
            target.setFont(getDefaultFont());
        }
        if (!target.isBackgroundSet()) {
            target.setBackground(SystemColor.window);
        }
        if (!target.isForegroundSet()) {
            target.setForeground(SystemColor.windowText);
        }

        if (target instanceof RootPaneContainer) {
            JRootPane rootpane = ((RootPaneContainer)target).getRootPane();
            if (rootpane != null) {
                Object roundedCornerKind = rootpane.getClientProperty(WINDOW_CORNER_RADIUS);
                if (roundedCornerKind != null) {
                    setRoundedCornerKind(WLRoundedCornersManager.roundedCornerKindFrom(roundedCornerKind));
                }
            }
        }
    }

    @Override
    protected void wlSetVisible(boolean v) {
        if (!v) ungrab(true);

        if (v && targetIsWlPopup() && shouldBeFocusedOnShowing()) {
            requestWindowFocus();
        }
        super.wlSetVisible(v);
        final AWTAccessor.ComponentAccessor acc = AWTAccessor.getComponentAccessor();
        for (Component c : getWindow().getComponents()) {
            ComponentPeer cPeer = acc.getPeer(c);
            if (cPeer instanceof WLComponentPeer) {
                ((WLComponentPeer) cPeer).wlSetVisible(v);
            }
        }
        if (!v && targetIsWlPopup() && getWindow().isFocused()) {
            Window targetOwner = getWindow().getOwner();
            while (targetOwner != null && (targetOwner.getOwner() != null && !targetOwner.isFocusableWindow())) {
                targetOwner = targetOwner.getOwner();
            }
            if (targetOwner != null) {
                WLWindowPeer wndpeer = AWTAccessor.getComponentAccessor().getPeer(targetOwner);
                if (wndpeer != null) {
                    wndpeer.requestWindowFocus();
                }
            }
        }
    }

    @Override
    public Insets getInsets() {
        return new Insets(0, 0, 0, 0);
    }

    @Override
    public void beginValidate() {

    }

    @Override
    public void endValidate() {

    }

    @Override
    public void toFront() {
        activate();
    }

    @Override
    public void toBack() {
        // TODO
    }

    @Override
    public void updateAlwaysOnTopState() {

    }

    @Override
    public void updateFocusableWindowState() {

    }

    @Override
    public void setModalBlocked(Dialog blocker, boolean blocked) {
        this.blocker = blocked ? blocker : null;
    }

    @Override
    public void updateMinimumSize() {
        // No op, it gets updated at each resize
    }

    @Override
    public void updateIconImages() {
        // No support for this from Wayland, icon is a desktop integration feature.
    }

    @Override
    public void setOpacity(float opacity) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setOpaque(boolean isOpaque) {
        if (!isOpaque) {
            throw new UnsupportedOperationException("Transparent windows are not supported");
        }
    }

    @Override
    public void updateWindow() {
        if (roundedCornersRequested() && canPaintRoundedCorners()) {
            paintRoundCorners();
        }
        commitToServer();
    }

    @Override
    public GraphicsConfiguration getAppropriateGraphicsConfiguration(
            GraphicsConfiguration gc) {
        return gc;
    }

    @Override
    public void dispose() {
        ungrab(true);
        resetCornerMasks();
        super.dispose();
    }

    @Override
    void updateSurfaceData() {
        resetCornerMasks();
        super.updateSurfaceData();
    }

    private Window getWindow() {
        return (Window) target;
    }

    private boolean shouldBeFocusedOnShowing() {
        Window window = getWindow();
        return window.isFocusableWindow() &&
                window.isAutoRequestFocus() &&
                blocker == null;
    }

    // supporting only 'synthetic' focus transfers for now (when natively focused window stays the same)
    private void requestWindowFocus() {
        Window window = getWindow();
        Window nativeFocusTarget = getNativelyFocusableOwnerOrSelf(window);
        if (nativeFocusTarget != null &&
                WLKeyboardFocusManagerPeer.getInstance().getCurrentFocusedWindow() == nativeFocusTarget) {
            WLToolkit.postPriorityEvent(new WindowEvent(window, WindowEvent.WINDOW_GAINED_FOCUS));
        }
    }

    public Component getSyntheticFocusOwner() {
        return synthFocusOwner.get();
    }

    public void setSyntheticFocusOwner(Component c) {
        synthFocusOwner = new WeakReference<>(c);
    }

    public void grab() {
        if (grabbingWindow != null && !isGrabbing()) {
            grabbingWindow.ungrab(false);
        }
        grabbingWindow = this;
    }

    public void ungrab(boolean externalUngrab) {
        if (isGrabbing()) {
            grabbingWindow = null;
            if (externalUngrab) {
                WLToolkit.postEvent(new UngrabEvent(getTarget()));
            }
        }
    }

    private boolean isGrabbing() {
        return this == grabbingWindow;
    }

    @Override
    void handleJavaWindowFocusEvent(AWTEvent e) {
        if (e.getID() == WindowEvent.WINDOW_LOST_FOCUS) {
            ungrab(true);
        }
    }

    @Override
    public BufferedImage getClientAreaSnapshot(int x, int y, int width, int height) {
        // Move the coordinate system to the client area
        Insets insets = getInsets();
        x += insets.left;
        y += insets.top;

        if (width <= 0 || height <= 0) {
            return null;
        }
        if (x < 0 || y < 0) {
            // Shouldn't happen, but better avoid accessing surface data outside the range
            throw new IllegalArgumentException("Negative coordinates are not allowed");
        }
        if (x >= getWidth()) {
            throw new IllegalArgumentException(String.format("x coordinate (%d) is out of bounds (%d)", x, getWidth()));
        }
        if (y >= getHeight()) {
            throw new IllegalArgumentException(String.format("y coordinate (%d) is out of bounds (%d)", y, getHeight()));
        }
        if ((long) x + width > getWidth()) {
            width = getWidth() - x;
        }
        if ((long) y + height > getHeight()) {
            height = getHeight() - y;
        }

        // At this point the coordinates and size are in Java units;
        // need to convert them into pixels.
        Rectangle bounds = new Rectangle(
                javaUnitsToBufferUnits(x),
                javaUnitsToBufferUnits(y),
                javaSizeToBufferSize(width),
                javaSizeToBufferSize(height)
        );
        Rectangle bufferBounds = getBufferBounds();
        if (bounds.x >= bufferBounds.width) {
            bounds.x = bufferBounds.width - 1;
        }
        if (bounds.y >= bufferBounds.height) {
            bounds.y = bufferBounds.height - 1;
        }
        if (bounds.x + bounds.width > bufferBounds.width) {
            bounds.width = bufferBounds.width - bounds.x;
        }
        if (bounds.y + bounds.height > bufferBounds.height) {
            bounds.height = bufferBounds.height - bounds.y;
        }

        if (surfaceData instanceof VKSurfaceData vksd) {
            return vksd.getSnapshot(bounds.x, bounds.y, bounds.width, bounds.height);
        } else if (surfaceData instanceof WLSMSurfaceData smsd) {
            return smsd.getSnapshot(bounds.x, bounds.y, bounds.width, bounds.height);
        }

        return null;
    }

    private boolean canPaintRoundedCorners() {
        int roundedCornerSize = WLRoundedCornersManager.roundCornerRadiusFor(roundedCornerKind);
        // Note: You would normally get a transparency-capable color model when using
        // the default graphics configuration
        return surfaceData.getColorModel().hasAlpha()
                && getWidth() > roundedCornerSize * 2
                && getHeight() > roundedCornerSize * 2;
    }

    protected boolean roundedCornersRequested() {
        synchronized (getStateLock()) {
            return roundedCornerKind == WLRoundedCornersManager.RoundedCornerKind.FULL
                    || roundedCornerKind == WLRoundedCornersManager.RoundedCornerKind.SMALL;
        }
    }

    WLRoundedCornersManager.RoundedCornerKind getRoundedCornerKind() {
        synchronized (getStateLock()) {
            return roundedCornerKind;
        }
    }

    void setRoundedCornerKind(WLRoundedCornersManager.RoundedCornerKind kind) {
        synchronized (getStateLock()) {
            if (roundedCornerKind != kind) {
                roundedCornerKind = kind;
                resetCornerMasks();
            }
        }
    }

    private void createCornerMasks() {
        if (graphics == null) {
            graphics = new SunGraphics2D(surfaceData, Color.WHITE, Color.BLACK, null);
            graphics.setComposite(AlphaComposite.Clear);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
        }

        if (topLeftMask == null) {
            createCornerMasks(WLRoundedCornersManager.roundCornerRadiusFor(roundedCornerKind));
        }
    }

    private void resetCornerMasks() {
        synchronized (getStateLock()) {
            if (graphics != null) graphics.dispose();
            graphics = null;
            topLeftMask = null;
            topRightMask = null;
            bottomLeftMask = null;
            bottomRightMask = null;
        }
    }

    private void createCornerMasks(int size) {
        int w = getWidth();
        int h = getHeight();

        topLeftMask = new Path2D.Double();
        topLeftMask.moveTo(0, 0);
        topLeftMask.lineTo(size, 0);
        topLeftMask.quadTo(0, 0, 0, size);
        topLeftMask.closePath();

        topRightMask = new Path2D.Double();
        topRightMask.moveTo(w - size, 0);
        topRightMask.quadTo(w, 0, w, size);
        topRightMask.lineTo(w, 0);
        topRightMask.closePath();

        bottomLeftMask = new Path2D.Double();
        bottomLeftMask.moveTo(0, h - size);
        bottomLeftMask.quadTo(0, h, size, h);
        bottomLeftMask.lineTo(0, h);
        bottomLeftMask.closePath();

        bottomRightMask = new Path2D.Double();
        bottomRightMask.moveTo(w - size, h);
        bottomRightMask.quadTo(w, h, w, h - size);
        bottomRightMask.lineTo(w, h);
        bottomRightMask.closePath();
    }

    private void paintRoundCorners() {
        synchronized (getStateLock()) {
            createCornerMasks();

            graphics.fill(topLeftMask);
            graphics.fill(topRightMask);
            graphics.fill(bottomLeftMask);
            graphics.fill(bottomRightMask);
        }
    }
}
