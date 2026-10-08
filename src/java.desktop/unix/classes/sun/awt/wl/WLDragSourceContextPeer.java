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
import sun.util.logging.PlatformLogger;

import java.awt.Cursor;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DragGestureEvent;
import java.awt.dnd.InvalidDnDOperationException;
import java.util.Map;

public class WLDragSourceContextPeer extends SunDragSourceContextPeer {
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.wl.WLDragSourceContextPeer");

    WLDragSourceContextPeer(WLDataDevice dataDevice) {
        super(null);
        this.dataDevice = dataDevice;
    }

    public WLDragSourceContextPeer createDragSourceContextPeer(DragGestureEvent dge) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("createDragSourceContextPeer(), dge = " + dge);
        }
        setTrigger(dge);
        return this;
    }

    private final WLDataDevice dataDevice;
    private WLDragSource currentDragSource = null; // synchronized by 'this'

    private class WLDragSource extends WLDataSource {
        int action;
        String mime;
        WLComponentPeer peer;
        boolean didSendFinishedEvent = false;
        boolean didSucceed = false;

        // pending flag: some compositors might reject a start_drag request silently.
        // When we receive any event on this drag source, or a relevant event on the data device, we set isPending = false.
        // When we see a mouse released event, and isPending is true, we can be sure that our start_drag request was rejected.
        boolean isPending = true;

        WLDragSource(Transferable data, int defaultAction, WLComponentPeer peer) {
            super(dataDevice, WLDataDevice.DATA_TRANSFER_PROTOCOL_WAYLAND, data);
            action = defaultAction;
            this.peer = peer;
        }

        private void sendFinishedEvent() {
            synchronized (WLDragSourceContextPeer.this) {
                if (didSendFinishedEvent) {
                    return;
                }
                didSendFinishedEvent = true;

                final int javaAction = didSucceed ? WLDataDevice.waylandActionsToJava(action) : 0;
                final int x = WLToolkit.getInputState().getPointerX();
                final int y = WLToolkit.getInputState().getPointerY();
                WLDragSourceContextPeer.this.dragDropFinished(didSucceed, javaAction, x, y);

                if (currentDragSource == this) {
                    currentDragSource = null;
                }

                destroy();
            }
        }

        void cancel() {
            synchronized (WLDragSourceContextPeer.this) {
                didSucceed = false;
                sendFinishedEvent();
            }
        }

        @Override
        protected void handleDnDAction(int action) {
            synchronized (WLDragSourceContextPeer.this) {
                super.handleDnDAction(action);
                isPending = false;

                // This if statement is a workaround for a KWin bug.
                // KWin 6.5.1 may send an additional action(0) after dnd_drop_performed().
                // Spec says that after dnd_drop_performed(), no further action() events will be sent,
                // except for maybe action(dnd_ask), but since we do not announce support for dnd_ask,
                // we don't need to worry about it.
                if (!didSucceed) {
                    this.action = action;
                }
            }
        }

        @Override
        protected void handleDnDDropPerformed() {
            synchronized (WLDragSourceContextPeer.this) {
                super.handleDnDDropPerformed();
                isPending = false;
                didSucceed = action != 0 && mime != null;
            }
        }

        @Override
        protected void handleDnDFinished() {
            synchronized (WLDragSourceContextPeer.this) {
                super.handleDnDFinished();
                isPending = false;
                sendFinishedEvent();
            }
        }

        @Override
        protected void handleTargetAcceptsMime(String mime) {
            synchronized (WLDragSourceContextPeer.this) {
                super.handleTargetAcceptsMime(mime);
                isPending = false;
                this.mime = mime;
            }
        }

        @Override
        protected void handleCancelled() {
            synchronized (WLDragSourceContextPeer.this) {
                if (log.isLoggable(PlatformLogger.Level.FINE)) {
                    log.fine("handleCancelled(), this = " + getID());
                }
                isPending = false;
                sendFinishedEvent();
            }
        }
    }

    private WLComponentPeer getPeer() {
        var comp = getComponent();
        while (comp != null) {
            var peer = AWTAccessor.getComponentAccessor().getPeer(comp);
            if (peer instanceof WLComponentPeer wlPeer) {
                return wlPeer;
            }
            comp = comp.getParent();
        }
        return null;
    }

    private synchronized void setDragSource(WLDragSource newDragSource) {
        if (currentDragSource != null) {
            currentDragSource.cancel();
        }
        currentDragSource = newDragSource;
    }

    synchronized void cancelDragIfPending() {
        if (currentDragSource != null && currentDragSource.isPending) {
            setDragSource(null);
        }
    }

    synchronized void cancelDragForPeer(WLComponentPeer peer) {
        if (currentDragSource != null && currentDragSource.peer == peer) {
            setDragSource(null);
        }
    }

    synchronized void unsetPending() {
        if (currentDragSource != null) {
            currentDragSource.isPending = false;
        }
    }

    private void doStartDrag(Transferable trans) {

    }

    @Override
    protected void startDrag(Transferable trans, long[] formats, Map<Long, DataFlavor> formatMap) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("startDrag(), trans = " + trans);
        }

        WLComponentPeer peer = getPeer();
        if (peer == null) {
            throw new InvalidDnDOperationException("startDrag(): peer is null");
        }
        WLMainSurface mainSurface = peer.getSurface();
        if (mainSurface == null) {
            throw new InvalidDnDOperationException("startDrag(): mainSurface is null");
        }

        int actions = 0;
        var dragSourceContext = getDragSourceContext();
        if (dragSourceContext != null) {
            actions = dragSourceContext.getSourceActions();
        }
        int waylandActions = WLDataDevice.javaActionsToWayland(actions);
        int defaultAction = 0;
        if ((waylandActions & WLDataDevice.DND_MOVE) != 0) {
            defaultAction = WLDataDevice.DND_MOVE;
        } else if ((waylandActions & WLDataDevice.DND_COPY) != 0) {
            defaultAction = WLDataDevice.DND_COPY;
        }

        // formats and formatMap are unused, because WLDataSource already references the same DataTransferer singleton
        // Configuring the dragImage for the source is done without holding a lock, because it might call out to user code.
        var source = new WLDragSource(trans, defaultAction, peer);
        try {
            source.setDnDActions(waylandActions);

            var dragImage = getDragImage();
            if (dragImage != null) {
                var dragImageOffset = getDragImageOffset();
                source.setDnDIcon(dragImage,
                        mainSurface.getGraphicsDevice().getDisplayScale(),
                        dragImageOffset.x, dragImageOffset.y);
            }
        } catch (RuntimeException e) {
            source.destroy();
            throw e;
        }

        // Take a lock, and check if we have a valid serial atomically.
        // This is needed, because startDrag() can be called off-EDT, and a mouse release event dispatched on the EDT
        // should either successfully either cause InvalidDnDOperationException to be thrown here,
        // or immediately invalidate the drag source.
        synchronized (this) {
            try {
                setDragSource(source);
                WLInputState inputState = WLToolkit.getInputState();
                WLInputSerial eventSerial = inputState.pointerButtonSerial();
                // Do not even try to start a drag without a good mouse button serial.
                // This should mostly prevent situations, where the compositor silently ignores our start_drag,
                // and we enter a confused state.
                // NOTE: WLDragSource.isPending is another mechanism to guard against this
                if (!eventSerial.isValid() || !inputState.hasPointerButtonPressed()) {
                    throw new InvalidDnDOperationException("startDrag(): no mouse button pressed");
                }

                dataDevice.startDrag(source, mainSurface.getWlSurfacePtr(), eventSerial.serial());
            } catch (RuntimeException e) {
                currentDragSource = null;
                source.destroy();
                throw e;
            }
        }

        SunDropTargetContextPeer.setCurrentJVMLocalSourceTransferable(trans);
    }

    @Override
    protected void setNativeCursor(long nativeCtxt, Cursor c, int cType) {
        // TODO: setting cursor here doesn't seem to be required on Wayland?
    }
}
