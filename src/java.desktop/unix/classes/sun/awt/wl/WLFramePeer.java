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

import java.awt.SystemColor;
import java.awt.Toolkit;
import java.util.Objects;
import sun.awt.PaintEventDispatcher;
import sun.java2d.SunGraphics2D;
import sun.java2d.SurfaceData;
import sun.java2d.pipe.Region;

import java.awt.AWTEvent;
import java.awt.AWTException;
import java.awt.BufferCapabilities;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.GraphicsConfiguration;
import java.awt.Image;
import java.awt.Insets;
import java.awt.MenuBar;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.FocusEvent;
import java.awt.event.PaintEvent;
import java.awt.event.WindowEvent;
import java.awt.image.ColorModel;
import java.awt.image.VolatileImage;
import java.awt.peer.ComponentPeer;
import java.awt.peer.ContainerPeer;
import java.awt.peer.FramePeer;
import sun.java2d.wl.WLSurfaceData;
import sun.util.logging.PlatformLogger;

public class WLFramePeer implements FramePeer {

    private final Frame target;

    private long nativePtr;
    SurfaceData surfaceData;
    private WLGraphicsConfig graphicsConfig;

    static {
        initIDs();
    }

    private Color background;

    public WLFramePeer(Frame target) {
        this.target = target;
        this.nativePtr = nativeCreateFrame();
        initGraphicsConfiguration();
        this.background = target.getBackground();
        this.surfaceData = graphicsConfig.createSurfaceData(this);
    }

    private static native void initIDs();

    public Frame getTarget() {
        return target;
    }

    @Override
    public boolean isObscured() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean canDetermineObscurity() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setVisible(boolean v) {
        if (v) {
            nativeShowFrame(nativePtr, target.getWidth(), target.getHeight());
            ((WLSurfaceData)surfaceData).initSurface(this, background != null ? background.getRGB() : 0, target.getWidth(), target.getHeight());
            PaintEvent event = PaintEventDispatcher.getPaintEventDispatcher().
                    createPaintEvent(target, 0, 0, target.getWidth(), target.getHeight());
            if (event != null) {
                WLToolkit.postEvent(WLToolkit.targetToAppContext(event.getSource()), event);
            }
        } else {
            nativeHideFrame(nativePtr);
        }
    }

    @Override
    public void setEnabled(boolean e) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void paint(Graphics g) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void print(Graphics g) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setBounds(int x, int y, int width, int height, int op) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void handleEvent(AWTEvent e) {
    }

    @Override
    public void coalescePaintEvent(PaintEvent e) {
        Component c = (Component)e.getSource();
        c.paint(c.getGraphics());
    }

    @Override
    public Point getLocationOnScreen() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Dimension getPreferredSize() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Dimension getMinimumSize() {
        throw new UnsupportedOperationException();
    }

    @Override
    public ColorModel getColorModel() {
        if (graphicsConfig != null) {
            return graphicsConfig.getColorModel ();
        }
        else {
            return Toolkit.getDefaultToolkit().getColorModel();
        }
    }

    Graphics getGraphics(SurfaceData surfData, Color afore, Color aback, Font afont) {
        if (surfData == null) return null;

        Component target = this.target;

        Color bgColor = aback;
        if (bgColor == null) {
            bgColor = SystemColor.window;
        }
        Color fgColor = afore;
        if (fgColor == null) {
            fgColor = SystemColor.windowText;
        }
        Font font = afont;
        if (font == null) {
            font = new Font(Font.DIALOG, Font.PLAIN, 12);
        }
        return new SunGraphics2D(surfData, fgColor, bgColor, font);
    }

    @Override
    public Graphics getGraphics() {
        return getGraphics(surfaceData,
                target.getForeground(),
                target.getBackground(),
                target.getFont());
    }

    @Override
    public FontMetrics getFontMetrics(Font font) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void dispose() {
        WLToolkit.targetDisposedPeer(target, this);
        nativeDisposeFrame(nativePtr);
    }

    @Override
    public void setForeground(Color c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setBackground(Color c) {
        if (Objects.equals(background, c)) {
            return;
        }
        background = c;
    }

    @Override
    public void setFont(Font f) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateCursorImmediately() {
    }

    @Override
    public boolean requestFocus(Component lightweightChild, boolean temporary, boolean focusedWindowChangeAllowed, long time, FocusEvent.Cause cause) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isFocusable() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Image createImage(int width, int height) {
        throw new UnsupportedOperationException();
    }

    @Override
    public VolatileImage createVolatileImage(int width, int height) {
        throw new UnsupportedOperationException();
    }

    protected void initGraphicsConfiguration() {
        graphicsConfig = (WLGraphicsConfig) target.getGraphicsConfiguration();
    }

    @Override
    public GraphicsConfiguration getGraphicsConfiguration() {
        if (graphicsConfig == null) {
            initGraphicsConfiguration();
        }
        return graphicsConfig;
    }

    @Override
    public boolean handlesWheelScrolling() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void createBuffers(int numBuffers, BufferCapabilities caps) throws AWTException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Image getBackBuffer() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void flip(int x1, int y1, int x2, int y2, BufferCapabilities.FlipContents flipAction) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void destroyBuffers() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void reparent(ContainerPeer newContainer) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isReparentSupported() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void layout() {
    }

    @Override
    public void applyShape(Region shape) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setZOrder(ComponentPeer above) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean updateGraphicsData(GraphicsConfiguration gc) {
        throw new UnsupportedOperationException();
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
    public void beginLayout() {
    }

    @Override
    public void endLayout() {
    }

    @Override
    public void setTitle(String title) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setMenuBar(MenuBar mb) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setResizable(boolean resizeable) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setState(int state) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getState() {
        throw new UnsupportedOperationException();
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
        throw new UnsupportedOperationException();
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

    private native long nativeCreateFrame();
    private native void nativeShowFrame(long ptr, int width, int height);
    private native void nativeHideFrame(long ptr);
    private native void nativeDisposeFrame(long ptr);

    private native long getWLSurface();

    // called from native code
    private void postWindowClosing() {
        WLToolkit.postEvent(new WindowEvent(target, WindowEvent.WINDOW_CLOSING));
    }
}
