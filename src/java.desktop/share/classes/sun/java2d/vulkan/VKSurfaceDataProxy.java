/*
 * Copyright 2026 JetBrains s.r.o.
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

package sun.java2d.vulkan;

import sun.java2d.SurfaceData;
import sun.java2d.SurfaceDataProxy;
import sun.java2d.loops.CompositeType;

import java.awt.Color;
import java.awt.Transparency;

public class VKSurfaceDataProxy extends SurfaceDataProxy {

    public static SurfaceDataProxy createProxy(SurfaceData srcData, VKGraphicsConfig dstConfig) {
        if (srcData instanceof VKSurfaceData) {
            return UNCACHED;
        }
        return new VKSurfaceDataProxy(dstConfig, srcData.getTransparency());
    }

    private final VKGraphicsConfig vkgc;
    private final int transparency;

    private VKSurfaceDataProxy(VKGraphicsConfig vkgc, int transparency) {
        this.vkgc = vkgc;
        this.transparency = transparency;
    }

    @Override
    public boolean isSupportedOperation(SurfaceData srcData, int txtype, CompositeType comp, Color bgColor) {
        if (!VKEnv.isSurfaceDataAccelerated()) {
            return false; // no need to create proxies
        }
        // Exclude CUSTOM composites
        // Exclude textures with bgColor (except when it's provably unused)
        return (comp.isDerivedFrom(CompositeType.AnyAlpha) || comp.isDerivedFrom(CompositeType.Xor)) &&
                (bgColor == null || transparency == Transparency.OPAQUE);
    }

    @Override
    public SurfaceData validateSurfaceData(SurfaceData srcData, SurfaceData cachedData, int w, int h) {
        if (cachedData == null) {
            try {
                cachedData = vkgc.createManagedSurface(w, h, transparency);
            } catch (NullPointerException | OutOfMemoryError ignored) {
                return null;
            }
        }
        return cachedData;
    }
}
