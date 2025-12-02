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

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.datatransfer.Transferable;
import java.awt.image.BufferedImage;
import java.util.HashSet;

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

    private static native void setDnDIconImpl(long nativePtr, int scale, int width, int height, int offsetX, int offsetY, int[] pixels);

    WLDataSource(WLDataDevice dataDevice, int protocol, Transferable data) {
        var wlDataTransferer = (WLDataTransferer) WLDataTransferer.getInstance();

        nativePtr = initNative(dataDevice.getNativePtr(), protocol);
        assert nativePtr != 0 : "Failed to initialize the native part of the source"; // should've already thrown in native
        this.data = data;

        try {
            if (data != null) {
                var mimes = new HashSet<String>();

                long[] formats = wlDataTransferer.getFormatsForTransferableAsArray(data, wlDataTransferer.getFlavorTable());
                for (long format : formats) {
                    String mime = wlDataTransferer.getNativeForFormat(format);
                    mimes.add(mime);

                    if (mime.contains("/") && !mime.startsWith("JAVA_DATAFLAVOR")) {
                        // Qt apps require lowercase spelling of mime types, like text/plain;charset=utf-8
                        mimes.add(mime.toLowerCase());
                    }
                }

                for (var mime : mimes) {
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

    public void setDnDIcon(Image image, int scale, int offsetX, int offsetY) {
        if (nativePtr == 0) {
            throw new IllegalStateException("Native pointer is null");
        }

        int width = image.getWidth(null);
        int height = image.getHeight(null);
        int[] pixels = new int[width * height];

        if (image instanceof BufferedImage) {
            // NOTE: no need to ensure that the BufferedImage is TYPE_INT_ARGB,
            // getRGB() does pixel format conversion automatically
            ((BufferedImage) image).getRGB(0, 0, width, height, pixels, 0, width);
        } else {
            BufferedImage bufferedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = bufferedImage.createGraphics();
            g.drawImage(image, 0, 0, null);
            g.dispose();

            bufferedImage.getRGB(0, 0, width, height, pixels, 0, width);
        }

        setDnDIconImpl(nativePtr, scale, width, height, offsetX, offsetY, pixels);
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

    protected void handleTargetAcceptsMime(String mime) {}

    protected void handleDnDDropPerformed() {}

    protected void handleDnDFinished() {}

    protected void handleDnDAction(int action) {}
}