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

import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;

public abstract class WLDecoratedPeer extends WLWindowPeer {
    private final WLFrameDecoration decoration;

    public WLDecoratedPeer(Window target, boolean isUndecorated, boolean showMinimize, boolean showMaximize) {
        super(target);
        decoration = new WLFrameDecoration(this, isUndecorated, showMinimize, showMaximize);
    }

    private static native void initIDs();

    static {
        if (!GraphicsEnvironment.isHeadless()) {
            initIDs();
        }
    }

    public abstract boolean isResizable();

    public abstract void setState(int newState);
    public abstract int getState();
    public abstract void setExtendedState(int newState);

    @Override
    public Insets getInsets() {
        return decoration.getInsets();
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
        final Dimension targetMinimumSize = target.isMinimumSizeSet()
                ? target.getMinimumSize()
                : new Dimension(1, 1);
        final Dimension decorMinimumSize = decoration.getMinimumSize();
        final Dimension frameMinimumSize
                = (decorMinimumSize.getWidth() == 0 && decorMinimumSize.getHeight() == 0)
                ? new Dimension(1, 1)
                : decorMinimumSize;
        return new Rectangle(targetMinimumSize)
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
        boolean processed = decoration.processMouseEvent(e);
        if (!processed) {
            super.postMouseEvent(e);
        }
    }

    @Override
    void notifyConfigured(int newWidth, int newHeight, boolean active, boolean maximized) {
        super.notifyConfigured(newWidth, newHeight, active, maximized);
        decoration.setActive(active);
    }

    @Override
    public Rectangle getVisibleBounds() {
        // TODO: modify if our decorations ever acquire special effects that
        // do not count into "visible bounds" of the window
        return super.getVisibleBounds();
    }

    @Override
    public void setBounds(int x, int y, int width, int height, int op) {
        super.setBounds(x, y, width, height, op);
        notifyClientDecorationsChanged();
    }

    final void notifyClientDecorationsChanged() {
        final Rectangle bounds = decoration.getBounds();
        if (!bounds.isEmpty()) {
            decoration.markRepaintNeeded();
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

    final void paintClientDecorations(final Graphics g) {
        if (decoration.isRepaintNeeded()) {
            decoration.paint(g);
        }
    }

    @Override
    Cursor getCursor(int x, int y) {
        Cursor cursor = decoration.getCursor(x, y);
        if (cursor != null) {
            return cursor;
        } else {
            return super.getCursor(x, y);
        }
    }
}
