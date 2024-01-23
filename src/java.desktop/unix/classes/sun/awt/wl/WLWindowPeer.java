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
import java.awt.*;
import java.awt.event.WindowEvent;
import java.awt.peer.ComponentPeer;
import java.awt.peer.WindowPeer;

public class WLWindowPeer extends WLComponentPeer implements WindowPeer {
    private static Font defaultFont;
    private Dialog blocker;

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
    void configureWLSurface() {
        super.configureWLSurface();
        updateMinimumSize();
        updateMaximumSize();
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
        final Dimension minSize = getMinimumSize();
        super.setMinimumSizeTo(minSize);
    }

    public void updateMaximumSize() {
        // TODO: make sure this is called when our target's maximum size changes
        final Dimension maxSize = target.isMaximumSizeSet() ? target.getMaximumSize() : null;
        if (maxSize != null) super.setMaximumSizeTo(maxSize);
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
        commitToServer();
    }

    @Override
    public GraphicsConfiguration getAppropriateGraphicsConfiguration(
            GraphicsConfiguration gc) {
        return gc;
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
            WLToolkit.postEvent(new WindowEvent(window, WindowEvent.WINDOW_GAINED_FOCUS));
        }
    }
}
