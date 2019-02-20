/*
 * Copyright (c) 2019, 2019, Oracle and/or its affiliates. All rights reserved.
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

package sun.java2d.metal;

import java.awt.*;

import sun.java2d.SurfaceData;

import java.awt.Composite;
import sun.java2d.loops.CompositeType;
import sun.java2d.loops.GraphicsPrimitive;
import java.awt.image.ColorModel;
import java.awt.image.Raster;
import sun.java2d.loops.SurfaceType;
import sun.java2d.metal.MetalLayer;
import sun.awt.SunHints;
import sun.java2d.metal.MetalGraphicsConfig;
import sun.java2d.opengl.CGLGraphicsConfig;
import sun.java2d.opengl.CGLLayer;
import sun.java2d.opengl.CGLSurfaceData;
import sun.java2d.opengl.OGLRenderQueue;
import sun.lwawt.macosx.CPlatformView;
import sun.java2d.pipe.hw.AccelSurface;
import sun.java2d.metal.MetalRenderer;
import sun.java2d.metal.MetalRenderQueue;
import sun.java2d.pipe.PixelToParallelogramConverter;
import sun.java2d.pipe.ParallelogramPipe;
import sun.java2d.SunGraphics2D;

public class MetalSurfaceData extends SurfaceData
    implements AccelSurface {
    //protected final int scale;
    protected final int width;
    protected final int height;
    protected CPlatformView pView;
    private MetalGraphicsConfig graphicsConfig;

    //private MetalGraphicsConfig graphicsConfig;
    private int nativeWidth, nativeHeight;
    protected int type;
    protected static ParallelogramPipe mtlAAPgramPipe;
    protected static MetalRenderer mtlRenderPipe;
    protected static PixelToParallelogramConverter mtlTxRenderPipe;

    private native int getTextureTarget(long pData);
    private native int getTextureID(long pData);
    protected native boolean initTexture(long pData,
                                         boolean isOpaque,
                                         int width, int height);
    protected native void clearWindow();

 static {
        if (!GraphicsEnvironment.isHeadless()) {
            MetalRenderQueue rq = MetalRenderQueue.getInstance();
            mtlRenderPipe = new MetalRenderer(rq);  

            mtlAAPgramPipe = mtlRenderPipe.getAAParallelogramPipe();

            mtlTxRenderPipe =
                new PixelToParallelogramConverter(mtlRenderPipe,
                                                  mtlRenderPipe,
                                                  1.0, 0.25, true);
        }
    }

    native void validate(int xoff, int yoff, int width, int height, boolean isOpaque);

    private native void initOps(long pConfigInfo, long pPeerData, long layerPtr,
                                int xoff, int yoff, boolean isOpaque);

    MetalSurfaceData(MetalGraphicsConfig gc, ColorModel cm, int type,
                     int width, int height) {
        // TODO : Map the coming type to proper custom type and call super()
        //super(gc, cm, type);
        super(SurfaceType.Any3Byte, cm );
        // TEXTURE shouldn't be scaled, it is used for managed BufferedImages.
        // TODO : We need to set scale factor
        //scale = type == TEXTURE ? 1 : gc.getDevice().getScaleFactor();
        this.width = width ;// * scale;
        this.height = height;// * scale;

        graphicsConfig = gc;
    }

    protected MetalSurfaceData(CPlatformView pView, MetalGraphicsConfig gc,
                              ColorModel cm, int type, int width, int height)
    {
        this(gc, cm, type, width, height);
        this.pView = pView;
        this.graphicsConfig = gc;

        // TODO : Check whether we need native config info here
        long pConfigInfo = gc.getNativeConfigInfo();
        long pPeerData = 0L;
        boolean isOpaque = true;
        if (pView != null) {
            pPeerData = pView.getAWTView();
            isOpaque = pView.isOpaque();
        }
        // TODO : check initOps logic it is native is OGL
        initOps(pConfigInfo, pPeerData, 0, 0, 0, isOpaque);
    }

    protected MetalSurfaceData(MetalLayer layer, MetalGraphicsConfig gc,
                             ColorModel cm, int type, int width, int height)
    {
        this(gc, cm, type, width, height);
        this.graphicsConfig = gc;

        long pConfigInfo = gc.getNativeConfigInfo();
        long layerPtr = 0L;
        boolean isOpaque = true;
        if (layer != null) {
            layerPtr = layer.getPointer();
            isOpaque = layer.isOpaque();
        }
        initOps(pConfigInfo, 0, layerPtr, 0, 0, isOpaque);
    }

    public  GraphicsConfiguration getDeviceConfiguration() {
        return graphicsConfig; //dummy
    }

    public static MetalWindowSurfaceData createData(CPlatformView pView) {
        MetalGraphicsConfig gc = getGC(pView);
        return new MetalWindowSurfaceData(pView, gc);
    }

    public static MetalSurfaceData createData(MetalLayer layer) {
        MetalGraphicsConfig gc = getGC(layer);
        Rectangle r = layer.getBounds();
        //return new MetalSurfaceData( gc, gc.getColorModel(), 1, r.width, r.height);
        return new MetalLayerSurfaceData(layer, gc, r.width, r.height);
    }

    public static MetalGraphicsConfig getGC(CPlatformView pView) {
        if (pView != null) {
            return (MetalGraphicsConfig)pView.getGraphicsConfiguration();
        } else {
            // REMIND: this should rarely (never?) happen, but what if
            // default config is not CGL?
            GraphicsEnvironment env = GraphicsEnvironment
                    .getLocalGraphicsEnvironment();
            GraphicsDevice gd = env.getDefaultScreenDevice();
            return (MetalGraphicsConfig) gd.getDefaultConfiguration();
        }
    }

    public static MetalGraphicsConfig getGC(MetalLayer layer) {
        return (MetalGraphicsConfig)layer.getGraphicsConfiguration();
    }

    public void validate() {
        // Overridden in MetalWindowSurfaceData below
    }

    public Rectangle getNativeBounds() {
        MetalRenderQueue rq = MetalRenderQueue.getInstance();
        rq.lock();
        try {
            return new Rectangle(nativeWidth, nativeHeight);
        } finally {
            rq.unlock();
        }
    }

    public long getNativeResource(int resType) {
        if (resType == TEXTURE) {
            return getTextureID();
        }
        return 0L;
    }

    public  Object getDestination() {
          return this; //dummy
    }


     //Returns one of the surface type constants defined above.
     
    public final int getType() {
        return type;
    }

     //
     // If this surface is backed by a texture object, returns the target
     // for that texture (either GL_TEXTURE_2D or GL_TEXTURE_RECTANGLE_ARB).
     /// Otherwise, this method will return zero.
    public final int getTextureTarget() {
        return getTextureTarget(getNativeOps());
    }

    //
    // If this surface is backed by a texture object, returns the texture ID
    // for that texture.
    //Otherwise, this method will return zero.
    //
    public final int getTextureID() {
        return getTextureID(getNativeOps());
    }

    // Returns the MetalContext for the GraphicsConfig associated with this
    //surface.   
    public final MetalContext getContext() {
        return graphicsConfig.getContext();
    }




public void validatePipe(SunGraphics2D sg2d) {
        //TextPipe textpipe;
        //boolean validated = false;

        // OGLTextRenderer handles both AA and non-AA text, but
        // only works with the following modes:
        // (Note: For LCD text we only enter this code path if
        // canRenderLCDText() has already validated that the mode is
        // CompositeType.SrcNoEa (opaque color), which will be subsumed
        // by the CompositeType.SrcNoEa (any color) test below.)

        // Copy block from OGLSurfaceData
        //textpipe = sg2d.textpipe; //tmp

        PixelToParallelogramConverter txPipe = null;
        MetalRenderer nonTxPipe = null;

        if (sg2d.antialiasHint != SunHints.INTVAL_ANTIALIAS_ON) {
            if (sg2d.paintState <= SunGraphics2D.PAINT_ALPHACOLOR) {
                if (sg2d.compositeState <= SunGraphics2D.COMP_XOR) {
                    txPipe = mtlTxRenderPipe;
                    nonTxPipe = mtlRenderPipe;
                }
            } else if (sg2d.compositeState <= SunGraphics2D.COMP_ALPHA) {
                //if (OGLPaints.isValid(sg2d)) {
                    txPipe = mtlTxRenderPipe;
                    nonTxPipe = mtlRenderPipe;
                //}
                // custom paints handled by super.validatePipe() below
            }
        } else {
            if (sg2d.paintState <= SunGraphics2D.PAINT_ALPHACOLOR) {
                if (//graphicsConfig.isCapPresent(CAPS_PS30) &&
                    (sg2d.imageComp == CompositeType.SrcOverNoEa ||
                     sg2d.imageComp == CompositeType.SrcOver))
                {
                    //if (!validated) {
                        super.validatePipe(sg2d);
                        //validated = true;
                    //}
                    PixelToParallelogramConverter aaConverter =
                        new PixelToParallelogramConverter(sg2d.shapepipe,
                                                          mtlAAPgramPipe,
                                                          1.0/8.0, 0.499,
                                                          false);
                    sg2d.drawpipe = aaConverter;
                    sg2d.fillpipe = aaConverter;
                    sg2d.shapepipe = aaConverter;
                } else if (sg2d.compositeState == SunGraphics2D.COMP_XOR) {
                    // install the solid pipes when AA and XOR are both enabled
                    txPipe = mtlTxRenderPipe;
                    nonTxPipe = mtlRenderPipe;
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
            
                super.validatePipe(sg2d);
            
        }

        // install the text pipe based on our earlier decision
        //sg2d.textpipe = textpipe;

        // always override the image pipe with the specialized OGL pipe
        // TODO : We dont override image pipe with MetalImagePipe.
        // this needs to be implemented.
        sg2d.imagepipe = imagepipe;
    }

    public SurfaceData getReplacement() {
        return this; //dummy
    }

    /*
     * TODO : In case of OpenGL their is no getRaster()
     * implementation in CGLSurfaceData or OGLSurfaceData.
     * Needs more verification.
     */
    public Raster getRaster(int x, int y, int w, int h) {
        throw new InternalError("not implemented yet");
        //System.out.println("MetalSurfaceData -- getRaster() not implemented yet");
    }

    public Rectangle getBounds() {
        //Rectangle r = pView.getBounds();
        return new Rectangle(0, 0, width, height);
    }

    protected void initSurface(final int width, final int height) {
        MetalRenderQueue rq = MetalRenderQueue.getInstance();
        rq.lock();
        try {
            rq.flushAndInvokeNow(new Runnable() {
                public void run() {
                    initSurfaceNow(width, height);
                }
            });
        } finally {
            rq.unlock();
        }
    }

    private void initSurfaceNow(int width, int height) {
        boolean isOpaque = (getTransparency() == Transparency.OPAQUE);
        boolean success = false;

        /*switch (type) {
            case TEXTURE:
                success = initTexture(getNativeOps(),
                        isOpaque, isTexNonPow2Available(),
                        isTexRectAvailable(),
                        width, height);
                break;

            case FBOBJECT:
                success = initFBObject(getNativeOps(),
                        isOpaque, isTexNonPow2Available(),
                        isTexRectAvailable(),
                        width, height);
                break;

            case FLIP_BACKBUFFER:
                success = initFlipBackbuffer(getNativeOps());
                break;

            default:
                break;
        }*/

        success = initTexture(getNativeOps(),
                              isOpaque,
                              width, height);
        if (!success) {
            throw new OutOfMemoryError("can't create offscreen surface");
        }
    }

    /**
     * Creates a SurfaceData object representing the back buffer of a
     * double-buffered on-screen Window.
     */
    /*public static CGLOffScreenSurfaceData createData(CPlatformView pView,
                                                     Image image, int type) {
        CGLGraphicsConfig gc = getGC(pView);
        Rectangle r = pView.getBounds();
        if (type == FLIP_BACKBUFFER) {
            return new CGLOffScreenSurfaceData(pView, gc, r.width, r.height,
                    image, gc.getColorModel(), FLIP_BACKBUFFER);
        } else {
            return new CGLVSyncOffScreenSurfaceData(pView, gc, r.width,
                    r.height, image, gc.getColorModel(), type);
        }
    }*/

    /**
     * Creates a SurfaceData object representing an off-screen buffer (either a
     * FBO or Texture).
     */
    public static MetalOffScreenSurfaceData createData(MetalGraphicsConfig gc,
                                                     int width, int height, ColorModel cm, Image image, int type) {
        return new MetalOffScreenSurfaceData(null, gc, width, height, image, cm,
                type);
    }

    public static class MetalWindowSurfaceData extends MetalSurfaceData {

        public MetalWindowSurfaceData(CPlatformView pView,
                                    MetalGraphicsConfig gc) {
            super(pView, gc, gc.getColorModel(), WINDOW, 0, 0);
        }

        @Override
        public SurfaceData getReplacement() {
            return pView.getSurfaceData();
        }

        @Override
        public Rectangle getBounds() {
            Rectangle r = pView.getBounds();
            return new Rectangle(0, 0, r.width, r.height);
        }

        /**
         * Returns destination Component associated with this SurfaceData.
         */
        @Override
        public Object getDestination() {
            return pView.getDestination();
        }

        @Override
        public void validate() {
            MetalRenderQueue rq = MetalRenderQueue.getInstance();
            rq.lock();
            try {
                rq.flushAndInvokeNow(new Runnable() {
                    public void run() {
                        Rectangle peerBounds = pView.getBounds();
                        validate(0, 0, peerBounds.width, peerBounds.height, pView.isOpaque());
                    }
                });
            } finally {
                rq.unlock();
            }
        }

        @Override
        public void invalidate() {
            super.invalidate();
            clearWindow();
        }
    }

    public static class MetalLayerSurfaceData extends MetalSurfaceData {

        private MetalLayer layer;

        public MetalLayerSurfaceData(MetalLayer layer, MetalGraphicsConfig gc,
                                   int width, int height) {
            super(layer, gc, gc.getColorModel(), RT_TEXTURE, width, height);
            this.layer = layer;
            initSurface(this.width, this.height);
        }

        @Override
        public SurfaceData getReplacement() {
            return layer.getSurfaceData();
        }

        /*@Override
        boolean isOnScreen() {
            return true;
        }*/

        @Override
        public Rectangle getBounds() {
            return new Rectangle(width, height);
        }

        @Override
        public Object getDestination() {
            return layer.getDestination();
        }

        @Override
        public int getTransparency() {
            return layer.getTransparency();
        }

        @Override
        public void invalidate() {
            super.invalidate();
            clearWindow();
        }
    }

    public static class MetalOffScreenSurfaceData extends MetalSurfaceData {
        private Image offscreenImage;

        public MetalOffScreenSurfaceData(CPlatformView pView,
                                       MetalGraphicsConfig gc, int width, int height, Image image,
                                       ColorModel cm, int type) {
            super(pView, gc, cm, type, width, height);
            offscreenImage = image;
            initSurface(this.width, this.height);
        }

        @Override
        public SurfaceData getReplacement() {
            return restoreContents(offscreenImage);
        }

        @Override
        public Rectangle getBounds() {
            if (type == FLIP_BACKBUFFER) {
                Rectangle r = pView.getBounds();
                return new Rectangle(0, 0, r.width, r.height);
            } else {
                return new Rectangle(width, height);
            }
        }

        /**
         * Returns destination Image associated with this SurfaceData.
         */
        @Override
        public Object getDestination() {
            return offscreenImage;
        }
    }
    // TODO : We have some OGL Mac specific functions, verify their use case
}
