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

import sun.awt.AWTAccessor;
import sun.awt.AWTAccessor.ComponentAccessor;
import sun.awt.PaintEventDispatcher;
import sun.awt.event.IgnorePaintEvent;
import sun.awt.image.SunVolatileImage;
import sun.java2d.SunGraphics2D;
import sun.java2d.SurfaceData;
import sun.java2d.pipe.Region;
import sun.java2d.wl.WLSurfaceData;
import sun.util.logging.PlatformLogger;
import sun.util.logging.PlatformLogger.Level;

import java.awt.*;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.InputMethodEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.PaintEvent;
import java.awt.event.WindowEvent;
import java.awt.image.ColorModel;
import java.awt.image.VolatileImage;
import java.awt.peer.ComponentPeer;
import java.awt.peer.ContainerPeer;
import java.util.ArrayList;
import java.util.Objects;
import java.util.function.Supplier;

public class WLComponentPeer implements ComponentPeer {
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.wl.WLComponentPeer");
    private static final PlatformLogger focusLog = PlatformLogger.getLogger("sun.awt.wl.focus.WLComponentPeer");

    private static final String appID = System.getProperty("sun.java.command");

    // mapping of AWT cursor types to X cursor names
    // multiple variants can be specified, that will be tried in order
    private static final String[][] CURSOR_NAMES = {
            {"default", "arrow"}, // DEFAULT_CURSOR
            {"crosshair"}, // CROSSHAIR_CURSOR
            {"text", "xterm"}, // TEXT_CURSOR
            {"wait", "watch"}, // WAIT_CURSOR
            {"sw-resize", "bottom_left_corner"}, // SW_RESIZE_CURSOR
            {"se-resize", "bottom_right_corner"}, // SE_RESIZE_CURSOR
            {"nw-resize", "top_left_corner"}, // NW_RESIZE_CURSOR
            {"ne-resize", "top_right_corner"}, // NE_RESIZE_CURSOR
            {"n-resize", "top_side"}, // N_RESIZE_CURSOR
            {"s-resize", "bottom_side"}, // S_RESIZE_CURSOR
            {"w-resize", "left_side"}, // W_RESIZE_CURSOR
            {"e-resize", "right_side"}, // E_RESIZE_CURSOR
            {"hand"}, // HAND_CURSOR
            {"move"}, // MOVE_CURSOR
    };

    private long nativePtr;
    protected final Component target;

    // Graphics devices this top-level component is visible on
    protected final java.util.List<WLGraphicsDevice> devices = new ArrayList<>();

    protected Color background;
    WLSurfaceData surfaceData;
    WLRepaintArea paintArea;
    boolean paintPending = false;
    boolean isLayouting = false;
    boolean visible = false;

    int x;
    int y;

    private final Object sizeLock = new Object();
    int width;  // protected by sizeLock
    int height; // protected by sizeLock
    int wlBufferScale; // protected by sizeLock

    static {
        initIDs();
    }

    /**
     * Standard peer constructor, with corresponding Component
     */
    WLComponentPeer(Component target) {
        this.target = target;
        this.background = target.getBackground();
        Rectangle bounds = target.getBounds();
        x = bounds.x;
        y = bounds.y;
        width = bounds.width;
        height = bounds.height;
        final WLGraphicsConfig config = (WLGraphicsConfig)target.getGraphicsConfiguration();
        wlBufferScale = config.getScale();
        surfaceData = (WLSurfaceData) config.createSurfaceData(this);
        nativePtr = nativeCreateFrame();
        paintArea = new WLRepaintArea();
        log.fine("WLComponentPeer: target=" + target + " x=" + x + " y=" + y +
                " width=" + width + " height=" + height);
        // TODO
        // setup parent window for target
    }

    public int getWidth() {
        synchronized (sizeLock) {
            return width;
        }
    }

    public int getHeight() {
        synchronized (sizeLock) {
            return height;
        }
    }

    public Color getBackground() {
        return background;
    }

    public void postPaintEvent(int x, int y, int w, int h) {
        PaintEvent event = PaintEventDispatcher.getPaintEventDispatcher().
                createPaintEvent(target, x, y, w, h);
        if (event != null) {
            WLToolkit.postEvent(event);
        }
    }

    void postPaintEvent() {
        if (isVisible()) {
            synchronized (sizeLock) {
                postPaintEvent(0, 0, width, height);
            }
        }
    }

    boolean isVisible() {
        return visible;
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
        final Component currentlyFocused = WLKeyboardFocusManagerPeer.getInstance().getCurrentFocusOwner();
        if (currentlyFocused == null)
            return false;

        WLComponentPeer peer = AWTAccessor.getComponentAccessor().getPeer(currentlyFocused);
        if (peer == null)
            return false;

        if (this == peer) {
            WLKeyboardFocusManagerPeer.deliverFocus(lightweightChild,
                    target,
                    true,
                    cause,
                    WLKeyboardFocusManagerPeer.getInstance().getCurrentFocusOwner());
        } else {
            return false;
        }

        return true;
    }

    protected void wlSetVisible(boolean v) {
        this.visible = v;
        if (this.visible) {
            final String title = getTitle();
            final boolean isPopup = target instanceof Window window && window.getType() == Window.Type.POPUP;
            performLocked(() -> {
                nativeCreateWLSurface(nativePtr,
                        getParentNativePtr(target), isPopup, target.getX(), target.getY(), title, appID);
                final long wlSurfacePtr = getWLSurface(nativePtr);
                WLToolkit.registerWLSurface(wlSurfacePtr, this);
            });
            configureWLSurface();
            // Now wait for the sequence of configure events and the window
            // will finally appear on screen after we post a PaintEvent
            // from notifyConfigured()
        } else {
            performLocked(() -> {
                WLToolkit.unregisterWLSurface(getWLSurface(nativePtr));
                surfaceData.assignSurface(0);
                nativeHideFrame(nativePtr);
            });
        }
    }

    void configureWLSurface() {
        synchronized (sizeLock) {
            if (log.isLoggable(PlatformLogger.Level.FINE)) {
                log.fine(String.format("%s is configured to %dx%d with %dx scale", this, getBufferWidth(), getBufferHeight(), getBufferScale()));
            }
            surfaceData.revalidate(getBufferWidth(), getBufferHeight(), getBufferScale());
        }
    }

    @Override
    public void setVisible(boolean v) {
        wlSetVisible(v);
    }

    /**
     * @see ComponentPeer
     */
    public void setEnabled(final boolean value) {
        log.info("Not implemented: WLComponentPeer.setEnabled(boolean)");
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
     * previously. In the latter case, the commit will happen later when
     * the server notifies us (through an event on EDT) that the displaying
     * buffer is ready to accept new data.
     */
    public void commitToServer() {
        performLocked(() -> {
            if (getWLSurface(nativePtr) != 0) {
                surfaceData.commitToServer();
            }
        });
    }

    public Component getTarget() {
        return target;
    }

    public void print(Graphics g) {
        log.info("Not implemented: WLComponentPeer.print(Graphics)");
    }

    public void setBounds(int x, int y, int width, int height, int op) {
        final boolean positionChanged = this.x != x || this.y != y;
        if (positionChanged) {
            performLocked(() -> WLRobotPeer.setLocationOfWLSurface(getWLSurface(nativePtr), x, y));
        }
        this.x = x;
        this.y = y;

        if (positionChanged) {
            WLToolkit.postEvent(new ComponentEvent(getTarget(), ComponentEvent.COMPONENT_MOVED));
        }

        synchronized(sizeLock) {
            final boolean sizeChanged = this.width != width || this.height != height;
            if (sizeChanged) {
                this.width = width;
                this.height = height;
                if (log.isLoggable(PlatformLogger.Level.FINE)) {
                    log.fine(String.format("%s is resizing its buffer to %dx%d with %dx scale", this, getBufferWidth(), getBufferHeight(), getBufferScale()));
                }
                surfaceData.revalidate(getBufferWidth(), getBufferHeight(), getBufferScale());
                updateWindowGeometry();
                layout();

                WLToolkit.postEvent(new ComponentEvent(getTarget(), ComponentEvent.COMPONENT_RESIZED));
            }
            postPaintEvent();
        }
    }

    public Rectangle getVisibleBounds() {
        synchronized(sizeLock) {
            return new Rectangle(0, 0, width, height);
        }
    }

    /**
     * Returns the scale of wl_buffer attached to this component's wl_surface.
     * Buffer coordinate space is linearly scaled wrt the component (or surface)
     * coordinate space, so component's coordinates have to be translated
     * to buffers' whenever Wayland protocol requires "buffer-local" coordinates.
     * See wl_surface.set_buffer_scale in wayland.xml for more details.
     */
    int getBufferScale() {
        synchronized(sizeLock) {
            return wlBufferScale;
        }
    }

    private int toBufferUnits(int x) {
        return x * getBufferScale();
    }

    public int getBufferWidth() {
        return toBufferUnits(getWidth());
    }

    public int getBufferHeight() {
        return toBufferUnits(getHeight());
    }

    public Rectangle getBufferBounds() {
        synchronized (sizeLock) {
            return new Rectangle(getBufferWidth(), getBufferHeight());
        }
    }

    private void updateWindowGeometry() {
        // From xdg-shell.xml:
        // "The window geometry of a surface is its "visible bounds" from the
        //	user's perspective. Client-side decorations often have invisible
        //	portions like drop-shadows which should be ignored for the
        //	purposes of aligning, placing and constraining windows"
        final Rectangle visibleBounds = getVisibleBounds();
        performLocked(() -> nativeSetWindowGeometry(nativePtr,
                visibleBounds.x, visibleBounds.y,
                visibleBounds.width, visibleBounds.height));
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
            final long wlSurfacePtr = getWLSurface(nativePtr);
            if (wlSurfacePtr != 0) {
                try {
                    return WLRobotPeer.getLocationOfWLSurface(wlSurfacePtr);
                } catch (UnsupportedOperationException ignore) {
                    return new Point(0, 0);
                }
            } else {
                throw new UnsupportedOperationException("getLocationOnScreen() not supported without wayland surface");
            }
        }, Point::new);
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
            log.info("Not implemented: WLComponentPeer.handleEvent(AWTEvent): handleF10JavaKeyEvent");
        } else if (e instanceof InputMethodEvent) {
            log.info("Not implemented: WLComponentPeer.handleEvent(AWTEvent): handleJavaInputMethodEvent");
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
        isLayouting = true;

    }

    public void endLayout() {
        if (log.isLoggable(Level.FINE)) {
            log.fine("WLComponentPeer.endLayout(): paintArea.isEmpty() " + paintArea.isEmpty());
        }
        if (!paintPending && !paintArea.isEmpty()
                && !AWTAccessor.getComponentAccessor().getIgnoreRepaint(target)) {
            // if not waiting for native painting repaint damaged area
            WLToolkit.postEvent(new PaintEvent(target, PaintEvent.PAINT,
                    new Rectangle()));
        }
        isLayouting = false;
    }

    public Dimension getMinimumSize() {
        return target.getSize();
    }
    
    void setMinimumSizeTo(Dimension minSize) {
        performLocked(() -> nativeSetMinimumSize(nativePtr, minSize.width, minSize.height));
    }

    void setMaximumSizeTo(Dimension maxSize) {
        performLocked(() -> nativeSetMaximumSize(nativePtr, maxSize.width, maxSize.height));
    }

    void showWindowMenu(int x, int y) {
        performLocked(() -> nativeShowWindowMenu(nativePtr, x, y));
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
        if (Objects.equals(background, c)) {
            return;
        }
        background = c;
        // TODO: propagate this change to WLSurfaceData
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
        performLocked(() -> {
            assert(!isVisible());
            nativeDisposeFrame(nativePtr);
            nativePtr = 0;
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
        updateCursorImmediately(WLToolkit.getInputState());
    }

    private void updateCursorImmediately(WLInputState inputState) {
        WLComponentPeer peer = inputState.getPeer();
        if (peer == null) return;
        Cursor cursor = peer.getCursor(inputState.getPointerX(), inputState.getPointerY());
        setCursor(cursor);
    }

    Cursor getCursor(int x, int y) {
        Component target = this.target;
        if (target instanceof Container) {
            Component c = AWTAccessor.getContainerAccessor().findComponentAt((Container) target, x, y, false);
            if (c != null) {
                target = c;
            }
        }
        return AWTAccessor.getComponentAccessor().getCursor(target);
    }

    private static void setCursor(Cursor c) {
        Cursor cursor;
        if (c.getType() == Cursor.CUSTOM_CURSOR && !(c instanceof WLCustomCursor)) {
            cursor = Cursor.getDefaultCursor();
        } else {
            cursor = c;
        }
        performLockedGlobal(() -> {
            long pData = AWTAccessor.getCursorAccessor().getPData(cursor);
            if (pData == 0) {
                pData = createNativeCursor(cursor.getType());
                if (pData == 0) {
                    pData = createNativeCursor(Cursor.DEFAULT_CURSOR);
                }
                if (pData == 0) {
                    pData = -1; // mark as unavailable
                }
                AWTAccessor.getCursorAccessor().setPData(cursor, pData);
            }
            nativeSetCursor(pData);
        });
    }

    private static long createNativeCursor(int type) {
        if (type < Cursor.DEFAULT_CURSOR || type > Cursor.MOVE_CURSOR) {
            type = Cursor.DEFAULT_CURSOR;
        }
        for (String name : CURSOR_NAMES[type]) {
            long pData = nativeGetPredefinedCursor(name);
            if (pData != 0) {
                return pData;
            }
        }
        return 0;
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
        log.info("Not implemented: WLComponentPeer.setZOrder(ComponentPeer)");
    }

    @Override
    public void applyShape(Region shape) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean updateGraphicsData(GraphicsConfiguration gc) {
        final int newScale = ((WLGraphicsConfig)gc).getScale();

        synchronized (sizeLock) {
            if (newScale != wlBufferScale) {
                wlBufferScale = newScale;
                if (log.isLoggable(PlatformLogger.Level.FINE)) {
                    log.fine(String.format("%s is updating buffer to %dx%d with %dx scale", this, getBufferWidth(), getBufferHeight(), wlBufferScale));
                }
                surfaceData.revalidate(getBufferWidth(), getBufferHeight(), wlBufferScale);
                postPaintEvent();
            }
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

    private static native void initIDs();

    protected native long nativeCreateFrame();

    protected native void nativeCreateWLSurface(long ptr, long parentPtr, boolean isPopup,
                                                int x, int y, String title, String appID);

    protected native void nativeHideFrame(long ptr);

    protected native void nativeDisposeFrame(long ptr);

    private native long getWLSurface(long ptr);
    private native void nativeStartDrag(long ptr);
    private native void nativeStartResize(long ptr, int edges);

    private native void nativeSetTitle(long ptr, String title);
    private native void nativeRequestMinimized(long ptr);
    private native void nativeRequestMaximized(long ptr);
    private native void nativeRequestUnmaximized(long ptr);
    private native void nativeRequestFullScreen(long ptr, int wlID);
    private native void nativeRequestUnsetFullScreen(long ptr);

    private native void nativeSetWindowGeometry(long ptr, int x, int y, int width, int height);
    private native void nativeSetMinimumSize(long ptr, int width, int height);
    private native void nativeSetMaximumSize(long ptr, int width, int height);
    private static native void nativeSetCursor(long pData);
    private static native long nativeGetPredefinedCursor(String name);
    private native void nativeShowWindowMenu(long ptr, int x, int y);

    static long getParentNativePtr(Component target) {
        Component parent = target.getParent();
        if (parent == null) return 0;

        final ComponentAccessor acc = AWTAccessor.getComponentAccessor();
        ComponentPeer peer = acc.getPeer(parent);

        return ((WLComponentPeer)peer).nativePtr;
    }

    private final Object state_lock = new Object();
    /**
     * This lock object is used to protect instance data from concurrent access
     * by two threads.
     */
    Object getStateLock() {
        return state_lock;
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
            updateCursorImmediately(newInputState);
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
                isPopupTrigger = buttonCode.isPopupTrigger();
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

        if (e.hasAxisEvent() && e.getIsAxis0Valid()) {
            final MouseEvent mouseEvent = new MouseWheelEvent(getTarget(),
                    MouseEvent.MOUSE_WHEEL,
                    timestamp,
                    newInputState.getModifiers(),
                    x, y,
                    xAbsolute, yAbsolute,
                    1,
                    isPopupTrigger,
                    MouseWheelEvent.WHEEL_UNIT_SCROLL,
                    1,
                    e.getAxis0Value());
            postMouseEvent(mouseEvent);
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

    void startDrag() {
        performLocked(() -> nativeStartDrag(nativePtr));
    }

    void startResize(int edges) {
        performLocked(() -> nativeStartResize(nativePtr, edges));
    }

    void notifyConfigured(int newWidth, int newHeight, boolean active, boolean maximized) {
        final long wlSurfacePtr = getWLSurface(nativePtr);
        // TODO: this needs to be done only once after wlSetVisible(true)
        surfaceData.assignSurface(wlSurfacePtr);
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine(String.format("%s configured to %dx%d", this, newWidth, newHeight));
        }

        if (newWidth != 0 && newHeight != 0) performUnlocked(() ->target.setSize(newWidth, newHeight));
        if (newWidth == 0 && newHeight == 0) {
            // From xdg-shell.xml: "If the width or height arguments are zero,
            // it means the client should decide its own window dimension".

            // In case this is the first configure after setVisible(true), we
            // need to post the initial paint event for the window to appear on
            // the screen. In the other case, this paint event is posted
            // by setBounds() eventually called from target.setSize() above.
            postPaintEvent();
        }
    }

    void notifyEnteredOutput(int wlOutputID) {
        // Called from native code whenever the corresponding wl_surface enters a new output
        synchronized (devices) {
            final WLGraphicsEnvironment ge = (WLGraphicsEnvironment)WLGraphicsEnvironment.getLocalGraphicsEnvironment();
            final WLGraphicsDevice gd = ge.notifySurfaceEnteredOutput(this, wlOutputID);
            if (gd != null) {
                if (log.isLoggable(PlatformLogger.Level.FINE)) {
                    log.fine(this + " has entered " + gd);
                }
                devices.add(gd);
            } else {
                log.severe("Entered output " + wlOutputID + " for which WLGraphicsEnvironment has no record");
            }
        }

        checkIfOnNewScreen();
    }

    void notifyLeftOutput(int wlOutputID) {
        // Called from native code whenever the corresponding wl_surface leaves an output
        synchronized (devices) {
            final WLGraphicsEnvironment ge = (WLGraphicsEnvironment)WLGraphicsEnvironment.getLocalGraphicsEnvironment();
            final WLGraphicsDevice gd = ge.notifySurfaceLeftOutput(this, wlOutputID);
            if (gd != null) {
                if (log.isLoggable(PlatformLogger.Level.FINE)) {
                    log.fine(this + " has left " + gd);
                }
                devices.remove(gd);
            } else {
                log.severe("Left output " + wlOutputID + " for which WLGraphicsEnvironment has no record");
            }
        }

        checkIfOnNewScreen();
    }

    private WLGraphicsDevice getGraphicsDevice() {
        int scale = 0;
        WLGraphicsDevice theDevice = null;
        // AFAIK there's no way of knowing which WLGraphicsDevice is displaying
        // the largest portion of this component, so choose the first in the ordered list
        // of devices with the maximum scale simply to be deterministic.
        // NB: devices are added to the end of the list when we enter the corresponding
        // Wayland's output and are removed as soon as we have left.
        synchronized (devices) {
            for (WLGraphicsDevice gd : devices) {
                if (gd.getScale() > scale) {
                    scale = gd.getScale();
                    theDevice = gd;
                }
            }
        }

        return theDevice;
    }

    private void checkIfOnNewScreen() {
        final WLGraphicsDevice newDevice = getGraphicsDevice();
        if (newDevice != null) { // could be null when screens are being reconfigured
            final GraphicsConfiguration gc = newDevice.getDefaultConfiguration();
            if (log.isLoggable(Level.FINE)) {
                log.fine(this + " is on (possibly) new device " + newDevice);
            }
            var acc = AWTAccessor.getComponentAccessor();
            acc.setGraphicsConfiguration(target, gc);
        }
    }

    // The following methods exist to prevent native code from using stale pointers (pointing to memory already
    // deallocated). This includes pointers to object allocated by the toolkit directly, as well as those allocated by
    // Wayland client API.
    // An example case when a stale pointer can be accessed is performing some operation on a window/surface, while it's
    // being destroyed/hidden in another thread.
    // All accesses to native data, associated with the peer object (e.g. wl_surface proxy object), are expected to be
    // done using these methods. Then one can be sure that native data is not changed concurrently in any way while the
    // specified task is executed.

    static void performLockedGlobal(Runnable task) {
        WLToolkit.awtLock();
        try {
            task.run();
        } finally {
            WLToolkit.awtUnlock();
        }
    }

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
}
