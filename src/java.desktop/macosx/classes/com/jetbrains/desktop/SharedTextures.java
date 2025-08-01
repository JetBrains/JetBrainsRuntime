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

package com.jetbrains.desktop;

import com.jetbrains.desktop.image.TextureWrapperImage;
import com.jetbrains.desktop.image.TextureWrapperSurfaceManager;
import com.jetbrains.exported.JBRApi;
import sun.awt.image.SurfaceManager;
import sun.java2d.SurfaceData;
import sun.java2d.metal.MTLGraphicsConfig;
import sun.java2d.metal.MTLSurfaceData;
import sun.java2d.metal.MTLTextureWrapperSurfaceData;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Image;

@JBRApi.Service
@JBRApi.Provides("SharedTextures")
public class SharedTextures {
    public final static int METAL_TEXTURE_TYPE = 1;

    private final int textureType;

    public static SharedTextures create() {
        return new SharedTextures();
    }

    private SharedTextures() {
        textureType = getTextureTypeImpl();
        if (textureType == 0) {
            throw new JBRApi.ServiceNotAvailableException();
        }
    }

    public int getTextureType() {
        return textureType;
    }

    public Image wrapTexture(GraphicsConfiguration gc, long texture) {
        return new TextureWrapperImage(gc, texture);
    }

    private static int getTextureTypeImpl() {
        GraphicsConfiguration gc = GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .getDefaultScreenDevice()
                .getDefaultConfiguration();

        if (gc instanceof MTLGraphicsConfig) {
            return METAL_TEXTURE_TYPE;
        }

        return 0;
    }

    static SurfaceManager createSurfaceManager(GraphicsConfiguration gc, Image image, long texture) {
        SurfaceData sd;
        if (gc instanceof MTLGraphicsConfig mtlGraphicsConfig) {
            sd = new MTLTextureWrapperSurfaceData(mtlGraphicsConfig, image, texture);
        } else {
            throw new IllegalArgumentException("Unsupported graphics configuration: " + gc);
        }

        return new TextureWrapperSurfaceManager(sd);
    }
}
