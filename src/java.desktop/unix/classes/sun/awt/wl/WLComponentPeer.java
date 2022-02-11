/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2021, JetBrains s.r.o.. All rights reserved.
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

import java.awt.AWTEvent;
import java.awt.AWTException;
import java.awt.BufferCapabilities;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.GraphicsConfiguration;
import java.awt.Image;
import java.awt.Point;
import java.awt.SystemColor;
import java.awt.Toolkit;
import java.awt.event.FocusEvent;
import java.awt.event.PaintEvent;
import java.awt.image.ColorModel;
import java.awt.image.VolatileImage;
import java.awt.peer.ComponentPeer;
import java.awt.peer.ContainerPeer;
import java.util.Objects;
import sun.java2d.SunGraphics2D;
import sun.java2d.SurfaceData;
import sun.java2d.pipe.Region;
import sun.util.logging.PlatformLogger;


public class WLComponentPeer implements ComponentPeer
{
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.wl.WLComponentPeer");
    protected final Component target;
    protected WLGraphicsConfig graphicsConfig;
    protected Color background;
    SurfaceData surfaceData;

    /**
     * Standard peer constructor, with corresponding Component
     */
    WLComponentPeer(Component target) {
        this.target = target;
        this.background = target.getBackground();
        initGraphicsConfiguration();
        this.surfaceData = graphicsConfig.createSurfaceData(this);
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
    public boolean isObscured() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean canDetermineObscurity() {
        throw new UnsupportedOperationException();
    }

    public void focusGained(FocusEvent e) {
        log.info("Not implemented: WLComponentPeer.isObscured()");
    }

    public void focusLost(FocusEvent e) {
        log.info("Not implemented: WLComponentPeer.focusLost(FocusEvent)");
    }

    @Override
    public boolean isFocusable() {
        throw new UnsupportedOperationException();
    }

    public boolean requestFocus(Component lightweightChild, boolean temporary,
                                      boolean focusedWindowChangeAllowed, long time,
                                      FocusEvent.Cause cause)
    {
        log.info("Not implemented: WLComponentPeer.focusLost(FocusEvent)");
        return true;
    }

    public void setVisible(boolean b) {
        log.info("Not implemented: WLComponentPeer.setVisible(boolean)");
    }

    /**
     * @see ComponentPeer
     */
    public void setEnabled(final boolean value) {
        log.info("Not implemented: WLComponentPeer.setEnabled(boolean)");
    }

    @Override
    public void paint(final Graphics g) {
        log.info("Not implemented: WLComponentPeer.paint(Graphics)");
    }

    Graphics getGraphics(SurfaceData surfData, Color afore, Color aback, Font afont) {
        if (surfData == null) return null;

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

    public Graphics getGraphics() {
        return getGraphics(surfaceData,
                target.getForeground(),
                target.getBackground(),
                target.getFont());
    }

    public Component getTarget() {
        return target;
    }

    public void print(Graphics g) {
        log.info("Not implemented: WLComponentPeer.print(Graphics)");
    }

    public void setBounds(int x, int y, int width, int height, int op) {
        log.info("Not implemented: WLComponentPeer.setBounds(int,int,int,int)");
    }

    public void coalescePaintEvent(PaintEvent e) {
        log.info("Not implemented: WLComponentPeer.coalescePaintEvent(PaintEvent)");
    }

    @Override
    public Point getLocationOnScreen() {
        throw new UnsupportedOperationException();
    }

    @SuppressWarnings("fallthrough")
    public void handleEvent(AWTEvent e) {
        log.info("Not implemented: WLComponentPeer.handleEvent(AWTEvent)");
    }

    public Dimension getMinimumSize() {
        return target.getSize();
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

    public Dimension getPreferredSize() {
        return getMinimumSize();
    }

    public void layout() {}

    @Override
    public void setBackground(Color c) {
        if (Objects.equals(background, c)) {
            return;
        }
        background = c;
    }

    @Override
    public void setForeground(Color c) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Set foreground to " + c);
        }
        log.info("Not implemented: WLComponentPeer.setForeground(Color)");
    }

    @Override
    public FontMetrics getFontMetrics(Font font) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void dispose() {
        WLToolkit.targetDisposedPeer(target, this);
    }

    @Override
    public void setFont(Font f) {
        throw new UnsupportedOperationException();
    }

    public Font getFont() {
        return null;
    }

    public void updateCursorImmediately() {
        log.info("Not implemented: WLComponentPeer.updateCursorImmediately()");
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
    public void flip(int x1, int y1, int x2, int y2, BufferCapabilities.FlipContents flipAction) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Image getBackBuffer() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void destroyBuffers() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setZOrder(ComponentPeer above) {
        log.info("Not implemented: WLComponentPeer.setZOrder(ComponentPeer)");
    }

    @Override
    public void applyShape(Region shape) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean updateGraphicsData(GraphicsConfiguration gc) {
        throw new UnsupportedOperationException();
    }
}