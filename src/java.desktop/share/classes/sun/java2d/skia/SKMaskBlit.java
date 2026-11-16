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

    // BufferedMaskBlit.c's MAX_MASK_LENGTH (32 x 32): it drops a tile whose mask array is longer
    private static final int MAX_TILE_SIZE = 32;

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

    private SKMaskBlit(SurfaceType sourceType, CompositeType compositeType) {
        super(SKRenderQueue.getInstance(), sourceType, compositeType, SKSurfaceData.SKSurface);
    }

    /**
     * BufferedMaskBlit drops a tile whose mask array is longer than 32 x 32, whatever part of it the tile uses
     * (BufferedMaskBlit.c). Text tiles share their glyph list's mask array, which a glyph larger than that grows
     * for good (GlyphList.getGrayBits): every later tile of such text would be dropped. Larger arrays are copied
     * into compact masks, in tiles of at most 32 x 32.
     */
    @Override
    public void MaskBlit(SurfaceData source, SurfaceData destination, Composite composite, Region clip,
                         int sourceX, int sourceY, int destinationX, int destinationY, int width, int height,
                         byte[] mask, int maskOffset, int maskScan) {
        if (mask == null || mask.length <= MAX_TILE_SIZE * MAX_TILE_SIZE) {
            super.MaskBlit(source, destination, composite, clip, sourceX, sourceY, destinationX, destinationY,
                           width, height, mask, maskOffset, maskScan);
            return;
        }
        for (int tileY = 0; tileY < height; tileY += MAX_TILE_SIZE) {
            for (int tileX = 0; tileX < width; tileX += MAX_TILE_SIZE) {
                int tileWidth = Math.min(MAX_TILE_SIZE, width - tileX), tileHeight = Math.min(MAX_TILE_SIZE, height - tileY);
                byte[] tileMask = new byte[tileWidth * tileHeight];
                for (int row = 0; row < tileHeight; row++) {
                    System.arraycopy(mask, maskOffset + (tileY + row) * maskScan + tileX, tileMask, row * tileWidth,
                                     tileWidth);
                }
                super.MaskBlit(source, destination, composite, clip, sourceX + tileX, sourceY + tileY,
                               destinationX + tileX, destinationY + tileY, tileWidth, tileHeight, tileMask, 0, tileWidth);
            }
        }
    }

    @Override
    protected void validateContext(SurfaceData destinationData, Composite composite, Region clip) {
        SKSurfaceData destination = (SKSurfaceData) destinationData;
        SKContext.validateContext(destination, destination,
                clip, composite, null, null, null,
                SKContext.NO_CONTEXT_FLAGS);
    }
}
