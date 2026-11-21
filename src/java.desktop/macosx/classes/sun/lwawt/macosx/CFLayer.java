/*
 * Copyright (c) 2021–2026, Oracle and/or its affiliates. All rights reserved.
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

package sun.lwawt.macosx;

import sun.java2d.SurfaceData;

import java.awt.GraphicsConfiguration;
import java.awt.Rectangle;
import java.awt.Transparency;
import java.awt.Window;

import javax.swing.JRootPane;
import javax.swing.RootPaneContainer;

import sun.lwawt.LWWindowPeer;

/**
 * Common layer class between OpenGl and Metal.
 */
public abstract class CFLayer extends CFRetainedResource {
    protected final LWWindowPeer peer;
    private final boolean windowSurfaceDisabled;
    protected SurfaceData surfaceData; // represents intermediate buffer (texture)

    protected CFLayer(long ptr, boolean disposeOnAppKitThread, LWWindowPeer peer) {
        super(ptr, disposeOnAppKitThread);
        this.peer = peer;
        this.windowSurfaceDisabled = readWindowSurfaceDisabled(peer);
    }

    public abstract SurfaceData replaceSurfaceData(int scale);

    public SurfaceData replaceSurfaceData() {
        return replaceSurfaceData(0);
    }

    @Override
    public void dispose() {
        super.dispose();
    }

    public long getPointer() {
        return ptr;
    }

    public SurfaceData getSurfaceData() {
        return surfaceData;
    }

    public Rectangle getBounds() {
        return peer.getBounds();
    }

    public GraphicsConfiguration getGraphicsConfiguration() {
        return peer.getGraphicsConfiguration();
    }

    public boolean isOpaque() {
        return !peer.isTranslucent();
    }

    public void setOpaque(boolean opaque) {
        // Default is no op (works well for OGL)
    }

    public int getTransparency() {
        return isOpaque() ? Transparency.OPAQUE : Transparency.TRANSLUCENT;
    }

    public Object getDestination() {
        return peer.getTarget();
    }

    protected final boolean isWindowSurfaceDisabled() {
        return windowSurfaceDisabled;
    }

    private static boolean readWindowSurfaceDisabled(LWWindowPeer peer) {
        if (peer == null) return false;
        Window target = peer.getTarget();
        if (target instanceof RootPaneContainer rpc) {
            JRootPane rootPane = rpc.getRootPane();
            if (rootPane != null) {
                Object value = rootPane.getClientProperty(CPlatformWindow.WINDOW_SURFACE_DISABLED);
                return value != null && Boolean.parseBoolean(value.toString());
            }
        }
        return false;
    }
}
