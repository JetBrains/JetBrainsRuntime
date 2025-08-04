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

import sun.java2d.opengl.*;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Image;

@JBRApi.Service
@JBRApi.Provides("SharedTextures")
public class SharedTextures {
    public final static int METAL_TEXTURE_TYPE = 1;
    public final static int OPENGL_TEXTURE_TYPE = 2;

    public static SharedTextures create() {
        return new SharedTextures();
    }

    private SharedTextures() {}

    public Image wrapTexture(GraphicsConfiguration gc, long texture) {
        return new TextureWrapperImage(gc, texture);
    }

    public int getTextureType(GraphicsConfiguration gc) {
        try {
            if (gc instanceof WGLGraphicsConfig) {
                return OPENGL_TEXTURE_TYPE;
            }
        } catch (Exception e) {
            throw new InternalError("Unexpected exception during reflection", e);
        }

        return 0;
    }

    @Deprecated
    public int getTextureType() {
        GraphicsConfiguration gc = GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .getDefaultScreenDevice()
                .getDefaultConfiguration();
        return getTextureType(gc);
    }

    public long[] getOpenGLContextInfo(GraphicsConfiguration gc) {
        if (gc instanceof WGLGraphicsConfig wglGraphicsConfig) {
            return new long[] {
                    WGLGraphicsConfigExt.getSharedOpenGLContext(),
                    WGLGraphicsConfigExt.getSharedOpenGLPixelFormat(wglGraphicsConfig),
            };
        }

        throw new UnsupportedOperationException("Unsupported graphics configuration: " + gc);
    }

    static SurfaceManager createSurfaceManager(GraphicsConfiguration gc, Image image, long texture) {
        SurfaceData sd;
        if (gc instanceof WGLGraphicsConfig wglGraphicsConfig) {
            sd = new WGLTextureWrapperSurfaceData(wglGraphicsConfig, image, texture);
        } else {
            throw new IllegalArgumentException("Unsupported graphics configuration: " + gc);
        }

        return new TextureWrapperSurfaceManager(sd);
    }
}
