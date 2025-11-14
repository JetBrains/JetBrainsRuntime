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

import java.awt.Component;
import java.awt.event.MouseEvent;
import java.io.IOException;

public class WLDropTargetContextPeer extends SunDropTargetContextPeer {
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

    private synchronized void postEvent(int event) {
        if (currentOffer == null || currentTarget == null) {
            return;
        }
        var peer = (WLComponentPeer) AWTAccessor.getComponentAccessor().getPeer(currentTarget);
        var x = peer.surfaceUnitsToJavaUnits((int) currentX);
        var y = peer.surfaceUnitsToJavaUnits((int) currentY);
        var actions = WLDataDevice.waylandActionsToJava(currentOffer.getSourceActions());
        int dropAction = 0;

        if (hasTarget() && event != MouseEvent.MOUSE_EXITED) {
            dropAction = WLDataDevice.waylandActionsToJava(currentOffer.getSelectedAction());
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

        throw new IOException("Unknown format " + format + ", aka " + dataTransferer.getNativeForFormat(format));
    }

    @Override
    protected void doDropDone(boolean success, int dropAction, boolean isLocal) {
        reset();
    }

    private synchronized void updateActions() {
        if (currentOffer == null) {
            return;
        }

        int javaActions = 0;
        if (hasTarget()) {
            javaActions = getTargetActions();
        }

        int javaPreferredAction = SunDragSourceContextPeer.convertModifiersToDropAction(WLToolkit.getInputState().getModifiers(), javaActions);

        int waylandActions = WLDataDevice.javaActionsToWayland(javaActions);
        int waylandPreferredAction = WLDataDevice.javaActionsToWayland(javaPreferredAction);

        if (waylandActions != lastActions || waylandPreferredAction != lastPreferredAction) {
            currentOffer.setDnDActions(waylandActions, waylandPreferredAction);
            lastActions = waylandActions;
            lastPreferredAction = waylandPreferredAction;
        }
    }

    private synchronized void reset() {
        if (currentOffer != null) {
            currentOffer.destroy();
        }

        currentOffer = null;
        currentTarget = null;
        lastPreferredAction = -1;
        lastActions = -1;
        didDrop = false;
    }

    @Override
    public synchronized void acceptDrag(int dragOperation) {
        super.acceptDrag(dragOperation);
        updateActions();
    }

    @Override
    public synchronized void rejectDrag() {
        super.rejectDrag();
        updateActions();
    }

    public synchronized void handleEnter(WLDataOffer offer, long serial, long surfacePtr, double x, double y) {
        var peer = WLToolkit.peerFromSurface(surfacePtr);
        if (peer == null) {
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
        if (!didDrop) {
            postEvent(MouseEvent.MOUSE_EXITED);
            reset();
        }
    }

    public synchronized void handleMotion(long timestamp, double x, double y) {
        currentX = x;
        currentY = y;
        postEvent(MouseEvent.MOUSE_DRAGGED);
        updateActions();
    }

    public synchronized void handleDrop() {
        if (hasTarget()) {
            didDrop = true;
            postEvent(SunDropTargetEvent.MOUSE_DROPPED);
        } else {
            postEvent(MouseEvent.MOUSE_EXITED);
            reset();
        }
    }

    public synchronized void handleModifiersUpdate() {
        updateActions();
    }
}
