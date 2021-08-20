/*
 * Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
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

package sun.awt.wl;

import sun.awt.AWTAccessor;
import sun.awt.KeyboardFocusManagerPeerImpl;
import sun.util.logging.PlatformLogger;

import java.awt.Component;
import java.awt.Window;
import java.lang.ref.WeakReference;

public class WLKeyboardFocusManagerPeer extends KeyboardFocusManagerPeerImpl {
    private static final PlatformLogger focusLog = PlatformLogger.getLogger("sun.awt.wl.focus.WLKeyboardFocusManagerPeer");

    private WeakReference<Window> currentFocusedWindow = new WeakReference<>(null);
    private WeakReference<Component> currentFocusOwner = new WeakReference<>(null);

    private static final WLKeyboardFocusManagerPeer instance = new WLKeyboardFocusManagerPeer();

    public static WLKeyboardFocusManagerPeer getInstance() {
        return instance;
    }

    @Override
    public void setCurrentFocusedWindow(Window win) {
        synchronized (this) {
            if (focusLog.isLoggable(PlatformLogger.Level.FINER)) {
                focusLog.finer("Current focused window -> " + win);
            }
            currentFocusedWindow = new WeakReference<>(win);
        }
    }

    @Override
    public Window getCurrentFocusedWindow() {
        synchronized (this) {
            return currentFocusedWindow.get();
        }
    }

    @Override
    public void setCurrentFocusOwner(Component comp) {
        synchronized (this) {
            currentFocusOwner = new WeakReference<>(comp);
        }
        if (comp == null) {
            return;
        }
        Window nativeFocusable = WLComponentPeer.getNativelyFocusableOwnerOrSelf(comp);
        if (nativeFocusable != getCurrentFocusedWindow()) {
            focusLog.severe("Unexpected focus owner set outside of focused window: " + comp);
            return;
        }
        AWTAccessor.ComponentAccessor acc = AWTAccessor.getComponentAccessor();
        WLComponentPeer nativeFocusablePeer = acc.getPeer(nativeFocusable);
        if (nativeFocusablePeer instanceof WLWindowPeer windowPeer) {
            // May have to transfer the keyboard focus to a child popup window
            // when this 'windowPeer' receives focus from Wayland again because popups
            // aren't natively focusable under Wayland.
            Component synthFocusOwner = nativeFocusable != comp ? comp : null;
            windowPeer.setSyntheticFocusOwner(synthFocusOwner);
        }
    }

    @Override
    public Component getCurrentFocusOwner() {
        synchronized (this) {
            return currentFocusOwner.get();
        }
    }
}
