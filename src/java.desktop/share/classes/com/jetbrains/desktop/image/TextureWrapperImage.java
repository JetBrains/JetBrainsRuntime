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

package com.jetbrains.desktop.image;

import sun.awt.image.SurfaceManager;
import sun.java2d.SurfaceData;
import sun.java2d.SurfaceManagerFactory;

import java.awt.AlphaComposite;
import java.awt.GraphicsConfiguration;
import java.awt.Graphics2D;
import java.awt.Graphics;
import java.awt.Image;
import java.awt.ImageCapabilities;
import java.awt.image.BufferedImage;
import java.awt.image.ImageObserver;
import java.awt.image.ImageProducer;

/**
 * This class is a wrapper for a GPU texture-based image.
 * It provides functionalities to integrate a texture image with AWT's Image class.
 * The wrapped texture has to correspond to the current rendering pipeline (see details below).
 * <p>
 * Only Metal textures are supported at the moment.
 * <p>
 * Platform-specific details:
 * 1. macOS.
 * The MTLTexture reference counter will be incremented in the constructor and decremented on
 * the surface flushing.
 */
public class TextureWrapperImage extends Image {
    final GraphicsConfiguration gc;
    final SurfaceData sd;
    final static ImageCapabilities capabilities = new ImageCapabilities(true);

    /**
     * Constructs a TextureWrapperImage instance with the specified graphics configuration
     * and a texture.
     *
     * @param gc the graphics configuration
     * @param texture the texture that will be wrapped by this instance.
     *                Platform-specific details:
     *                macOS (with the Metal rendering pipeline) - a pointer to an MTLTexture object is expected
     *
     * @throws UnsupportedOperationException if the current pipeline is not supported
     * @throws IllegalArgumentException if the texture cannot be wrapped
     */
    public TextureWrapperImage(GraphicsConfiguration gc, long texture)
            throws UnsupportedOperationException, IllegalArgumentException {
        this.gc = gc;
        SurfaceManager surfaceManager = SurfaceManagerFactory.getInstance().createTextureWrapperSurfaceManager(gc, this, texture);
        sd = surfaceManager.getPrimarySurfaceData();
        SurfaceManager.setManager(this, surfaceManager);
    }

    @Override
    public int getWidth(ImageObserver observer) {
        return (int) (sd.getBounds().width / sd.getDefaultScaleX());
    }

    @Override
    public int getHeight(ImageObserver observer) {
        return (int) (sd.getBounds().height / sd.getDefaultScaleY());
    }

    @Override
    public ImageProducer getSource() {
        BufferedImage bi = sd.getDeviceConfiguration().createCompatibleImage(getWidth(null), getHeight(null));
        Graphics2D g = bi.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.drawImage(this, 0, 0, null);
        g.dispose();
        return bi.getSource();
    }

    @Override
    public Graphics getGraphics() {
        throw new UnsupportedOperationException("This image can't be the drawing destination");
    }

    @Override
    public Object getProperty(String name, ImageObserver observer) {
        if (name == null) {
            throw new NullPointerException("null property name is not allowed");
        }
        return java.awt.Image.UndefinedProperty;
    }

    @Override
    public ImageCapabilities getCapabilities(GraphicsConfiguration gc) {
        return capabilities;
    }
}
