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
import java.awt.event.FocusEvent;
import java.awt.event.PaintEvent;
import java.awt.image.ColorModel;
import java.awt.image.VolatileImage;
import java.awt.peer.ComponentPeer;
import java.awt.peer.ContainerPeer;
import sun.awt.image.SunVolatileImage;
import sun.java2d.pipe.Region;
import sun.util.logging.PlatformLogger;


public class WLComponentPeer implements ComponentPeer
{
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.wl.WLComponentPeer");
    protected final Component target;

    /**
     * Standard peer constructor, with corresponding Component
     */
    WLComponentPeer(Component target) {
        this.target = target;
    }

    public void reparent(ContainerPeer newNativeParent) {
        log.info("Not implemented: WLComponentPeer.reparent(ContainerPeer)");
    }

    public boolean isReparentSupported() {
        log.info("Not implemented: WLComponentPeer.isReparentSupported()");
        return false;
    }

    public boolean isObscured() {
        log.info("Not implemented: WLComponentPeer.isObscured()");
        return true;
    }

    public boolean canDetermineObscurity() {
        return true;
    }

    public void focusGained(FocusEvent e) {
        log.info("Not implemented: WLComponentPeer.isObscured()");
    }

    public void focusLost(FocusEvent e) {
        log.info("Not implemented: WLComponentPeer.focusLost(FocusEvent)");
    }

    public boolean isFocusable() {
        /* should be implemented by other sub-classes */
        return false;
    }

    public final boolean requestFocus(Component lightweightChild, boolean temporary,
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

    public Graphics getGraphics() {
        log.info("Not implemented: WLComponentPeer.paint(Graphics)");
        return null;
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
        return null;
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
        return null;
    }

    public Dimension getPreferredSize() {
        return getMinimumSize();
    }

    public void layout() {}

    @Override
    public void setBackground(Color c) {
        log.info("Not implemented: WLComponentPeer.setBackground(Color)");
    }

    @Override
    public void setForeground(Color c) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Set foreground to " + c);
        }
        log.info("Not implemented: WLComponentPeer.setForeground(Color)");
    }

    public FontMetrics getFontMetrics(Font font) {
        log.info("Not implemented: WLComponentPeer.getFontMetrics(Font)");
        return null;
    }

    @Override
    public void dispose() {
    }

    @Override
    public void setFont(Font f) {
        log.info("Not implemented: WLComponentPeer.setFont(Font)");
    }

    public Font getFont() {
        return null;
    }

    public void updateCursorImmediately() {
        log.info("Not implemented: WLComponentPeer.updateCursorImmediately()");
    }

    public Image createImage(int width, int height) {
        log.info("Not implemented: WLComponentPeer.createImage(int width, int height)");
        return null;
    }

    public VolatileImage createVolatileImage(int width, int height) {
        return new SunVolatileImage(target, width, height);
    }

    @Override
    public GraphicsConfiguration getGraphicsConfiguration() {
        return null;
    }

    public boolean handlesWheelScrolling() {
        return false;
    }

    @Override
    public void createBuffers(int numBuffers, BufferCapabilities caps) throws AWTException {
        log.info("Not implemented: WLComponentPeer.createBuffers(int,BufferCapabilities)");
    }

    public void flip(int x1, int y1, int x2, int y2,
                     BufferCapabilities.FlipContents flipAction)
    {
        log.info("Not implemented: WLComponentPeer.flip(int,int,int,int,BufferCapabilities.FlipContents)");
    }

    public Image getBackBuffer() {
        log.info("Not implemented: WLComponentPeer.getBackBuffer()");
        return null;
    }

    public void destroyBuffers() {
        log.info("Not implemented: WLComponentPeer.destroyBuffers()");
    }

    public void setZOrder(ComponentPeer above) {
        log.info("Not implemented: WLComponentPeer.setZOrder(ComponentPeer)");
    }

    public void applyShape(Region shape) {
        log.info("Not implemented: WLComponentPeer.applyShape(Region)");
    }

    public boolean updateGraphicsData(GraphicsConfiguration gc) {
        log.info("Not implemented: WLComponentPeer.updateGraphicsData(GraphicsConfiguration)");
        return false;
    }
}