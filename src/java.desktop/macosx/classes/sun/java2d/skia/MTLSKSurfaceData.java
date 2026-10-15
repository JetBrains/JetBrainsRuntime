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
import sun.java2d.metal.MTLGraphicsConfig;
import sun.java2d.metal.MTLLayer;
import sun.java2d.metal.MTLSurfaceData;

import java.awt.GraphicsConfiguration;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.image.ColorModel;

/**
 * Skia surface on the Metal pipeline: <em>wraps</em> a Metal surface that owns
 * the GPU texture and, for windows, the presentation layer. Skia draws into
 * that texture through a Graphite context on the same command queue, and the
 * Metal layer presents it exactly as it presents Metal-rendered content. This
 * keeps all window, HiDPI and display-sync handling in the existing code while
 * the rendering itself goes through Skia.
 */
public final class MTLSKSurfaceData extends SKSurfaceData {

    private final MTLSurfaceData delegate;
    private final MTLGraphicsConfig graphicsConfig;
    private final MTLLayer layer;          // non-null for window surfaces
    private final Image offscreenImage;    // non-null for VolatileImage surfaces

    private MTLSKSurfaceData(MTLSurfaceData delegate, MTLGraphicsConfig gc, MTLLayer layer,
                             Image offscreenImage, ColorModel cm, int type) {
        super(gc, cm, type, delegate.getNativeOps());
        this.delegate = delegate;
        this.graphicsConfig = gc;
        this.layer = layer;
        this.offscreenImage = offscreenImage;
        // the two surfaces have separate Disposer records; the Metal one must
        // not free the native ops while Skia ops referencing them are queued
        retainUntilDisposed(delegate);
    }

    /** Window surface: wraps the Metal layer surface created for {@code layer}. */
    public static MTLSKSurfaceData createData(MTLLayer layer) {
        MTLGraphicsConfig gc = (MTLGraphicsConfig) layer.getGraphicsConfiguration();
        MTLSurfaceData mtl = MTLSurfaceData.createData(layer);
        return new MTLSKSurfaceData(mtl, gc, layer, null, mtl.getColorModel(), WINDOW);
    }

    /** Off-screen surface (VolatileImage back buffers, textures). */
    public static MTLSKSurfaceData createData(MTLGraphicsConfig gc, int width, int height,
                                              ColorModel cm, Image image, int type) {
        MTLSurfaceData mtl = MTLSurfaceData.createData(gc, width, height, cm, image, type);
        return new MTLSKSurfaceData(mtl, gc, null, image, cm, type);
    }

    /** The Metal surface that owns the texture (and layer) this surface renders into. */
    public MTLSurfaceData getDelegate() {
        return delegate;
    }

    @Override
    public boolean isOnScreen() {
        return layer != null;
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

    @Override
    public void flush() {
        super.flush();
        // the Skia wrapper is gone; now the Metal side may release the texture
        delegate.flush();
    }

    @Override
    public void invalidate() {
        super.invalidate();   // the Skia side has forgotten the surface when this returns
        delegate.invalidate();
    }

    // ---- AccelSurface ------------------------------------------------------

    @Override
    public long getNativeResource(int resType) {
        return delegate.getNativeResource(resType);
    }

    @Override
    public Rectangle getNativeBounds() {
        return delegate.getNativeBounds();
    }
}
