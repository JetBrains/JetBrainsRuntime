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

import sun.awt.LightweightFrame;
import sun.lwawt.LWLightweightFramePeer;
import sun.lwawt.PlatformComponent;
import sun.lwawt.PlatformWindow;
import sun.lwawt.ToolkitAPI;

/**
 * macOS-specific lightweight frame peer that includes the Jbr7481MouseEnteredExitedFix workaround.
 */
public final class LWCLightweightFramePeer extends LWLightweightFramePeer implements LWCMouseEventNotifier {

    Jbr7481MouseEnteredExitedFix jbr7481MouseEnteredExitedFix = null;

    public LWCLightweightFramePeer(LightweightFrame target,
                                   PlatformComponent platformComponent,
                                   PlatformWindow platformWindow,
                                   ToolkitAPI toolkitApi) {
        super(target, platformComponent, platformWindow, toolkitApi);
    }

    @Override
    public void notifyMouseEvent(int id, long when, int button,
                                 int x, int y, int absX, int absY,
                                 int modifiers, int clickCount, boolean popupTrigger,
                                 byte[] bdata) {
        if (Jbr7481MouseEnteredExitedFix.isEnabled) {
            mouseEnteredExitedBugWorkaround().apply(id, when, button, x, y, absX, absY, modifiers, clickCount, popupTrigger, bdata);
        } else {
            doNotifyMouseEvent(id, when, button, x, y, absX, absY, modifiers, clickCount, popupTrigger, bdata);
        }
    }

    @Override
    public void doNotifyMouseEvent(int id, long when, int button,
                                   int x, int y, int absX, int absY,
                                   int modifiers, int clickCount, boolean popupTrigger,
                                   byte[] bdata) {
        super.notifyMouseEvent(id, when, button, x, y, absX, absY, modifiers, clickCount, popupTrigger, bdata);
    }

    private Jbr7481MouseEnteredExitedFix mouseEnteredExitedBugWorkaround() {
        if (jbr7481MouseEnteredExitedFix == null) {
            jbr7481MouseEnteredExitedFix = new Jbr7481MouseEnteredExitedFix(this);
        }
        return jbr7481MouseEnteredExitedFix;
    }
}
