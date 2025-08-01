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

package sun.java2d.opengl;

import sun.java2d.SurfaceData;

import java.awt.*;
import java.awt.image.ColorModel;
import java.util.concurrent.atomic.AtomicBoolean;

public class WGLTextureWrapperSurfaceData extends WGLSurfaceData {
    private final Image image;

    public WGLTextureWrapperSurfaceData(WGLGraphicsConfig gc, Image image, long textureId) {
        super(null, gc, ColorModel.getRGBdefault(), RT_TEXTURE);
        this.image = image;

        OGLRenderQueue rq = OGLRenderQueue.getInstance();
        AtomicBoolean success = new AtomicBoolean(false);
        rq.lock();
        try {
            OGLContext.setScratchSurface(gc);
            rq.flushAndInvokeNow(() -> success.set(OGLSurfaceDataExt.initWithTexture(this, textureId)));
        } finally {
            rq.unlock();
        }

        if (!success.get()) {
            throw new IllegalArgumentException("Failed to init the surface data");
        }
    }

    @Override
    public SurfaceData getReplacement() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Rectangle getBounds() {
        return getNativeBounds();
    }

    @Override
    public Object getDestination() {
        return null;
    }

    @Override
    public void flush() {
        // reset the texture id first to avoid the texture deallocation
        OGLSurfaceDataExt.resetTextureId(this);
        super.flush();
    }
}
