/*
 * Copyright 2025 JetBrains s.r.o.
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
import sun.awt.dnd.SunDragSourceContextPeer;
import sun.awt.dnd.SunDropTargetContextPeer;
import sun.awt.dnd.SunDropTargetEvent;
import sun.util.logging.PlatformLogger;

import java.awt.Component;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.Arrays;

public class WLDropTargetContextPeer extends SunDropTargetContextPeer {
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.wl.WLDropTargetContextPeer");

    private WLDataOffer currentOffer;
    private Component currentTarget;
    private double currentX;
    private double currentY;
    private long[] sourceFormats;
    private int lastPreferredAction = -1;
    private int lastActions = -1;
    private boolean didDrop = false;

    private WLDropTargetContextPeer() {
    }

    private static final WLDropTargetContextPeer INSTANCE = new WLDropTargetContextPeer();

    public static WLDropTargetContextPeer getInstance() {
        return INSTANCE;
    }

    private synchronized boolean hasTarget() {
        var dropTarget = getDropTarget();
        var context = (dropTarget != null) ? dropTarget.getDropTargetContext() : null;
        return context != null;
    }

    private static String eventToString(int event) {
        return switch (event) {
            case MouseEvent.MOUSE_CLICKED -> "MOUSE_CLICKED";
            case MouseEvent.MOUSE_PRESSED -> "MOUSE_PRESSED";
            case SunDropTargetEvent.MOUSE_DROPPED -> "MOUSE_DROPPED";
            case MouseEvent.MOUSE_MOVED -> "MOUSE_MOVED";
            case MouseEvent.MOUSE_ENTERED -> "MOUSE_ENTERED";
            case MouseEvent.MOUSE_EXITED -> "MOUSE_EXITED";
            case MouseEvent.MOUSE_DRAGGED -> "MOUSE_DRAGGED";
            default -> "(unknown event enum value " + event + ")";
        };
    }

    private synchronized void postEvent(int event) {
        if (currentOffer == null || currentTarget == null) {
            log.warning("postEvent(" + eventToString(event) + "): no current offer or current target, currentOffer = " + currentOffer + ", currentTarget = " + currentTarget);
            return;
        }
        var peer = (WLComponentPeer) AWTAccessor.getComponentAccessor().getPeer(currentTarget);
        var x = peer.surfaceUnitsToJavaUnits((int) currentX);
        var y = peer.surfaceUnitsToJavaUnits((int) currentY);
        var actions = WLDataDevice.waylandActionsToJava(currentOffer.getSourceActions());
        int dropAction = 0;

        if (hasTarget() && event != MouseEvent.MOUSE_EXITED) {
            if (currentJVMLocalSourceTransferable != null) {
                dropAction = SunDragSourceContextPeer.convertModifiersToDropAction(WLToolkit.getInputState().getModifiers(), actions);
            } else {
                dropAction = WLDataDevice.waylandActionsToJava(currentOffer.getSelectedAction());
            }
        }

        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("postEvent(" + eventToString(event) + "): currentTarget = " + currentTarget + ", x = " + x +
                    ", y = " + y + ", dropAction = " + dropAction + ", actions = " + actions + ", sourceFormats = " + Arrays.toString(sourceFormats) +
                    ", lastPreferredAction = " + lastPreferredAction + ", lastActions = " + lastActions);
        }

        postDropTargetEvent(
                currentTarget,
                x,
                y,
                dropAction,
                actions,
                sourceFormats,
                0,
                event,
                false);
    }

    @Override
    protected Object getNativeData(long format) throws IOException {
        var dataTransferer = (WLDataTransferer) WLDataTransferer.getInstance();

        synchronized (this) {
            if (log.isLoggable(PlatformLogger.Level.FINE)) {
                log.fine("getNativeData(), format = " + format + ", currentOffer = " + currentOffer);
            }

            if (currentOffer == null) {
                return null;
            }

            // Since one format can be mapped to multiple mimes, we need to iterate over all of them.
            for (var mime : currentOffer.getMimes()) {
                if (dataTransferer.getFormatForNativeAsLong(mime) == format) {
                    return currentOffer.receiveData(mime);
                }
            }
        }

        log.warning("getNativeData(): unknown format, format = " + format + ", currentOffer = " + currentOffer);

        throw new IOException("Unknown format " + format + ", aka " + dataTransferer.getNativeForFormat(format));
    }

    @Override
    protected synchronized void doDropDone(boolean success, int dropAction, boolean isLocal) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("doDropDone(), success = " + success + ", dropAction = " + dropAction + ", isLocal = " + isLocal);
        }

        if (success && currentOffer != null) {
            currentOffer.finishDnD();
        }
        reset();
    }

    private synchronized void updateActions() {
        if (currentOffer == null) {
            log.warning("updateActions(): currentOffer is null");
            return;
        }

        int javaActions = 0;
        if (hasTarget()) {
            javaActions = getTargetActions();
        }

        int javaPreferredAction = SunDragSourceContextPeer.convertModifiersToDropAction(WLToolkit.getInputState().getModifiers(), javaActions);

        int waylandActions = WLDataDevice.javaActionsToWayland(javaActions);
        int waylandPreferredAction = WLDataDevice.javaActionsToWayland(javaPreferredAction);

        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("updateActions(), hasTarget = " + hasTarget() + ", waylandActions = " + waylandActions + ", waylandPreferredAction = " + waylandPreferredAction);
        }

        if (waylandActions != lastActions || waylandPreferredAction != lastPreferredAction) {
            currentOffer.setDnDActions(waylandActions, waylandPreferredAction);
            lastActions = waylandActions;
            lastPreferredAction = waylandPreferredAction;
        }
    }

    private synchronized void reset() {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("reset()");
        }

        if (currentOffer != null) {
            currentOffer.unref();
        }

        currentOffer = null;
        currentTarget = null;
        lastPreferredAction = -1;
        lastActions = -1;
        didDrop = false;
    }

    @Override
    public synchronized void acceptDrag(int dragOperation) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("acceptDrag(), dragOperation = " + dragOperation);
        }
        super.acceptDrag(dragOperation);
        updateActions();
    }

    @Override
    public synchronized void rejectDrag() {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("rejectDrag()");
        }
        super.rejectDrag();
        updateActions();
    }

    public synchronized void handleEnter(WLDataOffer offer, long serial, long surfacePtr, double x, double y) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("handleEnter(), offer = " + offer + ", serial = " + serial + ", surfacePtr = 0x" + Long.toHexString(surfacePtr) + ", x = " + x + ", y = " + y);
        }

        var peer = WLToolkit.peerFromSurface(surfacePtr);
        if (peer == null) {
            log.warning("handleEnter(): no peer for surface 0x" +  Long.toHexString(surfacePtr));
            return;
        }

        reset();

        currentTarget = peer.getTarget();
        currentX = x;
        currentY = y;
        lastPreferredAction = -1;
        currentOffer = offer;

        var mimes = offer.getMimes();
        var wlDataTransferer = (WLDataTransferer) WLDataTransferer.getInstance();
        long[] formats = new long[mimes.size()];
        for (int i = 0; i < mimes.size(); ++i) {
            formats[i] = wlDataTransferer.getFormatForNativeAsLong(mimes.get(i));
        }
        sourceFormats = formats;

        postEvent(MouseEvent.MOUSE_ENTERED);

        currentOffer.setListener(new WLDataOffer.EventListener() {
            @Override
            public void availableActionsChanged(int actions) {
                postEvent(MouseEvent.MOUSE_DRAGGED);
            }

            @Override
            public void selectedActionChanged(int action) {
                postEvent(MouseEvent.MOUSE_DRAGGED);
            }
        });

        updateActions();

        // Accept all formats by default. Rejecting the drop is done by setting supported actions to 0.
        for (var mime : offer.getMimes()) {
            offer.accept(serial, mime);
        }
    }

    public synchronized void handleLeave() {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("handleLeave(), didDrop = " + didDrop);
        }
        if (!didDrop) {
            postEvent(MouseEvent.MOUSE_EXITED);
            reset();
        }
    }

    public synchronized void handleMotion(long timestamp, double x, double y) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("handleMotion(), timestamp = " + timestamp + ", x = " + x + ", y = " + y);
        }
        currentX = x;
        currentY = y;
        postEvent(MouseEvent.MOUSE_DRAGGED);
        updateActions();
    }

    public synchronized void handleDrop() {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("handleDrop(), didDrop = " + didDrop + ", hasTarget = " + hasTarget());
        }
        if (hasTarget()) {
            didDrop = true;
            postEvent(SunDropTargetEvent.MOUSE_DROPPED);
        } else {
            postEvent(MouseEvent.MOUSE_EXITED);
            reset();
        }
    }

    public synchronized void handleModifiersUpdate() {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("handleModifiersUpdate()");
        }
        if (currentOffer != null) {
            updateActions();
        }
    }
}
