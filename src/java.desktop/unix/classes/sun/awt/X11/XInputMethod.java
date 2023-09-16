/*
 * Copyright (c) 2003, 2021, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2023, JetBrains s.r.o.. All rights reserved.
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

package sun.awt.X11;

import java.awt.*;
import java.awt.event.*;
import java.awt.im.InputMethodRequests;
import java.awt.im.spi.InputMethodContext;
import java.awt.peer.ComponentPeer;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;

import sun.awt.AWTAccessor;
import sun.awt.X11GraphicsDevice;
import sun.awt.X11InputMethod;

import sun.util.logging.PlatformLogger;

import javax.swing.*;
import javax.swing.event.CaretEvent;
import javax.swing.event.CaretListener;
import javax.swing.text.JTextComponent;

/**
 * Input Method Adapter for XIM (without Motif)
 *
 * @author JavaSoft International
 */
public final class XInputMethod extends X11InputMethod {
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.X11.XInputMethod");

    public XInputMethod() throws AWTException {
        super();
        clientComponentCaretPositionTracker = new ClientComponentCaretPositionTracker(this);
    }

    @Override
    public void setInputMethodContext(InputMethodContext context) {
        context.enableClientWindowNotification(this, true);
    }

    @Override
    public void notifyClientWindowChange(Rectangle location) {
        XComponentPeer peer = (XComponentPeer)getPeer(clientComponentWindow);
        if (peer != null) {
            adjustStatusWindow(peer.getContentWindow());
        }

        if (doesSupportMovingCandidatesNativeWindow) {
            clientComponentCaretPositionTracker.onNotifyClientWindowChange(location);
        }
    }

    @Override
    public synchronized void activate() {
        super.activate();

        if (doesSupportMovingCandidatesNativeWindow) {
            updateCandidatesNativeWindowPosition(true);
            clientComponentCaretPositionTracker.startTracking(getClientComponent());
        }
    }

    @Override
    public synchronized void deactivate(boolean isTemporary) {
        clientComponentCaretPositionTracker.stopTrackingCurrentComponent();
        super.deactivate(isTemporary);
    }

    @Override
    public void dispatchEvent(AWTEvent e) {
        if (doesSupportMovingCandidatesNativeWindow) {
            clientComponentCaretPositionTracker.onDispatchEvent(e);
        }
        super.dispatchEvent(e);
    }


    // Is called from native
    private static boolean isJbNewXimClientEnabled() {
        try {
            final String strVal = System.getProperty("jb.awt.newXimClient.enabled");
            final boolean defVal = true;

            return (strVal == null) ? defVal : Boolean.parseBoolean(strVal);
        } catch (Exception err) {
            if (log.isLoggable(PlatformLogger.Level.SEVERE)) {
                log.severe("Error at isJbNewXimClientEnabled", err);
            }
        }

        return false;
    }

    protected boolean preferXBelowTheSpot() {
        try {
            if (BrokenImDetectionContext.EATEN_EVENTS_THRESHOLD > 0) {
                // The fix of JBR-1573,
                //   which is incompatible with the implementation of the native below-the-spot mode (a.k.a. X over-the-spot),
                //   is explicitly enabled.
                // So let's disable this mode in favor of that fix.

                if (log.isLoggable(PlatformLogger.Level.WARNING)) {
                    log.warning("The property \"jb.awt.newXimClient.preferBelowTheSpot\" is ignored in favor of the explicitly enabled \"recreate.x11.input.method\"");
                }

                return false;
            }

            final String strVal = System.getProperty("jb.awt.newXimClient.preferBelowTheSpot");
            final boolean defVal = true;

            return (strVal == null) ? defVal : Boolean.parseBoolean(strVal);
        } catch (Exception err) {
            if (log.isLoggable(PlatformLogger.Level.SEVERE)) {
                log.severe("Error at isJbNewXimClientEnabled", err);
            }
        }

        return false;
    }

    protected boolean openXIM() {
        return openXIMNative(XToolkit.getDisplay());
    }

    @Override
    protected boolean createXIC() {
        XComponentPeer peer = (XComponentPeer)getPeer(clientComponentWindow);
        if (peer == null) {
            return false;
        }
        return createXICNative(peer.getContentWindow(), preferXBelowTheSpot());
    }

    protected boolean recreateXIC(int ctxid) {
        final XComponentPeer peer = (XComponentPeer)getPeer(clientComponentWindow);
        if (peer == null || pData == 0)
            return true;
        return recreateXICNative(peer.getContentWindow(), pData, ctxid, preferXBelowTheSpot());
    }
    protected int releaseXIC() {
        if (pData == 0)
            return 0;
        return releaseXICNative(pData);
    }

    private static volatile long xicFocus;

    @Override
    protected void setXICFocus(ComponentPeer peer, boolean value, boolean active) {
        if (peer == null) {
            return;
        }
        xicFocus = ((XComponentPeer)peer).getContentWindow();
        setXICFocusNative(((XComponentPeer)peer).getContentWindow(),
                          value,
                          active);

        doesSupportMovingCandidatesNativeWindow = value && doesFocusedXICSupportMovingCandidatesNativeWindow();
    }

    public static long getXICFocus() {
        return xicFocus;
    }

/* XAWT_HACK  FIX ME!
   do NOT call client code!
*/
    @Override
    protected Container getParent(Component client) {
        return client.getParent();
    }

    /**
     * Returns peer of the given client component. If the given client component
     * doesn't have peer, peer of the native container of the client is returned.
     */
    @Override
    protected ComponentPeer getPeer(Component client) {
        XComponentPeer peer;

        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Client is " + client);
        }
        peer = (XComponentPeer)XToolkit.targetToPeer(client);
        while (client != null && peer == null) {
            client = getParent(client);
            peer = (XComponentPeer)XToolkit.targetToPeer(client);
        }
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Peer is {0}, client is {1}", peer, client);
        }

        if (peer != null)
            return peer;

        return null;
    }

    /*
     * Subclasses should override disposeImpl() instead of dispose(). Client
     * code should always invoke dispose(), never disposeImpl().
     */
    @Override
    protected synchronized void disposeImpl() {
        super.disposeImpl();
        clientComponentWindow = null;
    }

    @Override
    protected void awtLock() {
        XToolkit.awtLock();
    }

    @Override
    protected void awtUnlock() {
        XToolkit.awtUnlock();
    }

    long getCurrentParentWindow() {
        XWindow peer = AWTAccessor.getComponentAccessor()
                                  .getPeer(clientComponentWindow);
        return peer.getContentWindow();
    }


    static void onXKeyEventFiltering(final boolean isXKeyEventFiltered) {
        // Fix of JBR-1573, JBR-2444, JBR-4394 (a.k.a. IDEA-246833).
        // Input method is considered broken if and only if all the following statements are true:
        //   * XFilterEvent have filtered more than filteredEventsThreshold last events of types KeyPress, KeyRelease;
        //   * Input method hasn't been changed (e.g. recreated);
        //   * The input context is not in preedit state (XNPreeditStartCallback has been called but then XNPreeditDoneCallback - hasn't)

        // The functionality is disabled
        if (BrokenImDetectionContext.EATEN_EVENTS_THRESHOLD < 1) {
            return;
        }
        // Must be called within AWT_LOCK
        if (!XToolkit.isAWTLockHeldByCurrentThread()) {
            return;
        }

        if (isXKeyEventFiltered) {
            final long nativeDataPtr = BrokenImDetectionContext.obtainCurrentXimNativeDataPtr();
            if (nativeDataPtr == 0) {
                ++BrokenImDetectionContext.eatenKeyEventsCount;
            } else {
                final int isDuringPreediting = BrokenImDetectionContext.isDuringPreediting(nativeDataPtr);
                if (isDuringPreediting > 0) {
                    BrokenImDetectionContext.eatenKeyEventsCount = 0;
                } else if (isDuringPreediting == 0) {
                    ++BrokenImDetectionContext.eatenKeyEventsCount;
                } else if (BrokenImDetectionContext.isCurrentXicPassive(nativeDataPtr)) {
                    // Unfortunately for passive XIC (XIMPreeditNothing | XIMStatusNothing) we have no way to get know
                    //  whether the XIC is in preediting state or not, so we won't handle this case.
                    BrokenImDetectionContext.eatenKeyEventsCount = 0;
                } else {
                    ++BrokenImDetectionContext.eatenKeyEventsCount;
                }
            }
        } else {
            BrokenImDetectionContext.eatenKeyEventsCount = 0;
        }

        if (BrokenImDetectionContext.eatenKeyEventsCount > BrokenImDetectionContext.EATEN_EVENTS_THRESHOLD) {
            BrokenImDetectionContext.eatenKeyEventsCount = 0;
            recreateAllXIC();
        }
    }

    private static class BrokenImDetectionContext {
        static final int EATEN_EVENTS_THRESHOLD;

        static int eatenKeyEventsCount = 0;

        /**
         * @return pointer to X11InputMethodData
         */
        static native long obtainCurrentXimNativeDataPtr();

        /**
         * <0 - unknown
         * >0 - true
         *  0 - false
         */
        static native int isDuringPreediting(long ximNativeDataPtr);

        static native boolean isCurrentXicPassive(long ximNativeDataPtr);


        static {
            int eatenEventsThresholdInitializer = 0;
            final String eventsThresholdMode = System.getProperty("recreate.x11.input.method", "false");

            if ("true".equalsIgnoreCase(eventsThresholdMode)) {
                eatenEventsThresholdInitializer = 7;
            } else if (!"false".equalsIgnoreCase(eventsThresholdMode)) {
                try {
                    eatenEventsThresholdInitializer = Integer.parseInt(eventsThresholdMode);
                } catch (NumberFormatException err) {
                    log.warning(
                        "Invalid value of \"recreate.x11.input.method\" system property \"" +
                            eventsThresholdMode +
                            "\". Only \"true\", \"false\" and integer values are supported",
                        err
                    );
                }
            }

            EATEN_EVENTS_THRESHOLD = eatenEventsThresholdInitializer;
        }
    }


    // JBR-2460
    private volatile boolean doesSupportMovingCandidatesNativeWindow = false;
    private Point lastKnownCandidatesNativeWindowAbsolutePosition = null;

    private void updateCandidatesNativeWindowPosition(final boolean forceUpdate) {
        assert(SwingUtilities.isEventDispatchThread());

        if (!doesSupportMovingCandidatesNativeWindow) {
            return;
        }

        final Component clientComponent = getClientComponent();
        if (clientComponent == null) {
            // No client
            return;
        }

        final Window clientComponentWindow = getClientComponentWindow();
        if (clientComponentWindow == null) {
            // Impossible?
            return;
        }

        if (!clientComponent.isShowing() || (!clientComponentWindow.isShowing())) {
            // Components are not showing yet, so it's impossible to determine their location on the screen
            //   and/or the location of the caret
            return;
        }

        final Point clientComponentAbsolutePos = clientComponent.getLocationOnScreen();
        final int clientComponentAbsoluteMaxX = clientComponentAbsolutePos.x + clientComponent.getWidth();
        final int clientComponentAbsoluteMaxY = clientComponentAbsolutePos.y + clientComponent.getHeight();

        // Initial values are the fallback which is the bottom-left corner of the component
        final Point expectedCandidatesNativeWindowAbsolutePos = new Point(
            clientComponentAbsolutePos.x,
            clientComponentAbsoluteMaxY
        );

        final InputMethodRequests clientImr = clientComponent.getInputMethodRequests();
        if (clientImr != null) {
            // An active client

            final Rectangle caretRect = clientImr.getTextLocation(null);
            if (caretRect != null) {
                expectedCandidatesNativeWindowAbsolutePos.x = caretRect.x;
                expectedCandidatesNativeWindowAbsolutePos.y = caretRect.y + caretRect.height;
            }
        }

        // Clamping within the client component's visible rect (if available and not empty) or just its bounds
        final var clientComponentVisibleRect = getJComponentVisibleRectIfNotEmpty(clientComponent);
        if (clientComponentVisibleRect == null) {
            expectedCandidatesNativeWindowAbsolutePos.x =
                Math.max(clientComponentAbsolutePos.x, Math.min(expectedCandidatesNativeWindowAbsolutePos.x, clientComponentAbsoluteMaxX - 1));
            expectedCandidatesNativeWindowAbsolutePos.y =
                Math.max(clientComponentAbsolutePos.y, Math.min(expectedCandidatesNativeWindowAbsolutePos.y, clientComponentAbsoluteMaxY - 1));
        } else {
            final int visibleBoundsAbsoluteMinX = clientComponentAbsolutePos.x + clientComponentVisibleRect.x;
            final int visibleBoundsAbsoluteMaxX = visibleBoundsAbsoluteMinX + clientComponentVisibleRect.width;
            final int visibleBoundsAbsoluteMinY = clientComponentAbsolutePos.y + clientComponentVisibleRect.y;
            final int visibleBoundsAbsoluteMaxY = visibleBoundsAbsoluteMinY + clientComponentVisibleRect.height;

            expectedCandidatesNativeWindowAbsolutePos.x =
                Math.max(visibleBoundsAbsoluteMinX, Math.min(expectedCandidatesNativeWindowAbsolutePos.x, visibleBoundsAbsoluteMaxX - 1));
            expectedCandidatesNativeWindowAbsolutePos.y =
                Math.max(visibleBoundsAbsoluteMinY, Math.min(expectedCandidatesNativeWindowAbsolutePos.y, visibleBoundsAbsoluteMaxY - 1));
        }

        // Scaling the coordinates according to the screen's current scaling settings.
        // To do it properly, we have to know the screen which the point is on.
        // The code below supposes this is the one which clientComponent belongs to, because we've clamped
        //   the point coordinates within the component's bounds above.
        final X11GraphicsDevice candidatesNativeWindowDevice = getComponentX11Device(clientComponent);
        if (candidatesNativeWindowDevice != null) {
            expectedCandidatesNativeWindowAbsolutePos.x =
                candidatesNativeWindowDevice.scaleUpX(expectedCandidatesNativeWindowAbsolutePos.x);
            expectedCandidatesNativeWindowAbsolutePos.y =
                candidatesNativeWindowDevice.scaleUpY(expectedCandidatesNativeWindowAbsolutePos.y);
        }

        // Clamping within screen bounds (to avoid the input candidates window to appear outside a screen).
        final Rectangle closestScreenScaledBounds = new Rectangle();
        final X11GraphicsDevice candidatesNativeWindowClosestScreen = findClosestScreenToPoint(
            closestScreenScaledBounds,
            expectedCandidatesNativeWindowAbsolutePos,
            candidatesNativeWindowDevice
        );
        if (candidatesNativeWindowClosestScreen != null) {
            final int screenScaledBoundsXMax = closestScreenScaledBounds.x + closestScreenScaledBounds.width - 1;
            final int screenScaledBoundsYMax = closestScreenScaledBounds.y + closestScreenScaledBounds.height - 1;

            expectedCandidatesNativeWindowAbsolutePos.x =
                Math.max(closestScreenScaledBounds.x, Math.min(expectedCandidatesNativeWindowAbsolutePos.x, screenScaledBoundsXMax));
            expectedCandidatesNativeWindowAbsolutePos.y =
                    Math.max(closestScreenScaledBounds.y, Math.min(expectedCandidatesNativeWindowAbsolutePos.y, screenScaledBoundsYMax));
        }

        if (forceUpdate || !expectedCandidatesNativeWindowAbsolutePos.equals(lastKnownCandidatesNativeWindowAbsolutePosition)) {
            // adjustCandidatesNativeWindowPosition expects coordinates relative to the client window
            final Point clientComponentWindowAbsolutePos = clientComponentWindow.getLocationOnScreen();
            final X11GraphicsDevice clientComponentWindowDevice = getComponentX11Device(clientComponentWindow);
            if (clientComponentWindowDevice != null) {
                clientComponentWindowAbsolutePos.x =
                    clientComponentWindowDevice.scaleUpX(clientComponentWindowAbsolutePos.x);
                clientComponentWindowAbsolutePos.y =
                    clientComponentWindowDevice.scaleUpY(clientComponentWindowAbsolutePos.y);
            }

            final int relativeX = expectedCandidatesNativeWindowAbsolutePos.x - clientComponentWindowAbsolutePos.x;
            final int relativeY = expectedCandidatesNativeWindowAbsolutePos.y - clientComponentWindowAbsolutePos.y;

            awtLock();
            try {
                adjustCandidatesNativeWindowPosition(relativeX, relativeY);
            } finally {
                awtUnlock();
            }

            lastKnownCandidatesNativeWindowAbsolutePosition = expectedCandidatesNativeWindowAbsolutePos;
        }
    }

    private static Rectangle getJComponentVisibleRectIfNotEmpty(final Component component) {
        if (component instanceof JComponent jComponent) {
            final Rectangle result = jComponent.getVisibleRect();
            if ((result != null) && (result.width > 0) && (result.height > 0)) {
                return result;
            }
        }
        return null;
    }

    private static X11GraphicsDevice getComponentX11Device(final Component component) {
        if (component == null) return null;

        final var componentGc = component.getGraphicsConfiguration();
        if (componentGc == null) return null;

        return (componentGc.getDevice() instanceof X11GraphicsDevice result) ? result : null;
    }

    private static X11GraphicsDevice findClosestScreenToPoint(
        final Rectangle outScreenScaledBounds,
        final Point absolutePointScaled,
        final X11GraphicsDevice... screensToCheckFirst
    ) {
        assert(outScreenScaledBounds != null);

        if (absolutePointScaled == null) {
            return null;
        }

        final Iterator<X11GraphicsDevice> screensToCheck =
            Stream.concat( // screensToCheckFirst + GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()
                Arrays.stream(screensToCheckFirst),
                Stream.<Supplier<GraphicsDevice[]>>of(() -> {
                    final var localGe = GraphicsEnvironment.getLocalGraphicsEnvironment();
                    if (localGe != null) {
                        return localGe.getScreenDevices();
                    }
                    return null;
                }).flatMap(supplier -> Stream.of(supplier.get()))
            ).map(device -> (device instanceof X11GraphicsDevice screen) ? screen : null)
             .filter(Objects::nonNull)
             .iterator();

        int closestScreenMinDistance = Integer.MAX_VALUE;
        X11GraphicsDevice result = null;
        while (screensToCheck.hasNext()) {
            final X11GraphicsDevice screen = screensToCheck.next();

            final Rectangle screenBoundsScaled = screen.getBounds();
            if (screenBoundsScaled == null) {
                continue;
            }
            screenBoundsScaled.width = screen.scaleUp(screenBoundsScaled.width);
            screenBoundsScaled.height = screen.scaleUp(screenBoundsScaled.height);

            final int distance = obtainDistanceBetween(screenBoundsScaled, absolutePointScaled);
            if (distance < closestScreenMinDistance) {
                result = screen;
                closestScreenMinDistance = distance;

                outScreenScaledBounds.x = screenBoundsScaled.x;
                outScreenScaledBounds.y = screenBoundsScaled.y;
                outScreenScaledBounds.width = screenBoundsScaled.width;
                outScreenScaledBounds.height = screenBoundsScaled.height;

                if (distance < 1) {
                    break;
                }
            }
        }

        return result;
    }

    private static int obtainDistanceBetween(final Rectangle rectangle, final Point absolutePointScaled) {
        if ((rectangle.width < 1) || (rectangle.height < 1)) {
            return Integer.MAX_VALUE;
        }

        final int screenBoundsScaledXMax = rectangle.x + rectangle.width - 1;
        final int screenBoundsScaledYMax = rectangle.y + rectangle.height - 1;

        final int dx = Math.max(0, Math.max(rectangle.x - absolutePointScaled.x, absolutePointScaled.x - screenBoundsScaledXMax));
        final int dy = Math.max(0, Math.max(rectangle.y - absolutePointScaled.y, absolutePointScaled.y - screenBoundsScaledYMax));

        return dx + dy; // just sum is enough for our purposes
    }


    /*
     * Native methods
     */
    private native boolean openXIMNative(long display);
    private native boolean createXICNative(long window, boolean preferBelowTheSpot);
    private native boolean recreateXICNative(long window, long px11data, int ctxid, boolean preferBelowTheSpot);
    private native int releaseXICNative(long px11data);
    private native void setXICFocusNative(long window, boolean value, boolean active);
    private native void adjustStatusWindow(long window);

    private native boolean doesFocusedXICSupportMovingCandidatesNativeWindow();

    private native void adjustCandidatesNativeWindowPosition(int x, int y);


    /**
     * This class tries to track all the cases when the position of the parent XInputMethod's candidate window has
     * to be updated. Here are the examples of such cases:
     * <ul>
     * <li>The caret position has changed ;
     * <li>The component has been moved/resized ;
     * <li>The component's window has been moved/resized ;
     * <li>The component's text has been changed ;
     * </ul>
     * Tracking makes sense only when the parent XIM is in a mode allowing to move a native candidates window.
     * This is controlled by a flag {@link XInputMethod#doesSupportMovingCandidatesNativeWindow}.
     * Thus, the tracking gets enabled (via {@link #startTracking(Component)}) only when the flag is evaluated to true.
     */
    private static class ClientComponentCaretPositionTracker implements ComponentListener, CaretListener, TextListener
    {
        public ClientComponentCaretPositionTracker(XInputMethod owner) {
            this.owner = new WeakReference<>(owner);
        }


        public void startTracking(final Component component) {
            stopTrackingCurrentComponent();

            if (component == null) {
                return;
            }

            trackedComponent = new WeakReference<>(component);

            // Moving and changing the size causes a possible change of caret position
            component.addComponentListener(this);

            if (component instanceof JTextComponent jtc) {
                jtc.addCaretListener(this);
                isCaretListenerInstalled = true;
            } else if (component instanceof TextComponent tc) {
                tc.addTextListener(this);
                isTextListenerInstalled = true;
            }
        }

        public void stopTrackingCurrentComponent() {
            final Component trackedComponentStrong;
            if (trackedComponent == null) {
                trackedComponentStrong = null;
            } else {
                trackedComponentStrong = trackedComponent.get();
                trackedComponent.clear();
                trackedComponent = null;
            }

            if (trackedComponentStrong == null) {
                isCaretListenerInstalled = false;
                isTextListenerInstalled = false;
                return;
            }

            if (isTextListenerInstalled) {
                isTextListenerInstalled = false;
                ((TextComponent)trackedComponentStrong).removeTextListener(this);
            }

            if (isCaretListenerInstalled) {
                isCaretListenerInstalled = false;
                ((JTextComponent)trackedComponentStrong).removeCaretListener(this);
            }

            trackedComponentStrong.removeComponentListener(this);
        }

        /* Listening callbacks */

        public void onDispatchEvent(AWTEvent event) {
            if (isCaretListenerInstalled) {
                return;
            }

            final int eventId = event.getID();

            if ( (eventId >= MouseEvent.MOUSE_FIRST) && (eventId <= MouseEvent.MOUSE_LAST) ) {
                // The event hasn't been dispatched yet, so the caret position couldn't be changed.
                // Hence, we have to postpone the updating request.
                SwingUtilities.invokeLater(() -> updateImCandidatesNativeWindowPosition(false));
                return;
            }

            if ( !isTextListenerInstalled && (eventId >= KeyEvent.KEY_FIRST) && (eventId <= KeyEvent.KEY_LAST) ) {
                // The event hasn't been dispatched yet, so the caret position couldn't be changed.
                // Hence, we have to postpone the updating request.
                SwingUtilities.invokeLater(() -> updateImCandidatesNativeWindowPosition(false));
            }
        }

        public void onNotifyClientWindowChange(Rectangle location) {
            if (location != null) {
                updateImCandidatesNativeWindowPosition(lastKnownClientWindowBounds == null);
            }
            lastKnownClientWindowBounds = location;
        }

        // ComponentListener

        @Override
        public void componentHidden(ComponentEvent e) {}

        @Override
        public void componentMoved(ComponentEvent e) {
            updateImCandidatesNativeWindowPosition(false);
        }

        @Override
        public void componentResized(ComponentEvent e) {
            updateImCandidatesNativeWindowPosition(false);
        }

        @Override
        public void componentShown(ComponentEvent e) {
            updateImCandidatesNativeWindowPosition(false);
        }

        // CaretListener

        @Override
        public void caretUpdate(CaretEvent e) {
            updateImCandidatesNativeWindowPosition(false);
        }

        // TextListener

        @Override
        public void textValueChanged(TextEvent e) {
            updateImCandidatesNativeWindowPosition(false);
        }

        /* Private parts */

        private final WeakReference<XInputMethod> owner;
        private WeakReference<Component> trackedComponent = null;
        private boolean isCaretListenerInstalled = false;
        private boolean isTextListenerInstalled = false;
        private Rectangle lastKnownClientWindowBounds = null;


        private void updateImCandidatesNativeWindowPosition(boolean forceUpdate) {
            final XInputMethod ownerStrong = owner.get();

            if ((ownerStrong == null) || (ownerStrong.isDisposed())) {
                // The owning XInputMethod instance is no longer valid

                stopTrackingCurrentComponent();
                owner.clear();

                return;
            }

            if (!ownerStrong.isActive) {
                stopTrackingCurrentComponent(); // will start tracking back when the owner gets active back
                return;
            }

            ownerStrong.updateCandidatesNativeWindowPosition(forceUpdate);
        }
    }

    final ClientComponentCaretPositionTracker clientComponentCaretPositionTracker;
}
