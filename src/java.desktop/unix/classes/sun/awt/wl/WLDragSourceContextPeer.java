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
import sun.awt.datatransfer.DataTransferer;
import sun.awt.dnd.SunDragSourceContextPeer;
import sun.util.logging.PlatformLogger;

import java.awt.Component;
import java.awt.Cursor;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DragGestureEvent;
import java.awt.dnd.InvalidDnDOperationException;
import java.util.Arrays;
import java.util.Map;

public class WLDragSourceContextPeer extends SunDragSourceContextPeer {
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.wl.WLDragSourceContextPeer");

    private static final WLDragSourceContextPeer INSTANCE = new WLDragSourceContextPeer(null);

    /**
     * construct a new SunDragSourceContextPeer
     *
     * @param dge
     */
    public WLDragSourceContextPeer(DragGestureEvent dge) {
        super(dge);
    }

    @Override
    protected void startDrag(Transferable trans, long[] formats, Map<Long, DataFlavor> formatMap) {
        WLDataTransferer wlDataTransferer = (WLDataTransferer) DataTransferer.getInstance();
        WLPointerEvent wlPointerEvent = WLToolkit.getInputState().eventWithSerial();
        if (wlPointerEvent != null) {
            if (log.isLoggable(PlatformLogger.Level.FINE)) {
                log.fine("formats: " + Arrays.toString(formats));
            }

            if (formats.length > 0) {
                String[] mime = new String[formats.length];
                for (int i = 0; i < formats.length; i++) {
                    mime[i] = wlDataTransferer.getNativeForFormat(formats[i]);
                }

                if (log.isLoggable(PlatformLogger.Level.FINE)) {
                    log.fine("Dragging this transferable (" + trans + ") in these MIME formats: " + Arrays.toString(mime));
                }
                long surfacePtr = WLComponentPeer.getParentWindowNativePtr(getComponent());
                startDragNative(wlPointerEvent.getSerial(), surfacePtr, mime, trans);
            }
        }
    }

    @Override
    protected void setNativeCursor(long nativeCtxt, Cursor c, int cType) {
        // TODO
    }

    static WLDragSourceContextPeer createDragSourceContextPeer(DragGestureEvent dge)
            throws InvalidDnDOperationException {
        INSTANCE.setTrigger(dge);
        return INSTANCE;
    }

    private native void startDragNative(long eventSerial, long windowSurfacePtr, String[] mime, Object data);
}