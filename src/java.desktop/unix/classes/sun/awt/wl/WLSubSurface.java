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

import java.awt.Point;

public class WLSubSurface extends WLSurface {
    private final Point offset = new Point();
    private final WLMainSurface mainSurface;
    private final long wlSubSurfacePtr; // a pointer to wl_subsurface object


    public WLSubSurface(WLMainSurface mainSurface, int x, int y) {
        super();
        this.mainSurface = mainSurface;
        offset.setLocation(x, y);
        wlSubSurfacePtr = nativeCreateWlSubSurface(getWlSurfacePtr(), mainSurface.getWlSurfacePtr());
        if (wlSubSurfacePtr == 0) {
            throw new RuntimeException("Failed to create WLSubSurface");
        }

        nativeSetPosition(wlSubSurfacePtr, x, y);
    }

    @Override
    void notifyEnteredOutput(int wlOutputID) {
        // Deliberately ignored
    }

    @Override
    void notifyLeftOutput(int wlOutputID) {
        // Deliberately ignored
    }

    @Override
    public void dispose() {
        super.dispose();
    }

    @Override
    public void updateSurfaceSize(int surfaceWidth, int surfaceHeight) {
        super.updateSurfaceSize(surfaceWidth, surfaceHeight);
        // TODO: create input region excluding the main surface
        // Need to know main surface's size for that
    }

    private native long nativeCreateWlSubSurface(long surfacePtr, long parentSurfacePtr);
    private native void nativeSetPosition(long ptr, int x, int y);
}
