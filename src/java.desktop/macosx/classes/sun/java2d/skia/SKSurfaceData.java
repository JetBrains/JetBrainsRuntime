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

import sun.awt.SunHints;
import sun.java2d.SunGraphics2D;
import sun.java2d.SurfaceData;
import sun.java2d.SurfaceDataProxy;
import sun.awt.image.PixelConverter;
import sun.java2d.loops.GraphicsPrimitive;
import sun.java2d.loops.MaskFill;
import sun.java2d.loops.SurfaceType;
import sun.java2d.loops.CompositeType;
import sun.java2d.metal.MTLGraphicsConfig;
import sun.java2d.metal.MTLLayer;
import sun.java2d.metal.MTLSurfaceData;
import sun.java2d.pipe.BufferedContext;
import sun.java2d.pipe.ParallelogramPipe;
import sun.java2d.pipe.PixelToParallelogramConverter;
import sun.java2d.pipe.RenderBuffer;
import sun.java2d.pipe.ShapeDrawPipe;
import sun.java2d.pipe.TextPipe;
import sun.java2d.pipe.hw.AccelSurface;

import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Transparency;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.image.ColorModel;
import java.awt.image.Raster;
import java.util.concurrent.ConcurrentHashMap;

import static sun.java2d.pipe.BufferedOpCodes.FLUSH_SURFACE;

/**
 * Destination surface of the experimental Skia backend (JBR-10654).
 * <p>
 * The surface <em>wraps</em> a Metal surface that owns the GPU texture and,
 * for windows, the presentation layer: Skia draws into that texture through
 * a Graphite context on the same command queue, and the Metal layer presents
 * it exactly as it presents Metal-rendered content. This keeps all window,
 * HiDPI and display-sync handling in the existing code while the rendering
 * itself goes through Skia.
 */
public final class SKSurfaceData extends SurfaceData implements AccelSurface {

    // Pixel formats of software sources/destinations handled natively
    // (same numbering as MTLSurfaceData for easy cross-checking).
    public static final int PF_INT_ARGB     = 0;
    public static final int PF_INT_ARGB_PRE = 1;
    public static final int PF_INT_RGB      = 2;
    public static final int PF_INT_RGBX     = 3;
    public static final int PF_INT_BGR      = 4;
    public static final int PF_INT_BGRX     = 5;

    private static final String DESC_SKIA_SURFACE     = "Skia Surface";
    private static final String DESC_SKIA_SURFACE_RTT = "Skia Surface (render-to-texture)";
    private static final String DESC_SKIA_TEXTURE     = "Skia Texture";

    static final SurfaceType SKSurface =
            SurfaceType.Any.deriveSubType(DESC_SKIA_SURFACE, PixelConverter.ArgbPre.instance);
    static final SurfaceType SKSurfaceRTT =
            SKSurface.deriveSubType(DESC_SKIA_SURFACE_RTT);
    static final SurfaceType SKTexture =
            SurfaceType.Any.deriveSubType(DESC_SKIA_TEXTURE);

    private static SKRenderer skRenderPipe;
    private static PixelToParallelogramConverter skTxRenderPipe;
    private static ParallelogramPipe skAAPgramPipe;
    private static SKTextRenderer skTextPipe;
    private static SKShapePipe skShapePipe;
    private static SKDrawImage skImagePipe;

    static {
        if (!GraphicsEnvironment.isHeadless()) {
            SKRenderQueue rq = SKRenderQueue.getInstance();
            skImagePipe = new SKDrawImage();
            skTextPipe = new SKTextRenderer(rq);
            skShapePipe = new SKShapePipe(rq);
            skRenderPipe = new SKRenderer(rq);
            skAAPgramPipe = skRenderPipe.getAAParallelogramPipe();
            skTxRenderPipe = new PixelToParallelogramConverter(skRenderPipe, skRenderPipe, 1.0, 0.25, true);

            SKBlitLoops.register();
            SKMaskFill.register();
            SKMaskBlit.register();
        }
    }

    private final MTLSurfaceData delegate;
    private final MTLGraphicsConfig graphicsConfig;
    private final MTLLayer layer;          // non-null for window surfaces
    private final Image offscreenImage;    // non-null for VolatileImage surfaces
    private final int type;

    /**
     * Keeps each wrapped Metal surface reachable until the Skia side has
     * released its wrapper (see {@link #dispose(long)}). The two surfaces have
     * separate Disposer records; without this the Metal one could free the
     * native ops while Skia ops referencing them are still queued.
     */
    private static final ConcurrentHashMap<Long, MTLSurfaceData> delegatesByOps = new ConcurrentHashMap<>();

    private native void initOps(long mtlOps, int type, boolean isOpaque);

    private static SurfaceType getCustomSurfaceType(int type) {
        switch (type) {
            case TEXTURE:    return SKTexture;
            case RT_TEXTURE: return SKSurfaceRTT;
            default:         return SKSurface;
        }
    }

    private SKSurfaceData(MTLSurfaceData delegate, MTLGraphicsConfig gc, MTLLayer layer,
                            Image offscreenImage, ColorModel cm, int type) {
        super(getCustomSurfaceType(type), cm);
        this.delegate = delegate;
        this.graphicsConfig = gc;
        this.layer = layer;
        this.offscreenImage = offscreenImage;
        this.type = type;
        // one proxy cache per config, shared with the Metal surfaces of that config
        setBlitProxyCache(gc.getSurfaceDataProxyCache());
        initOps(delegate.getNativeOps(), type, cm.getTransparency() == OPAQUE);
        delegatesByOps.put(getNativeOps(), delegate);
    }

    /** Window surface: wraps the Metal layer surface created for {@code layer}. */
    public static SKSurfaceData createData(MTLLayer layer) {
        MTLGraphicsConfig gc = (MTLGraphicsConfig) layer.getGraphicsConfiguration();
        MTLSurfaceData mtl = MTLSurfaceData.createData(layer);
        return new SKSurfaceData(mtl, gc, layer, null, mtl.getColorModel(), WINDOW);
    }

    /** Off-screen surface (VolatileImage back buffers, textures). */
    public static SKSurfaceData createData(MTLGraphicsConfig gc, int width, int height,
                                             ColorModel cm, Image image, int type) {
        MTLSurfaceData mtl = MTLSurfaceData.createData(gc, width, height, cm, image, type);
        return new SKSurfaceData(mtl, gc, null, image, cm, type);
    }

    /** The Metal surface that owns the texture (and layer) this surface renders into. */
    public MTLSurfaceData getDelegate() {
        return delegate;
    }

    // ---- SurfaceData -------------------------------------------------------

    @Override
    public SurfaceData getReplacement() {
        if (layer != null) {
            return layer.getSurfaceData();
        }
        return restoreContents(offscreenImage);
    }

    @Override
    public GraphicsConfiguration getDeviceConfiguration() {
        return graphicsConfig;
    }

    @Override
    public Raster getRaster(int x, int y, int w, int h) {
        throw new InternalError("not implemented yet");
    }

    @Override
    public Rectangle getBounds() {
        return delegate.getBounds();
    }

    @Override
    public Object getDestination() {
        return delegate.getDestination();
    }

    @Override
    public int getTransparency() {
        return delegate.getTransparency();
    }

    @Override
    public double getDefaultScaleX() {
        return delegate.getDefaultScaleX();
    }

    @Override
    public double getDefaultScaleY() {
        return delegate.getDefaultScaleY();
    }

    public boolean isOnScreen() {
        return layer != null;
    }

    @Override
    public SurfaceDataProxy makeProxyFor(SurfaceData srcData) {
        return SKSurfaceDataProxy.createProxy(srcData, graphicsConfig);
    }

    @Override
    public boolean copyArea(SunGraphics2D sg2d, int x, int y, int w, int h, int dx, int dy) {
        if (sg2d.compositeState >= SunGraphics2D.COMP_XOR) {
            return false;
        }
        skRenderPipe.copyArea(sg2d, x, y, w, h, dx, dy);
        return true;
    }

    @Override
    public void flush() {
        invalidate();
        SKRenderQueue rq = SKRenderQueue.getInstance();
        rq.lock();
        try {
            SKContext.setScratchSurface(graphicsConfig);
            RenderBuffer buf = rq.getBuffer();
            rq.ensureCapacityAndAlignment(12, 4);
            buf.putInt(FLUSH_SURFACE);
            buf.putLong(getNativeOps());
            rq.flushNow();
        } finally {
            rq.unlock();
        }
        // the Skia wrapper is gone; now the Metal side may release the texture
        delegate.flush();
    }

    @Override
    public void invalidate() {
        super.invalidate();
        delegate.invalidate();
    }

    // ---- AccelSurface ------------------------------------------------------

    @Override
    public int getType() {
        return type;
    }

    @Override
    public long getNativeResource(int resType) {
        return delegate.getNativeResource(resType);
    }

    @Override
    public Rectangle getNativeBounds() {
        return delegate.getNativeBounds();
    }

    @Override
    public BufferedContext getContext() {
        return SKContext.forConfig(graphicsConfig);
    }

    /**
     * Same rule as {@code MTLSurfaceData}: LCD text only on opaque surfaces
     * with an opaque colour and a composite that copies or SrcOvers it.
     * Without this override the generic lookup finds no LCD loop for a Skia
     * surface and the hint is downgraded to grayscale.
     */
    @Override
    public boolean canRenderLCDText(SunGraphics2D sg2d) {
        return sg2d.surfaceData.getTransparency() == Transparency.OPAQUE
                && sg2d.paintState <= SunGraphics2D.PAINT_OPAQUECOLOR
                && (sg2d.compositeState <= SunGraphics2D.COMP_ISCOPY
                    || (sg2d.compositeState <= SunGraphics2D.COMP_ALPHA && canHandleComposite(sg2d.composite)));
    }

    private static boolean canHandleComposite(Composite c) {
        return c instanceof AlphaComposite ac && ac.getRule() == AlphaComposite.SRC_OVER && ac.getAlpha() >= 1f;
    }

    // ---- pipeline selection ------------------------------------------------

    @Override
    public void validatePipe(SunGraphics2D sg2d) {
        TextPipe textpipe;
        boolean validated = false;

        // Skia composites Java's glyph images; the text pipe handles the same
        // composite modes as the Metal one.
        if ((sg2d.compositeState <= SunGraphics2D.COMP_ISCOPY &&
                sg2d.paintState <= SunGraphics2D.PAINT_ALPHACOLOR) ||
            (sg2d.compositeState == SunGraphics2D.COMP_ALPHA &&
                sg2d.paintState <= SunGraphics2D.PAINT_ALPHACOLOR &&
                ((AlphaComposite) sg2d.composite).getRule() == AlphaComposite.SRC_OVER) ||
            // XOR text: the software glyph loops cannot lock a Skia surface; draw it
            // through the Skia text pipe (as Src, see SKBackend_SetXorComposite).
            (sg2d.compositeState == SunGraphics2D.COMP_XOR &&
                sg2d.paintState <= SunGraphics2D.PAINT_ALPHACOLOR)) {
            textpipe = skTextPipe;
        } else {
            super.validatePipe(sg2d);
            textpipe = sg2d.textpipe;
            validated = true;
        }

        PixelToParallelogramConverter txPipe = null;
        SKRenderer nonTxPipe = null;

        if (sg2d.antialiasHint != SunHints.INTVAL_ANTIALIAS_ON) {
            // Same conditions as MTLSurfaceData (XOR is a blender on the native
            // side). A custom Composite falls through to the software loops,
            // which cannot touch a Skia surface, and fails like it does on Metal.
            if (sg2d.paintState <= SunGraphics2D.PAINT_ALPHACOLOR &&
                    sg2d.compositeState <= SunGraphics2D.COMP_XOR) {
                txPipe = skTxRenderPipe;
                nonTxPipe = skRenderPipe;
            } else if (sg2d.compositeState <= SunGraphics2D.COMP_ALPHA && SKPaints.isValid(sg2d)) {
                txPipe = skTxRenderPipe;
                nonTxPipe = skRenderPipe;
            }
        } else {
            boolean skiaPaint = sg2d.compositeState <= SunGraphics2D.COMP_ALPHA
                    && (sg2d.paintState <= SunGraphics2D.PAINT_ALPHACOLOR || SKPaints.isValid(sg2d));
            if (skiaPaint && (SKEnvironment.rasterizesShapes() || SKEnvironment.rasterizesAnalyticShapes())) {
                // shapes on Skia, all of them or the analytic ones with Marlin
                // for the rest; rectangles and lines still become antialiased
                // parallelograms via the converter
                ShapeDrawPipe shapes = skShapePipe;
                if (SKEnvironment.rasterizesAnalyticShapes()) {
                    super.validatePipe(sg2d);   // the Marlin pipe for this state
                    shapes = new SKShapePipe.Hybrid(skShapePipe, sg2d.shapepipe);
                }
                PixelToParallelogramConverter aaConverter =
                        new PixelToParallelogramConverter(shapes, skAAPgramPipe, 1.0 / 8.0, 0.499, false);
                sg2d.drawpipe = aaConverter;
                sg2d.fillpipe = aaConverter;
                sg2d.shapepipe = aaConverter;
                validated = true;
            } else if (sg2d.paintState <= SunGraphics2D.PAINT_ALPHACOLOR &&
                    (sg2d.imageComp == CompositeType.SrcOverNoEa || sg2d.imageComp == CompositeType.SrcOver)) {
                if (!validated) {
                    super.validatePipe(sg2d);
                    validated = true;
                }
                PixelToParallelogramConverter aaConverter =
                        new PixelToParallelogramConverter(sg2d.shapepipe, skAAPgramPipe, 1.0 / 8.0, 0.499, false);
                sg2d.drawpipe = aaConverter;
                sg2d.fillpipe = aaConverter;
                sg2d.shapepipe = aaConverter;
            } else if (sg2d.compositeState == SunGraphics2D.COMP_XOR) {
                // AA and XOR together: the solid pipes, as in MTLSurfaceData
                txPipe = skTxRenderPipe;
                nonTxPipe = skRenderPipe;
            }
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
            sg2d.shapepipe = txPipe;
        } else if (!validated) {
            super.validatePipe(sg2d);
        }

        sg2d.textpipe = textpipe;
        sg2d.imagepipe = skImagePipe;
    }

    @Override
    protected MaskFill getMaskFill(SunGraphics2D sg2d) {
        if (sg2d.paintState > SunGraphics2D.PAINT_ALPHACOLOR && !SKPaints.isValid(sg2d)) {
            return null;
        }
        return super.getMaskFill(sg2d);
    }

    /** Called from the native Dispose() on the Disposer thread. */
    static void dispose(long pData) {
        SKRenderQueue rq = SKRenderQueue.getInstance();
        rq.lock();
        try {
            SKContext.disposeSurface(pData);
            rq.flushNow();
        } finally {
            rq.unlock();
        }
        // queued Skia ops have run; the Metal surface may now be disposed
        delegatesByOps.remove(pData);
    }
}
