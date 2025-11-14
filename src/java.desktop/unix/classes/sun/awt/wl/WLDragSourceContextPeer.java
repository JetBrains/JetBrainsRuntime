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

import java.awt.Cursor;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DragGestureEvent;
import java.util.Map;

public class WLDragSourceContextPeer extends SunDragSourceContextPeer {
    private final WLDataDevice dataDevice;

    private class WLDragSource extends WLDataSource {
        private int action;
        private String mime;
        private boolean didSendFinishedEvent = false;
        private boolean didSucceed = false;

        WLDragSource(Transferable data) {
            super(dataDevice, WLDataDevice.DATA_TRANSFER_PROTOCOL_WAYLAND, data);
        }

        private void sendFinishedEvent() {
            if (didSendFinishedEvent) {
                return;
            }
            didSendFinishedEvent = true;

            final int javaAction = didSucceed ? WLDataDevice.waylandActionsToJava(action) : 0;
            final int x = WLToolkit.getInputState().getPointerX();
            final int y = WLToolkit.getInputState().getPointerY();
            WLDragSourceContextPeer.this.dragDropFinished(didSucceed, javaAction, x, y);
        }

        @Override
        protected synchronized void handleDnDAction(int action) {
            // This if statement is a workaround for a KWin bug.
            // KWin 6.5.1 may send an additional action(0) after dnd_drop_performed().
            // Spec says that after dnd_drop_performed(), no further action() events will be sent,
            // except for maybe action(dnd_ask), but since we do not announce support for dnd_ask,
            // we don't need to worry about it.
            if (!didSucceed) {
                this.action = action;
            }
        }

        @Override
        protected synchronized void handleDnDDropPerformed() {
            didSucceed = action != 0 && mime != null;
        }

        @Override
        protected synchronized void handleDnDFinished() {
            sendFinishedEvent();
            destroy();
        }

        @Override
        protected synchronized void handleTargetAcceptsMime(String mime) {
            this.mime = mime;
        }

        @Override
        protected void handleCancelled() {
            sendFinishedEvent();
            super.handleCancelled();
        }
    }

    public WLDragSourceContextPeer(DragGestureEvent dge, WLDataDevice dataDevice) {
        super(dge);
        this.dataDevice = dataDevice;
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

    private WLMainSurface getSurface() {
        WLComponentPeer peer = getPeer();
        if (peer != null) {
            return peer.getSurface();
        }
        return null;
    }

    @Override
    protected void startDrag(Transferable trans, long[] formats, Map<Long, DataFlavor> formatMap) {
        var mainSurface = getSurface();
        if (mainSurface == null) {
            return;
        }

        // formats and formatMap are unused, because WLDataSource already references the same DataTransferer singleton
        var source = new WLDragSource(trans);

        var actions = getDragSourceContext().getSourceActions();
        int waylandActions = WLDataDevice.javaActionsToWayland(actions);

        source.setDnDActions(waylandActions);

        var dragImage = getDragImage();
        if (dragImage != null) {
            var dragImageOffset = getDragImageOffset();
            source.setDnDIcon(dragImage,
                    mainSurface.getGraphicsDevice().getDisplayScale(),
                    dragImageOffset.x, dragImageOffset.y);
        }

        long eventSerial = WLToolkit.getInputState().pointerButtonSerial();

        dataDevice.startDrag(source, mainSurface.getWlSurfacePtr(), eventSerial);
    }

    @Override
    protected void setNativeCursor(long nativeCtxt, Cursor c, int cType) {
        // TODO: setting cursor here doesn't seem to be required on Wayland?
    }
}
