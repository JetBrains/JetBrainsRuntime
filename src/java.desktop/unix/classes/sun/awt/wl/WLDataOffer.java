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

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class WLDataOffer {
    private long nativePtr;
    private final List<String> mimes = new ArrayList<>();
    private int sourceActions = -1;
    private int selectedAction = -1;

    private static native void destroyImpl(long nativePtr);

    private static native void acceptImpl(long nativePtr, long serial, String mime);

    private static native int openReceivePipe(long nativePtr, String mime);

    private static native void finishDnDImpl(long nativePtr);

    private static native void setDnDActionsImpl(long nativePtr, int actions, int preferredAction);

    private WLDataOffer(long nativePtr) {
        if (nativePtr == 0) {
            throw new IllegalArgumentException("nativePtr is null");
        }
        this.nativePtr = nativePtr;
    }

    // after calling destroy(), this object enters an invalid state and needs to be deleted
    public void destroy() {
        if (nativePtr != 0) {
            destroyImpl(nativePtr);
            nativePtr = 0;
        }
    }

    public byte[] receiveData(String mime) throws IOException  {
        int fd;

        if (nativePtr == 0) {
            throw new IllegalStateException("nativePtr is 0");
        }

        fd = openReceivePipe(nativePtr, mime);

        assert(fd != -1); // Otherwise an exception should be thrown from native code

        FileDescriptor javaFD = new FileDescriptor();
        jdk.internal.access.SharedSecrets.getJavaIOFileDescriptorAccess().set(javaFD, fd);
        try (var in = new FileInputStream(javaFD)) {
            return WLDataDevice.readAllBytesFrom(in);
        }
    }

    public void accept(long serial, String mime) {
        if (nativePtr == 0) {
            throw new IllegalStateException("nativePtr is 0");
        }

        acceptImpl(nativePtr, serial, mime);
    }

    public void finishDnD() {
        if (nativePtr == 0) {
            throw new IllegalStateException("nativePtr is 0");
        }

        finishDnDImpl(nativePtr);
    }

    public void setDnDActions(int actions, int preferredAction) {
        if (nativePtr == 0) {
            throw new IllegalStateException("nativePtr is 0");
        }

        if (actions != 0) {
            if ((actions & preferredAction) == 0) {
                throw new IllegalArgumentException("preferredAction is not a valid action");
            }
        }

        setDnDActionsImpl(nativePtr, actions, preferredAction);
    }

    public synchronized List<String> getMimes() {
        return mimes;
    }

    // Event handlers, called from native code on the data device dispatch thread
    private synchronized void handleOfferMime(String mime) {
        mimes.add(mime);
    }

    private synchronized void handleSourceActions(int actions) {
        sourceActions = actions;
    }

    private synchronized void handleAction(int action) {
        selectedAction = action;
    }
}
