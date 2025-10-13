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

import sun.awt.X11ComponentPeer;
import sun.java2d.SurfaceData;
import sun.java2d.pipe.RenderBuffer;

import java.awt.Rectangle;

import static sun.java2d.pipe.BufferedOpCodes.FLUSH_BUFFER;

public class X11VKWindowSurfaceData extends VKSurfaceData {
    private native void initOps(int vkFormat, int backgroundRGB);
    private native void initAsReplacement(X11VKWindowSurfaceData oldSD);
    private native void assignWindow(int window);

    private final X11ComponentPeer peer;

    X11VKWindowSurfaceData(X11ComponentPeer peer) {
        super(((VKGraphicsConfig) peer.getGraphicsConfiguration()).getFormat(), peer.getColorModel().getTransparency(), WINDOW);

        this.gc = (VKGraphicsConfig) peer.getGraphicsConfiguration();
        this.peer = peer;
        Rectangle bounds = peer.getBounds();
        this.width = bounds.width;
        this.height = bounds.height;
        this.scale = gc.getFractionalScale();

        SurfaceData oldSurfaceData = peer.getSurfaceData();
        if (oldSurfaceData instanceof X11VKWindowSurfaceData) {
            initAsReplacement((X11VKWindowSurfaceData) oldSurfaceData);
        } else {
            initOps(gc.getFormat().getValue(peer.getColorModel().getTransparency()), 0);
            assignWindow((int) peer.getWindow());
        }

        revalidate(gc);
        configure();
    }

    @Override
    public SurfaceData getReplacement() {
        return null;
    }

    @Override
    public long getNativeResource(int resType) {
        return 0;
    }

    @Override
    public Rectangle getBounds() {
        return new Rectangle(width, height);
    }

    @Override
    public Object getDestination() {
        return peer.getTarget();
    }

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
}
