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

import sun.awt.CGraphicsConfig;
import sun.awt.CGraphicsDevice;
import sun.java2d.Disposer;
import sun.java2d.SurfaceData;
import sun.java2d.pipe.hw.ContextCapabilities;
import sun.lwawt.LWComponentPeer;
import sun.lwawt.macosx.CPlatformView;
import sun.java2d.opengl.CGLLayer;
import sun.java2d.metal.MetalContext;
import sun.java2d.metal.MetalRenderQueue;

import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DirectColorModel;
import java.awt.image.VolatileImage;
import java.awt.image.WritableRaster;

import java.awt.*;

public final class MetalGraphicsConfig extends CGraphicsConfig {

    private int pixfmt;
    public long pConfigInfo; //public access is a hack
    private static native long getMetalConfigInfo(int displayID, int visualnum);

    private MetalContext context;
    
    long getNativeConfigInfo() {
        return pConfigInfo;
    }

    @Override
    public int getMaxTextureWidth() {
        return 16384; 
        /* Getting Device Caps from Metal Device is required. 
           There is no API for this. We need to refer manual and populate these values. 
           Refer https://wiki.se.oracle.com/display/JPGC/Metal+Rendering+Pipeline */
    }

    @Override
    public int getMaxTextureHeight() {
        return 16384;
    }

    @Override
    public void assertOperationSupported(int numBuffers, BufferCapabilities caps) throws AWTException {

    }

    @Override
    public Image createBackBuffer(LWComponentPeer<?, ?> peer) {
        return null;
    }

    @Override
    public void destroyBackBuffer(Image backBuffer) {

    }

    @Override
    public void flip(LWComponentPeer<?, ?> peer, Image backBuffer, int x1, int y1, int x2, int y2, BufferCapabilities.FlipContents flipAction) {

    }

    @Override
    public Image createAcceleratedImage(Component target, int width, int height) {
        return null;
    }

    private MetalGraphicsConfig(CGraphicsDevice device, int pixfmt,
                                long configInfo) {
        super(device);

        this.pixfmt = pixfmt;
        this.pConfigInfo = configInfo;
        //this.oglCaps = oglCaps;
        //this.maxTextureSize = maxTextureSize;
        // TODO : We are using MetalContext as of now.
        // We need to verify its usage in future to keep/remove it.
        context = new MetalContext(MetalRenderQueue.getInstance(), this);

        // add a record to the Disposer so that we destroy the native
        // CGLGraphicsConfigInfo data when this object goes away
        //Disposer.addRecord(disposerReferent,
                //new CGLGraphicsConfig.CGLGCDisposerRecord(pConfigInfo));
    }

    public static MetalGraphicsConfig getConfig(CGraphicsDevice device, int displayID,
                                              int pixfmt)
    {
        //if (!cglAvailable) {
            //return null;
        //}

        long cfginfo = 0;
        //int textureSize = 0;
        //final String ids[] = new String[1];
        //OGLRenderQueue rq = OGLRenderQueue.getInstance();
        //rq.lock();
        //try {
            // getCGLConfigInfo() creates and destroys temporary
            // surfaces/contexts, so we should first invalidate the current
            // Java-level context and flush the queue...
            //OGLContext.invalidateCurrentContext();

            cfginfo = getMetalConfigInfo(displayID, pixfmt);
            /*if (cfginfo != 0L) {
                textureSize = nativeGetMaxTextureSize();
                // 7160609: GL still fails to create a square texture of this
                // size. Half should be safe enough.
                // Explicitly not support a texture more than 2^14, see 8010999.
                textureSize = textureSize <= 16384 ? textureSize / 2 : 8192;
                OGLContext.setScratchSurface(cfginfo);
                rq.flushAndInvokeNow(() -> {
                    ids[0] = OGLContext.getOGLIdString();
                });
            }
        } finally {
            rq.unlock();
        }
        if (cfginfo == 0) {
            return null;
        }

        int oglCaps = getOGLCapabilities(cfginfo);
        ContextCapabilities caps = new OGLContext.OGLContextCaps(oglCaps, ids[0]);*/
        return new MetalGraphicsConfig(device, pixfmt, cfginfo);
    }

    @Override
    public SurfaceData createSurfaceData(CPlatformView pView) {
        return null;
    }

    @Override
    public SurfaceData createSurfaceData(CGLLayer layer) {
        return null;
    }

    @Override
    public SurfaceData createSurfaceData(MetalLayer layer) {
        return MetalSurfaceData.createData(layer);
    }

    @Override
    public ColorModel getColorModel(int transparency) {
        switch (transparency) {
        case Transparency.OPAQUE:
            // REMIND: once the ColorModel spec is changed, this should be
            //         an opaque premultiplied DCM...
            return new DirectColorModel(24, 0xff0000, 0xff00, 0xff);
        case Transparency.BITMASK:
            return new DirectColorModel(25, 0xff0000, 0xff00, 0xff, 0x1000000);
        case Transparency.TRANSLUCENT:
            ColorSpace cs = ColorSpace.getInstance(ColorSpace.CS_sRGB);
            return new DirectColorModel(cs, 32,
                                        0xff0000, 0xff00, 0xff, 0xff000000,
                                        true, DataBuffer.TYPE_INT);
        default:
            return null;
        }
    }

    public MetalContext getContext() {
        return context;
    }
}
