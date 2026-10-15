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
package sun.java2d.skia;

import sun.java2d.SurfaceData;
import sun.java2d.SurfaceDataProxy;
import sun.java2d.loops.CompositeType;

import java.awt.Color;
import java.awt.Transparency;

/**
 * Caches a software source image as a Skia texture after it has been drawn a
 * few times, like {@code MTLSurfaceDataProxy}. The cached copy is a
 * {@code TEXTURE}-type {@link SKSurfaceData}; the sw->texture blit loops keep
 * it up to date and the texture->surface loops draw it.
 */
final class SKSurfaceDataProxy extends SurfaceDataProxy {

    static SurfaceDataProxy createProxy(SurfaceData srcData, SKGraphicsConfig gc) {
        if (srcData instanceof SKSurfaceData) {
            // a VolatileImage: already on the GPU, nothing to cache
            return UNCACHED;
        }
        return new SKSurfaceDataProxy(gc, srcData.getTransparency());
    }

    private final SKGraphicsConfig gc;
    private final int transparency;

    private SKSurfaceDataProxy(SKGraphicsConfig gc, int transparency) {
        this.gc = gc;
        this.transparency = transparency;
    }

    @Override
    public SurfaceData validateSurfaceData(SurfaceData srcData, SurfaceData cachedData, int w, int h) {
        if (cachedData == null || cachedData.isSurfaceLost()) {
            if (cachedData != null) {
                cachedData.flush();
            }
            try {
                cachedData = gc.createSKSurfaceData(w, h, gc.getColorModel(transparency), null,
                                                      SKSurfaceData.TEXTURE);
            } catch (OutOfMemoryError e) {
                return null;
            }
        }
        return cachedData;
    }

    @Override
    public boolean isSupportedOperation(SurfaceData srcData, int txtype, CompositeType comp, Color bgColor) {
        return comp.isDerivedFrom(CompositeType.AnyAlpha) && (bgColor == null || transparency == Transparency.OPAQUE);
    }
}
