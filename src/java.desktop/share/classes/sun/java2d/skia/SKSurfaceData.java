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

import sun.awt.AWTAccessor;
import sun.awt.SunHints;
import sun.java2d.SunGraphics2D;
import sun.java2d.SurfaceData;
import sun.java2d.SurfaceDataProxy;
import sun.awt.image.PixelConverter;
import sun.java2d.loops.MaskFill;
import sun.java2d.loops.SurfaceType;
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
import java.awt.Window;
import java.awt.GraphicsEnvironment;
import java.awt.image.ColorModel;
import java.awt.image.Raster;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static sun.java2d.pipe.BufferedOpCodes.FLUSH_SURFACE;

/**
 * Destination surface of the Skia backend (JBR-10654).
 * <p>
 * Skia draws into a GPU texture through a Graphite context; where that
 * texture comes from and how a window surface is presented is the platform
 * subclass's business: on Windows the surface owns a texture and a swap
 * chain itself. Pipeline selection, LCD rules and the
 * flush/dispose protocol with the native side are shared here.
 */
abstract class SKSurfaceData extends SurfaceData implements AccelSurface {

    // Pixel formats of software sources/destinations handled natively
    // (same numbering as MTLSurfaceData for easy cross-checking).
    static final int PF_INT_ARGB     = 0;
    static final int PF_INT_ARGB_PRE = 1;
    static final int PF_INT_RGB      = 2;
    static final int PF_INT_RGBX     = 3;
    static final int PF_INT_BGR      = 4;
    static final int PF_INT_BGRX     = 5;

    private static final String DESC_SKIA_SURFACE     = "Skia Surface";
    private static final String DESC_SKIA_SURFACE_RTT = "Skia Surface (render-to-texture)";
    private static final String DESC_SKIA_TEXTURE     = "Skia Texture";

    static final SurfaceType SKSurface =
            SurfaceType.Any.deriveSubType(DESC_SKIA_SURFACE, PixelConverter.ArgbPre.instance);
    static final SurfaceType SKSurfaceRTT =
            SKSurface.deriveSubType(DESC_SKIA_SURFACE_RTT);
    static final SurfaceType SKTexture =
            SurfaceType.Any.deriveSubType(DESC_SKIA_TEXTURE);

    /*
     * How PixelToParallelogramConverter turns Java2D's pixel-based drawing calls into
     * parallelograms, the same numbers the other accelerated pipelines use (SurfaceData.makeConverter
     * for antialiased drawing, MTLSurfaceData/D3DSurfaceData/VKSurfaceData for non-antialiased):
     * a pen thinner than the minimum is widened to it (dropout control), and STROKE_NORMALIZE
     * snaps endpoints to the given sub-pixel position — for strokes only when antialiased
     * (adjustfill false), for fills too when not (adjustfill true).
     */
    /** Antialiased: a pen is at least an eighth of a pixel wide, so a hairline keeps some coverage. */
    static final double AA_MIN_PEN_SIZE = 1.0 / 8.0;
    /** Antialiased: endpoints snap just below the pixel center, so a line at exactly .5 stays on one row. */
    static final double AA_NORMALIZE_POSITION = 0.499;
    /** Non-antialiased: a pen is at least one pixel wide. */
    private static final double NON_AA_MIN_PEN_SIZE = 1.0;
    /** Non-antialiased: endpoints snap to the pixel quarter, as the software loops round. */
    private static final double NON_AA_NORMALIZE_POSITION = 0.25;

    private static SKRenderPipe skRenderPipe;
    private static PixelToParallelogramConverter skTransformedRenderPipe;
    private static ParallelogramPipe skAAParallelogramPipe;
    private static SKTextPipe skTextPipe;
    private static SKAnalyticShapes skAnalyticShapes;
    private static SKDrawImage skImagePipe;
    /**
     * The antialiased converters by the pipe they hand the other shapes to (one of
     * SurfaceData's few static AA pipes, as validated for the state): built once each
     * instead of at every validation; immutable, shared as SurfaceData shares its own.
     */
    private static final Map<ShapeDrawPipe, PixelToParallelogramConverter> aaConverters = new ConcurrentHashMap<>();

    private static PixelToParallelogramConverter aaConverter(ShapeDrawPipe marlinPipe) {
        return aaConverters.computeIfAbsent(marlinPipe, pipe -> new PixelToParallelogramConverter(
                SKEnvironment.rasterizesShapes() ? new SKAnalyticShapes.HybridShapePipe(skAnalyticShapes, pipe) : pipe,
                skAAParallelogramPipe, AA_MIN_PEN_SIZE, AA_NORMALIZE_POSITION, /* adjustfill */ false));
    }

    static {
        if (!GraphicsEnvironment.isHeadless()) {
            SKRenderQueue renderQueue = SKRenderQueue.getInstance();
            skImagePipe = new SKDrawImage();
            skTextPipe = new SKTextPipe(renderQueue);
            skAnalyticShapes = new SKAnalyticShapes(renderQueue);
            skRenderPipe = new SKRenderPipe(renderQueue);
            skAAParallelogramPipe = skRenderPipe.getAAParallelogramPipe();
            skTransformedRenderPipe = new PixelToParallelogramConverter(skRenderPipe, skRenderPipe,
                    NON_AA_MIN_PEN_SIZE, NON_AA_NORMALIZE_POSITION, /* adjustfill */ true);

            SKBlitLoops.register();
            SKMaskFill.register();
            SKMaskBlit.register();
        }
    }

    private final SKGraphicsConfig graphicsConfig;
    private final SKContext context;
    private final int type;

    private native void initOps(long platformOps, int type, boolean isOpaque);

    private static SurfaceType getCustomSurfaceType(int type) {
        return switch (type) {
            case TEXTURE -> SKTexture;
            case RT_TEXTURE -> SKSurfaceRTT;
            default -> SKSurface;
        };
    }

    /**
     * @param platformOps native ops of the platform surface that owns the
     *                    texture ({@code SKPlatformSurface} in {@code SKPlatform.h})
     */
    protected SKSurfaceData(SKGraphicsConfig graphicsConfig, ColorModel colorModel, int type, long platformOps) {
        super(getCustomSurfaceType(type), colorModel);
        this.graphicsConfig = graphicsConfig;
        this.context = SKContext.forConfig(graphicsConfig);
        this.type = type;
        // one proxy cache per config, shared with the platform surfaces of that config
        setBlitProxyCache(graphicsConfig.getSurfaceDataProxyCache());
        initOps(platformOps, type, colorModel.getTransparency() == OPAQUE);
    }

    /** Whether this is a window surface the platform presents. */
    abstract boolean isOnScreen();

    // ---- awt.window.counters ----------------------------------------------

    private native void setCountFrames(boolean countFrames);

    /**
     * Reports this window surface's presents to JBR's {@code awt.window.counters}
     * ({@code java2d.native.frames}, {@code java2d.native.framesDropped}), as
     * {@code MTLLayer} and the Wayland surfaces do, when they are on for its window.
     */
    protected final void enableFrameCounters() {
        if (getDestination() instanceof Window window && AWTAccessor.getWindowAccessor().countersEnabled(window)) {
            setCountFrames(true);
        }
    }

    /** Called from the native code after this window surface was presented. */
    @SuppressWarnings("unused")
    private void countNewFrame() {
        countFrame("java2d.native.frames");
    }

    /**
     * Called from the native code when this window surface had new content but could
     * not be presented (no swap chain image, a lost device).
     */
    @SuppressWarnings("unused")
    private void countDroppedFrame() {
        countFrame("java2d.native.framesDropped");
    }

    private void countFrame(String counterName) {
        if (getDestination() instanceof Window window) {
            AWTAccessor.getWindowAccessor().incrementCounter(window, counterName);
        }
    }

    // ---- SurfaceData -------------------------------------------------------

    @Override
    public Raster getRaster(int x, int y, int width, int height) {
        throw new InternalError("not implemented yet");
    }

    @Override
    public SurfaceDataProxy makeProxyFor(SurfaceData sourceData) {
        return SKSurfaceDataProxy.createProxy(sourceData, graphicsConfig);
    }

    @Override
    public boolean copyArea(SunGraphics2D sunGraphics2D, int x, int y, int width, int height, int dx, int dy) {
        if (sunGraphics2D.compositeState >= SunGraphics2D.COMP_XOR) {
            return false;
        }
        skRenderPipe.copyArea(sunGraphics2D, x, y, width, height, dx, dy);
        return true;
    }

    /** A flushed surface is invalid, see {@link #invalidate}. */
    @Override
    public void flush() {
        invalidate();
    }

    /**
     * An invalid surface is not drawn to or from anymore, so its GPU side goes
     * with the validity: the native side forgets the Skia wrapper and releases
     * the platform surface (texture, swap chain) synchronously under the queue
     * lock ({@code FLUSH_SURFACE}), as Metal releases a flushed texture, rather
     * than when the Disposer collects this object. The surface counts as lost
     * from then on, so that a holder of it (a stale source) fails validation.
     */
    @Override
    public void invalidate() {
        super.invalidate();
        releaseSkiaSurface();
    }

    private boolean skiaSurfaceReleased;

    private void releaseSkiaSurface() {
        SKRenderQueue renderQueue = SKRenderQueue.getInstance();
        renderQueue.lock();
        try {
            if (skiaSurfaceReleased) {
                return;
            }
            skiaSurfaceReleased = true;
            setSurfaceLost(true);
            SKContext.setScratchSurface(graphicsConfig);
            RenderBuffer buffer = renderQueue.getBuffer();
            // the opcode, then this surface's native ops
            renderQueue.ensureCapacityAndAlignment(Integer.BYTES + Long.BYTES, Integer.BYTES);
            buffer.putInt(FLUSH_SURFACE);
            buffer.putLong(getNativeOps());
            renderQueue.flushNow();
        } finally {
            renderQueue.unlock();
        }
    }

    // ---- AccelSurface ------------------------------------------------------

    @Override
    public int getType() {
        return type;
    }

    @Override
    public BufferedContext getContext() {
        return context;
    }

    /**
     * Same rule as {@code MTLSurfaceData}: LCD text only on opaque surfaces
     * with an opaque color and a composite that copies or SrcOvers it.
     * Without this override the generic lookup finds no LCD loop for a Skia
     * surface and the hint is downgraded to grayscale.
     */
    @Override
    public boolean canRenderLCDText(SunGraphics2D sunGraphics2D) {
        return sunGraphics2D.surfaceData.getTransparency() == Transparency.OPAQUE
                && sunGraphics2D.paintState <= SunGraphics2D.PAINT_OPAQUECOLOR
                && (sunGraphics2D.compositeState <= SunGraphics2D.COMP_ISCOPY
                    || (sunGraphics2D.compositeState == SunGraphics2D.COMP_ALPHA
                        && isOpaqueSrcOver(sunGraphics2D.composite)));
    }

    private static boolean isOpaqueSrcOver(Composite composite) {
        return composite instanceof AlphaComposite alphaComposite
                && alphaComposite.getRule() == AlphaComposite.SRC_OVER && alphaComposite.getAlpha() >= 1f;
    }

    // ---- pipeline selection ------------------------------------------------

    @Override
    @SuppressWarnings("DuplicatedCode")   // the pipe assignment every accelerated pipeline spells out (MTL, D3D, OGL, VK)
    public void validatePipe(SunGraphics2D sunGraphics2D) {
        if (SKCompositePipe.handles(sunGraphics2D)) {
            // a custom Composite, XOR with a paint: no Skia blender; the software
            // pipes' composition on the destination read back (images: SKAnyCompositeBlit)
            SKCompositePipe.validatePipe(sunGraphics2D);
            sunGraphics2D.imagepipe = skImagePipe;
            return;
        }
        TextPipe textPipe;
        boolean validated = false;

        // Skia composites Java's glyph images; the text pipe handles the same
        // composite modes as the Metal one.
        if ((sunGraphics2D.compositeState <= SunGraphics2D.COMP_ISCOPY &&
                sunGraphics2D.paintState <= SunGraphics2D.PAINT_ALPHACOLOR) ||
            (sunGraphics2D.compositeState == SunGraphics2D.COMP_ALPHA &&
                sunGraphics2D.paintState <= SunGraphics2D.PAINT_ALPHACOLOR &&
                ((AlphaComposite) sunGraphics2D.composite).getRule() == AlphaComposite.SRC_OVER) ||
            // XOR text: the software glyph loops cannot lock a Skia surface; draw it
            // through the Skia text pipe (XOR blender with the glyph coverage,
            // SKPaints.cpp).
            (sunGraphics2D.compositeState == SunGraphics2D.COMP_XOR &&
                sunGraphics2D.paintState <= SunGraphics2D.PAINT_ALPHACOLOR)) {
            textPipe = skTextPipe;
        } else {
            super.validatePipe(sunGraphics2D);
            textPipe = sunGraphics2D.textpipe;
            validated = true;
        }

        PixelToParallelogramConverter transformedPipe = null;
        SKRenderPipe untransformedPipe = null;

        if (sunGraphics2D.antialiasHint != SunHints.INTVAL_ANTIALIAS_ON) {
            // Same conditions as MTLSurfaceData (XOR is a blender on the native side)
            if (sunGraphics2D.paintState <= SunGraphics2D.PAINT_ALPHACOLOR &&
                    sunGraphics2D.compositeState <= SunGraphics2D.COMP_XOR) {
                transformedPipe = skTransformedRenderPipe;
                untransformedPipe = skRenderPipe;
            } else if (sunGraphics2D.compositeState <= SunGraphics2D.COMP_ALPHA && SKPaints.isValid(sunGraphics2D)) {
                transformedPipe = skTransformedRenderPipe;
                untransformedPipe = skRenderPipe;
            }
        } else {
            boolean skiaPaint = sunGraphics2D.compositeState <= SunGraphics2D.COMP_ALPHA
                    && (sunGraphics2D.paintState <= SunGraphics2D.PAINT_ALPHACOLOR || SKPaints.isValid(sunGraphics2D));
            if (skiaPaint) {
                // Marlin's masks (the pipe of this state) for the shapes Skia does not draw
                // analytically, all of them with shapes=marlin; rectangles and lines become
                // antialiased parallelograms either way, under every AlphaComposite rule and
                // paint Skia draws (MTLSurfaceData takes SrcOver colors only)
                if (!validated) {
                    super.validatePipe(sunGraphics2D);
                }
                PixelToParallelogramConverter aaConverter = aaConverter(sunGraphics2D.shapepipe);
                sunGraphics2D.drawpipe = aaConverter;
                sunGraphics2D.fillpipe = aaConverter;
                sunGraphics2D.shapepipe = aaConverter;
                validated = true;
            } else if (sunGraphics2D.compositeState == SunGraphics2D.COMP_XOR) {
                // AA and XOR with a color: the solid pipes, as in MTLSurfaceData
                transformedPipe = skTransformedRenderPipe;
                untransformedPipe = skRenderPipe;
            }
        }

        if (transformedPipe != null) {
            if (sunGraphics2D.transformState >= SunGraphics2D.TRANSFORM_TRANSLATESCALE) {
                sunGraphics2D.drawpipe = transformedPipe;
                sunGraphics2D.fillpipe = transformedPipe;
            } else if (sunGraphics2D.strokeState != SunGraphics2D.STROKE_THIN) {
                sunGraphics2D.drawpipe = transformedPipe;
                sunGraphics2D.fillpipe = untransformedPipe;
            } else {
                sunGraphics2D.drawpipe = untransformedPipe;
                sunGraphics2D.fillpipe = untransformedPipe;
            }
            sunGraphics2D.shapepipe = transformedPipe;
        } else if (!validated) {
            super.validatePipe(sunGraphics2D);
        }

        sunGraphics2D.textpipe = textPipe;
        sunGraphics2D.imagepipe = skImagePipe;
    }

    @Override
    protected MaskFill getMaskFill(SunGraphics2D sunGraphics2D) {
        if (sunGraphics2D.paintState > SunGraphics2D.PAINT_ALPHACOLOR && !SKPaints.isValid(sunGraphics2D)) {
            return null;
        }
        return super.getMaskFill(sunGraphics2D);
    }

    /** Called from the native Dispose() on the Disposer thread. */
    @SuppressWarnings("unused")
    static void dispose(long surfaceOps) {
        SKRenderQueue renderQueue = SKRenderQueue.getInstance();
        renderQueue.lock();
        try {
            SKContext.disposeSurface(surfaceOps);
            renderQueue.flushNow();
        } finally {
            renderQueue.unlock();
        }
    }
}
