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

import java.awt.Component;
import java.awt.GraphicsConfiguration;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Objects;

import sun.awt.wl.WLComponentPeer;
import sun.java2d.SurfaceData;
import static sun.java2d.pipe.BufferedOpCodes.FLUSH_BUFFER;
import sun.java2d.pipe.RenderBuffer;
import sun.java2d.wl.WLPixelGrabberExt;
import sun.java2d.wl.WLSurfaceDataExt;
import sun.java2d.wl.WLSurfaceSizeListener;

public class WLVKWindowSurfaceData extends VKSurfaceData
        implements WLPixelGrabberExt, WLSurfaceDataExt {
    private final Component target; // optional
    private final WLSurfaceSizeListener sizeListener;

    private native void initOps(int format, int backgroundRGB);

    private native void assignWlSurface(long surfacePtr);

    public WLVKWindowSurfaceData(WLComponentPeer peer) {
        super(((VKGraphicsConfig) peer.getGraphicsConfiguration()).getFormat(), peer.getColorModel().getTransparency(), WINDOW);
        this.target = peer.getTarget();
        this.sizeListener = peer;
        this.gc = (WLVKGraphicsConfig) peer.getGraphicsConfiguration();
        this.width = peer.getBufferWidth();
        this.height = peer.getBufferHeight();

        final int backgroundRGB = peer.getBackground() != null
                ? peer.getBackground().getRGB()
                : 0;
        initOps(getFormat().getValue(getTransparency()), backgroundRGB);
    }

    public WLVKWindowSurfaceData(WLSurfaceSizeListener sizeListener, int width, int height, WLVKGraphicsConfig gc) {
        super(gc.getFormat(), gc.getColorModel().getTransparency(), WINDOW);
        this.target = null;
        this.sizeListener = sizeListener;
        this.gc = gc;
        this.width = width;
        this.height = height;

        Objects.requireNonNull(sizeListener);
        Objects.requireNonNull(gc);

        initOps(getFormat().getValue(getTransparency()), 0);
    }

    public SurfaceData getReplacement() {
        return null;
    }

    @Override
    public long getNativeResource(int resType) {
        return 0;
    }

    public Rectangle getBounds() {
        return new Rectangle(width, height);
    }

    /**
     * Returns destination Component associated with this SurfaceData.
     */
    public Object getDestination() {
        // NB: optional; could be null
        return target;
    }

    @Override
    public void assignSurface(long surfacePtr) {
        assignWlSurface(surfacePtr);
        if (surfacePtr != 0) configure();
    }

    @Override
    public void revalidate(GraphicsConfiguration gc, int width, int height, int scale) {
        this.width = width;
        this.height = height;
        this.scale = scale;
        revalidate((VKGraphicsConfig) gc);
        configure();
    }

    @Override
    public synchronized void commit() {
        VKRenderQueue rq = VKRenderQueue.getInstance();
        rq.lock();
        try {
            RenderBuffer buf = rq.getBuffer();
            rq.ensureCapacityAndAlignment(12, 4);
            buf.putInt(FLUSH_BUFFER);
            buf.putLong(getNativeOps());

            rq.flushNow();
        } finally {
            rq.unlock();
        }
    }

    private void bufferAttached() {
        // Called from the native code when a buffer has just been attached to this surface
        // but the surface has not been committed yet.
        sizeListener.updateSurfaceSize();
    }
    public int getRGBPixelAt(int x, int y) {
        Rectangle r = getBounds();
        if (x < r.x || x >= r.x + r.width || y < r.y || y >= r.y + r.height) {
            throw new ArrayIndexOutOfBoundsException("x,y outside of buffer bounds");
        }

        BufferedImage image = getSnapshot(x, y, 1, 1);
        return image.getRGB(0, 0);
    }

    public int [] getRGBPixelsAt(Rectangle bounds) {
        Rectangle r = getBounds();

        if ((long)bounds.width * (long)bounds.height > Integer.MAX_VALUE) {
            throw new IndexOutOfBoundsException("Dimensions (width=" + bounds.width +
                    " height=" + bounds.height + ") are too large");
        }

        Rectangle b = bounds.intersection(r);

        if (b.isEmpty()) {
            throw new IndexOutOfBoundsException("Requested bounds are outside of surface bounds");
        }

        BufferedImage image = getSnapshot(b.x, b.y, b.width, b.height);
        int [] pixels = new int[b.width * b.height];
        image.getRGB(0, 0, b.width, b.height, pixels, 0, b.width);
        return pixels;
    }
}