/*
 * Copyright 2026 JetBrains s.r.o.
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

import sun.java2d.SurfaceData;
import sun.lwawt.LWWindowPeerAPI;
import sun.lwawt.PlatformWindow;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GraphicsDevice;
import java.awt.Insets;
import java.awt.MenuBar;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.FocusEvent;

public class WLPlatformWindow implements PlatformWindow {

    private final WLWindowPeer peer;

    WLPlatformWindow(WLWindowPeer peer) {
        this.peer = peer;
    }

    @Override
    public LWWindowPeerAPI getPeer() {
        return peer;
    }

    @Override
    public void initialize(Window target, LWWindowPeerAPI peer, PlatformWindow owner) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void dispose() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setVisible(boolean visible) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setTitle(String title) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setBounds(int x, int y, int w, int h) {
        throw new UnsupportedOperationException();
    }

    @Override
    public GraphicsDevice getGraphicsDevice() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Point getLocationOnScreen() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Insets getInsets() {
        throw new UnsupportedOperationException();
    }

    @Override
    public FontMetrics getFontMetrics(Font f) {
        throw new UnsupportedOperationException();
    }

    @Override
    public SurfaceData getScreenSurface() {
        throw new UnsupportedOperationException();
    }

    @Override
    public SurfaceData replaceSurfaceData() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setModalBlocked(boolean blocked) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void toFront() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void toBack() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setMenuBar(MenuBar mb) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setAlwaysOnTop(boolean value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateFocusableWindowState() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean rejectFocusRequest(FocusEvent.Cause cause) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean requestWindowFocus() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isActive() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setResizable(boolean resizable) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setSizeConstraints(int minW, int minH, int maxW, int maxH) {
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
    public void enterFullScreenMode() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void exitFullScreenMode() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isFullScreenMode() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setWindowState(int windowState) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long getLayerPtr() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isUnderMouse() {
        throw new UnsupportedOperationException();
    }

    @Override
    public long getWindowHandle() {
        throw new UnsupportedOperationException();
    }
}
