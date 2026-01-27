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

import java.awt.event.MouseEvent;

import sun.lwawt.LWWindowPeer;

/**
 * macOS-specific workaround for JBR-7481 mouse entered/exited event issues.
 */
class Jbr7481MouseEnteredExitedFix {
    static final boolean isEnabled;

    static {
        boolean isEnabledLocal = false;

        try {
            isEnabledLocal = Boolean.parseBoolean(System.getProperty("awt.mac.enableMouseEnteredExitedWorkaround", "true"));
        } catch (Exception ignored) {
        }

        isEnabled = isEnabledLocal;
    }

    private final LWWindowPeer peer;
    long when;
    int x;
    int y;
    int absX;
    int absY;
    int modifiers;

    Jbr7481MouseEnteredExitedFix(LWWindowPeer peer) {
        this.peer = peer;
    }

    void apply(
            int id, long when, int button,
            int x, int y, int absX, int absY,
            int modifiers, int clickCount, boolean popupTrigger,
            byte[] bdata
    ) {
        this.when = when;
        this.x = x;
        this.y = y;
        this.absX = absX;
        this.absY = absY;
        this.modifiers = modifiers;
        switch (id) {
            case MouseEvent.MOUSE_ENTERED -> {
                var currentPeerWorkaround = getCurrentPeerWorkaroundOrNull();
                // First, send a "mouse exited" to the current window, if any,
                // to maintain a sensible "exited, entered" order.
                if (currentPeerWorkaround != null && currentPeerWorkaround != this) {
                    currentPeerWorkaround.sendMouseExited();
                }
                // Then forward the "mouse entered" to this window, regardless of whether it's already current.
                // Repeated "mouse entered" are allowed and may be even needed somewhere deep inside this call.
                peer.doNotifyMouseEvent(id, when, button, x, y, absX, absY, modifiers, clickCount, popupTrigger, bdata);
            }
            case MouseEvent.MOUSE_EXITED -> {
                var currentPeerWorkaround = getCurrentPeerWorkaroundOrNull();
                // An "exited" event often arrives too late. Such events may cause the current window to get lost.
                // And since we've already sent a "mouse exited" when entering the current window, we don't need another one.
                if (currentPeerWorkaround == this) {
                    peer.doNotifyMouseEvent(id, when, button, x, y, absX, absY, modifiers, clickCount, popupTrigger, bdata);
                }
            }
            case MouseEvent.MOUSE_MOVED -> {
                var currentPeerWorkaround = getCurrentPeerWorkaroundOrNull();
                if (currentPeerWorkaround != this) {
                    // Inconsistency detected: either the events arrived out of order or never did.
                    // First, send an "exited" event to the current window, if any.
                    if (currentPeerWorkaround != null) {
                        currentPeerWorkaround.sendMouseExited();
                    }
                    // Next, send a fake "mouse entered" to the new window.
                    sendMouseEntered();
                }
                peer.doNotifyMouseEvent(id, when, button, x, y, absX, absY, modifiers, clickCount, popupTrigger, bdata);
            }
            default -> {
                peer.doNotifyMouseEvent(id, when, button, x, y, absX, absY, modifiers, clickCount, popupTrigger, bdata);
            }
        }
    }

    private static Jbr7481MouseEnteredExitedFix getCurrentPeerWorkaroundOrNull() {
        var currentPeer = getCurrentWindowPeer();
        if (currentPeer instanceof LWCWindowPeer lwcPeer) {
            return lwcPeer.jbr7481MouseEnteredExitedFix;
        }
        if (currentPeer instanceof LWCLightweightFramePeer lwcLwPeer) {
            return lwcLwPeer.jbr7481MouseEnteredExitedFix;
        }
        return null;
    }

    private static LWWindowPeer getCurrentWindowPeer() {
        return LWWindowPeer.getWindowUnderCursor();
    }

    private void sendMouseEntered() {
        peer.doNotifyMouseEvent(
                MouseEvent.MOUSE_ENTERED, when, MouseEvent.NOBUTTON,
                x, y, absX, absY,
                modifiers, 0, false,
                null
        );
    }

    private void sendMouseExited() {
        peer.doNotifyMouseEvent(
                MouseEvent.MOUSE_EXITED, when, MouseEvent.NOBUTTON,
                x, y, absX, absY,
                modifiers, 0, false,
                null
        );
    }

    @Override
    public String toString() {
        return "Jbr7481MouseEnteredExitedFix{" +
                "peer=" + peer +
                ", when=" + when +
                ", x=" + x +
                ", y=" + y +
                ", absX=" + absX +
                ", absY=" + absY +
                ", modifiers=" + modifiers +
                '}';
    }
}
