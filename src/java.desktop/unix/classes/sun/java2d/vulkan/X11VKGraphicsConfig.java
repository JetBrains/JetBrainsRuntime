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
import sun.awt.X11GraphicsConfig;
import sun.awt.X11GraphicsDevice;
import sun.java2d.SurfaceData;
import sun.util.logging.PlatformLogger;

public class X11VKGraphicsConfig extends X11GraphicsConfig implements VKGraphicsConfig {
    private static final PlatformLogger log =
            PlatformLogger.getLogger("sun.java2d.vulkan.X11VKGraphicsConfig");

    private final VKGraphicsConfig offscreenConfig;

    protected X11VKGraphicsConfig(VKGraphicsConfig offscreenConfig, X11GraphicsDevice device, int visualnum) {
        super(device, visualnum, 0, 0, false);
        this.offscreenConfig = offscreenConfig;
    }

    public static X11VKGraphicsConfig getConfig(VKGraphicsConfig offscreenConfig, X11GraphicsDevice device, int visualnum) {
        return new X11VKGraphicsConfig(offscreenConfig, device, visualnum);
    }

    @Override
    public double getFractionalScale() {
        return super.getScale();
    }

    @Override
    public VKGraphicsConfig getOffscreenConfig() {
        return offscreenConfig;
    }

    @Override
    public String toString() {
        return "X11VKGraphicsConfig[" + descriptorString() + ", " + getDevice().getIDstring() + "]";
    }

    @Override
    public SurfaceData createSurfaceData(X11ComponentPeer peer) {
        SurfaceData oldSurfaceData = peer.getSurfaceData();
        if (oldSurfaceData instanceof X11VKWindowSurfaceData) {
            return oldSurfaceData;
        }
        return new X11VKWindowSurfaceData(peer);
    }
}
