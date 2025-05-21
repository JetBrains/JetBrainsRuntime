/*
 * Copyright (c) 2022-2025, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2022-2025, JetBrains s.r.o.. All rights reserved.
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

import sun.java2d.SurfaceData;
import sun.java2d.wl.WLSurfaceDataExt;

public class WLSurface {
    private final long nativePtr;
    private SurfaceData surfaceData; // accessed under AWT lock
    private final WLComponentPeer peer;
    boolean visible;

    static {
        initIDs();
    }

    WLSurface(WLComponentPeer peer) {
        this.peer = peer;
        nativePtr = nativeCreateWlSurface();
        if (nativePtr == 0) {
            throw new RuntimeException("Failed to create WLSurface");
        }
        WLToolkit.registerWLSurface(wlSurfacePtr(nativePtr), peer);
    }

    void destroy() {
        synchronized (this) {
            WLToolkit.unregisterWLSurface(nativePtr);
            SurfaceData.convertTo(WLSurfaceDataExt.class, surfaceData).assignSurface(0);
            nativeDestroyWlSurface(nativePtr);
            visible = false;
        }
    }

    void commit() {
        nativeCommitWlSurface(nativePtr);
    }

    void moveTo(int x, int y) {
        nativeMoveSurface(nativePtr, x, y);
    }

    private native long wlSurfacePtr(long ptr);
    private native void nativeCommitWlSurface(long ptr);
    private native long nativeCreateWlSurface();
    private native void nativeDestroyWlSurface(long ptr);
    private native void nativeSetOpaqueRegion(long ptr, int x, int y, int width, int height);
    private native void nativeMoveSurface(long ptr, int x, int y);
    private static native void initIDs();
}
