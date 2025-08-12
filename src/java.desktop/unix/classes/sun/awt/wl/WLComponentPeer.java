/*
 * Copyright (c) 2022-2024, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2022-2024, JetBrains s.r.o.. All rights reserved.
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
import sun.awt.AWTAccessor.ComponentAccessor;
import sun.awt.PaintEventDispatcher;
import sun.awt.SunToolkit;
import sun.awt.event.IgnorePaintEvent;
import sun.awt.image.SunVolatileImage;
import sun.java2d.SunGraphics2D;
import sun.java2d.SunGraphicsEnvironment;
import sun.java2d.SurfaceData;
import sun.java2d.pipe.Region;
import sun.java2d.wl.WLSurfaceDataExt;
import sun.java2d.wl.WLSurfaceSizeListener;
import sun.util.logging.PlatformLogger;
import sun.util.logging.PlatformLogger.Level;

import javax.swing.SwingUtilities;
import java.awt.AWTEvent;
import java.awt.AWTException;
import java.awt.BufferCapabilities;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.GraphicsConfiguration;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.SystemColor;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.InputMethodEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.PaintEvent;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.awt.image.VolatileImage;
import java.awt.peer.ComponentPeer;
import java.awt.peer.ContainerPeer;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Supplier;

public class WLComponentPeer implements ComponentPeer, WLSurfaceSizeListener {
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.wl.WLComponentPeer");
    private static final PlatformLogger focusLog = PlatformLogger.getLogger("sun.awt.wl.focus.WLComponentPeer");
    private static final PlatformLogger popupLog = PlatformLogger.getLogger("sun.awt.wl.popup.WLComponentPeer");

    private static final int MINIMUM_WIDTH = 1;
    private static final int MINIMUM_HEIGHT = 1;

    private final Object stateLock = new Object();

    private long nativePtr; // accessed under AWT lock
    protected final Component target;

    private Color background; // protected by stateLock
    protected SurfaceData surfaceData; // accessed under AWT lock
    private WLMainSurface wlSurface; // accessed under AWT lock
    private Shadow shadow; // accessed under AWT lock
    private final WLRepaintArea paintArea;
    private boolean paintPending = false; // protected by stateLock
    private boolean isLayouting = false; // protected by stateLock
    protected boolean visible = false;

    private boolean isFullscreen = false;  // protected by stateLock
    private boolean sizeIsBeingConfigured = false; // protected by stateLock
    private int displayScale; // protected by stateLock
    private double effectiveScale; // protected by stateLock
    private final WLSize wlSize = new WLSize();
    private boolean repositionPopup = false; // protected by stateLock
    private boolean resizePending = false; // protected by stateLock

    static {
        initIDs();
    }

    /**
     * Standard peer constructor, with corresponding Component
     */
    WLComponentPeer(Component target) {
        this.target = target;
        this.background = target.getBackground();
        Dimension size = constrainSize(target.getBounds().getSize());
        final WLGraphicsConfig config = (WLGraphicsConfig) target.getGraphicsConfiguration();
        displayScale = config.getDisplayScale();
        effectiveScale = config.getEffectiveScale();
        wlSize.deriveFromJavaSize(size.width, size.height);
        surfaceData = config.createSurfaceData(this);
        nativePtr = nativeCreateFrame();
        paintArea = new WLRepaintArea();
        if (log.isLoggable(Level.FINE)) {
            log.fine("WLComponentPeer: target=" + target + " with size=" + wlSize);
        }

        shadow = new Shadow(targetIsWlPopup() ? ShadowImage.POPUP_SHADOW_SIZE : ShadowImage.WINDOW_SHADOW_SIZE);
        // TODO
        // setup parent window for target
    }

    int getDisplayScale() {
        synchronized (getStateLock()) {
            return displayScale;
        }
    }

    public int getWidth() {
        return wlSize.getJavaWidth();
    }

    public int getHeight() {
        return wlSize.getJavaHeight();
    }

    public Color getBackground() {
        synchronized (getStateLock()) {
            return background;
        }
    }

    public void postPaintEvent(int x, int y, int w, int h) {
        if (isVisible()) {
            PaintEvent event = PaintEventDispatcher.getPaintEventDispatcher().
                    createPaintEvent(target, x, y, w, h);
            if (event != null) {
                WLToolkit.postEvent(event);
            }
        }
    }

    void postPaintEvent() {
        if (isVisible()) {
            postPaintEvent(0, 0, getWidth(), getHeight());
        }
    }

    boolean isVisible() {
        WLToolkit.awtLock();
        try {
            return wlSurface != null && visible;
        } finally {
            WLToolkit.awtUnlock();
        }
    }

    boolean isFullscreen() {
        synchronized (getStateLock()) {
            return isFullscreen;
        }
    }

    @Override
    public void reparent(ContainerPeer newContainer) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isReparentSupported() {
        return false;
    }

    @Override
    public boolean isObscured() {
        // In general, it is impossible to know this in Wayland
        return false;
    }

    @Override
    public boolean canDetermineObscurity() {
        return false;
    }

    public void focusGained(FocusEvent e) {
    }

    public void focusLost(FocusEvent e) {
    }

    @Override
    public boolean isFocusable() {
        return false;
    }

    public boolean requestFocus(Component lightweightChild, boolean temporary,
                                boolean focusedWindowChangeAllowed, long time,
                                FocusEvent.Cause cause) {
        final Window currentlyFocusedWindow = WLKeyboardFocusManagerPeer.getInstance().getCurrentFocusedWindow();
        if (currentlyFocusedWindow == null)
            return false;

        if (WLKeyboardFocusManagerPeer.
                processSynchronousLightweightTransfer(target, lightweightChild, temporary,
                        false, time)) {
            return true;
        }

        Window nativelyFocusableWindow = getNativelyFocusableOwnerOrSelf(target);
        int result = WLKeyboardFocusManagerPeer.
                shouldNativelyFocusHeavyweight(nativelyFocusableWindow, lightweightChild,
                        temporary, false,
                        time, cause, true);

        switch (result) {
            case WLKeyboardFocusManagerPeer.SNFH_FAILURE:
                return false;

            case WLKeyboardFocusManagerPeer.SNFH_SUCCESS_PROCEED: {
                Component focusOwner = WLKeyboardFocusManagerPeer.getInstance().getCurrentFocusOwner();
                return WLKeyboardFocusManagerPeer.deliverFocus(lightweightChild,
                        target,
                        true,
                        cause,
                        focusOwner);
            }

            case WLKeyboardFocusManagerPeer.SNFH_SUCCESS_HANDLED:
                // Either lightweight or excessive request - all events are generated.
                return true;
        }

        return false;
    }

    private static Window getToplevelFor(Component component) {
        Container container = component instanceof Container c ? c : component.getParent();
        for (Container p = container; p != null; p = p.getParent()) {
            if (p instanceof Window window && !isWlPopup(window)) {
                return window;
            }
        }

        return null;
    }

    private static Component realParentFor(Component c) {
        return (c instanceof Window window && isWlPopup(window))
                ? AWTAccessor.getWindowAccessor().getPopupParent(window)
                : c.getParent();
    }

    static Point getRelativeLocation(Component c, Window toplevel) {
        Objects.requireNonNull(c);

        if (toplevel == null) {
            return c.getLocation();
        }

        int x = 0, y = 0;
        while (c != null && c != toplevel) {
            x += c.getX();
            y += c.getY();
            c = realParentFor(c);
        }

        return new Point(x, y);
    }

    static void moveToOverlap(Rectangle what, Rectangle where) {
        if (what.getMaxX() <= where.getMinX()) {
            what.x += where.getMaxX() - what.getMaxX();
        }
        if (what.getMinX() >= where.getMaxX()) {
            what.x -= what.getMinX() - where.getMaxX();
        }
        if (what.getMaxY() <= where.getMinY()) {
            what.y += where.getMaxY() - what.getMaxY();
        }
        if (what.getMinY() >= where.getMaxY()) {
            what.y -= what.getMinY() - where.getMaxY();
        }
        assert what.intersects(where);
    }

    Point nativeLocationForPopup(Window popup, Component popupParent, Window toplevel) {
        // NB: all the coordinates are in the "surface" space as consumed by Wayland,
        //     not in pixels and not in the Java units.

        // We need to provide popup's "parent" location relative to the surface this parent is painted upon:
        Point parentLocation = javaUnitsToSurfaceUnits(getRelativeLocation(popupParent, toplevel));

        // Offset is relative to the top-left corner of the "parent".
        Point offsetFromParent = javaUnitsToSurfaceUnits(popup.getLocation());
        var popupBounds = new Rectangle(
                parentLocation.x + offsetFromParent.x,
                parentLocation.y + offsetFromParent.y,
                wlSize.getSurfaceWidth(),
                wlSize.getSurfaceHeight());
        var safeToplevelBounds = toplevel.getSize();
        safeToplevelBounds.height -= 1;
        safeToplevelBounds.width -= 1;
        var safePopupBounds = new Rectangle(
                0,
                0,
                javaUnitsToSurfaceUnits(safeToplevelBounds.width),
                javaUnitsToSurfaceUnits(safeToplevelBounds.height));
        if (!safePopupBounds.intersects(popupBounds)) {
            // Many Wayland compositors will immediately send popup_done to a popup that attempts to
            // go outside the parent surface's bounds.
            moveToOverlap(popupBounds, safePopupBounds);
        }
        return popupBounds.getLocation();
    }

    protected void wlSetVisible(boolean v) {
        // TODO: this whole method should be moved to WLWindowPeer
        synchronized (getStateLock()) {
            if (this.visible == v) return;

            this.visible = v;
        }
        if (v) {
            String title = getTitle();
            boolean isWlPopup = targetIsWlPopup();
            int thisWidth = javaUnitsToSurfaceSize(getWidth());
            int thisHeight = javaUnitsToSurfaceSize(getHeight());
            boolean isModal = targetIsModal();

            int state = (target instanceof Frame frame)
                    ? frame.getExtendedState()
                    : Frame.NORMAL;
            boolean isMaximized = (state & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH;
            boolean isMinimized = (state & Frame.ICONIFIED) == Frame.ICONIFIED;

            performLocked(() -> {
                assert wlSurface == null;
                wlSurface = new WLMainSurface((WLWindowPeer) this);
                long wlSurfacePtr = wlSurface.getWlSurfacePtr();
                if (isWlPopup) {
                    Window popup = (Window) target;
                    Component popupParent = AWTAccessor.getWindowAccessor().getPopupParent(popup);
                    Window toplevel = getToplevelFor(popupParent);
                    Point nativeLocation = nativeLocationForPopup(popup, popupParent, toplevel);
                    nativeCreatePopup(nativePtr, getNativePtrFor(toplevel), wlSurfacePtr,
                            thisWidth, thisHeight, nativeLocation.x, nativeLocation.y);
                } else {
                    nativeCreateWindow(nativePtr, getParentNativePtr(target), wlSurfacePtr,
                            isModal, isMaximized, isMinimized, title, WLToolkit.getApplicationID());
                    int xNative = javaUnitsToSurfaceUnits(target.getX());
                    int yNative = javaUnitsToSurfaceUnits(target.getY());
                    WLRobotPeer.setLocationOfWLSurface(wlSurface, xNative, yNative);
                }

                shadow.createSurface();

                // From xdg-shell.xml: "After creating a role-specific object and
                // setting it up, the client must perform an initial commit
                // without any buffer attached"
                shadow.commitSurface();
                wlSurface.commit();

                ((WLToolkit) Toolkit.getDefaultToolkit()).flush();
            });
            configureWLSurface();
            // Now wait for the sequence of configure events and the window
            // will finally appear on screen after we post a PaintEvent
            // from notifyConfigured()
        } else {
            performLocked(() -> {
                if (wlSurface != null) { // may get a "hide" request even though we were never shown
                    nativeHideFrame(nativePtr);

                    shadow.hide();
                    wlSurface.dispose();
                    wlSurface = null;
                }
            });
        }
    }

    /**
     * Returns true if our target should be treated as a popup in Wayland's sense,
     * i.e. it has to have a parent to position relative to.
     */
    protected boolean targetIsWlPopup() {
        return target instanceof Window window && isWlPopup(window);
    }

    static boolean isWlPopup(Window window) {
        return window.getType() == Window.Type.POPUP
                && AWTAccessor.getWindowAccessor().getPopupParent(window) != null;
    }

    private boolean targetIsModal() {
        return target instanceof Dialog dialog
                && (dialog.getModalityType() == Dialog.ModalityType.APPLICATION_MODAL
                || dialog.getModalityType() == Dialog.ModalityType.TOOLKIT_MODAL);
    }

    void updateSurfaceData() {
        SurfaceData.convertTo(WLSurfaceDataExt.class, surfaceData).revalidate(
                getGraphicsConfiguration(), getBufferWidth(), getBufferHeight(), getDisplayScale());

        shadow.updateSurfaceData();
    }

    @Override
    public void updateSurfaceSize() {
        assert SunToolkit.isAWTLockHeldByCurrentThread();
        // Note: must be called after a buffer of proper size has been attached to the surface,
        // but the surface has not yet been committed. Otherwise, the sizes will get out of sync,
        // which may result in visual artifacts.
        int surfaceWidth = wlSize.getSurfaceWidth();
        int surfaceHeight = wlSize.getSurfaceHeight();
        Dimension minSize = getMinimumSize();
        if (target.isMinimumSizeSet()) {
            Dimension targetMinSize = target.getMinimumSize();
            minSize.width = Math.max(minSize.width, targetMinSize.width);
            minSize.height = Math.max(minSize.height, targetMinSize.height);
        }
        Dimension surfaceMinSize = javaUnitsToSurfaceSize(constrainSize(minSize));
        Dimension maxSize = target.isMaximumSizeSet() ? target.getMaximumSize() : null;
        Dimension surfaceMaxSize = maxSize != null ? javaUnitsToSurfaceSize(constrainSize(maxSize)) : null;

        wlSurface.updateSurfaceSize(surfaceWidth, surfaceHeight);
        nativeSetWindowGeometry(nativePtr, 0, 0, surfaceWidth, surfaceHeight);
        nativeSetMinimumSize(nativePtr, surfaceMinSize.width, surfaceMinSize.height);
        if (surfaceMaxSize != null) {
            nativeSetMaximumSize(nativePtr, surfaceMaxSize.width, surfaceMaxSize.height);
        }

        if (popupNeedsReposition()) {
            popupRepositioned();

            // Since popup's reposition request includes both its size and location, the request
            // needs to be in sync with all the other sizes this method is responsible for updating.
            Window popup = (Window) target;
            final Component popupParent = AWTAccessor.getWindowAccessor().getPopupParent(popup);
            final Window toplevel = getToplevelFor(popupParent);
            Point nativeLocation = nativeLocationForPopup(popup, popupParent, toplevel);
            nativeRepositionWLPopup(nativePtr, surfaceWidth, surfaceHeight, nativeLocation.x, nativeLocation.y);
        }
    }

    void configureWLSurface() {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine(String.format("%s is configured to %dx%d pixels", this, getBufferWidth(), getBufferHeight()));
        }
        updateSurfaceData();
    }

    @Override
    public void setVisible(boolean v) {
        wlSetVisible(v);
    }

    /**
     * @see ComponentPeer
     */
    public void setEnabled(final boolean value) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Not implemented: WLComponentPeer.setEnabled(boolean)");
        }
    }

    @Override
    public void paint(final Graphics g) {
        paintPeer(g);
        target.paint(g);
    }

    void paintPeer(final Graphics g) {
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

    /**
     * Commits changes accumulated in the underlying SurfaceData object
     * to the server for displaying on the screen. The request may not be
     * granted immediately as the server may be busy reading data provided
     * previously. In the latter case, the commit will automatically happen
     * later when the server notifies us (through an event on EDT) that
     * the displaying buffer is ready to accept new data.
     */
    public void commitToServer() {
        performLocked(() -> {
            if (wlSurface != null) {
                shadow.paint();
                shadow.commitSurfaceData();
                SurfaceData.convertTo(WLSurfaceDataExt.class, surfaceData).commit();
            }
        });
        ((WLToolkit) Toolkit.getDefaultToolkit()).flush();
    }

    public Component getTarget() {
        return target;
    }

    public void print(Graphics g) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Not implemented: WLComponentPeer.print(Graphics)");
        }
    }

    private void resetTargetLocationTo(int newX, int newY) {
        var acc = AWTAccessor.getComponentAccessor();
        acc.setLocation(target, newX, newY);
    }

    private boolean popupNeedsReposition() {
        synchronized (getStateLock()) {
            return repositionPopup;
        }
    }

    private void markPopupNeedsReposition() {
        synchronized (getStateLock()) {
            repositionPopup = true;
        }
    }

    private void popupRepositioned() {
        synchronized (getStateLock()) {
            repositionPopup = false;
        }
    }

    private boolean resizePending() {
        synchronized (getStateLock()) {
            return resizePending;
        }
    }

    private void markResizePending() {
        synchronized (getStateLock()) {
            resizePending = true;
        }
    }

    private void resizeCompleted() {
        synchronized (getStateLock()) {
            resizePending = false;
        }
    }

    public void setBounds(int newX, int newY, int newWidth, int newHeight, int op) {
        Dimension newSize = constrainSize(newWidth, newHeight);
        boolean positionChanged = (op == SET_BOUNDS || op == SET_LOCATION);
        boolean sizeChanged = (op == SET_BOUNDS || op == SET_SIZE || op == SET_CLIENT_SIZE);
        boolean isPopup = targetIsWlPopup();

        if (positionChanged && isVisible() && !isPopup) {
            // Wayland provides the ability to programmatically change the location of popups,
            // but not top-level windows. So we can only ask robot to do that.
            int newXNative = javaUnitsToSurfaceUnits(newX);
            int newYNative = javaUnitsToSurfaceUnits(newY);
            performLocked(() -> WLRobotPeer.setLocationOfWLSurface(wlSurface, newXNative, newYNative));
        }

        if ((positionChanged || sizeChanged) && isPopup && visible) {
            // Need to update the location and size even if does not (yet) have a surface
            // as the initial configure event needs to have the latest data on the location/size.
            markPopupNeedsReposition();
        }

        if (positionChanged) {
            WLToolkit.postEvent(new ComponentEvent(getTarget(), ComponentEvent.COMPONENT_MOVED));
        }

        if (sizeChanged) {
            if (!isSizeBeingConfigured()) {
                wlSize.deriveFromJavaSize(newSize.width, newSize.height);
                shadow.resizeToParentWindow();
                markResizePending();
            }
            if (log.isLoggable(PlatformLogger.Level.FINE)) {
                log.fine(String.format("%s is resizing its buffer to %dx%d pixels",
                        this, getBufferWidth(), getBufferHeight()));
            }
            updateSurfaceData();
            layout();

            WLToolkit.postEvent(new ComponentEvent(getTarget(), ComponentEvent.COMPONENT_RESIZED));

            postPaintEvent(); // no need to repaint after being moved, only when resized
        }
    }

    boolean isSizeBeingConfigured() {
        synchronized (getStateLock()) {
            return sizeIsBeingConfigured;
        }
    }

    private void setSizeIsBeingConfigured(boolean value) {
        synchronized (getStateLock()) {
            sizeIsBeingConfigured = value;
        }
    }

    public int getBufferWidth() {
        return wlSize.getPixelWidth();
    }

    public int getBufferHeight() {
        return wlSize.getPixelHeight();
    }

    public Rectangle getBufferBounds() {
        synchronized (getStateLock()) {
            return new Rectangle(getBufferWidth(), getBufferHeight());
        }
    }

    public void coalescePaintEvent(PaintEvent e) {
        Rectangle r = e.getUpdateRect();
        if (!(e instanceof IgnorePaintEvent)) {
            paintArea.add(r, e.getID());
        }

        switch (e.getID()) {
            case PaintEvent.UPDATE -> {
                if (log.isLoggable(Level.FINE)) {
                    log.fine("WLCP coalescePaintEvent : UPDATE : add : x = " +
                            r.x + ", y = " + r.y + ", width = " + r.width + ",height = " + r.height);
                }
            }
            case PaintEvent.PAINT -> {
                if (log.isLoggable(Level.FINE)) {
                    log.fine("WLCP coalescePaintEvent : PAINT : add : x = " +
                            r.x + ", y = " + r.y + ", width = " + r.width + ",height = " + r.height);
                }
            }
        }
    }

    @Override
    public Point getLocationOnScreen() {
        return performLocked(() -> {
            if (wlSurface != null) {
                try {
                    return WLRobotPeer.getLocationOfWLSurface(wlSurface);
                } catch (UnsupportedOperationException ignore) {
                    return getFakeLocationOnScreen();
                }
            } else {
                return getFakeLocationOnScreen();
            }
        }, this::getFakeLocationOnScreen);
    }

    private Point getFakeLocationOnScreen() {
        // If we can't learn the real location from WLRobotPeer, we can at least
        // return a reasonable fake. This fake location places all windows in the top-left
        // corner of their respective screen and popups at the offset from
        // their parents' fake screen location.
        if (targetIsWlPopup()) {
            Window popup = (Window) target;
            Component popupParent = AWTAccessor.getWindowAccessor().getPopupParent(popup);
            Window toplevel = getToplevelFor(popupParent);
            Point popupOffset = popup.getLocation(); // popup's offset from its parent
            if (toplevel != null) {
                Point parentOffset = getRelativeLocation(popupParent, toplevel);
                Point thisLocation = toplevel.getLocationOnScreen();
                thisLocation.translate(parentOffset.x, parentOffset.y);
                thisLocation.translate(popupOffset.x, popupOffset.y);
                return thisLocation;
            } else {
                return popupOffset;
            }
        } else {
            GraphicsConfiguration graphicsConfig = target.getGraphicsConfiguration();
            if (graphicsConfig != null) {
                return graphicsConfig.getBounds().getLocation();
            } else {
                return new Point();
            }
        }
    }

    /**
     * Translate the point of coordinates relative to this component to the absolute coordinates,
     * if getLocationOnScreen() is supported. Otherwise, the argument is returned.
     */
    Point relativePointToAbsolute(Point relativePoint) {
        Point absolute = relativePoint;
        try {
            final Point myLocation = getLocationOnScreen();
            absolute = absolute.getLocation();
            absolute.translate(myLocation.x, myLocation.y);
        } catch (UnsupportedOperationException ignore) {
        }
        return absolute;
    }

    @SuppressWarnings("fallthrough")
    public void handleEvent(AWTEvent e) {
        if ((e instanceof InputEvent inputEvent) && !inputEvent.isConsumed() && target.isEnabled()) {
            if (e instanceof MouseEvent mouseEvent) {
                if (e instanceof MouseWheelEvent mouseWheelEvent) {
                    handleJavaMouseWheelEvent(mouseWheelEvent);
                } else
                    handleJavaMouseEvent(mouseEvent);
            } else if (e instanceof KeyEvent keyEvent) {
                handleJavaKeyEvent(keyEvent);
            }
        } else if (e instanceof KeyEvent && !((InputEvent) e).isConsumed()) {
            // even if target is disabled.
            if (log.isLoggable(PlatformLogger.Level.FINE)) {
                log.fine("Not implemented: WLComponentPeer.handleEvent(AWTEvent): handleF10JavaKeyEvent");
            }
        } else if (e instanceof InputMethodEvent) {
            if (log.isLoggable(PlatformLogger.Level.FINE)) {
                log.fine("Not implemented: WLComponentPeer.handleEvent(AWTEvent): handleJavaInputMethodEvent");
            }
        }

        int id = e.getID();

        switch (id) {
            case PaintEvent.PAINT:
                // Got native painting
                paintPending = false;
                // Fallthrough to next statement
            case PaintEvent.UPDATE:
                if (log.isLoggable(Level.FINE)) {
                    log.fine("UPDATE " + this);
                }
                // Skip all painting while layouting and all UPDATEs
                // while waiting for native paint
                if (!isLayouting && !paintPending) {
                    paintArea.paint(target, false);
                }
                return;
            case FocusEvent.FOCUS_LOST:
            case FocusEvent.FOCUS_GAINED:
                handleJavaFocusEvent(e);
                break;
            case WindowEvent.WINDOW_LOST_FOCUS:
            case WindowEvent.WINDOW_GAINED_FOCUS:
                handleJavaWindowFocusEvent(e);
                break;
            default:
                break;
        }
    }

    void handleJavaKeyEvent(KeyEvent e) {
    }

    void handleJavaMouseEvent(MouseEvent e) {
    }

    void handleJavaMouseWheelEvent(MouseWheelEvent e) {
    }

    void handleJavaFocusEvent(AWTEvent e) {
        if (focusLog.isLoggable(PlatformLogger.Level.FINER)) {
            focusLog.finer(String.valueOf(e));
        }

        if (e.getID() == FocusEvent.FOCUS_GAINED) {
            focusGained((FocusEvent)e);
        } else {
            focusLost((FocusEvent)e);
        }
    }

    void handleJavaWindowFocusEvent(AWTEvent e) {
    }

    public void beginLayout() {
        // Skip all painting till endLayout
        synchronized (getStateLock()) {
            isLayouting = true;
        }
    }

    private boolean needPaintEvent() {
        synchronized (getStateLock()) {
            // If not waiting for native painting, repaint the damaged area
            return !paintPending && !paintArea.isEmpty()
                    && !AWTAccessor.getComponentAccessor().getIgnoreRepaint(target);
        }
    }

    public void endLayout() {
        if (log.isLoggable(Level.FINE)) {
            log.fine("WLComponentPeer.endLayout(): paintArea.isEmpty() " + paintArea.isEmpty());
        }
        if (needPaintEvent()) {
            WLToolkit.postEvent(new PaintEvent(target, PaintEvent.PAINT, new Rectangle()));
        }
        synchronized (getStateLock()) {
            isLayouting = false;
        }
    }

    public Dimension getMinimumSize() {
        int shadowSize = (int) Math.ceil(shadow.getSize() * 4);
        return new Dimension(shadowSize, shadowSize);
    }

    void showWindowMenu(long serial, int x, int y) {
        // "This request must be used in response to some sort of user action like
        //  a button press, key press, or touch down event."
        // So 'serial' must appertain to such an event.

        assert serial != 0;

        int xNative = javaUnitsToSurfaceUnits(x);
        int yNative = javaUnitsToSurfaceUnits(y);
        performLocked(() -> nativeShowWindowMenu(serial, nativePtr, xNative, yNative));
    }

    @Override
    public ColorModel getColorModel() {
        GraphicsConfiguration graphicsConfig = target.getGraphicsConfiguration();
        if (graphicsConfig != null) {
            return graphicsConfig.getColorModel();
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
        synchronized (getStateLock()) {
            if (Objects.equals(background, c)) {
                return;
            }
            background = c;
            // TODO: propagate this change to WLSurfaceData
        }
    }

    @Override
    public void setForeground(Color c) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Set foreground to " + c);
        }

        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Not implemented: WLComponentPeer.setForeground(Color)");
        }
    }

    @Override
    public FontMetrics getFontMetrics(Font font) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void dispose() {
        WLToolkit.targetDisposedPeer(target, this);

        performLocked(() -> {
            assert !isVisible();

            nativeDisposeFrame(nativePtr);
            nativePtr = 0;
            if (wlSurface != null) {
                wlSurface.dispose();
                wlSurface = null;
            }
            SurfaceData oldData = surfaceData;
            surfaceData = null;
            if (oldData != null) {
                oldData.invalidate();
            }
            if (shadow != null) {
                shadow.dispose();
                shadow = null;
            }
        });
    }

    @Override
    public void setFont(Font f) {
        throw new UnsupportedOperationException();
    }

    public Font getFont() {
        return null;
    }

    public void updateCursorImmediately() {
        WLToolkit.getCursorManager().updateCursorImmediatelyFor(this);
    }

    Cursor cursorAt(int x, int y) {
        Component target = this.target;
        if (target instanceof Container) {
            Component c = AWTAccessor.getContainerAccessor().findComponentAt((Container) target, x, y, false);
            if (c != null) {
                target = c;
            }
        }
        return AWTAccessor.getComponentAccessor().getCursor(target);
    }

    @Override
    public Image createImage(int width, int height) {
        WLGraphicsConfig graphicsConfig = (WLGraphicsConfig) target.getGraphicsConfiguration();
        return graphicsConfig.createAcceleratedImage(target, width, height);
    }

    @Override
    public VolatileImage createVolatileImage(int width, int height) {
        return new SunVolatileImage(target, width, height);
    }

    @Override
    public GraphicsConfiguration getGraphicsConfiguration() {
        return target.getGraphicsConfiguration();
    }

    @Override
    public boolean handlesWheelScrolling() {
        return false;
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
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Not implemented: WLComponentPeer.setZOrder(ComponentPeer)");
        }
    }

    @Override
    public void applyShape(Region shape) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean updateGraphicsData(GraphicsConfiguration gc) {
        final int newScale = ((WLGraphicsConfig)gc).getDisplayScale();

        WLGraphicsDevice gd = ((WLGraphicsConfig) gc).getDevice();
        gd.addWindow(this);
        synchronized (getStateLock()) {
            if (newScale != displayScale) {
                displayScale = newScale;
                effectiveScale = ((WLGraphicsConfig)gc).getEffectiveScale();
                wlSize.updateWithNewScale();
                shadow.resizeToParentWindow();
                if (log.isLoggable(PlatformLogger.Level.FINE)) {
                    log.fine(String.format("%s is updating buffer to %dx%d pixels", this, getBufferWidth(), getBufferHeight()));
                }
            }
            updateSurfaceData();
            postPaintEvent();
        }

        // Not sure what would need to have changed in Wayland's graphics configuration
        // to warrant destroying the peer and creating a new one from scratch.
        // So return "never recreate" here.
        return false;
    }

    final void setFrameTitle(String title) {
        Objects.requireNonNull(title);
        performLocked(() -> nativeSetTitle(nativePtr, title));
    }

    public String getTitle() {
        return null;
    }

    final void requestMinimized() {
        performLocked(() -> nativeRequestMinimized(nativePtr));
    }

    final void requestMaximized() {
        performLocked(() -> nativeRequestMaximized(nativePtr));
    }

    final void requestUnmaximized() {
        performLocked(() -> nativeRequestUnmaximized(nativePtr));
    }

    final void requestFullScreen(int wlID) {
        performLocked(() -> nativeRequestFullScreen(nativePtr, wlID));
    }

    final void requestUnsetFullScreen() {
        performLocked(() -> nativeRequestUnsetFullScreen(nativePtr));
    }

    final void activate() {
        // "The serial can come from an input or focus event."
        long serial = getSerialForActivation();
        if (serial != 0) {
            performLocked(() -> {
                long surface = WLToolkit.getInputState().surfaceForKeyboardInput();
                // The surface pointer may be out of date, which will cause a protocol error.
                // So make sure it is valid and do that under AWT lock.
                if (wlSurface != null && surface != 0 && WLToolkit.peerFromSurface(surface) != null) {
                    wlSurface.activateByAnotherSurface(serial, surface);
                }
            });
        }
    }

    private static long getSerialForActivation() {
        long serial = WLToolkit.getInputState().keyboardEnterSerial(); // a focus event
        if (serial == 0) { // may have just left one surface and not yet entered another
            serial = WLToolkit.getInputState().keySerial(); // an input event
        }
        if (serial == 0) {
            // The pointer button serial seems to not work with Mutter, but may work
            // with other implementations, so let's keep it as an input event serial
            // of the last resort.
            serial = WLToolkit.getInputState().pointerButtonSerial();
        }
        return serial;
    }

    private static native void initIDs();

    protected native long nativeCreateFrame();

    protected native void nativeCreateWindow(long ptr, long parentPtr, long wlSurfacePtr,
                                                boolean isModal, boolean isMaximized, boolean isMinimized,
                                                String title, String appID);

    protected native void nativeCreatePopup(long ptr, long parentPtr, long wlSurfacePtr,
                                              int width, int height,
                                              int offsetX, int offsetY);

    protected native void nativeRepositionWLPopup(long ptr,
                                                  int width, int height,
                                                  int offsetX, int offsetY);
    protected native void nativeHideFrame(long ptr);

    protected native void nativeDisposeFrame(long ptr);

    private native void nativeStartDrag(long serial, long ptr);
    private native void nativeStartResize(long serial, long ptr, int edges);

    private native void nativeSetTitle(long ptr, String title);
    private native void nativeRequestMinimized(long ptr);
    private native void nativeRequestMaximized(long ptr);
    private native void nativeRequestUnmaximized(long ptr);
    private native void nativeRequestFullScreen(long ptr, int wlID);
    private native void nativeRequestUnsetFullScreen(long ptr);

    private native void nativeSetWindowGeometry(long ptr, int x, int y, int width, int height);
    private native void nativeSetMinimumSize(long ptr, int width, int height);
    private native void nativeSetMaximumSize(long ptr, int width, int height);
    private native void nativeShowWindowMenu(long serial, long ptr, int x, int y);

    static long getNativePtrFor(Component component) {
        final ComponentAccessor acc = AWTAccessor.getComponentAccessor();
        ComponentPeer peer = acc.getPeer(component);

        return ((WLComponentPeer)peer).nativePtr;
    }

    static long getParentNativePtr(Component target) {
        Component parent = target.getParent();
        return parent ==  null ? 0 : getNativePtrFor(parent);
    }

    public WLMainSurface getSurface() {
        return wlSurface;
    }

    /**
     * This lock object is used to protect instance data from concurrent access
     * by two threads.
     */
    Object getStateLock() {
        return stateLock;
    }

    void postMouseEvent(MouseEvent e) {
        WLToolkit.postEvent(e);
    }

    /**
     * Creates and posts mouse events based on the given WLPointerEvent received from Wayland,
     * the freshly updated WLInputState, and the previous WLInputState.
     */
    void dispatchPointerEventInContext(WLPointerEvent e, WLInputState oldInputState, WLInputState newInputState) {
        final int x = newInputState.getPointerX();
        final int y = newInputState.getPointerY();
        final Point abs = relativePointToAbsolute(new Point(x, y));
        int xAbsolute = abs.x;
        int yAbsolute = abs.y;

        final long timestamp = newInputState.getTimestamp();

        if (e.hasEnterEvent()) {
            updateCursorImmediately();
            final MouseEvent mouseEvent = new MouseEvent(getTarget(), MouseEvent.MOUSE_ENTERED,
                    timestamp,
                    newInputState.getModifiers(),
                    x, y,
                    xAbsolute, yAbsolute,
                    0, false, MouseEvent.NOBUTTON);
            postMouseEvent(mouseEvent);
        }

        int clickCount = 0;
        boolean isPopupTrigger = false;
        int buttonChanged = MouseEvent.NOBUTTON;

        if (e.hasButtonEvent()) {
            final WLPointerEvent.PointerButtonCodes buttonCode
                    = WLPointerEvent.PointerButtonCodes.recognizedOrNull(e.getButtonCode());
            if (buttonCode != null) {
                clickCount = newInputState.getClickCount();
                isPopupTrigger = buttonCode.isPopupTrigger() && e.getIsButtonPressed();
                buttonChanged = buttonCode.javaCode;

                final MouseEvent mouseEvent = new MouseEvent(getTarget(),
                        e.getIsButtonPressed() ? MouseEvent.MOUSE_PRESSED : MouseEvent.MOUSE_RELEASED,
                        timestamp,
                        newInputState.getModifiers(),
                        x, y,
                        xAbsolute, yAbsolute,
                        clickCount,
                        isPopupTrigger,
                        buttonChanged);
                postMouseEvent(mouseEvent);

                final boolean isButtonReleased = !e.getIsButtonPressed();
                final boolean wasSameButtonPressed = oldInputState.hasThisPointerButtonPressed(e.getButtonCode());
                final boolean isButtonClicked = isButtonReleased && wasSameButtonPressed;
                if (isButtonClicked) {
                    final MouseEvent mouseClickEvent = new MouseEvent(getTarget(),
                            MouseEvent.MOUSE_CLICKED,
                            timestamp,
                            newInputState.getModifiers(),
                            x, y,
                            xAbsolute, yAbsolute,
                            clickCount,
                            isPopupTrigger,
                            buttonChanged);
                    postMouseEvent(mouseClickEvent);
                }
            }
        }

        if (e.hasAxisEvent()) {
            convertPointerEventToMWEParameters(e, xAxisWheelRoundRotationsAccumulator, yAxisWheelRoundRotationsAccumulator, mweConversionInfo);

            if (log.isLoggable(PlatformLogger.Level.FINE)) {
                log.fine("{0} -> {1}", e, mweConversionInfo);
            }

            // macOS's and Windows' AWT implement the following logic, so do we:
            //   Shift + a vertical scroll means a horizontal scroll.
            // AWT/Swing components are also aware of it.

            final boolean isShiftPressed = (newInputState.getModifiers() & KeyEvent.SHIFT_DOWN_MASK) != 0;

            // These values decide whether a horizontal scrolling MouseWheelEvent will be created and posted
            final int    horizontalMWEScrollAmount;
            final double horizontalMWEPreciseRotations;
            final int    horizontalMWERoundRotations;

            // These values decide whether a vertical scrolling MouseWheelEvent will be created and posted
            final int    verticalMWEScrollAmount;
            final double verticalMWEPreciseRotations;
            final int    verticalMWERoundRotations;

            if (isShiftPressed) {
                // Pressing Shift makes only a horizontal scrolling MouseWheelEvent possible
                verticalMWEScrollAmount     = 0;
                verticalMWEPreciseRotations = 0;
                verticalMWERoundRotations   = 0;

                // Now we're deciding values of which axis will be used to generate a horizontal MouseWheelEvent

                if (mweConversionInfo.xAxisDirectionSign == mweConversionInfo.yAxisDirectionSign) {
                    // The scrolling directions don't contradict each other.
                    // Let's pick the more influencing axis.

                    final var xAxisUnitsToScroll = mweConversionInfo.xAxisMWEScrollAmount * (
                            Math.abs(mweConversionInfo.xAxisMWEPreciseRotations) > Math.abs(mweConversionInfo.xAxisMWERoundRotations)
                            ? mweConversionInfo.xAxisMWEPreciseRotations
                            : mweConversionInfo.xAxisMWERoundRotations );

                    final var yAxisUnitsToScroll = mweConversionInfo.yAxisMWEScrollAmount * (
                            Math.abs(mweConversionInfo.yAxisMWEPreciseRotations) > Math.abs(mweConversionInfo.yAxisMWERoundRotations)
                            ? mweConversionInfo.yAxisMWEPreciseRotations
                            : mweConversionInfo.yAxisMWERoundRotations );

                    if (xAxisUnitsToScroll > yAxisUnitsToScroll) {
                        horizontalMWEScrollAmount     = mweConversionInfo.xAxisMWEScrollAmount;
                        horizontalMWEPreciseRotations = mweConversionInfo.xAxisMWEPreciseRotations;
                        horizontalMWERoundRotations   = mweConversionInfo.xAxisMWERoundRotations;
                    } else {
                        horizontalMWEScrollAmount     = mweConversionInfo.yAxisMWEScrollAmount;
                        horizontalMWEPreciseRotations = mweConversionInfo.yAxisMWEPreciseRotations;
                        horizontalMWERoundRotations   = mweConversionInfo.yAxisMWERoundRotations;
                    }
                } else if (mweConversionInfo.yAxisMWERoundRotations != 0 || mweConversionInfo.yAxisMWEPreciseRotations != 0) {
                    // The scrolling directions contradict.
                    // I think consistently choosing the Y axis values (unless they're zero) provides the most expected UI behavior here.

                    horizontalMWEScrollAmount     = mweConversionInfo.yAxisMWEScrollAmount;
                    horizontalMWEPreciseRotations = mweConversionInfo.yAxisMWEPreciseRotations;
                    horizontalMWERoundRotations   = mweConversionInfo.yAxisMWERoundRotations;
                } else {
                    horizontalMWEScrollAmount     = mweConversionInfo.xAxisMWEScrollAmount;
                    horizontalMWEPreciseRotations = mweConversionInfo.xAxisMWEPreciseRotations;
                    horizontalMWERoundRotations   = mweConversionInfo.xAxisMWERoundRotations;
                }
            } else {
                // Shift is not pressed, so both horizontal and vertical MouseWheelEvent s are possible.

                horizontalMWEScrollAmount     = mweConversionInfo.xAxisMWEScrollAmount;
                horizontalMWEPreciseRotations = mweConversionInfo.xAxisMWEPreciseRotations;
                horizontalMWERoundRotations   = mweConversionInfo.xAxisMWERoundRotations;

                verticalMWEScrollAmount       = mweConversionInfo.yAxisMWEScrollAmount;
                verticalMWEPreciseRotations   = mweConversionInfo.yAxisMWEPreciseRotations;
                verticalMWERoundRotations     = mweConversionInfo.yAxisMWERoundRotations;
            }

            if (e.xAxisHasStopEvent()) {
                xAxisWheelRoundRotationsAccumulator.reset();
            }
            if (e.yAxisHasStopEvent()) {
                yAxisWheelRoundRotationsAccumulator.reset();
            }

            if (verticalMWERoundRotations != 0 || verticalMWEPreciseRotations != 0) {
                assert(verticalMWEScrollAmount > 0);

                final MouseEvent mouseEvent = new MouseWheelEvent(getTarget(),
                        MouseEvent.MOUSE_WHEEL,
                        timestamp,
                        // Making sure the event will cause scrolling along the vertical axis
                        newInputState.getModifiers() & ~KeyEvent.SHIFT_DOWN_MASK,
                        x, y,
                        xAbsolute, yAbsolute,
                        1,
                        isPopupTrigger,
                        MouseWheelEvent.WHEEL_UNIT_SCROLL,
                        verticalMWEScrollAmount,
                        verticalMWERoundRotations,
                        verticalMWEPreciseRotations);
                postMouseEvent(mouseEvent);
            }

            if (horizontalMWERoundRotations != 0 || horizontalMWEPreciseRotations != 0) {
                assert(horizontalMWEScrollAmount > 0);

                final MouseEvent mouseEvent = new MouseWheelEvent(getTarget(),
                        MouseEvent.MOUSE_WHEEL,
                        timestamp,
                        // Making sure the event will cause scrolling along the horizontal axis
                        newInputState.getModifiers() | KeyEvent.SHIFT_DOWN_MASK,
                        x, y,
                        xAbsolute, yAbsolute,
                        1,
                        isPopupTrigger,
                        MouseWheelEvent.WHEEL_UNIT_SCROLL,
                        horizontalMWEScrollAmount,
                        horizontalMWERoundRotations,
                        horizontalMWEPreciseRotations);
                postMouseEvent(mouseEvent);
            }
        }

        if (e.hasMotionEvent()) {
            final MouseEvent mouseEvent = new MouseEvent(getTarget(),
                    newInputState.hasPointerButtonPressed()
                            ? MouseEvent.MOUSE_DRAGGED : MouseEvent.MOUSE_MOVED,
                    timestamp,
                    newInputState.getModifiers(),
                    x, y,
                    xAbsolute, yAbsolute,
                    clickCount,
                    isPopupTrigger,
                    buttonChanged);
            postMouseEvent(mouseEvent);
        }

        if (e.hasLeaveEvent()) {
            final MouseEvent mouseEvent = new MouseEvent(getTarget(),
                    MouseEvent.MOUSE_EXITED,
                    timestamp,
                    newInputState.getModifiers(),
                    x, y,
                    xAbsolute, yAbsolute,
                    clickCount,
                    isPopupTrigger,
                    buttonChanged);
            postMouseEvent(mouseEvent);
        }
    }

    /**
     * Accumulates fractional parts of wheel rotation steps until their absolute sum represents one or more full step(s).
     * This allows implementing smoother scrolling, e.g., the sequence of wl_pointer::axis events with values
     *   [0.2, 0.1, 0.4, 0.4] can be accumulated into 1.1=0.2+0.1+0.4+0.4, making it possible to
     *   generate a MouseWheelEvent with wheelRotation=1
     *   (instead of 4 tries to generate a MouseWheelEvent with wheelRotation=0 due to double->int conversion)
     */
    private static final class MouseWheelRoundRotationsAccumulator {
        /**
         * This method is intended to accumulate fractional numbers of wheel rotations.
         *
         * @param fractionalRotations - fractional number of wheel rotations (usually got from a {@code wl_pointer::axis} event)
         * @return The number of wheel round rotations accumulated
         * @see #accumulateSteps120Rotations
         */
        public int accumulateFractionalRotations(double fractionalRotations) {
            // The code assumes that the target component ({@link WLComponentPeer#target}) never changes.
            // If it did, all the accumulating fields would have to be reset each time the target changed.

            accumulatedFractionalRotations += fractionalRotations;
            final int result = (int)accumulatedFractionalRotations;
            accumulatedFractionalRotations -= result;
            return result;
        }

        /**
         * This method is intended to accumulate 1/120 fractions of a rotation step.
         *
         * @param steps120Rotations - a number of 1/120 parts of a wheel step (so that, e.g.,
         *                            30 means one quarter of a step,
         *                            240 means two steps,
         *                            -240 means two steps in the negative direction,
         *                            540 means 4.5 steps).
         *                            Usually got from a {@code wl_pointer::axis_discrete}/{@code axis_value120} event.
         * @return The number of wheel round rotations accumulated
         * @see #accumulateFractionalRotations
         */
        public int accumulateSteps120Rotations(int steps120Rotations) {
            // The code assumes that the target component ({@link WLComponentPeer#target}) never changes.
            // If it did, all the accumulating fields would have to be reset each time the target changed.

            accumulatedSteps120Rotations += steps120Rotations;
            final int result = accumulatedSteps120Rotations / 120;
            accumulatedSteps120Rotations %= 120;
            return result;
        }

        public void reset() {
            accumulatedFractionalRotations = 0;
            accumulatedSteps120Rotations = 0;
        }


        private double accumulatedFractionalRotations = 0;
        private int accumulatedSteps120Rotations = 0;
    }
    private final MouseWheelRoundRotationsAccumulator xAxisWheelRoundRotationsAccumulator = new MouseWheelRoundRotationsAccumulator();
    private final MouseWheelRoundRotationsAccumulator yAxisWheelRoundRotationsAccumulator = new MouseWheelRoundRotationsAccumulator();

    private static final class PointerEventToMWEConversionInfo {
        public double  xAxisVector = 0;
        public int     xAxisSteps120 = 0;
        public int     xAxisDirectionSign = 0;
        public double  xAxisMWEPreciseRotations = 0;
        public int     xAxisMWERoundRotations = 0;
        public int     xAxisMWEScrollAmount = 0;

        public double  yAxisVector = 0;
        public int     yAxisSteps120 = 0;
        public int     yAxisDirectionSign = 0;
        public double  yAxisMWEPreciseRotations = 0;
        public int     yAxisMWERoundRotations = 0;
        public int     yAxisMWEScrollAmount = 0;

        private final StringBuilder toStringBuf = new StringBuilder(1024);
        @Override
        public String toString() {
            toStringBuf.setLength(0);

            toStringBuf.append("PointerEventToMWEConversionInfo[")
                       .append("xAxisVector="             ).append(xAxisVector             ).append(", ")
                       .append("xAxisSteps120="           ).append(xAxisSteps120           ).append(", ")
                       .append("xAxisDirectionSign="      ).append(xAxisDirectionSign      ).append(", ")
                       .append("xAxisMWEPreciseRotations=").append(xAxisMWEPreciseRotations).append(", ")
                       .append("xAxisMWERoundRotations="  ).append(xAxisMWERoundRotations  ).append(", ")
                       .append("xAxisMWEScrollAmount="    ).append(xAxisMWEScrollAmount    ).append(", ")

                       .append("yAxisVector="             ).append(yAxisVector             ).append(", ")
                       .append("yAxisSteps120="           ).append(yAxisSteps120           ).append(", ")
                       .append("yAxisDirectionSign="      ).append(yAxisDirectionSign      ).append(", ")
                       .append("yAxisMWEPreciseRotations=").append(yAxisMWEPreciseRotations).append(", ")
                       .append("yAxisMWERoundRotations="  ).append(yAxisMWERoundRotations  ).append(", ")
                       .append("yAxisMWEScrollAmount="    ).append(yAxisMWEScrollAmount    )
                       .append(']');

            return toStringBuf.toString();
        }
    }
    private final PointerEventToMWEConversionInfo mweConversionInfo = new PointerEventToMWEConversionInfo();

    private static void convertPointerEventToMWEParameters(
            WLPointerEvent dispatchingEvent,
            MouseWheelRoundRotationsAccumulator xAxisWheelRoundRotationsAccumulator,
            MouseWheelRoundRotationsAccumulator yAxisWheelRoundRotationsAccumulator,
            PointerEventToMWEConversionInfo mweConversionInfo) {
        // WLPointerEvent -> MouseWheelEvent conversion constants.
        // Please keep in mind that they're all related, so that changing one may require adjusting the others
        //   (or altering this conversion routine).

        // XToolkit uses 3 units per a wheel step, so do we here to preserve the user experience
        final int  STEPS120_MWE_SCROLL_AMOUNT = 3;
        // For touchpad scrolling, it's worth being able to scroll the minimum possible number of units (i.e. 1)
        final int    VECTOR_MWE_SCROLL_AMOUNT = 1;
        // 0.28 has experimentally been found as providing a good balance between
        //   wheel scrolling sensitivity and touchpad scrolling sensitivity
        final double VECTOR_LENGTH_TO_MWE_ROTATIONS_FACTOR = 0.28;

        mweConversionInfo.xAxisVector   = dispatchingEvent.xAxisHasVectorValue()   ? dispatchingEvent.getXAxisVectorValue() : 0;
        mweConversionInfo.xAxisSteps120 = dispatchingEvent.xAxisHasSteps120Value() ? dispatchingEvent.getXAxisSteps120Value() : 0;

        // Converting the X axis Wayland values to MouseWheelEvent parameters.

        // wl_pointer::axis_discrete/axis_value120 are preferred over wl_pointer::axis because
        //   they're closer to MouseWheelEvent by their nature.
        if (mweConversionInfo.xAxisSteps120 != 0) {
            mweConversionInfo.xAxisDirectionSign       = Integer.signum(mweConversionInfo.xAxisSteps120);
            mweConversionInfo.xAxisMWEPreciseRotations = mweConversionInfo.xAxisSteps120 / 120d;
            mweConversionInfo.xAxisMWERoundRotations   = xAxisWheelRoundRotationsAccumulator.accumulateSteps120Rotations(mweConversionInfo.xAxisSteps120);
            // It would be probably better to calculate the scrollAmount taking the xAxisVector value into
            //   consideration, so that the wheel scrolling speed could be adjusted via some system settings.
            // However, neither Gnome nor KDE currently provide such a setting, making it difficult to test
            //   how well such an approach would work. So leaving it as is for now.
            mweConversionInfo.xAxisMWEScrollAmount     = STEPS120_MWE_SCROLL_AMOUNT;
        } else {
            mweConversionInfo.xAxisDirectionSign       = (int)Math.signum(mweConversionInfo.xAxisVector);
            mweConversionInfo.xAxisMWEPreciseRotations = mweConversionInfo.xAxisVector * VECTOR_LENGTH_TO_MWE_ROTATIONS_FACTOR;
            mweConversionInfo.xAxisMWERoundRotations   = xAxisWheelRoundRotationsAccumulator.accumulateFractionalRotations(mweConversionInfo.xAxisMWEPreciseRotations);
            mweConversionInfo.xAxisMWEScrollAmount     = VECTOR_MWE_SCROLL_AMOUNT;
        }

        mweConversionInfo.yAxisVector   = dispatchingEvent.yAxisHasVectorValue()   ? dispatchingEvent.getYAxisVectorValue() : 0;
        mweConversionInfo.yAxisSteps120 = dispatchingEvent.yAxisHasSteps120Value() ? dispatchingEvent.getYAxisSteps120Value() : 0;

        // Converting the Y axis Wayland values to MouseWheelEvent parameters.
        // (Currently, the routine is exactly like for X axis)

        if (mweConversionInfo.yAxisSteps120 != 0) {
            mweConversionInfo.yAxisDirectionSign       = Integer.signum(mweConversionInfo.yAxisSteps120);
            mweConversionInfo.yAxisMWEPreciseRotations = mweConversionInfo.yAxisSteps120 / 120d;
            mweConversionInfo.yAxisMWERoundRotations   = yAxisWheelRoundRotationsAccumulator.accumulateSteps120Rotations(mweConversionInfo.yAxisSteps120);
            mweConversionInfo.yAxisMWEScrollAmount     = STEPS120_MWE_SCROLL_AMOUNT;
        } else {
            mweConversionInfo.yAxisDirectionSign       = (int)Math.signum(mweConversionInfo.yAxisVector);
            mweConversionInfo.yAxisMWEPreciseRotations = mweConversionInfo.yAxisVector * VECTOR_LENGTH_TO_MWE_ROTATIONS_FACTOR;
            mweConversionInfo.yAxisMWERoundRotations   = yAxisWheelRoundRotationsAccumulator.accumulateFractionalRotations(mweConversionInfo.yAxisMWEPreciseRotations);
            mweConversionInfo.yAxisMWEScrollAmount     = VECTOR_MWE_SCROLL_AMOUNT;
        }
    }


    void startDrag(long serial) {
        // "This request must be used in response to some sort of user action like a button press,
        //  key press, or touch down event. The passed serial is used to determine the type
        //  of interactive move (touch, pointer, etc)."
        assert serial != 0;

        performLocked(() -> nativeStartDrag(serial, nativePtr));
    }

    void startResize(long serial, int edges) {
        // "This request must be used in response to some sort of user action like a button press,
        //  key press, or touch down event. The passed serial is used to determine the type
        //  of interactive resize (touch, pointer, etc)."
        assert serial != 0;

        performLocked(() -> nativeStartResize(serial, nativePtr, edges));
    }

    /**
     * Converts a value in the Wayland surface-local coordinate system
     * into the Java coordinate system.
     */
    int surfaceUnitsToJavaUnits(int value) {
        if (!WLGraphicsEnvironment.isDebugScaleEnabled()) {
            return value;
        } else {
            synchronized (getStateLock()) {
                return (int)(value * displayScale / effectiveScale);
            }
        }
    }

    int surfaceUnitsToJavaSize(int value) {
        if (!WLGraphicsEnvironment.isDebugScaleEnabled()) {
            return value;
        } else {
            synchronized (getStateLock()) {
                return (int) Math.ceil(value * displayScale / effectiveScale);
            }
        }
    }

    /**
     * Converts a value in the Java coordinate system into the Wayland
     * surface-local coordinate system.
     */
    int javaUnitsToSurfaceUnits(int value) {
        if (!WLGraphicsEnvironment.isDebugScaleEnabled()) {
            return value;
        } else {
            synchronized (getStateLock()) {
                return (int) Math.floor(value * effectiveScale / displayScale);
            }
        }
    }

    int javaUnitsToSurfaceSize(int value) {
        if (!WLGraphicsEnvironment.isDebugScaleEnabled()) {
            return value;
        } else {
            synchronized (getStateLock()) {
                return (int) Math.ceil(value * effectiveScale / displayScale);
            }
        }
    }

    Point javaUnitsToSurfaceUnits(Point p) {
        return new Point(javaUnitsToSurfaceUnits(p.x), javaUnitsToSurfaceUnits(p.y));
    }

    Dimension javaUnitsToSurfaceSize(Dimension d) {
        return new Dimension(javaUnitsToSurfaceSize(d.width), javaUnitsToSurfaceSize(d.height));
    }

    /**
     * Converts a point in the device (screen) space into coordinates on this surface
     */
    Point convertPontFromDeviceSpace(int x, int y) {
        Point userLoc = getLocationOnScreen();
        Point topLeft = SunGraphicsEnvironment.toDeviceSpace(getGraphicsConfiguration(), userLoc.x, userLoc.y, 0, 0).getLocation();
        return new Point(x - topLeft.x, y - topLeft.y);
    }

    void notifyConfigured(int newSurfaceX, int newSurfaceY, int newSurfaceWidth, int newSurfaceHeight,
                          boolean active, boolean maximized, boolean fullscreen) {
        assert SunToolkit.isAWTLockHeldByCurrentThread();

        // NB: The width and height, as well as X and Y arguments, specify the size and the location
        //     of the window in surface-local coordinates.
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine(String.format("%s configured to %dx%d surface units", this, newSurfaceWidth, newSurfaceHeight));
        }

        synchronized (getStateLock()) {
            isFullscreen = fullscreen;
        }

        boolean isWlPopup = targetIsWlPopup();
        boolean acceptNewLocation = !popupNeedsReposition();
        if (isWlPopup && acceptNewLocation) { // Only popups provide (relative) location
            int newX = surfaceUnitsToJavaUnits(newSurfaceX);
            int newY = surfaceUnitsToJavaUnits(newSurfaceY);

            // The popup itself stores its location relative to its parent, but what we've got is
            // the location relative to the toplevel. Let's convert:
            Window popup = (Window) target;
            Component popupParent = AWTAccessor.getWindowAccessor().getPopupParent(popup);
            Window toplevel = getToplevelFor(popupParent);
            Point parentLocation = getRelativeLocation(popupParent, toplevel);
            Point locationRelativeToParent = new Point(newX - parentLocation.x, newY - parentLocation.y);
            resetTargetLocationTo(locationRelativeToParent.x, locationRelativeToParent.y);
        }

        // From xdg-shell.xml: "If the width or height arguments are zero,
        // it means the client should decide its own window dimension".
        boolean clientDecidesDimension = newSurfaceWidth == 0 || newSurfaceHeight == 0;
        boolean desiredSize =
                (wlSize.javaSize.width == surfaceUnitsToJavaSize(newSurfaceWidth)
                && wlSize.javaSize.height == surfaceUnitsToJavaSize(newSurfaceHeight));
        boolean acceptNewSize = !resizePending() || maximized || desiredSize;
        if (!clientDecidesDimension && acceptNewSize) {
            changeSizeToConfigured(newSurfaceWidth, newSurfaceHeight);
        }

        if (!wlSurface.hasSurfaceData()) {
            wlSurface.associateWithSurfaceData(surfaceData);
        }

        shadow.notifyConfigured(active, maximized, fullscreen);

        if (clientDecidesDimension || isWlPopup) {
            // In case this is the first 'configure' after setVisible(true), we
            // need to post the initial paint event for the window to appear on
            // the screen. In the other case, this paint event is posted
            // by setBounds() eventually called from target.setSize() above.

            // Popups have their initial size communicated to Wayland even before they are shown,
            // so it is highly likely that they won't get the initial paint event because of
            // the size change from target.setSize() above.
            postPaintEvent();
        }
    }

    private void changeSizeToConfigured(int newSurfaceWidth, int newSurfaceHeight) {
        resizeCompleted();
        wlSize.deriveFromSurfaceSize(newSurfaceWidth, newSurfaceHeight);
        shadow.resizeToParentWindow();
        int newWidth = wlSize.getJavaWidth();
        int newHeight = wlSize.getJavaHeight();
        try {
            setSizeIsBeingConfigured(true);
            performUnlocked(() -> target.setSize(newWidth, newHeight));
        } finally {
            setSizeIsBeingConfigured(false);
        }
    }

    void notifyEnteredOutput(int wlOutputID) {
        performLocked(() -> {
            if (wlSurface != null) {
                wlSurface.notifyEnteredOutput(wlOutputID);
            }
        });
    }

    void notifyPopupDone() {
        assert(targetIsWlPopup());
        target.setVisible(false);
    }

    void checkIfOnNewScreen() {
        assert SunToolkit.isAWTLockHeldByCurrentThread();

        if (wlSurface == null) return;
        final WLGraphicsDevice newDevice = wlSurface.getGraphicsDevice();
        if (newDevice != null) { // could be null when screens are being reconfigured
            final GraphicsConfiguration gc = newDevice.getDefaultConfiguration();
            if (log.isLoggable(Level.FINE)) {
                log.fine(this + " is on (possibly) new device " + newDevice);
            }
            var oldDevice = (WLGraphicsDevice) target.getGraphicsConfiguration().getDevice();
            if (oldDevice != newDevice) {
                oldDevice.removeWindow(this);
                newDevice.addWindow(this);
            }
            performUnlocked(() -> {
                var acc = AWTAccessor.getComponentAccessor();
                acc.setGraphicsConfiguration(target, gc);
            });
        }
    }

    private static Dimension getMaxBufferBounds() {
        // Need to limit the maximum size of the window so that the creation of the underlying
        // buffers for it may succeed at least in theory. A window that is too large may crash
        // JVM or even the window manager.
        Dimension bounds = WLGraphicsEnvironment.getSingleInstance().getTotalDisplayBounds();
        bounds.width *= 2;
        bounds.height *= 2;
        return bounds;
    }

    private Dimension constrainSize(int width, int height) {
        Dimension maxBounds = getMaxBufferBounds();
        return new Dimension(
                Math.max(Math.min(width, maxBounds.width), MINIMUM_WIDTH),
                Math.max(Math.min(height, maxBounds.height), MINIMUM_HEIGHT));
    }

    private Dimension constrainSize(Dimension bounds) {
        return constrainSize(bounds.width, bounds.height);
    }

    // The following methods exist to prevent native code from using stale pointers (pointing to memory already
    // deallocated). This includes pointers to object allocated by the toolkit directly, as well as those allocated by
    // Wayland client API.
    // An example case when a stale pointer can be accessed is performing some operation on a window/surface, while it's
    // being destroyed/hidden in another thread.
    // All accesses to native data, associated with the peer object (e.g. wl_surface proxy object), are expected to be
    // done using these methods. Then one can be sure that native data is not changed concurrently in any way while the
    // specified task is executed.

    void performLocked(Runnable task) {
        WLToolkit.awtLock();
        try {
            if (nativePtr != 0) {
                task.run();
            }
        } finally {
            WLToolkit.awtUnlock();
        }
    }

    <T> T performLocked(Supplier<T> task, Supplier<T> defaultValue) {
        WLToolkit.awtLock();
        try {
            if (nativePtr != 0) {
                return task.get();
            }
        } finally {
            WLToolkit.awtUnlock();
        }
        return defaultValue.get();
    }

    // It's important not to take some other locks in AWT code (e.g. java.awt.Component.getTreeLock()) while holding the
    // lock protecting native data. If the locks can be taken also in a different order, a deadlock might occur. If some
    // code is known to be executed under native-data protection lock (e.g. Wayland event processing code), it can use
    // this method to give up the lock temporarily, to be able to call the code employing other locks.
    static void performUnlocked(Runnable task) {
        WLToolkit.awtUnlock();
        try {
            task.run();
        } finally {
            WLToolkit.awtLock();
        }
    }

    static <T> T performUnlocked(Supplier<T> task) {
        WLToolkit.awtUnlock();
        try {
            return task.get();
        } finally {
            WLToolkit.awtLock();
        }
    }

    static Window getNativelyFocusableOwnerOrSelf(Component component) {
        Window result = component instanceof Window window ? window : SwingUtilities.getWindowAncestor(component);
        while (result != null && isWlPopup(result)) {
            result = result.getOwner();
        }
        return result;
    }

    private static class ShadowImage {
        private static final Color activeColor = new Color(0, 0, 0, 0xA0);
        private static final Color inactiveColor = new Color(0, 0, 0, 0x40);
        private static final Color popupColor = new Color(0, 0, 0, 0x40);

        public static final int WINDOW_SHADOW_SIZE = 20;
        public static final int POPUP_SHADOW_SIZE = 10;

        public static final ShadowImage windowShadowActive = new ShadowImage(WINDOW_SHADOW_SIZE, activeColor);
        public static final ShadowImage windowShadow = new ShadowImage(WINDOW_SHADOW_SIZE, inactiveColor);
        public static final ShadowImage popupShadow = new ShadowImage(POPUP_SHADOW_SIZE, popupColor);

        private final BufferedImage image;
        private final int shadowSize;
        private final Color color;

        public ShadowImage(int shadowSize, Color color) {
            this.shadowSize = shadowSize;
            this.color = color;
            image = create(shadowSize * 8, shadowSize * 8, shadowSize, shadowSize);
        }

        public static ShadowImage forSize(int size, boolean active) {
            return switch (size) {
                case WINDOW_SHADOW_SIZE -> active ? windowShadowActive : windowShadow;
                case POPUP_SHADOW_SIZE -> popupShadow;
                default -> throw new IllegalArgumentException("Invalid shadow size: " + size);
            };
        }

        private BufferedImage create(int width, int height, int arc, int shadowSize) {
            var shadow = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            var g2d = shadow.createGraphics();
            g2d.setColor(color);
            g2d.fillRoundRect(shadowSize, shadowSize, width - 2 * shadowSize, height - 2 * shadowSize, arc, arc);

            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2d.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
            // Apply a Gaussian blur
            float[] blurKernel = createBlurKernel(shadowSize);
            ConvolveOp blurOp = new ConvolveOp(new Kernel(shadowSize, shadowSize, blurKernel), ConvolveOp.EDGE_NO_OP, null);
            shadow = blurOp.filter(shadow, null);

            g2d.dispose();
            return shadow;
        }

        private float[] createBlurKernel(int size) {
            float[] kernel = new float[size * size];
            float value = 1.0f / (size * size);
            Arrays.fill(kernel, value);
            return kernel;
        }

        public void paintTo(SunGraphics2D g, int width, int height) {
            // This is the size of the minimal square that fits a corner of the shadow
            int size = (int) Math.ceil(shadowSize * 2.5);

            if ( width > 2 * size && height > 2 * size) {
                g.setColor(color);
                g.fillRect(size, size, width - 2 * size, height - 2 * size);
            }

            int shadowImageWidth = image.getWidth(null);
            int shadowImageHeight = image.getHeight(null);

            // top
            g.copyImage(image, 0, 0, 0, 0, size, size, null, null);
            int horizGap = width - 2 * size;
            int vertGap = height - 2 * size;
            g.clipRect(size, 0, horizGap, size);
            for (int i = 0; i < horizGap / shadowSize + 1; i++) {
                g.copyImage(image, size + i * shadowSize, 0, size, 0, shadowSize, size, null, null);
            }
            g.setClip(null);

            g.copyImage(image, width - size, 0, shadowImageWidth - size, 0, size, size, null, null);

            // bottom
            g.copyImage(image, 0, height - size, 0, shadowImageHeight - size, size, size, null, null);
            g.clipRect(size, height - size, width - size * 2, size);
            for (int i = 0; i < horizGap / shadowSize + 1; i++) {
                g.copyImage(image, size + i * shadowSize, height - size, size, shadowImageHeight - size, shadowSize, size, null, null);
            }
            g.setClip(null);
            g.copyImage(image, width - size, height - size, shadowImageWidth - size, shadowImageHeight - size, size, size, null, null);

            // left
            g.clipRect(0, size, size, height - size * 2);
            for (int i = 0; i < vertGap / shadowSize + 1; i++) {
                g.copyImage(image, 0, size + shadowSize * i, 0, size, size, shadowSize, null, null);
            }
            g.setClip(null);

            // right
            g.clipRect(width - size, size, size, height - size * 2);
            for (int i = 0; i < vertGap / shadowSize + 1; i++) {
                g.copyImage(image, width - size, size + shadowSize * i, shadowImageWidth - size, size, size, shadowSize, null, null);
            }
            g.setClip(null);
        }
    }

    private class Shadow implements WLSurfaceSizeListener {
        private WLSubSurface shadowSurface; // protected by AWT lock
        private SurfaceData shadowSurfaceData;  // protected by AWT lock
        private boolean needsRepaint = true;  // protected by AWT lock
        private final int shadowSize;
        private final WLSize shadowWlSize = new WLSize(); // protected by stateLock
        private boolean isActive;  // protected by AWT lock

        public Shadow(int shadowSize) {
            this.shadowSize = shadowSize;
            shadowWlSize.deriveFromJavaSize(wlSize.getJavaWidth() + shadowSize * 2, wlSize.getJavaHeight() + shadowSize * 2);
            shadowSurfaceData = ((WLGraphicsConfig) getGraphicsConfiguration()).createSurfaceData(this, shadowWlSize.getPixelWidth(), shadowWlSize.getPixelHeight());
        }

        public int getSize() {
            return shadowSize;
        }

        @Override
        public void updateSurfaceSize() {
            assert SunToolkit.isAWTLockHeldByCurrentThread();

            shadowSurface.updateSurfaceSize(shadowWlSize.getSurfaceWidth(), shadowWlSize.getSurfaceHeight());
        }

        public void resizeToParentWindow() {
            synchronized (getStateLock()) {
                shadowWlSize.deriveFromJavaSize(wlSize.getJavaWidth() + 2 * shadowSize, wlSize.getJavaHeight() + 2 * shadowSize);
            }
        }

        public void createSurface() {
            assert shadowSurface == null;
            assert SunToolkit.isAWTLockHeldByCurrentThread();

            int shadowOffset = -javaUnitsToSurfaceUnits(shadowSize);
            shadowSurface = new WLSubSurface(wlSurface, shadowOffset, shadowOffset);
        }

        public void commitSurface() {
            assert shadowSurface != null;
            assert SunToolkit.isAWTLockHeldByCurrentThread();

            shadowSurface.commit();
        }

        public void dispose() {
            assert SunToolkit.isAWTLockHeldByCurrentThread();

            if (shadowSurface != null) {
                shadowSurface.dispose();
                shadowSurface = null;
            }

            SurfaceData oldShadowData = shadowSurfaceData;
            shadowSurfaceData = null;
            if (oldShadowData != null) {
                oldShadowData.invalidate();
            }
        }

        public void hide() {
            assert SunToolkit.isAWTLockHeldByCurrentThread();

            if (shadowSurface != null) {
                shadowSurface.dispose();
                shadowSurface = null;
            }
        }

        public void updateSurfaceData() {
            assert SunToolkit.isAWTLockHeldByCurrentThread();

            needsRepaint = true;
            SurfaceData.convertTo(WLSurfaceDataExt.class, shadowSurfaceData).revalidate(
                    getGraphicsConfiguration(), shadowWlSize.getPixelWidth(), shadowWlSize.getPixelHeight(), getDisplayScale());
        }

        public void paint() {
            assert SunToolkit.isAWTLockHeldByCurrentThread();

            if (!needsRepaint) {
                return;
            }

            int width = shadowWlSize.getJavaWidth();
            int height = shadowWlSize.getJavaHeight();

            var g = new SunGraphics2D(shadowSurfaceData, Color.BLACK, new Color(0, true), null);
            try {
                g.clearRect(0, 0, width, height);
                ShadowImage.forSize(shadowSize, isActive).paintTo(g, width, height);
            } finally {
                g.dispose();
            }

            needsRepaint = false;
        }

        public void commitSurfaceData() {
            SurfaceData.convertTo(WLSurfaceDataExt.class, shadowSurfaceData).commit();
        }

        public void notifyConfigured(boolean active, boolean maximized, boolean fullscreen) {
            assert shadowSurface != null;
            assert SunToolkit.isAWTLockHeldByCurrentThread();

            needsRepaint |= active ^ isActive;

            isActive = active;
            boolean showShadow = !fullscreen && !maximized;
            if (showShadow) {
                if (!shadowSurface.hasSurfaceData()) {
                    shadowSurface.associateWithSurfaceData(shadowSurfaceData);
                }
            } else {
                shadowSurface.hide();
                needsRepaint = false;
            }
            shadowSurface.commit();
        }
    }

    private class WLSize {
        /**
         * Represents the full size of the component in "client" units as returned by Component.getSize().
         */
        private final Dimension javaSize = new Dimension(); // in the client (Java) space, protected by stateLock

        /**
         * Represents the full size of the component in screen pixels.
         * The SurfaceData associated with this component takes its size from this value.
         */
        private final Dimension pixelSize = new Dimension(); // in pixels, protected by stateLock

        /**
         * Represents the full size of the component in "surface-local" units;
         * these are the units that Wayland uses in most of its API.
         * Unless the debug scale is used (WLGraphicsEnvironment.isDebugScaleEnabled()), it is identical
         * to javaSize.
         */
        private final Dimension surfaceSize = new Dimension(); // in surface units, protected by stateLock

        void deriveFromJavaSize(int width, int height) {
            synchronized (getStateLock()) {
                javaSize.width = width;
                javaSize.height = height;
                pixelSize.width = (int) (width * effectiveScale);
                pixelSize.height = (int) (height * effectiveScale);
                surfaceSize.width = javaUnitsToSurfaceSize(width);
                surfaceSize.height = javaUnitsToSurfaceSize(height);
            }
        }

        void deriveFromSurfaceSize(int width, int height) {
            synchronized (getStateLock()) {
                javaSize.width = surfaceUnitsToJavaSize(width);
                javaSize.height = surfaceUnitsToJavaSize(height);
                pixelSize.width = width * displayScale;
                pixelSize.height = height * displayScale;
                surfaceSize.width = width;
                surfaceSize.height = height;
            }
        }

        void updateWithNewScale() {
            synchronized (getStateLock()) {
                pixelSize.width = (int)(javaSize.width * effectiveScale);
                pixelSize.height = (int)(javaSize.height * effectiveScale);
                surfaceSize.width = javaUnitsToSurfaceSize(javaSize.width);
                surfaceSize.height = javaUnitsToSurfaceSize(javaSize.height);
            }
        }

        boolean hasPixelSizeSet() {
            synchronized (getStateLock()) {
                return pixelSize.width > 0 && pixelSize.height > 0;
            }
        }

        void setJavaSize(int width, int height) {
            synchronized (getStateLock()) {
                javaSize.width = width;
                javaSize.height = height;
            }
        }

        int getPixelWidth() {
            synchronized (getStateLock()) {
                return pixelSize.width;
            }
        }

        int getPixelHeight() {
            synchronized (getStateLock()) {
                return pixelSize.height;
            }
        }

        int getJavaWidth() {
            synchronized (getStateLock()) {
                return javaSize.width;
            }
        }

        int getJavaHeight() {
            synchronized (getStateLock()) {
                return javaSize.height;
            }
        }

        int getSurfaceWidth() {
            synchronized (getStateLock()) {
                return surfaceSize.width;
            }
        }

        int getSurfaceHeight() {
            synchronized (getStateLock()) {
                return surfaceSize.height;
            }
        }

        public String toString() {
            return "WLSize[client=" + javaSize + ", pixel=" + pixelSize + ", surface=" + surfaceSize + "]";
        }
    }
}
