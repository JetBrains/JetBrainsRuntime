/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2023, JetBrains s.r.o.. All rights reserved.
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

import sun.java2d.SunGraphics2D;
import sun.java2d.SurfaceData;
import sun.java2d.loops.CompositeType;
import sun.java2d.loops.GraphicsPrimitive;
import sun.java2d.loops.GraphicsPrimitiveMgr;
import sun.java2d.loops.SurfaceType;
import sun.java2d.pipe.BufferedMaskFill;

import java.awt.Composite;

import static sun.java2d.loops.CompositeType.SrcNoEa;
import static sun.java2d.loops.CompositeType.SrcOver;
import static sun.java2d.loops.SurfaceType.*;

class VKMaskFill extends BufferedMaskFill {

    static void register() {
        GraphicsPrimitive[] primitives = {
                new VKMaskFill(AnyColor,                  SrcOver),
                new VKMaskFill(OpaqueColor,               SrcNoEa),
                new VKMaskFill(GradientPaint,             SrcOver),
                new VKMaskFill(OpaqueGradientPaint,       SrcNoEa),
                new VKMaskFill(LinearGradientPaint,       SrcOver),
                new VKMaskFill(OpaqueLinearGradientPaint, SrcNoEa),
                new VKMaskFill(RadialGradientPaint,       SrcOver),
                new VKMaskFill(OpaqueRadialGradientPaint, SrcNoEa),
                new VKMaskFill(TexturePaint,              SrcOver),
                new VKMaskFill(OpaqueTexturePaint,        SrcNoEa),
        };
        GraphicsPrimitiveMgr.register(primitives);
    }

    protected VKMaskFill(SurfaceType srcType, CompositeType compType) {
        super(VKRenderQueue.getInstance(),
                srcType, compType, VKSurfaceData.VKSurface);
    }

    @Override
    protected native void maskFill(int x, int y, int w, int h,
                                   int maskoff, int maskscan, int masklen,
                                   byte[] mask);

    @Override
    protected void validateContext(SunGraphics2D sg2d,
                                   Composite comp, int ctxflags)
    {
        VKSurfaceData dstData = SurfaceData.convertTo(VKSurfaceData.class,
                                                       sg2d.surfaceData);

        VKContext.validateContext(dstData, dstData,
                sg2d.getCompClip(), comp,
                null, sg2d.paint, sg2d, ctxflags);
    }
}
