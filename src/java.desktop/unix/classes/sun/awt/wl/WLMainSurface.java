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

public class WLMainSurface extends WLSurface {
    private final WLWindowPeer peer;

    public WLMainSurface(WLWindowPeer peer) {
        this.peer = peer;
    }

    public WLWindowPeer getPeer() {
        return peer;
    }

    public void activateByAnotherSurface(long serial, long activatingSurfacePtr) {
        assert SunToolkit.isAWTLockHeldByCurrentThread() : "This method must be invoked while holding the AWT lock";
        assertIsValid();

        nativeActivate(getNativePtr(), serial, activatingSurfacePtr);
    }

    @Override
    public void associateWithSurfaceData(SurfaceData data) {
        super.associateWithSurfaceData(data);
        WLToolkit.registerWLSurface(getWlSurfacePtr(), peer);
    }

    @Override
    public void dispose() {
        if (isValid) {
            WLToolkit.unregisterWLSurface(getWlSurfacePtr());
            super.dispose();
        }
    }

    private native void nativeActivate(long ptr, long serial, long activatingSurfacePtr);
}
