/*
 * Copyright 2025 JetBrains s.r.o.
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

import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DirectColorModel;
import java.awt.image.PixelInterleavedSampleModel;
import java.awt.image.SampleModel;

import sun.awt.image.PixelConverter;
import sun.java2d.loops.SurfaceType;

import static java.awt.image.DataBuffer.TYPE_BYTE;
import static java.awt.image.DataBuffer.TYPE_INT;
import static java.awt.Transparency.OPAQUE;
import static java.awt.Transparency.TRANSLUCENT;

/**
 * Format model describes properties of the surface, necessary for the creation of compatible BufferedImages.
 */
public enum VKFormatModel {
    NONE(BufferedImage.TYPE_CUSTOM, null, null, null, null),

    INT_ARGB_PRE(BufferedImage.TYPE_INT_ARGB_PRE, SurfaceType.IntArgbPre, PixelConverter.ArgbPre.instance,
            new DirectColorModel(sRGB(), 32, 0xff0000, 0xff00, 0xff, 0xff000000, true, TYPE_INT), null),

    INT_RGB(BufferedImage.TYPE_INT_RGB, SurfaceType.IntRgb, PixelConverter.Xrgb.instance,
            new DirectColorModel(sRGB(), 24, 0xff0000, 0xff00, 0xff, 0x00000000, false, TYPE_INT), null),

    CUSTOM_4BYTE_BGRA_PRE(BufferedImage.TYPE_CUSTOM, SurfaceType.Any4Byte, PixelConverter.ArgbPre.instance,
            new ComponentColorModel(sRGB(), true, true, TRANSLUCENT, TYPE_BYTE), sampleModel(TYPE_BYTE, 4, 2, 1, 0, 3)),

    CUSTOM_4BYTE_BGRx(BufferedImage.TYPE_CUSTOM, SurfaceType.Any4Byte, PixelConverter.Xrgb.instance,
            new ComponentColorModel(sRGB(), false, false, OPAQUE, TYPE_BYTE), sampleModel(TYPE_BYTE, 4, 2, 1, 0)),

    CUSTOM_4BYTE_RGBA_PRE(BufferedImage.TYPE_CUSTOM, SurfaceType.Any4Byte, CustomPixelConverter.AbgrPre,
            new ComponentColorModel(sRGB(), true, true, TRANSLUCENT, TYPE_BYTE), sampleModel(TYPE_BYTE, 4, 0, 1, 2, 3)),

    CUSTOM_4BYTE_RGBx(BufferedImage.TYPE_CUSTOM, SurfaceType.Any4Byte, PixelConverter.Xbgr.instance,
            new ComponentColorModel(sRGB(), false, false, OPAQUE, TYPE_BYTE), sampleModel(TYPE_BYTE, 4, 0, 1, 2)),

    CUSTOM_INT_ABGR_PRE(BufferedImage.TYPE_CUSTOM, SurfaceType.AnyDcm, CustomPixelConverter.AbgrPre,
            new DirectColorModel(sRGB(), 32, 0xff, 0xff00, 0xff0000, 0xff000000, true, TYPE_INT), null),

    INT_BGR(BufferedImage.TYPE_INT_BGR, SurfaceType.IntBgr, PixelConverter.Xbgr.instance,
            new DirectColorModel(sRGB(), 24, 0xff, 0xff00, 0xff0000, 0x00000000, false, TYPE_INT), null);

    private final int type;
    private final SurfaceType surfaceType;
    private final ColorModel colorModel;
    private final SampleModelFactory sampleModelFactory;

    VKFormatModel(int type, SurfaceType surfaceType, PixelConverter pixelConverter,
                  ColorModel colorModel, SampleModelFactory sampleModelFactory) {
        this.type = type;
        this.surfaceType = surfaceType == null ? null : surfaceType.deriveSubType(
                "Vulkan-compatible buffered surface (" + surfaceType.getDescriptor() + ")", pixelConverter);
        this.colorModel = colorModel;
        this.sampleModelFactory = sampleModelFactory == null && colorModel != null ?
                this.colorModel::createCompatibleSampleModel : sampleModelFactory;
    }

    public SurfaceType getSurfaceType() {
        return surfaceType;
    }

    public ColorModel getColorModel() {
        return colorModel;
    }

    public SampleModel createSampleModel(int width, int height) {
        return sampleModelFactory.createSampleModel(width, height);
    }

    @FunctionalInterface
    interface SampleModelFactory {
        SampleModel createSampleModel(int w, int h);
    }

    private static SampleModelFactory sampleModel(int dataType, int components, int... bandOffsets) {
        return (w, h) -> new PixelInterleavedSampleModel(dataType, w, h, components, w*components, bandOffsets);
    }

    private static ColorSpace sRGB() {
        return ColorSpace.getInstance(ColorSpace. CS_sRGB);
    }

    private static abstract class CustomPixelConverter extends PixelConverter {

        private static final PixelConverter AbgrPre = new CustomPixelConverter(0xff000000) {
            @Override
            public int rgbToPixel(int rgb, ColorModel cm) {
                int a = rgb >>> 24;
                int r = (rgb >> 16) & 0xff;
                int g = (rgb >>  8) & 0xff;
                int b = (rgb      ) & 0xff;
                int a2 = a + (a >> 7);
                r = (r * a2) >> 8;
                g = (g * a2) >> 8;
                b = (b * a2) >> 8;
                return ((a << 24) | (b << 16) | (g << 8) | (r));
            }
            @Override
            public int pixelToRgb(int pixel, ColorModel cm) {
                int a = pixel >>> 24;
                int r = (pixel      ) & 0xff;
                int g = (pixel >>  8) & 0xff;
                int b = (pixel >> 16) & 0xff;
                if (a != 0) {
                    r = ((r << 8) - r) / a;
                    g = ((g << 8) - g) / a;
                    b = ((b << 8) - b) / a;
                }
                return ((a << 24) | (r << 16) | (g << 8) | (b));
            }
        };

        private CustomPixelConverter(int alphaMask) { this.alphaMask = alphaMask; }
        @Override
        public abstract int rgbToPixel(int rgb, ColorModel cm);
        @Override
        public abstract int pixelToRgb(int pixel, ColorModel cm);
    }
}
