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

import sun.awt.datatransfer.DataTransferer;
import sun.awt.datatransfer.SunClipboard;
import sun.util.logging.PlatformLogger;

import java.awt.datatransfer.FlavorTable;
import java.awt.datatransfer.Transferable;
import java.io.IOException;
import java.util.Arrays;

public final class WLClipboard extends SunClipboard {
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.wl.WLClipboard");

    // true if this is the "primary selection" clipboard,
    // false otherwise (the regular clipboard).
    private final boolean isPrimary; // used by native

    private final Object dataLock = new Object();
    // A handle to the native clipboard representation, 0 if not available.
    private WLDataOffer clipboardContents;   // guarded by dataLock
    private WLDataSource ourOffer;           // guarded by dataLock

    static {
        flavorTable = DataTransferer.adaptFlavorMap(getDefaultFlavorTable());
    }

    private final static FlavorTable flavorTable;

    public WLClipboard(String name, boolean isPrimary) {
        super(name);
        this.isPrimary = isPrimary;

        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("Clipboard: Created " + this);
        }
    }

    @Override
    public String toString() {
        return String.format("Clipboard: Wayland %s", (isPrimary ? "selection clipboard" : "clipboard"));
    }

    @Override
    public long getID() {
        return isPrimary ? 2 : 1;
    }

    /**
     * Called when we lose ownership of the clipboard.
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
                if (log.isLoggable(PlatformLogger.Level.FINE)) {
                    log.fine("Clipboard: Offering new contents (" + contents + ")");
                }

                WLDataSource newOffer = null;

                if (isPrimary) {
                    newOffer = WLDataSource.createPrimarySelection(contents);
                } else {
                    newOffer = WLDataSource.createWayland(contents);
                }

                synchronized (dataLock) {
                    if (ourOffer != null) {
                        ourOffer.destroy();
                    }
                    ourOffer = newOffer;
                    newOffer.setSelection(eventSerial);
                }
            }
        } else {
            this.owner = null;
            this.contents = null;
        }
    }

    /**
     * @return formats the current clipboard is available in; could be null
     */
    @Override
    protected long[] getClipboardFormats() {
        WLDataTransferer wlDataTransferer = (WLDataTransferer) DataTransferer.getInstance();
        synchronized (dataLock) {
            if (clipboardContents == null || !clipboardContents.isValid()) {
                return null;
            }

            var mimes = clipboardContents.getMimes();
            long[] formats = new long[mimes.size()];
            for (int i = 0; i < mimes.size(); ++i) {
                formats[i] = wlDataTransferer.getFormatForNativeAsLong(mimes.get(i));
            }
            return formats;
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
        WLDataTransferer wlDataTransferer = (WLDataTransferer) DataTransferer.getInstance();
        String mime = wlDataTransferer.getNativeForFormat(format);
        synchronized (dataLock) {
            return clipboardContents.receiveData(mime);
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

    void handleClipboardOffer(WLDataOffer offer /* nullable */) {
        lostOwnershipNow(null);

        synchronized (dataLock) {
            if (clipboardContents != null) {
                clipboardContents.destroy();
            }

            clipboardContents = offer;
        }
    }
}