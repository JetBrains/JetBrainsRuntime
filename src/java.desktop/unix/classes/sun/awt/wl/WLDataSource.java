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
    private long nativePtr;
    private Transferable data;

    private static native long createImpl(int protocol);

    private static native void bindNative(long nativePtr, WLDataSource object);

    private static native void offerMimeImpl(long nativePtr, String mime);

    private static native void destroyImpl(long nativePtr);

    private static native void setDnDActionsImpl(long nativePtr, int actions);

    private static native void setSelectionImpl(long nativePtr, long serial);

    private WLDataSource(int protocol, Transferable data) {
        var wlDataTransferer = (WLDataTransferer) WLDataTransferer.getInstance();

        long nativePtr = createImpl(protocol);
        if (nativePtr == 0) {
            throw new InternalError("Failed to create data source");
        }
        this.nativePtr = nativePtr;
        this.data = data;
        bindNative(nativePtr, this);

        if (data != null) {
            long[] formats = wlDataTransferer.getFormatsForTransferableAsArray(data, wlDataTransferer.getFlavorTable());
            for (long format : formats) {
                String mime = wlDataTransferer.getNativeForFormat(format);
                offerMime(mime);
            }
        }
    }

    public static WLDataSource createWayland(Transferable data) {
        return new WLDataSource(WLDataDevice.DATA_TRANSFER_PROTOCOL_WAYLAND, data);
    }

    public static WLDataSource createPrimarySelection(Transferable data) {
        return new WLDataSource(WLDataDevice.DATA_TRANSFER_PROTOCOL_PRIMARY_SELECTION, data);
    }

    public boolean isValid() {
        return nativePtr != 0;
    }

    public void offerMime(String mime) {
        if (nativePtr == 0) {
            throw new IllegalStateException("Native pointer is null");
        }
        offerMimeImpl(nativePtr, mime);
    }

    public void destroy() {
        if (nativePtr != 0) {
            destroyImpl(nativePtr);
            nativePtr = 0;
        }
    }

    public void setDnDActions(int actions) {
        if (nativePtr == 0) {
            throw new IllegalStateException("Native pointer is null");
        }
        setDnDActionsImpl(nativePtr, actions);
    }

    public void setSelection(long serial) {
        if (nativePtr == 0) {
            throw new IllegalStateException("Native pointer is null");
        }
        setSelectionImpl(nativePtr, serial);
    }

    // Event handlers, called from native code on the data transferer dispatch thread
    protected void handleTargetAcceptsMime(String mime) {
    }

    protected void handleSend(String mime, int fd) {
        WLDataDevice.transferContentsWithType(data, mime, fd);
    }

    protected void handleCancelled() {
        destroy();
    }

    protected void handleDnDDropPerformed() {
    }

    protected void handleDnDFinished() {
    }

    protected void handleDnDAction(int action) {
    }
}