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

package sun.lwawt.macosx;

import sun.lwawt.LWComponentPeerAPI;
import sun.lwawt.LWMouseEventDispatcher;
import sun.lwawt.LWWindowPeerAPI;

import java.awt.event.MouseEvent;

public class LWCMouseEventDispatcher extends LWMouseEventDispatcher {

    public LWCMouseEventDispatcher(LWWindowPeerAPI windowPeer) {
        super(windowPeer);
    }

    @Override
    protected void storeMouseDownTarget(int button, LWComponentPeerAPI peer) {
        assert button > 0 && button <= mouseDownTarget.length
                : "Unexpected button index: " + button + ", expected values: 1-" + mouseDownTarget.length;
        mouseDownTarget[getTargetIndex(button)] = peer;
    }

    @Override
    protected LWComponentPeerAPI getMouseDownTarget(int button) {
        assert button > 0 && button <= mouseDownTarget.length
                : "Unexpected button index: " + button + ", expected values: 1-" + mouseDownTarget.length;
        return mouseDownTarget[getTargetIndex(button)];
    }

    private static int getTargetIndex(int button) {
        // For pressed/dragged/released events OS X treats other
        // mouse buttons as if they were BUTTON2, so we do the same
        return (button > 3) ? MouseEvent.BUTTON2 : button;
    }
}
