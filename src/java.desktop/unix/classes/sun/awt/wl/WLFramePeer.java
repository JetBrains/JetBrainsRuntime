/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
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

import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.awt.peer.ComponentPeer;
import java.awt.peer.FramePeer;
import sun.awt.AWTAccessor;
import sun.awt.AWTAccessor.ComponentAccessor;
import sun.util.logging.PlatformLogger;

public class WLFramePeer extends WLComponentPeer implements FramePeer {
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.wl.WLFramePeer");

    private final WLFrameDecoration decoration;

    private int state; // Guarded by getStateLock()

    public WLFramePeer(Frame target) {
        super(target);
        decoration = target.isUndecorated() ? null : new WLFrameDecoration(this);
    }

    @Override
    protected void wlSetVisible(boolean v) {
        super.wlSetVisible(v);
        final ComponentAccessor acc = AWTAccessor.getComponentAccessor();
        for (Component c : ((Frame)target).getComponents()) {
            ComponentPeer cPeer = acc.getPeer(c);
            if (cPeer instanceof WLComponentPeer) {
                ((WLComponentPeer) cPeer).wlSetVisible(v);
            }
        }
    }

    private static native void initIDs();

    static {
        if (!GraphicsEnvironment.isHeadless()) {
            initIDs();
        }
    }

    @Override
    public Insets getInsets() {
        return decoration == null ? new Insets(0, 0, 0, 0) : decoration.getInsets();
    }

    @Override
    public void beginValidate() {
    }

    @Override
    public void endValidate() {
    }

//    @Override
//    public void beginLayout() {
//        log.info("Not implemented: WLFramePeer.beginLayout()");
//    }
//
//    @Override
//    public void endLayout() {
//        log.info("Not implemented: WLFramePeer.endLayout()");
//    }

    @Override
    public void setTitle(String title) {
        setFrameTitle(title);
    }

    @Override
    public void setMenuBar(MenuBar mb) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setResizable(boolean resizeable) {
        repaintClientDecorations();
    }

    @Override
    public void setState(int newState) {
        synchronized(getStateLock()) {
            if ((newState & Frame.ICONIFIED) != 0) {
                // Per xdg-shell.xml, "There is no way to know if the surface
                // is currently minimized, nor is there any way to unset
                // minimization on this surface". So 'state' will never
                // have 'Frame.ICONIFIED' bit set and every
                // request to iconify will be granted.
                requestMinimized();
                AWTAccessor.getFrameAccessor().setExtendedState((Frame) target, newState & ~Frame.ICONIFIED);
            } else if (newState == Frame.MAXIMIZED_BOTH) {
                requestMaximized();
            } else /* Frame.NORMAL */ {
                requestUnmaximized();
            }
        }
    }

    @Override
    public int getState() {
        synchronized(getStateLock()) {
            return state;
        }
    }

    @Override
    public void setMaximizedBounds(Rectangle bounds) {
    }

    @Override
    public void setBoundsPrivate(int x, int y, int width, int height) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Rectangle getBoundsPrivate() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void emulateActivation(boolean activate) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void toFront() {
        //throw new UnsupportedOperationException();
    }

    @Override
    public void toBack() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateAlwaysOnTopState() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateFocusableWindowState() {
    }

    @Override
    public void setModalBlocked(Dialog blocker, boolean blocked) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateMinimumSize() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateIconImages() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setOpacity(float opacity) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setOpaque(boolean isOpaque) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateWindow() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void repositionSecurityWarning() {
        throw new UnsupportedOperationException();
    }

    // called from native code
    void postWindowClosing() {
        WLToolkit.postEvent(new WindowEvent((Window) target, WindowEvent.WINDOW_CLOSING));
    }

    @Override
    void postMouseEvent(MouseEvent e) {
        if (decoration == null) {
            super.postMouseEvent(e);
        } else {
            decoration.processMouseEvent(e);
        }
    }

    @Override
    void notifyConfigured(int width, int height, boolean active, boolean maximized) {
        if (width != 0 || height != 0) target.setSize(width, height);
        if (decoration != null) decoration.setActive(active);

        synchronized (getStateLock()) {
            int oldState = state;
            state = maximized ? Frame.MAXIMIZED_BOTH : Frame.NORMAL;
            AWTAccessor.getFrameAccessor().setExtendedState((Frame)target, state);
            if (state != oldState) {
                WLToolkit.postEvent(new WindowEvent((Window)target, WindowEvent.WINDOW_STATE_CHANGED, oldState, state));
                repaintClientDecorations();
            }
        }
    }

    @Override
    void repaintClientDecorations() {
        if (decoration != null) decoration.paint();
    }
}
