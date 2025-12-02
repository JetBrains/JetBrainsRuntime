/*
 * Copyright 2022-2025 JetBrains s.r.o.
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

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Rectangle;

/**
 * Decorations based on the xdg-decoration-unstable-v1 protocol.
 * Supported iff WLToolkit.isSSDAvailable().
 *
 * The decoration itself is added by the server on a surface separate from the window itself,
 * so the window acts as if it is undecorated. For example, there are no insets, no special
 * repainting is done, etc.
 */
public class ServerSideFrameDecoration extends FrameDecoration {
    private long nativeDecorPtr;

    public ServerSideFrameDecoration(WLDecoratedPeer peer) {
        super(peer);
    }

    @Override
    public Insets getContentInsets() {
        return new Insets(0, 0, 0, 0);
    }

    @Override
    public Rectangle getTitleBarBounds() {
        return new Rectangle(0, 0, 0, 0);
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(0, 0);
    }

    @Override
    public void paint(Graphics g) {
        // Nothing to paint here, the Wayland server provides all the painting
    }

    @Override
    public void notifyConfigured(boolean active, boolean maximized, boolean fullscreen) {
    }

    @Override
    public boolean isRepaintNeeded() {
        return false;
    }

    @Override
    public void markRepaintNeeded(boolean value) {
        // Nothing to repaint
    }

    @Override
    public void notifyNativeWindowCreated(long nativePtr) {
        if (!peer.targetIsWlPopup()) {
            nativeDecorPtr = createToplevelDecorationImpl(nativePtr);
        }
    }

    @Override
    public void notifyNativeWindowToBeHidden(long nativePtr) {
        if (nativeDecorPtr != 0) {
            disposeImpl(nativeDecorPtr);
            nativeDecorPtr = 0;
        }
    }

    @Override
    public void dispose() {
        // Native resources must have been already disposed when the window was hidden
        assert nativeDecorPtr == 0 : "Native resources must have been already disposed";
    }

    private native long createToplevelDecorationImpl(long nativeFramePtr);
    private native void disposeImpl(long nativeDecorPtr);
}
