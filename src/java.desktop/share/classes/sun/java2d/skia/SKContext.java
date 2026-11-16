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

import sun.java2d.SunGraphics2D;

import sun.java2d.pipe.BufferedContext;
import sun.java2d.pipe.Region;
import sun.java2d.pipe.RenderBuffer;
import sun.java2d.pipe.hw.AccelSurface;

import java.awt.Composite;
import java.awt.Paint;
import java.awt.geom.AffineTransform;
import java.util.WeakHashMap;

import static sun.java2d.pipe.BufferedOpCodes.DISPOSE_SURFACE;
import static sun.java2d.pipe.BufferedOpCodes.SET_SCRATCH_SURFACE;

/**
 * Per-GraphicsConfig rendering context of the Skia backend. State validation
 * (surfaces, clip, composite, paint, transform) is inherited from
 * {@link BufferedContext}; this class only adds the queue plumbing.
 */
final class SKContext extends BufferedContext {

    private static final WeakHashMap<SKGraphicsConfig, SKContext> contexts = new WeakHashMap<>();

    private SKContext() {
        super(SKRenderQueue.getInstance());
    }

    static synchronized SKContext forConfig(SKGraphicsConfig graphicsConfig) {
        return contexts.computeIfAbsent(graphicsConfig, _ -> new SKContext());
    }

    /**
     * The native side keeps one render state for all configs, whereas
     * {@link BufferedContext} tracks what it last sent per context. Drop the
     * per-context cache whenever the current context changes so that clip,
     * composite, paint and transform are re-sent.
     */
    public static void validateContext(AccelSurface source, AccelSurface destination,
                                       Region clip, Composite composite, AffineTransform transform,
                                       Paint paint, SunGraphics2D sunGraphics2D, int flags) {
        BufferedContext context = destination.getContext();
        if (currentContext != context) {
            context.invalidateContext();
        }
        BufferedContext.validateContext(source, destination, clip, composite, transform, paint, sunGraphics2D, flags);
    }

    public static void validateContext(AccelSurface surface) {
        validateContext(surface, surface, null, null, null, null, null, NO_CONTEXT_FLAGS);
    }

    static void setScratchSurface(SKGraphicsConfig graphicsConfig) {
        setScratchSurface(graphicsConfig.getNativeConfigInfo());
    }

    /**
     * Makes the Skia context of the given config current without a destination.
     *
     * @param nativeConfigInfo {@link SKGraphicsConfig#getNativeConfigInfo}
     */
    static void setScratchSurface(long nativeConfigInfo) {
        currentContext = null;
        SKRenderQueue renderQueue = SKRenderQueue.getInstance();
        RenderBuffer buffer = renderQueue.getBuffer();
        // the opcode, then the native configuration
        renderQueue.ensureCapacityAndAlignment(Integer.BYTES + Long.BYTES, Integer.BYTES);
        buffer.putInt(SET_SCRATCH_SURFACE);
        buffer.putLong(nativeConfigInfo);
    }

    /**
     * Frees the native side of a surface ({@code SKSurfaceData.dispose}).
     *
     * @param surfaceOps the surface's native ops
     */
    static void disposeSurface(long surfaceOps) {
        currentContext = null;
        SKRenderQueue renderQueue = SKRenderQueue.getInstance();
        RenderBuffer buffer = renderQueue.getBuffer();
        // the opcode, then the surface's native ops
        renderQueue.ensureCapacityAndAlignment(Integer.BYTES + Long.BYTES, Integer.BYTES);
        buffer.putInt(DISPOSE_SURFACE);
        buffer.putLong(surfaceOps);
    }
}
