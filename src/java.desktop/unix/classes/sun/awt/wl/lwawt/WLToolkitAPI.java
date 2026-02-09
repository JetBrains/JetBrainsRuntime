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

package sun.awt.wl.lwawt;

import sun.awt.wl.WLToolkit;
import sun.lwawt.LWComponentPeerAPI;
import sun.lwawt.PlatformDropTarget;
import sun.lwawt.ToolkitAPI;

import java.awt.*;
import java.awt.dnd.DropTarget;

public class WLToolkitAPI implements ToolkitAPI {

    private static final WLToolkitAPI INSTANCE = new WLToolkitAPI();

    private WLToolkitAPI() {
    }

    public static WLToolkitAPI getInstance() {
        return INSTANCE;
    }

    @Override
    public Object targetToPeer(Object target) {
        return WLToolkit.targetToPeer(target);
    }

    @Override
    public void targetDisposedPeer(Object target, Object peer) {
        WLToolkit.targetDisposedPeer(target, peer);
    }

    @Override
    public void postEvent(AWTEvent event) {
        WLToolkit.postEvent(event);
    }

    @Override
    public void flushOnscreenGraphics() {
        // TODO not sure what we should do here
    }

    @Override
    public boolean needUpdateWindowAfterPaint() {
        return ((WLToolkit)Toolkit.getDefaultToolkit()).needUpdateWindowAfterPaint();
    }

    @Override
    public void updateCursorImmediately() {
        // TODO compare WLCursorManager and LWCursorManager
    }

    @Override
    public PlatformDropTarget createDropTarget(DropTarget dropTarget, Component component, LWComponentPeerAPI peer) {
        // TODO
        return null;
    }
}
