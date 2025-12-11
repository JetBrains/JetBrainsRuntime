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

import sun.awt.SunToolkit;

import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;

public abstract class WLDecoratedPeer extends WLWindowPeer {
    private FrameDecoration decoration; // protected by stateLock
    private final boolean isUndecorated;
    private final boolean showMaximize;
    private final boolean showMinimize;

    private enum DecorationTypePreference {
        DEFAULT, // The default decorations painted purely in Java
        GTK,     // Decorations are painted with the help of GTK (if available)
        SERVER;  // Wayland server-side decorations (if available)
    }

    private static final DecorationTypePreference decorationType = determineDecorationPreferenceType();

    private static DecorationTypePreference determineDecorationPreferenceType() {
        String decorationPreference = System.getProperty("sun.awt.wl.WindowDecorationStyle");
        if (decorationPreference != null) {
            if ("builtin".equals(decorationPreference)) {
                return DecorationTypePreference.DEFAULT;
            } else if ("gtk".equals(decorationPreference) && isGTKAvailable()) {
                return DecorationTypePreference.GTK;
            } else if ("server".equals(decorationPreference) && WLToolkit.isSSDAvailable()) {
                return DecorationTypePreference.SERVER;
            } else {
                return DecorationTypePreference.DEFAULT;
            }
        } else {
            if (!WLToolkit.isKDE() && isGTKAvailable()) {
                return DecorationTypePreference.GTK;
            } else {
                return DecorationTypePreference.DEFAULT;
            }
        }
    }

    public WLDecoratedPeer(Window target, boolean isUndecorated, boolean showMinimize, boolean showMaximize) {
        super(target, decorationType != DecorationTypePreference.SERVER);
        this.isUndecorated = isUndecorated;
        this.showMinimize = showMinimize;
        this.showMaximize = showMaximize;
        decoration = determineDecoration(isUndecorated, showMinimize, showMaximize);
    }

    private FrameDecoration determineDecoration(boolean isUndecorated, boolean showMinimize, boolean showMaximize) {
        FrameDecoration d;
        if (isUndecorated) {
            d = new MinimalFrameDecoration(this);
        } else {
            d = switch (decorationType) {
                case DecorationTypePreference.DEFAULT -> new DefaultFrameDecoration(this, showMinimize, showMaximize);
                case DecorationTypePreference.GTK -> new GtkFrameDecoration(this, showMinimize, showMaximize);
                case DecorationTypePreference.SERVER -> new ServerSideFrameDecoration(this);
            };
        }
        return d;
    }

    private static boolean isGTKAvailable() {
        return ((WLToolkit) WLToolkit.getDefaultToolkit()).checkGtkVersion(3, 20, 0);
    }

    private static native void initIDs();

    static {
        if (!GraphicsEnvironment.isHeadless()) {
            initIDs();
        }
    }

    public abstract boolean isInteractivelyResizable();
    public abstract boolean isFrameStateSupported(int state);
    public abstract void setState(int newState);
    public abstract int getState();
    public abstract void setExtendedState(int newState);

    @Override
    public Insets getInsets() {
        return getDecoration().getContentInsets();
    }

    public final void resetDecoration(boolean isFullscreen) {
        synchronized (getStateLock()) {
            decoration.dispose();
            decoration = determineDecoration(isFullscreen | isUndecorated, showMinimize, showMaximize);
        }
        // Since the client area of the window may have changed, need to re-validate the target
        // to let it re-layout its children.
        target.invalidate();
        target.validate();
    }

    protected final FrameDecoration getDecoration() {
        synchronized (getStateLock()) {
            return decoration;
        }
    }

    @Override
    public void beginValidate() {
    }

    @Override
    public void endValidate() {
    }

    public void setTitle(String title) {
        setFrameTitle(title);
        notifyClientDecorationsChanged();
    }

    public void setResizable(boolean resizeable) {
        notifyClientDecorationsChanged();
    }

    @Override
    public Dimension getMinimumSize() {
        final Dimension parentMinimumSize = super.getMinimumSize();
        var d = getDecoration();
        final Dimension decorMinimumSize = d != null ? d.getMinimumSize() : new Dimension(0, 0);
        final Dimension frameMinimumSize
                = (decorMinimumSize.getWidth() == 0 && decorMinimumSize.getHeight() == 0)
                ? new Dimension(MINIMUM_WIDTH, MINIMUM_HEIGHT)
                : decorMinimumSize;
        return new Rectangle(parentMinimumSize)
                .union(new Rectangle(frameMinimumSize))
                .getSize();
    }

    @Override
    public void updateWindow() {
        // signals the end of repainting by Swing and/or AWT
        paintClientDecorations(getGraphics());
        super.updateWindow();
    }

    // called from native code
    void postWindowClosing() {
        WLToolkit.postEvent(new WindowEvent((Window) target, WindowEvent.WINDOW_CLOSING));
    }

    @Override
    void postMouseEvent(MouseEvent e) {
        boolean processed = getDecoration().processMouseEvent(e);
        if (!processed) {
            super.postMouseEvent(e);
        }
    }

    @Override
    void notifyConfigured(int newSurfaceX, int newSurfaceY, int newSurfaceWidth, int newSurfaceHeight,
                          boolean active, boolean maximized, boolean fullscreen) {
        assert SunToolkit.isAWTLockHeldByCurrentThread() : "This method must be invoked while holding the AWT lock";

        boolean wasFullscreen = isFullscreen();

        super.notifyConfigured(newSurfaceX, newSurfaceY, newSurfaceWidth, newSurfaceHeight,
                active, maximized, fullscreen);

        if (wasFullscreen != fullscreen) {
            if (fullscreen) {
                resetDecoration(true);
            } else {
                if (!isUndecorated) {
                    resetDecoration(false);
                }
            }
        }
        getDecoration().notifyConfigured(active, maximized, fullscreen);
    }

    @Override
    public void setBounds(int newX, int newY, int newWidth, int newHeight, int op) {
        super.setBounds(newX, newY, newWidth, newHeight, op);
        notifyClientDecorationsChanged();
    }

    final void notifyClientDecorationsChanged() {
        FrameDecoration d = getDecoration();
        Rectangle bounds = d.getTitleBarBounds();
        if (!bounds.isEmpty()) {
            d.markRepaintNeeded(true);
            postPaintEvent(bounds.x, bounds.y, bounds.width, bounds.height);
        }
    }

    void postPaintEvent() {
        if (isVisible()) {
            // Full re-paint must include window decorations, if any
            notifyClientDecorationsChanged();
            super.postPaintEvent();
        }
    }

    final void paintClientDecorations(Graphics g) {
        FrameDecoration d = getDecoration();
        if (d.isRepaintNeeded()) {
            d.paint(g);
        }
    }

    @Override
    Cursor cursorAt(int x, int y) {
        Cursor cursor = getDecoration().cursorAt(x, y);
        if (cursor != null) {
            return cursor;
        } else {
            return super.cursorAt(x, y);
        }
    }

    @Override
    public void dispose() {
        getDecoration().dispose();
        super.dispose();
    }

    @Override
    protected void notifyNativeWindowCreated(long nativePtr) {
        decoration.notifyNativeWindowCreated(nativePtr);
    }

    @Override
    protected void notifyNativeWindowToBeHidden(long nativePtr) {
        decoration.notifyNativeWindowToBeHidden(nativePtr);
    }
}
