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
import sun.java2d.loops.CompositeType;
import sun.java2d.loops.GraphicsPrimitive;
import sun.java2d.loops.GraphicsPrimitiveMgr;
import sun.java2d.loops.SurfaceType;
import sun.java2d.pipe.BufferedMaskBlit;
import sun.java2d.pipe.Region;

import java.awt.Composite;

import static sun.java2d.loops.CompositeType.SrcNoEa;
import static sun.java2d.loops.CompositeType.SrcOver;
import static sun.java2d.loops.SurfaceType.*;

/** Mask blit (software-composited tiles) for the Skia backend; mirrors {@code MTLMaskBlit}. */
final class SKMaskBlit extends BufferedMaskBlit {

    static void register() {
        GraphicsPrimitive[] primitives = {
                new SKMaskBlit(IntArgb,    SrcOver),
                new SKMaskBlit(IntArgbPre, SrcOver),
                new SKMaskBlit(IntRgb,     SrcOver),
                new SKMaskBlit(IntRgb,     SrcNoEa),
                new SKMaskBlit(IntBgr,     SrcOver),
                new SKMaskBlit(IntBgr,     SrcNoEa),
        };
        GraphicsPrimitiveMgr.register(primitives);
    }

    private SKMaskBlit(SurfaceType srcType, CompositeType compType) {
        super(SKRenderQueue.getInstance(), srcType, compType, SKSurfaceData.SKSurface);
    }

    @Override
    protected void validateContext(SurfaceData dstData, Composite comp, Region clip) {
        SKSurfaceData skDst = (SKSurfaceData) dstData;
        SKContext.validateContext(skDst, skDst,
                clip, comp, null, null, null,
                SKContext.NO_CONTEXT_FLAGS);
    }
}
