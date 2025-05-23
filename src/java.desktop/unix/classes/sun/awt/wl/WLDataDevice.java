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

import jdk.internal.misc.InnocuousThread;
import sun.util.logging.PlatformLogger;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.SortedMap;

public class WLDataDevice {
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.wl.WLDataDevice");

    private long nativePtr;
    private final WLClipboard systemClipboard;
    private final WLClipboard primarySelectionClipboard;

    public static final int DND_COPY = 0x01;
    public static final int DND_MOVE = 0x02;
    public static final int DND_ASK = 0x04;
    public static final int DND_ALL = DND_COPY | DND_MOVE | DND_ASK;

    public static final int DATA_TRANSFER_PROTOCOL_WAYLAND = 1;
    public static final int DATA_TRANSFER_PROTOCOL_PRIMARY_SELECTION = 2;

    WLDataDevice(long wlSeatNativePtr) {
        nativePtr = initNative(wlSeatNativePtr);

        var queueThread = InnocuousThread.newThread("AWT-Wayland-data-transferer-dispatcher", () -> {
            dispatchDataTransferQueueImpl(nativePtr);
            if (log.isLoggable(PlatformLogger.Level.FINE)) {
                log.fine("data transfer dispatcher exited");
            }
        });
        queueThread.setDaemon(true);
        queueThread.start();

        systemClipboard = new WLClipboard(this, "System", false);
        if (isProtocolSupported(DATA_TRANSFER_PROTOCOL_PRIMARY_SELECTION)) {
            primarySelectionClipboard = new WLClipboard(this, "Selection", true);
        } else {
            primarySelectionClipboard = null;
        }
    }

    private native static void initIDs();
    static {
        initIDs();
    }

    private native long initNative(long wlSeatNativePtr);
    private static native boolean isProtocolSupportedImpl(long nativePtr, int protocol);
    private static native void dispatchDataTransferQueueImpl(long nativePtr);
    private static native void setSelectionImpl(int protocol, long nativePtr, long dataOfferNativePtr, long serial);

    public WLDataSource createDataSourceFromTransferable(int protocol, Transferable transferable) {
        return new WLDataSource(nativePtr, protocol, transferable);
    }

    public boolean isProtocolSupported(int protocol) {
        return isProtocolSupportedImpl(nativePtr, protocol);
    }

    public void setSelection(int protocol, WLDataSource source, long serial) {
        setSelectionImpl(protocol, nativePtr, source.getNativePtr(), serial);
    }

    public WLClipboard getSystemClipboard() {
        return systemClipboard;
    }

    public WLClipboard getPrimarySelectionClipboard() {
        return primarySelectionClipboard;
    }

    static void transferContentsWithType(Transferable contents, String mime, int fd) {
        Objects.requireNonNull(contents);
        Objects.requireNonNull(mime);

        WLDataTransferer wlDataTransferer = (WLDataTransferer) WLDataTransferer.getInstance();

        SortedMap<Long, DataFlavor> formatMap = wlDataTransferer.getFormatsForTransferable(contents, wlDataTransferer.getFlavorTable());

        long targetFormat = wlDataTransferer.getFormatForNativeAsLong(mime);
        DataFlavor flavor = formatMap.get(targetFormat);

        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("will write contents (" + contents + ") in format " + mime + " to fd=" + fd);
            log.fine("data flavor: " + flavor);
        }

        if (flavor != null) {
            try {
                byte[] bytes = wlDataTransferer.translateTransferable(contents, flavor, targetFormat);
                if (bytes == null) return;
                FileDescriptor javaDestFD = new FileDescriptor();
                jdk.internal.access.SharedSecrets.getJavaIOFileDescriptorAccess().set(javaDestFD, fd);
                try (var out = new FileOutputStream(javaDestFD)) {
                    if (log.isLoggable(PlatformLogger.Level.FINE)) {
                        log.fine("about to write " + bytes.length + " bytes to " + out);
                    }

                    FileChannel ch = out.getChannel();
                    ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    // Need to write with retries because when a pipe is in the non-blocking mode
                    // writing more than its capacity (usually 16 pages or 64K) fails with EAGAIN.
                    // Since we receive fd from the Wayland sever, we can't assume it
                    // to always be in the blocking mode.
                    while (buffer.hasRemaining()) {
                        ch.write(buffer);
                    }
                }
            } catch (IOException e) {
                log.warning("failed to write contents (" + contents + ") in format " + mime + " to fd=" + fd);
            }
        }
    }

    private static final int DEFAULT_BUFFER_SIZE = 4096;
    private static final int MAX_BUFFER_SIZE = Integer.MAX_VALUE - 8;

    /**
     * Reads the given input stream until EOF and returns its contents as an array of bytes.
     */
    static byte[] readAllBytesFrom(FileInputStream inputStream) throws IOException {
        int len = Integer.MAX_VALUE;
        List<byte[]> bufs = null;
        byte[] result = null;
        int total = 0;
        int remaining = len;
        int n;
        do {
            byte[] buf = new byte[Math.min(remaining, DEFAULT_BUFFER_SIZE)];
            int nread = 0;

            while ((n = inputStream.read(buf, nread,
                    Math.min(buf.length - nread, remaining))) > 0) {
                nread += n;
                remaining -= n;
            }

            if (nread > 0) {
                if (MAX_BUFFER_SIZE - total < nread) {
                    throw new OutOfMemoryError("Required array size too large");
                }
                if (nread < buf.length) {
                    buf = Arrays.copyOfRange(buf, 0, nread);
                }
                total += nread;
                if (result == null) {
                    result = buf;
                } else {
                    if (bufs == null) {
                        bufs = new ArrayList<>();
                        bufs.add(result);
                    }
                    bufs.add(buf);
                }
            }
            // if the last call to read returned -1 or the number of bytes
            // requested have been read then break
        } while (n >= 0 && remaining > 0);

        if (bufs == null) {
            if (result == null) {
                return new byte[0];
            }
            return result.length == total ?
                    result : Arrays.copyOf(result, total);
        }

        result = new byte[total];
        int offset = 0;
        remaining = total;
        for (byte[] b : bufs) {
            int count = Math.min(b.length, remaining);
            System.arraycopy(b, 0, result, offset, count);
            offset += count;
            remaining -= count;
        }

        return result;
    }

    private WLDataOffer currentDnDOffer = null;

    // Event handlers, called from native on the EDT
    private void handleDnDEnter(WLDataOffer offer, long serial, long surfacePtr, double x, double y) {
        if (currentDnDOffer != null) {
            currentDnDOffer.destroy();
        }
        currentDnDOffer = offer;
    }

    private void handleDnDLeave() {
        if (currentDnDOffer != null) {
            currentDnDOffer.destroy();
            currentDnDOffer = null;
        }
    }

    private void handleDnDMotion(long timestamp, double x, double y) {
    }

    private void handleDnDDrop() {
    }

    private void handleSelection(WLDataOffer offer /* nullable */, int protocol) {
        WLClipboard clipboard = (protocol == DATA_TRANSFER_PROTOCOL_PRIMARY_SELECTION) ? primarySelectionClipboard : systemClipboard;

        if (clipboard != null) {
            clipboard.handleClipboardOffer(offer);
        }
    }
}
