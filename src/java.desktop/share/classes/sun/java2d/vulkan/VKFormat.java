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

import java.awt.GraphicsConfiguration;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.PixelInterleavedSampleModel;
import java.awt.image.SinglePixelPackedSampleModel;
import java.awt.image.Raster;
import java.awt.image.SampleModel;
import java.awt.image.WritableRaster;
import java.nio.ByteOrder;
import java.security.AccessController;
import java.security.PrivilegedAction;

import sun.awt.image.BufImgSurfaceData;
import sun.awt.image.ByteComponentRaster;
import sun.awt.image.IntegerComponentRaster;
import sun.awt.image.SurfaceManager;
import sun.java2d.SurfaceData;
import sun.java2d.loops.SurfaceType;

/**
 * Vulkan format description, which allows creation of compatible BufferedImages.
 */
public enum VKFormat {
    // VK_FORMAT_B8G8R8A8_UNORM doesn't have a matching standard format in Java,
    // but it can be aliased as TYPE_INT_ARGB on little-endian systems.
    B8G8R8A8_UNORM(44,
            LEOptimizations.ENABLED ? VKFormatModel.INT_ARGB_PRE : VKFormatModel.CUSTOM_4BYTE_BGRA_PRE,
            LEOptimizations.ENABLED ? VKFormatModel.INT_RGB      : VKFormatModel.CUSTOM_4BYTE_BGRx);

    private final int value;
    private final SurfaceType surfaceType, translucentSurfaceType, opaqueSurfaceType;
    private final VKFormatModel translucentModel, opaqueModel;
    private final VKBufImgGraphicsConfig bufferedGraphicsConfig = new VKBufImgGraphicsConfig(this);

    VKFormat(int value, VKFormatModel translucentModel, VKFormatModel opaqueModel) {
        this.value = value;
        this.surfaceType = VKSurfaceData.VKSurface.deriveSubType("Vulkan surface (" + name() + ")");
        this.translucentSurfaceType = translucentModel == null ? null :
                                 this.surfaceType.deriveSubType("Vulkan surface (" + name() + ", TRANSLUCENT)");
        this.opaqueSurfaceType = this.surfaceType.deriveSubType("Vulkan surface (" + name() + ", OPAQUE)");
        this.translucentModel = translucentModel;
        this.opaqueModel = opaqueModel;
    }

    public int getValue() {
        return value;
    }

    public SurfaceType getSurfaceType(int transparency) {
        return switch (transparency) {
            case 0 -> surfaceType; // Any transparency.
            case Transparency.TRANSLUCENT, Transparency.BITMASK -> translucentSurfaceType;
            case Transparency.OPAQUE -> opaqueSurfaceType;
            default -> null;
        };
    }

    public VKFormatModel getFormatModel(int transparency) {
        return transparency != Transparency.OPAQUE ? translucentModel : opaqueModel;
    }

    public BufferedImage createCompatibleImage(int width, int height, int transparency) {
        VKFormatModel formatModel = getFormatModel(transparency);
        ColorModel colorModel = formatModel.getColorModel();
        SampleModel sampleModel = formatModel.createSampleModel(width, height);
        SurfaceType surfaceType = formatModel.getSurfaceType();
        WritableRaster raster = Raster.createWritableRaster(sampleModel, null);
        BufferedImage image = new BufferedImage(colorModel, raster, colorModel.isAlphaPremultiplied(), null);
        VKBufImgSurfaceData surfaceData = new VKBufImgSurfaceData(bufferedGraphicsConfig, raster, image, surfaceType);
        SurfaceManager.setManager(image, new VKBufImgSurfaceManager(image, surfaceData));
        return image;
    }

    public boolean isTranslucencyCapable() {
        return translucentModel != VKFormatModel.NONE;
    }

    /**
     * Some Vulkan formats can be more efficiently aliased as built-in Java formats on little-endian systems.
     */
    private interface LEOptimizations {
        @SuppressWarnings("removal")
        boolean ENABLED = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN &&
                "true".equalsIgnoreCase(AccessController.doPrivileged((PrivilegedAction<String>) () ->
                        System.getProperty("sun.java2d.vulkan.leOptimizations", "true")));
    }

    private static class VKBufImgSurfaceManager extends SurfaceManager {
        private final BufferedImage image;
        private final VKBufImgSurfaceData sd;
        private VKBufImgSurfaceManager(BufferedImage image, VKBufImgSurfaceData sd) {
            this.image = image;
            this.sd = sd;
        }
        public SurfaceData getPrimarySurfaceData() { return sd; }
        public SurfaceData restoreContents() { return sd; }
    }

    private static class VKBufImgSurfaceData extends BufImgSurfaceData {
        private final GraphicsConfiguration gc;
        private VKBufImgSurfaceData(GraphicsConfiguration gc,
                                    WritableRaster raster, BufferedImage image, SurfaceType surfaceType) {
            super(raster.getDataBuffer(), image, surfaceType, 1, 1);
            this.gc = gc;

            Object array;
            if (raster instanceof IntegerComponentRaster r) array = r.getDataStorage();
            else if (raster instanceof ByteComponentRaster r) array = r.getDataStorage();
            else throw new IllegalArgumentException("Unsupported raster type: " + raster.getClass().getCanonicalName());

            int pixStr, scanStr;
            if (raster.getSampleModel() instanceof PixelInterleavedSampleModel sm) {
                pixStr = sm.getPixelStride();
                scanStr = sm.getScanlineStride();
            } else if (raster.getSampleModel() instanceof SinglePixelPackedSampleModel sm) {
                pixStr = DataBuffer.getDataTypeSize(sm.getDataType()) / 8;
                scanStr = sm.getScanlineStride() * pixStr;
            } else throw new IllegalArgumentException("Unsupported sample model: " +
                    raster.getSampleModel().getClass().getCanonicalName());

            initRaster(array, 0, 0, image.getWidth(), image.getHeight(), pixStr, scanStr, null);
            initSolidLoops();
        }
        @Override
        public GraphicsConfiguration getDeviceConfiguration() {
            return gc;
        }
    }

    private static class VKBufImgGraphicsConfig extends VKOffscreenGraphicsConfig {
        private VKBufImgGraphicsConfig(VKFormat format) {
            super(null, format);
        }
        @Override
        public VKGPU getGPU() {
            throw new UnsupportedOperationException("No VKGPU associated with VKBufImgGraphicsConfig");
        }
    }
}
