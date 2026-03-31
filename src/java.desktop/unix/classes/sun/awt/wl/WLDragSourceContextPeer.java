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
import sun.awt.CustomCursor;
import sun.awt.dnd.SunDragSourceContextPeer;
import sun.awt.dnd.SunDropTargetContextPeer;

import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Image;
import java.awt.Point;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DragGestureEvent;
import java.awt.dnd.DragSource;
import java.util.Map;

public class WLDragSourceContextPeer extends SunDragSourceContextPeer {
    private final WLDataDevice dataDevice;
    private WLDragSource activeDragSource;
    private boolean activeDragSourceUsesCursorFallback;

    private class WLDragSource extends WLDataSource {
        private int action;
        private String mime;
        private boolean didSendFinishedEvent = false;
        private boolean didSucceed = false;

        WLDragSource(Transferable data, int defaultAction) {
            super(dataDevice, WLDataDevice.DATA_TRANSFER_PROTOCOL_WAYLAND, data);
            action = defaultAction;
        }

        private synchronized void sendFinishedEvent() {
            if (didSendFinishedEvent) {
                return;
            }
            didSendFinishedEvent = true;
            dataDevice.setCurrentDragSource(null);

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
                int previousAction = this.action;
                this.action = action;
                if (previousAction != action) {
                    WLDragSourceContextPeer.this.handleSourceActionChanged(previousAction, action);
                }
            }
        }

        @Override
        protected synchronized void handleDnDDropPerformed() {
            didSucceed = action != 0 && mime != null;
        }

        @Override
        protected synchronized void handleDnDFinished() {
            WLDragSourceContextPeer.this.finishDragSource(this);
            sendFinishedEvent();
        }

        @Override
        protected synchronized void handleTargetAcceptsMime(String mime) {
            this.mime = mime;
        }

        @Override
        protected synchronized void handleCancelled() {
            WLDragSourceContextPeer.this.finishDragSource(this);
            sendFinishedEvent();
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

    private synchronized void activateDragSource(WLDragSource dragSource) {
        activeDragSource = dragSource;
    }

    private synchronized void finishDragSource(WLDragSource dragSource) {
        if (activeDragSource == dragSource) {
            activeDragSource = null;
            activeDragSourceUsesCursorFallback = false;
        }
        dragSource.destroy();
    }

    private void handleSourceActionChanged(int previousWaylandAction, int waylandAction) {
        var inputState = WLToolkit.getInputState();
        int x = inputState.getPointerX();
        int y = inputState.getPointerY();
        int modifiers = inputState.getModifiers();
        int javaAction = WLDataDevice.waylandActionsToJava(waylandAction);
        int previousJavaAction = WLDataDevice.waylandActionsToJava(previousWaylandAction);

        if (previousJavaAction == 0 && javaAction != 0) {
            postDragSourceDragEvent(javaAction, modifiers, x, y, DISPATCH_ENTER);
        } else if (previousJavaAction != 0 && javaAction == 0) {
            dragExit(x, y);
        } else if (previousJavaAction != 0) {
            postDragSourceDragEvent(javaAction, modifiers, x, y, DISPATCH_CHANGED);
        }
    }

    @Override
    protected void startDrag(Transferable trans, long[] formats, Map<Long, DataFlavor> formatMap) {
        var mainSurface = getSurface();
        if (mainSurface == null) {
            return;
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
        var source = new WLDragSource(trans, defaultAction);

        source.setDnDActions(waylandActions);

        int scale = mainSurface.getGraphicsDevice().getDisplayScale();
        var dragImage = getDragImage();
        Point dragImageOffset = null;
        boolean usesCursorFallback = false;
        if (dragImage == null) {
            dragImage = cursorToImage(getDragSourceContext().getCursor());
            if (dragImage != null) {
                dragImageOffset = cursorToDragImageOffset(getDragSourceContext().getCursor(), scale);
                usesCursorFallback = true;
            }
        } else {
            dragImageOffset = getDragImageOffset();
        }
        if (dragImage != null) {
            source.setDnDIcon(dragImage,
                    scale,
                    dragImageOffset.x, dragImageOffset.y);
        }

        long eventSerial = WLToolkit.getInputState().pointerButtonSerial();

        synchronized (this) {
            activateDragSource(source);
            activeDragSourceUsesCursorFallback = usesCursorFallback;
        }
        try {
            dataDevice.startDrag(source, mainSurface.getWlSurfacePtr(), eventSerial);
        } catch (RuntimeException | Error e) {
            finishDragSource(source);
            throw e;
        }
        if (!source.hasSerializableFormats()) {
            dataDevice.setCurrentDragSource(source);
        }
    }

    private static int cursorToCursorShape(Cursor c) {
        if (c == DragSource.DefaultCopyDrop) {
            return WLCursorManager.CURSOR_SHAPE_COPY;
        } else if (c == DragSource.DefaultMoveDrop) {
            return WLCursorManager.CURSOR_SHAPE_MOVE;
        } else if (c == DragSource.DefaultLinkDrop) {
            return WLCursorManager.CURSOR_SHAPE_ALIAS;
        } else if (c == DragSource.DefaultCopyNoDrop
                || c == DragSource.DefaultMoveNoDrop
                || c == DragSource.DefaultLinkNoDrop) {
            return WLCursorManager.CURSOR_SHAPE_NO_DROP;
        }
        return -1;
    }

    private static Cursor waylandActionToDefaultCursor(int waylandAction) {
        if ((waylandAction & WLDataDevice.DND_MOVE) != 0) {
            return DragSource.DefaultMoveDrop;
        } else if ((waylandAction & WLDataDevice.DND_COPY) != 0) {
            return DragSource.DefaultCopyDrop;
        }
        return DragSource.DefaultCopyNoDrop;
    }

    @Override
    protected void setNativeCursor(long nativeCtxt, Cursor c, int cType) {
        if (c == null) {
            // null means "restore default cursor handling".
            WLCursorManager.setDnDCursorShape(WLCursorManager.CURSOR_SHAPE_DEFAULT);

            // In cursor-fallback mode, the drag icon is showing a custom
            // cursor image that must be replaced immediately with the
            // default DnD cursor for the current action.
            synchronized (this) {
                if (activeDragSource != null && activeDragSourceUsesCursorFallback) {
                    Cursor defaultCursor = waylandActionToDefaultCursor(activeDragSource.action);
                    Image image = cursorToImage(defaultCursor);
                    if (image != null) {
                        var mainSurface = getSurface();
                        int scale = mainSurface != null ? mainSurface.getGraphicsDevice().getDisplayScale() : 1;
                        Point offset = cursorToDragImageOffset(defaultCursor, scale);
                        activeDragSource.setDnDIcon(image, scale, offset.x, offset.y);
                    }
                }
            }
            return;
        }

        // Built-in DnD cursors: use cursor-shape-v1 (best effort).
        int shape = cursorToCursorShape(c);
        if (shape >= 0) {
            WLCursorManager.setDnDCursorShape(shape);
        }

        // Update the drag icon with cursor artwork when:
        // - no app drag image was provided (cursor fallback mode), OR
        // - the cursor is a custom (app-supplied) cursor
        // The drag icon is the only cross-client visual feedback channel.
        var mainSurface = getSurface();
        int scale = mainSurface != null ? mainSurface.getGraphicsDevice().getDisplayScale() : 1;

        synchronized (this) {
            if (activeDragSource == null) {
                return;
            }
            if (!activeDragSourceUsesCursorFallback) {
                return;
            }

            Image image = cursorToImage(c);
            if (image == null) {
                return;
            }

            Point dragImageOffset = cursorToDragImageOffset(c, scale);
            activeDragSource.setDnDIcon(image, scale, dragImageOffset.x, dragImageOffset.y);
        }
    }

    private static Image cursorToImage(Cursor cursor) {
        if (cursor instanceof CustomCursor cc) {
            return cc.getImage();
        }
        return null;
    }

    private static Point cursorToDragImageOffset(Cursor cursor, int scale) {
        if (!(cursor instanceof CustomCursor cc)) {
            return new Point(0, 0);
        }

        // For app-supplied custom cursors, respect their declared hotspot.
        if (cursorToCursorShape(cursor) < 0) {
            Point hs = cc.getHotSpot();
            return new Point(-hs.x / scale, -hs.y / scale);
        }

        // For built-in DnD cursors used as fallback drag icons, position
        // away from the pointer so they don't overlap the compositor cursor.
        Dimension cursorSize = WLCursorManager.getCursorSize(cursor, scale);
        if (cursorSize == null) {
            Image image = cc.getImage();
            if (image != null) {
                cursorSize = new Dimension(image.getWidth(null), image.getHeight(null));
            }
        }
        if (cursorSize != null) {
            return new Point(cursorSize.width / scale, cursorSize.height / scale);
        }
        return new Point(0, 0);
    }
}
