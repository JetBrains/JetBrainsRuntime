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

import sun.awt.SunToolkit;
import sun.java2d.SurfaceData;
import sun.java2d.wl.WLSurfaceDataExt;

import java.util.Objects;

/**
 * Represents a wl_surface Wayland object.
 * All operations must be performed on the AWT thread.
 */
public class WLSurface {
    private final long nativePtr; // an opaque native handle
    private final long wlSurfacePtr; // a pointer to a wl_surface object
    protected boolean isValid = true;

    private SurfaceData surfaceData;

    static {
        initIDs();
    }

    protected WLSurface() {
        nativePtr = nativeCreateWlSurface();
        if (nativePtr == 0) {
            throw new RuntimeException("Failed to create WLSurface");
        }
        wlSurfacePtr = wlSurfacePtr(nativePtr);
        surfaceData = null; // having surface data means that the surface is visible
    }

    public void dispose() {
        assert SunToolkit.isAWTLockHeldByCurrentThread();

        if (isValid) {
            hide();
            nativeDestroyWlSurface(nativePtr);
            isValid = false;
        }
    }

    public void hide() {
        assert SunToolkit.isAWTLockHeldByCurrentThread();
        assertIsValid();

        if (surfaceData == null) return;
        SurfaceData.convertTo(WLSurfaceDataExt.class, surfaceData).assignSurface(0);
        surfaceData = null;

        nativeHideWlSurface(nativePtr);
    }

    protected void assertIsValid() {
        if (!isValid) {
            throw new IllegalStateException("WLSurface is not valid");
        }
    }

    public boolean hasSurfaceData() {
        assert SunToolkit.isAWTLockHeldByCurrentThread();
        assertIsValid();

        return surfaceData != null;
    }

    public void associateWithSurfaceData(SurfaceData data) {
        assert SunToolkit.isAWTLockHeldByCurrentThread();
        Objects.requireNonNull(data);
        assertIsValid();

        if (surfaceData != null) return;

        surfaceData = data;
        SurfaceData.convertTo(WLSurfaceDataExt.class, data).assignSurface(wlSurfacePtr);
    }

    public void commit() {
        assert SunToolkit.isAWTLockHeldByCurrentThread();
        assertIsValid();

        nativeCommitWlSurface(nativePtr);
    }

    /**
     * NB: the returned pointer is valid for as long as AWT lock is being held
     *
     * @return a pointer to wl_surface native object
     */
    public long getWlSurfacePtr() {
        assert SunToolkit.isAWTLockHeldByCurrentThread();
        assertIsValid();

        return wlSurfacePtr;
    }

    protected long getNativePtr() {
        assert SunToolkit.isAWTLockHeldByCurrentThread();
        assertIsValid();

        return nativePtr;
    }

    public void setSize(int width, int height) {
        assert SunToolkit.isAWTLockHeldByCurrentThread();
        assertIsValid();

        nativeSetSize(nativePtr, width, height);
    }

    public void setOpaqueRegion(int x, int y, int width, int height) {
        assert SunToolkit.isAWTLockHeldByCurrentThread();
        assertIsValid();

        nativeSetOpaqueRegion(nativePtr, x, y, width, height);
    }

    void notifyEnteredOutput(int wlOutputID) {
        // Called from native code whenever the corresponding wl_surface leaves an output (monitor)
    }

    void notifyLeftOutput(int wlOutputID) {
        // Called from native code whenever the corresponding wl_surface leaves an output (monitor)
    }

    public void updateSurfaceSize(int surfaceWidth, int surfaceHeight) {
        assert SunToolkit.isAWTLockHeldByCurrentThread();
        assertIsValid();

        setSize(surfaceWidth, surfaceHeight);
        if (surfaceData != null && !surfaceData.getColorModel().hasAlpha()) {
            setOpaqueRegion(0, 0, surfaceWidth, surfaceHeight);
        }
    }

    private static native void initIDs();

    private native long wlSurfacePtr(long ptr);
    private native void nativeCommitWlSurface(long ptr);
    private native long nativeCreateWlSurface();
    private native void nativeSetSize(long ptr, int width, int height);
    private native void nativeDestroyWlSurface(long ptr);
    private native void nativeHideWlSurface(long ptr);
    private native void nativeSetOpaqueRegion(long ptr, int x, int y, int width, int height);
}
