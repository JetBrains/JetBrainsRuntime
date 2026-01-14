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

import java.awt.GraphicsConfiguration;
import java.util.Objects;

/**
 * Represents a wl_surface Wayland object; remains valid until it is hidden,
 * at which point wl_surface is scheduled for destruction.
 * Once associated with a SurfaceData object using showWithData(),
 * manages the lifetime of that SurfaceData object.
 *
 * All operations must be performed on the WL thread (enforced with assert).
 */
public class WLSurface {
    private final long nativePtr; // an opaque native handle
    private final long wlSurfacePtr; // a pointer to a wl_surface object
    private boolean isValid = true;

    private SurfaceData surfaceData;

    static {
        initIDs();
    }

    protected WLSurface() {
        assert WLToolkit.isWLThread() : "Method must only be invoked on EDT";

        nativePtr = nativeCreateWlSurface();
        if (nativePtr == 0) {
            throw new RuntimeException("Failed to create WLSurface");
        }
        wlSurfacePtr = wlSurfacePtr(nativePtr);
    }

    public void commitSurfaceData() {
        assert WLToolkit.isWLThread() : "Method must only be invoked on EDT";
        assertIsValid();

        if (surfaceData != null) {
            SurfaceData.convertTo(WLSurfaceDataExt.class, surfaceData).commit();
        }
    }

    public void hide() {
        assert WLToolkit.isWLThread() : "Method must only be invoked on EDT";
        assertIsValid();

        if (isValid) {
            nativeHideWlSurface(nativePtr);
            nativeCommitWlSurface(nativePtr);

            if (surfaceData != null) {
                surfaceData.invalidate();
                surfaceData = null;
            }

            isValid = false;
            WLToolkit.performOnWLThread(this::dispose);
        }
    }

    public final boolean isValid() {
        return isValid;
    }

    protected final void assertIsValid() {
        if (!isValid) {
            throw new IllegalStateException("WLSurface is not valid");
        }
    }

    public boolean isVisible() {
        assert WLToolkit.isWLThread() : "Method must only be invoked on EDT";
        return surfaceData != null;
    }

    public void updateSurfaceData(GraphicsConfiguration gc, int width, int height, int scale) {
        assert WLToolkit.isWLThread() : "Method must only be invoked on EDT";
        assertIsValid();

        if (surfaceData != null) {
            SurfaceData.convertTo(WLSurfaceDataExt.class, surfaceData).revalidate(gc, width, height, scale);
        }
    }

    public void showWithData(SurfaceData sd) {
        assert WLToolkit.isWLThread() : "Method must only be invoked on EDT";
        assertIsValid();
        Objects.requireNonNull(sd);
        assert sd.isValid() : "SurfaceData must be valid"; // avoid show/hide/show with the same data

        surfaceData = sd;
        SurfaceData.convertTo(WLSurfaceDataExt.class, surfaceData).assignSurface(wlSurfacePtr);
    }

    public void commit() {
        assert WLToolkit.isWLThread() : "Method must only be invoked on EDT";
        assertIsValid();

        nativeCommitWlSurface(nativePtr);
    }

    /**
     * NB: the returned pointer is valid for as long as it is used on EDT
     *
     * @return a pointer to wl_surface native object
     */
    public final long getWlSurfacePtr() {
        assert WLToolkit.isWLThread() : "Method must only be invoked on EDT";
        assertIsValid();

        return wlSurfacePtr;
    }

    protected final long getNativePtr() {
        assert WLToolkit.isWLThread() : "Method must only be invoked on EDT";
        assertIsValid();

        return nativePtr;
    }

    public void setSize(int width, int height) {
        assert WLToolkit.isWLThread() : "Method must only be invoked on EDT";
        assertIsValid();

        nativeSetSize(nativePtr, width, height);
    }

    public void setOpaqueRegion(int x, int y, int width, int height) {
        assert WLToolkit.isWLThread() : "Method must only be invoked on EDT";
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
        assert WLToolkit.isWLThread() : "Method must only be invoked on EDT";
        assertIsValid();

        setSize(surfaceWidth, surfaceHeight);
        if (surfaceData != null && !surfaceData.getColorModel().hasAlpha()) {
            setOpaqueRegion(0, 0, surfaceWidth, surfaceHeight);
        }
    }

    private void dispose() {
        assert WLToolkit.isWLThread() : "Method must only be invoked on EDT";
        nativeDestroyWlSurface(nativePtr);
    }

    private static native void initIDs();

    private native long wlSurfacePtr(long ptr);
    private native void nativeCommitWlSurface(long ptr);
    private native long nativeCreateWlSurface();
    private native void nativeSetSize(long ptr, int width, int height);
    private static native void nativeDestroyWlSurface(long ptr);
    private native void nativeHideWlSurface(long ptr);
    private native void nativeSetOpaqueRegion(long ptr, int x, int y, int width, int height);
}
