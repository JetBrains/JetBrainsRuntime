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

import java.awt.AlphaComposite;
import sun.awt.SunHints;
import sun.awt.image.PixelConverter;
import sun.java2d.SunGraphics2D;
import sun.java2d.SurfaceData;
import sun.java2d.loops.Blit;
import sun.java2d.loops.CompositeType;
import sun.java2d.loops.GraphicsPrimitive;
import sun.java2d.loops.SurfaceType;
import static sun.java2d.pipe.BufferedOpCodes.CONFIGURE_SURFACE;
import sun.java2d.pipe.BufferedContext;
import sun.java2d.pipe.ParallelogramPipe;
import sun.java2d.pipe.PixelToParallelogramConverter;
import sun.java2d.pipe.RenderBuffer;
import sun.java2d.pipe.TextPipe;
import sun.java2d.pipe.hw.AccelSurface;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.awt.image.VolatileImage;

import static sun.java2d.pipe.BufferedOpCodes.FLUSH_SURFACE;
import static sun.java2d.pipe.hw.ContextCapabilities.CAPS_PS30;


public abstract class VKSurfaceData extends SurfaceData
        implements AccelSurface {

    // We want non-premultiplied alpha to prevent precision loss, so use PixelConverter.Argb
    // See also VKUtil_DecodeJavaColor.
    static final SurfaceType VKSurface = SurfaceType.Any.deriveSubType("VK Surface", PixelConverter.Argb.instance);

    protected static VKRenderer vkRenderPipe;
    protected static PixelToParallelogramConverter vkTxRenderPipe;
    protected static ParallelogramPipe vkAAPgramPipe;
    protected static VKTextRenderer vkTextPipe;
    protected static VKDrawImage vkImagePipe;

    static {
        VKRenderQueue rq = VKRenderQueue.getInstance();
        vkImagePipe = new VKDrawImage();
        vkTextPipe = new VKTextRenderer(rq);
        vkRenderPipe = new VKRenderer(rq);
        if (GraphicsPrimitive.tracingEnabled()) {
            vkTextPipe = vkTextPipe.traceWrap();
            //The wrapped vkRenderPipe will wrap the AA pipe as well...
            vkAAPgramPipe = vkRenderPipe.traceWrap();
        }
        vkAAPgramPipe = vkRenderPipe.getAAParallelogramPipe();
        vkTxRenderPipe = new PixelToParallelogramConverter(vkRenderPipe, vkRenderPipe, 1.0, 0.25, true);
    }

    private final VKFormat format;
    protected VKGraphicsConfig gc;
    protected double scale;
    protected int width;
    protected int height;
    private int type;

    protected VKSurfaceData(VKFormat format, int transparency, int type) {
        super(format.getSurfaceType(transparency), format.getFormatModel(transparency).getColorModel());
        this.format = format;
        this.type = type;
    }

    public VKFormat getFormat() {
        return format;
    }

    @Override
    public double getDefaultScaleX() {
        return scale;
    }

    @Override
    public double getDefaultScaleY() {
        return scale;
    }

    /**
     * Returns one of the surface type constants defined above.
     */
    @Override
    public final int getType() {
        return type;
    }

    @Override
    public GraphicsConfiguration getDeviceConfiguration() {
        return (GraphicsConfiguration) gc;
    }

    public void flush() {
        invalidate();
        VKRenderQueue rq = VKRenderQueue.getInstance();
        rq.lock();
        try {
            RenderBuffer buf = rq.getBuffer();
            rq.ensureCapacityAndAlignment(12, 4);
            buf.putInt(FLUSH_SURFACE);
            buf.putLong(getNativeOps());

            // this call is expected to complete synchronously, so flush now
            rq.flushNow();
        } finally {
            rq.unlock();
        }
    }

    protected BufferedImage getSnapshot(int x, int y, int width, int height) {
        BufferedImage image = getFormat().createCompatibleImage(width, height, getTransparency());
        SurfaceData sd = SurfaceData.getPrimarySurfaceData(image);
        Blit blit = Blit.getFromCache(getSurfaceType(), CompositeType.SrcNoEa, sd.getSurfaceType());
        blit.Blit(this, sd, AlphaComposite.Src, null, x, y, 0, 0, width, height);
        return image;
    }

    public Raster getRaster(int x, int y, int w, int h) {
        return getSnapshot(x, y, w, h).getRaster().createTranslatedChild(x, y);
    }

    @Override
    public Rectangle getNativeBounds() {
        return new Rectangle(width, height);
    }

    public void validatePipe(SunGraphics2D sg2d) {
        TextPipe textpipe;
        boolean validated = false;

        // VKTextRenderer handles both AA and non-AA text, but
        // only works with the following modes:
        // (Note: For LCD text we only enter this code path if
        // canRenderLCDText() has already validated that the mode is
        // CompositeType.SrcNoEa (opaque color), which will be subsumed
        // by the CompositeType.SrcNoEa (any color) test below.)

        if (/* CompositeType.SrcNoEa (any color) */
                (sg2d.compositeState <= SunGraphics2D.COMP_ISCOPY &&
                        sg2d.paintState <= SunGraphics2D.PAINT_ALPHACOLOR)         ||

                        /* CompositeType.SrcOver (any color) */
                        (sg2d.compositeState == SunGraphics2D.COMP_ALPHA   &&
                                sg2d.paintState <= SunGraphics2D.PAINT_ALPHACOLOR &&
                                (((AlphaComposite)sg2d.composite).getRule() ==
                                        AlphaComposite.SRC_OVER))                                 ||

                        /* CompositeType.Xor (any color) */
                        (sg2d.compositeState == SunGraphics2D.COMP_XOR &&
                                sg2d.paintState <= SunGraphics2D.PAINT_ALPHACOLOR))
        {
            textpipe = vkTextPipe;
        } else {
            // do this to initialize textpipe correctly; we will attempt
            // to override the non-text pipes below
            super.validatePipe(sg2d);
            textpipe = sg2d.textpipe;
            validated = true;
        }

        PixelToParallelogramConverter txPipe = null;
        VKRenderer nonTxPipe = null;

        if (sg2d.antialiasHint != SunHints.INTVAL_ANTIALIAS_ON) {
            if (sg2d.paintState <= SunGraphics2D.PAINT_ALPHACOLOR) {
                if (sg2d.compositeState <= SunGraphics2D.COMP_XOR) {
                    txPipe = vkTxRenderPipe;
                    nonTxPipe = vkRenderPipe;
                }
            } else if (sg2d.compositeState <= SunGraphics2D.COMP_ALPHA) {
                if (VKPaints.isValid(sg2d)) {
                    txPipe = vkTxRenderPipe;
                    nonTxPipe = vkRenderPipe;
                }
                // custom paints handled by super.validatePipe() below
            }
        } else {
            if (sg2d.paintState <= SunGraphics2D.PAINT_ALPHACOLOR) {
                if (getGraphicsConfig().isCapPresent(CAPS_PS30) &&
                        (sg2d.imageComp == CompositeType.SrcOverNoEa ||
                                sg2d.imageComp == CompositeType.SrcOver))
                {
                    if (!validated) {
                        super.validatePipe(sg2d);
                        validated = true;
                    }
                    PixelToParallelogramConverter aaConverter =
                            new PixelToParallelogramConverter(sg2d.shapepipe,
                                    vkAAPgramPipe,
                                    1.0/8.0, 0.499,
                                    false);
                    sg2d.drawpipe = aaConverter;
                    sg2d.fillpipe = aaConverter;
                    sg2d.shapepipe = aaConverter;
                } else if (sg2d.compositeState == SunGraphics2D.COMP_XOR) {
                    // install the solid pipes when AA and XOR are both enabled
                    txPipe = vkTxRenderPipe;
                    nonTxPipe = vkRenderPipe;
                }
            }
            // other cases handled by super.validatePipe() below
        }

        if (txPipe != null) {
            if (sg2d.transformState >= SunGraphics2D.TRANSFORM_TRANSLATESCALE) {
                sg2d.drawpipe = txPipe;
                sg2d.fillpipe = txPipe;
            } else if (sg2d.strokeState != SunGraphics2D.STROKE_THIN) {
                sg2d.drawpipe = txPipe;
                sg2d.fillpipe = nonTxPipe;
            } else {
                sg2d.drawpipe = nonTxPipe;
                sg2d.fillpipe = nonTxPipe;
            }
            // Note that we use the transforming pipe here because it
            // will examine the shape and possibly perform an optimized
            // operation if it can be simplified.  The simplifications
            // will be valid for all STROKE and TRANSFORM types.
            sg2d.shapepipe = txPipe;
        } else {
            if (!validated) {
                super.validatePipe(sg2d);
            }
        }

        sg2d.textpipe = textpipe;

        // always override the image pipe with the specialized VK pipe
        sg2d.imagepipe = vkImagePipe;
    }

    // TODO this is only used for caps checks, refactor and remove this method
    public VKGraphicsConfig getGraphicsConfig() {
        return (VKGraphicsConfig) getDeviceConfiguration();
    }

    protected int revalidate(VKGraphicsConfig gc) {
        if (gc.getFormat() != format) return VolatileImage.IMAGE_INCOMPATIBLE;
        else if (this.gc == gc) return VolatileImage.IMAGE_OK;
        // TODO proxy cache needs to be cleared for this surface data?
        setBlitProxyCache(gc.getGPU().getSurfaceDataProxyCache());
        this.gc = gc;
        return VolatileImage.IMAGE_RESTORED;
    }

    protected void configure() {
        VKRenderQueue rq = VKRenderQueue.getInstance();
        rq.lock();
        try {
            RenderBuffer buf = rq.getBuffer();
            rq.ensureCapacityAndAlignment(24, 4);
            buf.putInt(CONFIGURE_SURFACE);
            buf.putLong(getNativeOps());
            buf.putLong(gc.getGPU().getNativeHandle());
            buf.putInt(width);
            buf.putInt(height);

            rq.flushNow();
        } finally {
            rq.unlock();
        }
    }

    @Override
    public BufferedContext getContext() {
        return VKContext.INSTANCE;
    }
}
