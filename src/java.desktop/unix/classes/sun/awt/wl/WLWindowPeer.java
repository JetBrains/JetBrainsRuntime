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
import sun.java2d.SunGraphics2D;

import javax.swing.JRootPane;
import javax.swing.RootPaneContainer;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.SystemColor;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.awt.peer.ComponentPeer;
import java.awt.peer.WindowPeer;
import java.util.Arrays;

public class WLWindowPeer extends WLComponentPeer implements WindowPeer {
    private static Font defaultFont;
    private Dialog blocker;

    public static final String WINDOW_CORNER_RADIUS = "apple.awt.windowCornerRadius";
    public static final int SHADOW_SIZE = 15;

    private WLRoundedCornersManager.RoundedCornerKind roundedCornerKind = WLRoundedCornersManager.RoundedCornerKind.DEFAULT; // guarded by dataLock
    private Path2D.Double topLeftMask;      // guarded by dataLock
    private Path2D.Double topRightMask;     // guarded by dataLock
    private Path2D.Double bottomLeftMask;   // guarded by dataLock
    private Path2D.Double bottomRightMask;  // guarded by dataLock
    private SunGraphics2D graphics;         // guarded by dataLock

    static synchronized Font getDefaultFont() {
        if (null == defaultFont) {
            defaultFont = new Font(Font.DIALOG, Font.PLAIN, 12);
        }
        return defaultFont;
    }

    public WLWindowPeer(Window target) {
        super(target);

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
        return new Insets(SHADOW_SIZE, SHADOW_SIZE, SHADOW_SIZE, SHADOW_SIZE);
//        return new Insets(0, 0, 0, 0);
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
        resetCornerMasks();
        super.dispose();
    }

    @Override
    void updateSurfaceData() {
        resetCornerMasks();
        super.updateSurfaceData();

        var g2d = new SunGraphics2D(surfaceData, Color.RED, new Color(0, 0, 0, 0), null);
        g2d.clearRect(0, 0, getWidth(), getHeight());
        var shadowImage = createShadow(getWidth(), getHeight(), SHADOW_SIZE, SHADOW_SIZE);
        g2d.drawImage(shadowImage, 0, 0, null);
        g2d.dispose();
    }

    public Graphics getGraphics() {
        var fullGraphics = super.getGraphics();
        if (fullGraphics != null) {
            // TODO: In dev clip, the size in in buffer units
//            ((SunGraphics2D)fullGraphics).setDevClip(SHADOW_SIZE, SHADOW_SIZE, getBufferWidth() - SHADOW_SIZE * 2, getBufferHeight() - SHADOW_SIZE * 2);
            fullGraphics.setClip(SHADOW_SIZE, SHADOW_SIZE, getWidth() - SHADOW_SIZE * 2, getHeight() - SHADOW_SIZE * 2);
            fullGraphics.translate(SHADOW_SIZE, SHADOW_SIZE);
        }
        return fullGraphics;
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

    private boolean canPaintRoundedCorners() {
        int roundedCornerSize = WLRoundedCornersManager.roundCornerRadiusFor(roundedCornerKind);
        // Note: You would normally get a transparency-capable color model when using
        // the default graphics configuration
        return surfaceData.getColorModel().hasAlpha()
                && getWidth() > roundedCornerSize * 2
                && getHeight() > roundedCornerSize * 2;
    }

    protected boolean roundedCornersRequested() {
        synchronized (dataLock) {
            return roundedCornerKind == WLRoundedCornersManager.RoundedCornerKind.FULL
                    || roundedCornerKind == WLRoundedCornersManager.RoundedCornerKind.SMALL;
        }
    }

    WLRoundedCornersManager.RoundedCornerKind getRoundedCornerKind() {
        synchronized (dataLock) {
            return roundedCornerKind;
        }
    }

    void setRoundedCornerKind(WLRoundedCornersManager.RoundedCornerKind kind) {
        synchronized (dataLock) {
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
        synchronized (dataLock) {
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
        synchronized (dataLock) {
            createCornerMasks();

            graphics.fill(topLeftMask);
            graphics.fill(topRightMask);
            graphics.fill(bottomLeftMask);
            graphics.fill(bottomRightMask);
        }
    }

    private static BufferedImage createShadow(int width, int height, int arc, int shadowSize) {
        BufferedImage shadow = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = shadow.createGraphics();

        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setColor(new Color(0, 0, 0, 100) );
        g2d.fillRoundRect(shadowSize, shadowSize, width - 2 * shadowSize, height - 2 * shadowSize, arc, arc);
        g2d.setBackground(new Color(0, 0, 0, 0) );
        g2d.clearRect(0, height - 2 * shadowSize, width, 2*shadowSize);
        g2d.fillRect(shadowSize, height - 2 * shadowSize, width - 2*shadowSize, shadowSize);

        // Apply a Gaussian blur
        float[] blurKernel = createBlurKernel(14);
        ConvolveOp blurOp = new ConvolveOp(new Kernel(14, 14, blurKernel), ConvolveOp.EDGE_NO_OP, null);
        shadow = blurOp.filter(shadow, null);

        g2d.dispose();
        return shadow;
    }

    private static float[] createBlurKernel(int size) {
        float[] kernel = new float[size * size];
        float value = 1.0f / (size * size);
        Arrays.fill(kernel, value);
        return kernel;
    }
}
