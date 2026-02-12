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

package sun.lwawt;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.dnd.DropTarget;

public interface ToolkitAPI {

    Object peerForTarget(Object target);

    void peerDisposedForTarget(Object target, Object peer);

    void postAWTEvent(AWTEvent event);

    void flushOnscreenGraphics();

    boolean needUpdateWindowAfterPaint();

    void updateCursorImmediately();

    void updateCursorLater(Window target);

    PlatformWindow getPlatformWindowUnderMouse();

    PlatformDropTarget createDropTarget(DropTarget dropTarget, Component component, LWComponentPeerAPI peer);

    static ToolkitAPI getDefaultToolkit() {
        return (ToolkitAPI)Toolkit.getDefaultToolkit();
    }
}
