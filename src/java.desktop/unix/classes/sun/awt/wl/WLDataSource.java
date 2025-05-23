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

import java.awt.datatransfer.Transferable;

public class WLDataSource {
    // nativePtr will be reset to 0 after this object receives a "cancelled" event, and is destroyed.
    // Reading from nativePtr doesn't need to be synchronized for methods that happen before announcing
    // this object as a selection, or a drag-and-drop source.
    private long nativePtr;

    private final Transferable data;

    private native long initNative(long dataDeviceNativePtr, int protocol);

    private static native void offerMimeImpl(long nativePtr, String mime);

    private static native void destroyImpl(long nativePtr);

    private static native void setDnDActionsImpl(long nativePtr, int actions);

    WLDataSource(long dataDeviceNativePtr, int protocol, Transferable data) {
        var wlDataTransferer = (WLDataTransferer) WLDataTransferer.getInstance();

        nativePtr = initNative(dataDeviceNativePtr, protocol);
        assert nativePtr != 0; // should've already thrown in native
        this.data = data;

        try {
            if (data != null) {
                long[] formats = wlDataTransferer.getFormatsForTransferableAsArray(data, wlDataTransferer.getFlavorTable());
                for (long format : formats) {
                    String mime = wlDataTransferer.getNativeForFormat(format);
                    offerMimeImpl(nativePtr, mime);
                }
            }
        } catch (Throwable e) {
            destroyImpl(nativePtr);
            throw e;
        }
    }

    // Used by WLDataDevice to set this source as a selection or to start drag-and-drop.
    // This method can only safely be called before setting this source as selection, or starting drag-and-drop,
    // because after that, source might receive a cancel event at any time and be destroyed.
    long getNativePtr() {
        return nativePtr;
    }

    // This method can only be called once before setting this object as a drag-and-drop source
    public void setDnDActions(int actions) {
        if (nativePtr == 0) {
            throw new IllegalStateException("Native pointer is null");
        }
        setDnDActionsImpl(nativePtr, actions);
    }

    public synchronized void destroy() {
        if (nativePtr != 0) {
            destroyImpl(nativePtr);
            nativePtr = 0;
        }
    }

    // Event handlers, called from native code on the data transferer dispatch thread

    protected void handleSend(String mime, int fd) {
        WLDataDevice.transferContentsWithType(data, mime, fd);
    }

    protected void handleCancelled() {
        destroy();
    }

    protected void handleTargetAcceptsMime(String mime) {
        // TODO: drag-and-drop implementation, synchronization
    }

    protected void handleDnDDropPerformed() {
        // TODO: drag-and-drop implementation, synchronization
    }

    protected void handleDnDFinished() {
        // TODO: drag-and-drop implementation, synchronization
    }

    protected void handleDnDAction(int action) {
        // TODO: drag-and-drop implementation, synchronization
    }
}