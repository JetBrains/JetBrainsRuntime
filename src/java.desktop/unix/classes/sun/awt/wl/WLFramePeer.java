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
import java.awt.event.WindowEvent;
import java.awt.peer.FramePeer;
import sun.awt.AWTAccessor;
import sun.util.logging.PlatformLogger;

public class WLFramePeer extends WLDecoratedPeer implements FramePeer {
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.wl.WLFramePeer");

    private int state; // Guarded by getStateLock()
    private int widthBeforeMaximized; // Guarded by getStateLock()
    private int heightBeforeMaximized; // Guarded by getStateLock()

    public WLFramePeer(Frame target) {
        super(target, target.isUndecorated(),
                Toolkit.getDefaultToolkit().isFrameStateSupported(Frame.ICONIFIED),
                Toolkit.getDefaultToolkit().isFrameStateSupported(Frame.MAXIMIZED_BOTH));
    }

    @Override
    void configureWLSurface() {
        super.configureWLSurface();
        int state = getFrame().getExtendedState();
        if (state != Frame.NORMAL) {
            setState(state);
        }
    }

//    @Override
//    public void beginLayout() {
//        log.fine("Not implemented: WLFramePeer.beginLayout()");
//    }
//
//    @Override
//    public void endLayout() {
//        log.fine("Not implemented: WLFramePeer.endLayout()");
//    }

    @Override
    public void setMenuBar(MenuBar mb) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isResizable() {
        return getFrame().isResizable();
    }

    @Override
    public boolean isInteractivelyResizable() {
        return getFrame().isResizable() && !isMaximized();
    }

    @Override
    public String getTitle() {
        return getFrame().getTitle();
    }

    @Override
    public boolean isFrameStateSupported(int state) {
        return switch (state) {
            case Frame.NORMAL, Frame.ICONIFIED, Frame.MAXIMIZED_BOTH, Frame.MAXIMIZED_HORIZ, Frame.MAXIMIZED_VERT ->
                    true;
            default -> false;
        };
    }

    @Override
    public void setState(int newState) {
        if (!isVisible()) return;

        if ((newState & Frame.ICONIFIED) != 0) {
            // Per xdg-shell.xml, "There is no way to know if the surface
            // is currently minimized, nor is there any way to unset
            // minimization on this surface". So 'state' will never
            // have 'Frame.ICONIFIED' bit set and every
            // request to iconify will be granted.
            requestMinimized();
            AWTAccessor.getFrameAccessor().setExtendedState(getFrame(), newState & ~Frame.ICONIFIED);
        } else if (newState == Frame.MAXIMIZED_BOTH) {
            requestMaximized();
        } else /* Frame.NORMAL */ {
            requestUnmaximized();
        }
    }

    public void setExtendedState(int newState) {
        getFrame().setExtendedState(newState);
    }

    @Override
    public int getState() {
        synchronized(getStateLock()) {
            return state;
        }
    }

    public boolean isMaximized() {
        return (getState() & Frame.MAXIMIZED_BOTH) != 0;
    }

    @Override
    public void setMaximizedBounds(Rectangle bounds) {
    }

    @Override
    public void setBounds(int newX, int newY, int newWidth, int newHeight, int op) {
        boolean isMaximized = (getState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH;
        if (isMaximized && !isSizeBeingConfigured()) {
            // Some Wayland implementations (Weston) react badly when the size of a maximized
            // surface differs from the size suggested by Wayland through the "configure" event.
            // Changing the location of a maximized window does not make sense also, even if it were
            // possible in Wayland.
            return;
        }

        super.setBounds(newX, newY, newWidth, newHeight, op);
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
    public void toBack() {
        throw new UnsupportedOperationException();
    }

    @Override
    void notifyConfigured(int newSurfaceX, int newSurfaceY, int newSurfaceWidth, int newSurfaceHeight, boolean active, boolean maximized) {
        int widthBefore = getWidth();
        int heightBefore = getHeight();

        super.notifyConfigured(newSurfaceX, newSurfaceY, newSurfaceWidth, newSurfaceHeight, active, maximized);

        synchronized (getStateLock()) {
            int oldState = state;
            state = maximized ? Frame.MAXIMIZED_BOTH : Frame.NORMAL;
            AWTAccessor.getFrameAccessor().setExtendedState(getFrame(), state);
            if (state != oldState) {
                boolean clientDecidesDimension = newSurfaceWidth == 0 || newSurfaceHeight == 0;
                if (maximized) {
                    widthBeforeMaximized = widthBefore;
                    heightBeforeMaximized = heightBefore;
                } else if (clientDecidesDimension && widthBeforeMaximized > 0 && heightBeforeMaximized > 0) {
                    performUnlocked(() -> target.setSize(widthBeforeMaximized, heightBeforeMaximized));
                }
                WLToolkit.postEvent(new WindowEvent(getFrame(), WindowEvent.WINDOW_STATE_CHANGED, oldState, state));
                notifyClientDecorationsChanged();
            }
        }
    }

    private Frame getFrame() {
        return (Frame)target;
    }
}
