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
import java.util.Objects;
import java.util.function.Supplier;

public class WLComponentPeer implements ComponentPeer {
    private static final PlatformLogger focusLog = PlatformLogger.getLogger("sun.awt.wl.focus.WLComponentPeer");

    private static final String appID = System.getProperty("sun.java.command");

    private long nativePtr;
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.wl.WLComponentPeer");
    protected final Component target;
    protected WLGraphicsConfig graphicsConfig;
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

    static {
        initIDs();
    }

    /**
     * Standard peer constructor, with corresponding Component
     */
    WLComponentPeer(Component target) {
        this.target = target;
        this.background = target.getBackground();
        initGraphicsConfiguration();
        Rectangle bounds = target.getBounds();
        x = bounds.x;
        y = bounds.y;
        width = bounds.width;
        height = bounds.height;
        this.surfaceData = (WLSurfaceData) graphicsConfig.createSurfaceData(this);
        this.nativePtr = nativeCreateFrame();
        paintArea = new WLRepaintArea();
        log.info("WLComponentPeer: target=" + target + " x=" + x + " y=" + y +
                " width=" + width + " height=" + height);
        // TODO
        // setup parent window for target
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public Color getBackground() {
        return background;
    }

    public void postPaintEvent(Component target, int x, int y, int w, int h) {
        PaintEvent event = PaintEventDispatcher.getPaintEventDispatcher().
                createPaintEvent(target, x, y, w, h);
        if (event != null) {
            WLToolkit.postEvent(event);
        }
    }

    void postPaintEvent() {
        if (isVisible()) {
            synchronized (sizeLock) {
                postPaintEvent(getTarget(), 0, 0, width, height);
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
        throw new UnsupportedOperationException();
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
                    temporary,
                    focusedWindowChangeAllowed,
                    time,
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
            final String title = target instanceof Frame frame ? frame.getTitle() : null;
            performLocked(() -> {
                nativeCreateWLSurface(nativePtr,
                        getParentNativePtr(target), target.getX(), target.getY(), title, appID);
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

    void configureWLSurface() {}

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
                surfaceData.revalidate(width, height);
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
                if (log.isLoggable(Level.INFO)) {
                    log.info("WLCP coalescePaintEvent : UPDATE : add : x = " +
                            r.x + ", y = " + r.y + ", width = " + r.width + ",height = " + r.height);
                }
            }
            case PaintEvent.PAINT -> {
                if (log.isLoggable(Level.INFO)) {
                    log.info("WLCP coalescePaintEvent : PAINT : add : x = " +
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
                log.info("WLComponentPeer.handleEvent(AWTEvent): UPDATE " + this);
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
        log.info("WLComponentPeer.endLayout(): paintArea.isEmpty() " + paintArea.isEmpty());
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

    @Override
    public ColorModel getColorModel() {
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
        log.info("Not implemented: WLComponentPeer.updateCursorImmediately()");
    }

    @Override
    public Image createImage(int width, int height) {
        return graphicsConfig.createAcceleratedImage(target, width, height);
    }

    @Override
    public VolatileImage createVolatileImage(int width, int height) {
        return new SunVolatileImage(target, width, height);
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

    final void setFrameTitle(String title) {
        Objects.requireNonNull(title);
        performLocked(() -> nativeSetTitle(nativePtr, title));
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

    final void requestFullScreen() {
        performLocked(() -> nativeRequestFullScreen(nativePtr));
    }

    final void requestUnsetFullScreen() {
        performLocked(() -> nativeRequestUnsetFullScreen(nativePtr));
    }

    private static native void initIDs();

    protected native long nativeCreateFrame();

    protected native void nativeCreateWLSurface(long ptr, long parentPtr, int x, int y, String title, String appID);

    protected native void nativeHideFrame(long ptr);

    protected native void nativeDisposeFrame(long ptr);

    private native long getWLSurface(long ptr);
    private native void nativeStartDrag(long ptr);
    private native void nativeStartResize(long ptr, int edges);

    private native void nativeSetTitle(long ptr, String title);
    private native void nativeRequestMinimized(long ptr);
    private native void nativeRequestMaximized(long ptr);
    private native void nativeRequestUnmaximized(long ptr);
    private native void nativeRequestFullScreen(long ptr);
    private native void nativeRequestUnsetFullScreen(long ptr);

    private native void nativeSetWindowGeometry(long ptr, int x, int y, int width, int height);
    private native void nativeSetMinimumSize(long ptr, int width, int height);
    private native void nativeSetMaximumSize(long ptr, int width, int height);

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

    void notifyConfigured(int width, int height, boolean active, boolean maximized) {
        final long wlSurfacePtr = getWLSurface(nativePtr);
        // TODO: this needs to be done only once after wlSetVisible(true)
        surfaceData.assignSurface(wlSurfacePtr);
        if (width != 0 && height != 0) performUnlocked(() ->target.setSize(width, height));
        if (width == 0 && height == 0) {
            // From xdg-shell.xml: "If the width or height arguments are zero,
            // it means the client should decide its own window dimension".

            // In case this is the first configure after setVisible(true), we
            // need to post the initial paint event for the window to appear on
            // the screen. In the other case, this paint event is posted
            // by setBounds() eventually called from target.setSize() above.
            postPaintEvent();
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
