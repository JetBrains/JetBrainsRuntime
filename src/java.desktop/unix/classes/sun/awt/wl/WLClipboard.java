/*
 * Copyright 2023 JetBrains s.r.o.
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
import sun.awt.datatransfer.DataTransferer;
import sun.awt.datatransfer.SunClipboard;
import sun.util.logging.PlatformLogger;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.FlavorTable;
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

public final class WLClipboard extends SunClipboard {
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.wl.WLClipboard");

    public static final int INITIAL_MIME_FORMATS_COUNT = 10;
    private static final int DEFAULT_BUFFER_SIZE = 4096;
    private static final int MAX_BUFFER_SIZE = Integer.MAX_VALUE - 8;

    // A native handle of a Wayland queue dedicated to handling
    // data offer-related events
    private static final long dataOfferQueuePtr;

    private final long ID;

    // true if this is the "primary selection" clipboard,
    // false otherwise (the regular clipboard).
    private final boolean isPrimary; // used by native

    private final Object dataLock = new Object();
    // A handle to the native clipboard representation, 0 if not available.
    private long clipboardNativePtr;     // guarded by dataLock
    private long ourOfferNativePtr;      // guarded by dataLock

    // The list of numeric format IDs the current clipboard is available in;
    // could be null or empty.
    private List<Long> clipboardFormats; // guarded by dataLock

    // The "current" list formats for the new clipboard contents that is about
    // to be received from Wayland. Could be empty, but never null.
    private List<Long> newClipboardFormats = new ArrayList<>(INITIAL_MIME_FORMATS_COUNT); // guarded by dataLock

    private static final Thread clipboardDispatcherThread;
    static {
        initIDs();
        dataOfferQueuePtr = createDataOfferQueue();
        flavorTable = DataTransferer.adaptFlavorMap(getDefaultFlavorTable());

        Thread t = InnocuousThread.newThread(
                "AWT-Wayland-clipboard-dispatcher",
                WLClipboard::dispatchDataOfferQueue);
        t.setDaemon(true);
        t.start();
        clipboardDispatcherThread = t;
    }

    private final static FlavorTable flavorTable;

    public WLClipboard(String name, boolean isPrimary) {
        super(name);
        this.ID = initNative(isPrimary);
        this.isPrimary = isPrimary;

        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Clipboard: Created " + this);
        }
    }

    private static void dispatchDataOfferQueue() {
        dispatchDataOfferQueueImpl(dataOfferQueuePtr); // does not return until error or server disconnect
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Clipboard: data offer dispatcher exited");
        }
    }

    @Override
    public String toString() {
        return String.format("Clipboard: Wayland %s (%x)", (isPrimary ? "selection clipboard" : "clipboard"), ID);
    }

    @Override
    public long getID() {
        return ID;
    }

    /**
     * Called when we loose ownership of the clipboard.
     */
    @Override
    protected void clearNativeContext() {
        // Unused in the Wayland clipboard as we don't (and can't) keep
        // any references to the native clipboard once we have lost keyboard focus.
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Clipboard: Lost ownership of our clipboard");
        }
    }

    /**
     * Called to make the new clipboard contents known to Wayland.
     *
     * @param contents clipboard's contents.
     */
    @Override
    protected void setContentsNative(Transferable contents) {
        // The server requires "serial number of the event that triggered this request"
        // as a proof of the right to copy data.

        // It is not specified which event's serial number the Wayland server expects here,
        // so the following is a speculation based on experiments.
        // The worst case is that a "wrong" serial will be silently ignored, and our clipboard
        // will be out of sync with the real one that Wayland maintains.
        long eventSerial = isPrimary
                ? WLToolkit.getInputState().pointerButtonSerial()
                : WLToolkit.getInputState().keySerial();
        if (!isPrimary && eventSerial == 0) {
            // The "regular" clipboard's content can be changed with either a mouse click
            // (like on a menu item) or with the keyboard (Ctrl-C).
            eventSerial = WLToolkit.getInputState().pointerButtonSerial();
        }
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Clipboard: About to offer new contents using Wayland event serial " + eventSerial);
        }
        if (eventSerial != 0) {
            WLDataTransferer wlDataTransferer = (WLDataTransferer) DataTransferer.getInstance();
            long[] formats = wlDataTransferer.getFormatsForTransferableAsArray(contents, flavorTable);

            if (log.isLoggable(PlatformLogger.Level.FINE)) {
                log.fine("Clipboard: New one is available in these integer formats: " + Arrays.toString(formats));
            }
            notifyOfNewFormats(formats);

            if (formats.length > 0) {
                String[] mime = new String[formats.length];
                for (int i = 0; i < formats.length; i++) {
                    mime[i] = wlDataTransferer.getNativeForFormat(formats[i]);
                    if (log.isLoggable(PlatformLogger.Level.FINE)) {
                        log.fine("Clipboard: formats mapping " + formats[i] + " -> " + mime[i]);
                    }
                }

                if (log.isLoggable(PlatformLogger.Level.FINE)) {
                    log.fine("Clipboard: Offering new contents (" + contents + ") in these MIME formats: " + Arrays.toString(mime));
                }

                synchronized (dataLock) {
                    if (ourOfferNativePtr != 0) {
                        cancelOffer(ourOfferNativePtr);
                        ourOfferNativePtr = 0;
                    }
                }
                long newOfferPtr = offerData(eventSerial, mime, contents, dataOfferQueuePtr);
                synchronized (dataLock) {
                    ourOfferNativePtr = newOfferPtr;
                }

                // Once we have offered the data, someone may come back and ask to provide them.
                // In that event, the transferContentsWithType() will be called from native on the
                // clipboard dispatch thread.
                // A reference to contents is retained until we are notified of the new contents
                // by the Wayland server.
            }
        } else {
            this.owner = null;
            this.contents = null;
        }
    }

    /**
     * Called from native on EDT when a client has asked to provide the actual data for
     * the clipboard that we own in the given format to the given file.
     * NB: that client could be us, but we aren't necessarily aware of that once we
     * lost keyboard focus at least once after Ctrl-C.
     *
     * @param contents a reference to the clipboard's contents to be transferred
     * @param mime transfer the contents in this MIME format
     * @param destFD transfer the contents to this file descriptor and close it afterward
     *
     * @throws IOException in case writing to the given file descriptor failed
     */
    private void transferContentsWithType(Transferable contents, String mime, int destFD) throws IOException {
        assert (Thread.currentThread() == clipboardDispatcherThread);

        Objects.requireNonNull(contents);
        Objects.requireNonNull(mime);

        WLDataTransferer wlDataTransferer = (WLDataTransferer) DataTransferer.getInstance();
        SortedMap<Long,DataFlavor> formatMap =
                wlDataTransferer.getFormatsForTransferable(contents, flavorTable);

        long targetFormat = wlDataTransferer.getFormatForNativeAsLong(mime);
        DataFlavor flavor = formatMap.get(targetFormat);

        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Clipboard: will write contents (" + contents + ") in format " + mime + " to fd=" + destFD);
            log.fine("Clipboard: data flavor: " + flavor);
        }

        if (flavor != null) {
            byte[] bytes = wlDataTransferer.translateTransferable(contents, flavor, targetFormat);
            if (bytes == null) return;
            FileDescriptor javaDestFD = new FileDescriptor();
            jdk.internal.access.SharedSecrets.getJavaIOFileDescriptorAccess().set(javaDestFD, destFD);
            try (var out = new FileOutputStream(javaDestFD)) {
                if (log.isLoggable(PlatformLogger.Level.FINE)) {
                    log.fine("Clipboard: about to write " + bytes.length + " bytes to " + out);
                }

                FileChannel ch = out.getChannel();
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                // Need to write with retries because when a pipe is in the non-blocking mode
                // writing more than its capacity (usually 16 pages or 64K) fails with EAGAIN.
                // Since we receive destFD from the Wayland sever, we can't assume it
                // to always be in the blocking mode.
                while (buffer.hasRemaining()) {
                    ch.write(buffer);
                }
            }
        }
    }

    /**
     * @return formats the current clipboard is available in; could be null
     */
    @Override
    protected long[] getClipboardFormats() {
        synchronized (dataLock) {
            if (clipboardFormats != null && !clipboardFormats.isEmpty()) {
                long[] res = new long[clipboardFormats.size()];
                for (int i = 0; i < res.length; i++) {
                    res[i] = clipboardFormats.get(i);
                }
                return res;
            } else {
                return null;
            }
        }
    }

    /**
     * The clipboard contents in the given numeric format ID.
     *
     * @param format the numeric ID of the format to provide clipboard contents in
     * @return contents in the given numeric format ID
     * @throws IOException when reading from the clipboard file fails
     */
    @Override
    protected byte[] getClipboardData(long format) throws IOException {
        int fd = getClipboardFDIn(format);
        if (fd >= 0) {
            FileDescriptor javaFD = new FileDescriptor();
            jdk.internal.access.SharedSecrets.getJavaIOFileDescriptorAccess().set(javaFD, fd);
            try (var in = new FileInputStream(javaFD)) {
                byte[] bytes = readAllBytesFrom(in);
                if (log.isLoggable(PlatformLogger.Level.FINE)) {
                    log.fine("Clipboard: read data from " + fd + ": "
                            + (bytes != null ? bytes.length : 0) + " bytes");
                }
                return bytes;
            }
        }

        return null;
    }

    private int getClipboardFDIn(long format) {
        synchronized (dataLock) {
            if (log.isLoggable(PlatformLogger.Level.FINE)) {
                log.fine("Clipboard: requested content of clipboard with handle "
                        + clipboardNativePtr + " in format " + format);
            }
            if (clipboardNativePtr != 0) {
                WLDataTransferer wlDataTransferer = (WLDataTransferer) DataTransferer.getInstance();
                String mime = wlDataTransferer.getNativeForFormat(format);
                int fd = requestDataInFormat(clipboardNativePtr, mime);
                if (log.isLoggable(PlatformLogger.Level.FINE)) {
                    log.fine("Clipboard: will read data from " + fd + " in format " + mime);
                }
                return fd;
            }
        }
        return -1;
    }

    /**
     * Called from native to notify us of the availability of a new clipboard
     * denoted by the native handle in a specific MIME format.
     * This method is usually called repeatedly with the same nativePtr and
     * different formats. When all formats are announces in this way,
     * handleNewClipboard() is called.
     *
     * @param nativePtr a native handle to the clipboard
     * @param mime the MIME format in which this clipboard is available.
     */
    private void handleClipboardFormat(long nativePtr, String mime) {
        WLDataTransferer wlDataTransferer = (WLDataTransferer) DataTransferer.getInstance();
        Long format = wlDataTransferer.getFormatForNativeAsLong(mime);

        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Clipboard: new format is available for " + nativePtr + ": " + mime);
        }

        synchronized (dataLock) {
            newClipboardFormats.add(format);
        }
    }

    /**
     * Called from native to notify us that a new clipboard content
     * has been made available. The list of supported formats
     * should have already been received and saved in newClipboardFormats.
     *
     * @param newClipboardNativePtr a native handle to the clipboard
     */
    private void handleNewClipboard(long newClipboardNativePtr) {
        // Since we have a new clipboard, the existing one is no longer valid.
        // We have no way of knowing whether this "new" one is the same as the "old" one.
        lostOwnershipNow(null);

        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Clipboard: new clipboard is available: " + newClipboardNativePtr);
        }

        synchronized (dataLock) {
            long oldClipboardNativePtr = clipboardNativePtr;
            if (oldClipboardNativePtr != 0) {
                // "The client must destroy the previous selection data_offer, if any, upon receiving this event."
                destroyClipboard(oldClipboardNativePtr);
            }
            clipboardFormats = newClipboardFormats;
            clipboardNativePtr = newClipboardNativePtr; // Could be NULL

            newClipboardFormats = new ArrayList<>(INITIAL_MIME_FORMATS_COUNT);
        }

        notifyOfNewFormats(getClipboardFormats());
    }

    private void handleOfferCancelled(long offerNativePtr) {
        synchronized (dataLock) {
            assert offerNativePtr == ourOfferNativePtr;
            ourOfferNativePtr = 0;
        }
    }

    @Override
    protected void registerClipboardViewerChecked() {
        // TODO: is there any need to do more here?
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Unimplemented");
        }
    }

    @Override
    protected void unregisterClipboardViewerChecked() {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Unimplemented");
        }
    }

    private void notifyOfNewFormats(long[] formats) {
        if (areFlavorListenersRegistered()) {
            checkChange(formats);
        }
    }

    /**
     * Reads the given input stream until EOF and returns its contents as an array of bytes.
     */
    private byte[] readAllBytesFrom(FileInputStream inputStream) throws IOException {
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

    private static native void initIDs();
    private static native long createDataOfferQueue();
    private static native void dispatchDataOfferQueueImpl(long dataOfferQueuePtr);
    private native long initNative(boolean isPrimary);
    private native long offerData(long eventSerial, String[] mime, Object data, long dataOfferQueuePtr);
    private native void cancelOffer(long offerNativePtr);

    private native int requestDataInFormat(long clipboardNativePtr, String mime);
    private native void destroyClipboard(long clipboardNativePtr);
}