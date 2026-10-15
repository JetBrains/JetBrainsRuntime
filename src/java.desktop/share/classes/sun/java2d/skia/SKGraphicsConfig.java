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

import sun.awt.image.SunVolatileImage;
import sun.awt.image.SurfaceManager;
import sun.java2d.Surface;
import sun.java2d.pipe.hw.AccelSurface;
import sun.java2d.pipe.hw.AccelTypedVolatileImage;
import sun.java2d.pipe.hw.ContextCapabilities;

import java.awt.GraphicsConfiguration;
import java.awt.Image;
import java.awt.ImageCapabilities;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.ColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DirectColorModel;
import java.awt.image.VolatileImage;

/**
 * What the Skia backend needs from the {@code GraphicsConfiguration} that
 * hosts it. Implemented by the configuration of the platform pipeline Skia
 * renders through: {@code MTLGraphicsConfig} on macOS (Skia surfaces wrap
 * Metal surfaces) and {@code Win32SKGraphicsConfig} on Windows (Skia owns the
 * surfaces and presents them itself). Same pattern as {@code OGLGraphicsConfig}
 * over the CGL/WGL/GLX configurations. The helpers are what a configuration
 * owned by Skia answers the same way on every platform.
 */
public interface SKGraphicsConfig extends SurfaceManager.ProxiedGraphicsConfig {

    /**
     * Pointer to the native configuration the Skia context of this
     * configuration is created for ({@code SKPlatformConfig} in
     * {@code SKPlatform.h}).
     */
    long getNativeConfigInfo();

    ColorModel getColorModel(int transparency);

    /** Off-screen Skia surface (VolatileImage back buffers, cached textures). */
    SKSurfaceData createSKSurfaceData(int width, int height, ColorModel cm, Image image, int type);

    /** The colour models of the accelerated pipelines: 24-bit RGB, 25-bit bitmask, premultiplied ARGB. */
    static ColorModel colorModelFor(int transparency) {
        switch (transparency) {
            case Transparency.OPAQUE:
                return new DirectColorModel(24, 0xff0000, 0xff00, 0xff);
            case Transparency.BITMASK:
                return new DirectColorModel(25, 0xff0000, 0xff00, 0xff, 0x1000000);
            case Transparency.TRANSLUCENT:
                ColorSpace cs = ColorSpace.getInstance(ColorSpace.CS_sRGB);
                return new DirectColorModel(cs, 32, 0xff0000, 0xff00, 0xff, 0xff000000, true, DataBuffer.TYPE_INT);
            default:
                return null;
        }
    }

    /**
     * {@code AccelGraphicsConfig.createCompatibleVolatileImage} for a
     * configuration whose accelerated surfaces are Skia's: textures and
     * render-to-texture surfaces, no bitmask transparency.
     */
    default VolatileImage createSKVolatileImage(int width, int height, int transparency, int type) {
        if ((type != AccelSurface.RT_TEXTURE && type != AccelSurface.TEXTURE) || transparency == Transparency.BITMASK) {
            return null;
        }
        SunVolatileImage vi = new AccelTypedVolatileImage((GraphicsConfiguration) this, width, height, transparency, type);
        Surface sd = vi.getDestSurface();
        if (!(sd instanceof AccelSurface accel) || accel.getType() != type) {
            vi.flush();
            return null;
        }
        return vi;
    }

    /** Every Skia texture can be a render target; the rest is what Graphite guarantees. */
    final class ContextCaps extends ContextCapabilities {
        public ContextCaps(String adapterId) {
            super(CAPS_RT_TEXTURE_ALPHA | CAPS_RT_TEXTURE_OPAQUE | CAPS_MULTITEXTURE
                  | CAPS_TEXNONPOW2 | CAPS_TEXNONSQUARE | CAPS_PS30, adapterId);
        }
    }

    final class ImageCaps extends ImageCapabilities {
        public ImageCaps() {
            super(true);
        }

        @Override
        public boolean isTrueVolatile() {
            return true;
        }
    }
}
