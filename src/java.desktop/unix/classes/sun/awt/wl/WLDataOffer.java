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

import sun.util.logging.PlatformLogger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class WLDataOffer {
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.wl.WLDataOffer");

    public interface EventListener {
        void availableActionsChanged(int actions);
        void selectedActionChanged(int action);
    }

    private long nativePtr;
    private final List<String> mimes = new ArrayList<>();
    private int sourceActions = 0;
    private int selectedAction = 0;
    private EventListener listener;
    private int refcount = 1;

    private static native void destroyImpl(long nativePtr);

    private static native void acceptImpl(long nativePtr, long serial, String mime);

    private static native int openReceivePipe(long nativePtr, String mime);

    private static native void finishDnDImpl(long nativePtr);

    private static native void setDnDActionsImpl(long nativePtr, int actions, int preferredAction);

    @Override
    public synchronized String toString() {
        return "WLDataOffer{" +
                "nativePtr=" + getID() +
                ", mimes=" + mimes +
                ", sourceActions=" + sourceActions +
                ", selectedAction=" + selectedAction +
                ", listener=" + listener +
                ", refcount=" + refcount +
                '}';
    }

    public synchronized String getID() {
        return "0x" + Long.toHexString(nativePtr);
    }

    private WLDataOffer(long nativePtr) {
        if (nativePtr == 0) {
            throw new IllegalArgumentException("nativePtr is null");
        }
        this.nativePtr = nativePtr;
    }

    public synchronized void unref() {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("unref(), this = " + getID() + ", old refcount = " + refcount);
        }
        if (nativePtr != 0 && refcount > 0) {
            --refcount;
            if (refcount == 0) {
                if (log.isLoggable(PlatformLogger.Level.FINE)) {
                    log.fine("destroyImpl(" + getID() + ")");
                }
                destroyImpl(nativePtr);
                nativePtr = 0;
            }
        }
    }

    public synchronized WLDataOffer ref() {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("ref(), this = " + getID() + ", old refcount = " + refcount);
        }
        ++refcount;
        return this;
    }

    public synchronized byte[] receiveData(String mime) throws IOException  {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("receiveData(), this = " + getID() + ", mime = " + mime);
        }
        int fd;

        if (nativePtr == 0) {
            throw new IllegalStateException("nativePtr is 0");
        }

        fd = openReceivePipe(nativePtr, mime);

        // Otherwise an exception should be thrown from native code
        assert fd != -1 : "An invalid file descriptor received from the native code";

        int timeoutMs = 100;
        return WLDataDevice.readAllBytesFromFd(fd, timeoutMs);
    }

    public synchronized void accept(long serial, String mime) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("accept(), this = " + getID() + ", serial = " + serial + ", mime = " + mime);
        }

        if (nativePtr == 0) {
            throw new IllegalStateException("nativePtr is 0");
        }

        acceptImpl(nativePtr, serial, mime);
    }

    public synchronized void finishDnD() {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("finishDnD(), this = " + getID());
        }

        if (nativePtr == 0) {
            throw new IllegalStateException("nativePtr is 0");
        }

        if (selectedAction != 0) {
            finishDnDImpl(nativePtr);
        }
    }

    public synchronized void setDnDActions(int actions, int preferredAction) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("setDnDActions(), this = " + getID() + ", actions = " + actions + ", preferredAction = " + preferredAction);
        }

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

    public synchronized void setListener(EventListener listener) {
        this.listener = listener;
    }

    public synchronized List<String> getMimes() {
        return new ArrayList<>(mimes);
    }

    public synchronized int getSourceActions() {
        return sourceActions;
    }

    public synchronized int getSelectedAction() {
        return selectedAction;
    }

    // Event handlers, called from native code on the data device dispatch thread
    private synchronized void handleOfferMime(String mime) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("handleOfferMime(), this = " + getID() + ", mime = '" + mime + "'");
        }
        mimes.add(mime);
    }

    private synchronized void handleSourceActions(int actions) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("handleSourceActions(), this = " + getID() + ", actions = " + actions);
        }
        sourceActions = actions;
        if (this.listener != null) {
            this.listener.availableActionsChanged(actions);
        }
    }

    private synchronized void handleAction(int action) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("handleAction(), this = " + getID() + ", action = " + action);
        }
        selectedAction = action;
        if (this.listener != null) {
            this.listener.selectedActionChanged(action);
        }
    }
}
