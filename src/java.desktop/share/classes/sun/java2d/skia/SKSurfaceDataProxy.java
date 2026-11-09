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

import sun.awt.SunToolkit;
import sun.java2d.SurfaceData;
import sun.java2d.SurfaceDataProxy;
import sun.java2d.loops.CompositeType;

import java.awt.Color;
import java.awt.Transparency;

/**
 * Caches a software source image as a Skia texture after it has been drawn a
 * few times, like {@code MTLSurfaceDataProxy}. The cached copy is a
 * {@code TEXTURE}-type {@link SKSurfaceData}; the software->texture blit loops keep
 * it up to date and the texture->surface loops draw it.
 */
final class SKSurfaceDataProxy extends SurfaceDataProxy {

    static SurfaceDataProxy createProxy(SurfaceData source, SKGraphicsConfig graphicsConfig) {
        if (source instanceof SKSurfaceData) {
            // a VolatileImage: already on the GPU, nothing to cache
            return UNCACHED;
        }
        return new SKSurfaceDataProxy(graphicsConfig, source.getTransparency());
    }

    private final SKGraphicsConfig graphicsConfig;
    private final int transparency;

    private SKSurfaceDataProxy(SKGraphicsConfig graphicsConfig, int transparency) {
        this.graphicsConfig = graphicsConfig;
        this.transparency = transparency;
    }

    @Override
    public SurfaceData validateSurfaceData(SurfaceData source, SurfaceData cachedData, int width, int height) {
        if (cachedData == null || cachedData.isSurfaceLost()) {
            if (cachedData != null) {
                cachedData.flush();
            }
            try {
                cachedData = graphicsConfig.createSKSurfaceData(width, height, graphicsConfig.getColorModel(transparency),
                                                                null, SKSurfaceData.TEXTURE);
            } catch (OutOfMemoryError error) {
                return null;
            }
        }
        return cachedData;
    }

    /**
     * Under the render queue's lock a cached texture is handed out only when it is
     * current; it is not created or updated there. {@code BufferedPaints.setTexturePaint}
     * looks a TexturePaint's image up again in the middle of a validation, under the lock;
     * if its cached texture was dropped since {@code SKPaints} accepted the paint (an
     * {@code Image.flush()}, a modification), creating and filling a new one validated that
     * texture as the destination in the middle of the outer validation, and the fill that
     * followed went into the texture instead of its destination. The source surface goes
     * instead, and the backend uploads it for the paint ({@code SKPaints::setTexturePaint}).
     */
    @Override
    public SurfaceData replaceData(SurfaceData source, int transformType, CompositeType compositeType,
                                   Color backgroundColor) {
        if (SunToolkit.isAWTLockHeldByCurrentThread() && !isAccelerated()) {
            return source;
        }
        return super.replaceData(source, transformType, compositeType, backgroundColor);
    }

    @Override
    public boolean isSupportedOperation(SurfaceData source, int transformType, CompositeType compositeType,
                                        Color backgroundColor) {
        return compositeType.isDerivedFrom(CompositeType.AnyAlpha)
                && (backgroundColor == null || transparency == Transparency.OPAQUE);
    }
}
