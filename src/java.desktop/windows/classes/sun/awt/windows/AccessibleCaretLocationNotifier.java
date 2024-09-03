/*
 * Copyright 2024 JetBrains s.r.o.
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

package sun.awt.windows;

import sun.awt.AWTAccessor;
import sun.java2d.SunGraphicsEnvironment;

import javax.swing.*;
import javax.swing.event.CaretEvent;
import javax.swing.event.CaretListener;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.im.InputMethodRequests;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.lang.ref.WeakReference;

/**
 * Provides caret tracking support for assistive tools that don't work with Java Access Bridge.
 * Specifically, it's targeted for the built-in Windows Magnifier.
 * This class listens to caret change events of the currently focused JTextComponent
 * and forwards them to the native code, which then sends them as Win32 IAccessible events.
 * <p>
 * A typical high-level scenario of the interaction with the magnifier:
 * <ol>
 * <li>Magnifier sends a WM_GETOBJECT window message to get accessible content of the window.</li>
 * <li>The message is handled in AwtComponent native class (awt_Component.cpp),
 *     which calls {@link #startCaretNotifier}.</li>
 * <li>We start listening for keyboard focus change events.</li>
 * <li>If at some point focus gets to a {@link JTextComponent}, we subscribe to its caret events.</li>
 * <li>When the caret changes, we need to move the magnifier viewport to the new caret location.
 *     To achieve this, we create a Win32 IAccessible object for the caret (see AccessibleCaret.cpp)
 *     and send an event that its location was changed (EVENT_OBJECT_LOCATIONCHANGE).</li>
 * <li>Magnifier receives this event and sends the WM_GETOBJECT message with the OBJID_CARET argument
 *     to get the caret object and its location property. After that, it moves the viewport to the returned location.
 * </li>
 * <li>When the {@link JTextComponent} loses focus, we stop listening to caret events
 *     and release the IAccessible caret object.</li>
 * </ol>
 * </p>
 * <p>
 * The feature is enabled by default
 * and can be toggled by setting the sun.awt.windows.use.native.caret.accessibility.events property.
 * </p>
 */
@SuppressWarnings("unused") // Used from the native side through JNI.
class AccessibleCaretLocationNotifier implements PropertyChangeListener, CaretListener {
    private volatile static AccessibleCaretLocationNotifier caretNotifier;
    private static final boolean nativeCaretEventsEnabled =
            Boolean.parseBoolean(System.getProperty("sun.awt.windows.use.native.caret.accessibility.events", "true"));

    private WeakReference<JTextComponent> currentFocusedComponent;
    private long currentHwnd;

    @SuppressWarnings("unused") // Called from the native through JNI.
    public static void startCaretNotifier(long hwnd) {
        if (nativeCaretEventsEnabled && caretNotifier == null) {
            SwingUtilities.invokeLater(() -> {
                if (caretNotifier == null) {
                    caretNotifier = new AccessibleCaretLocationNotifier(hwnd);
                    KeyboardFocusManager cfm = KeyboardFocusManager.getCurrentKeyboardFocusManager();
                    cfm.addPropertyChangeListener("focusOwner", caretNotifier);
                    if (cfm.getFocusOwner() instanceof JTextComponent textComponent) {
                        caretNotifier.propertyChange(new PropertyChangeEvent(caretNotifier, "focusOwner", null, textComponent));
                    }
                }
            });
        }
    }

    public AccessibleCaretLocationNotifier(long hwnd) {
        currentHwnd = hwnd;
    }

    private static native void updateNativeCaretLocation(long hwnd, int x, int y, int width, int height);
    private static native void releaseNativeCaret(long hwnd);

    @Override
    public void propertyChange(PropertyChangeEvent e) {
        Window w = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow();
        if (w != null) {
            WWindowPeer wp = AWTAccessor.getComponentAccessor().getPeer(w);
            if (wp != null) {
                long hwnd = wp.getHWnd();
                if (currentHwnd != hwnd) {
                    currentHwnd = hwnd;
                }
            }
        }

        Object newFocusedComponent = e.getNewValue();
        if (currentFocusedComponent != null) {
            JTextComponent currentComponentStrong = currentFocusedComponent.get();
            if (currentComponentStrong != null && newFocusedComponent != currentComponentStrong) {
                currentComponentStrong.removeCaretListener(this);
                currentFocusedComponent.clear();
                currentFocusedComponent = null;
                releaseNativeCaret(currentHwnd);
            }
        }

        if (newFocusedComponent instanceof JTextComponent textComponent) {
            currentFocusedComponent = new WeakReference<>(textComponent);
            textComponent.addCaretListener(this);
            // Trigger the caret event when the text component receives focus to notify about the initial caret location
            caretUpdate(new CaretEvent(textComponent) {
                // Dot and mark won't be used, so we can set any values.
                @Override
                public int getDot() { return 0; }

                @Override
                public int getMark() { return 0; }
            });
        }
    }

    @Override
    public void caretUpdate(CaretEvent e) {
        if (!(e.getSource() instanceof JTextComponent textComponent)) {
            return;
        }

        SwingUtilities.invokeLater(() -> {
            if (!textComponent.isShowing()) return;
            InputMethodRequests imr = textComponent.getInputMethodRequests();
            if (imr == null) return;
            Rectangle caretRectangle = imr.getTextLocation(null);
            if (caretRectangle == null) return;
            caretRectangle.width = 1;

            Container parent = textComponent.getParent();
            if (parent != null && parent.isShowing()) {
                // Make sure we don't go outside of parent bounds, which can happen in the case of scrollable components.
                Rectangle parentBounds = parent.getBounds();
                parentBounds.setLocation(parent.getLocationOnScreen());

                if (!parentBounds.contains(caretRectangle)) {
                    caretRectangle = parentBounds.intersection(caretRectangle);
                    if (caretRectangle.isEmpty()) return;
                }
            }

            caretRectangle = SunGraphicsEnvironment.toDeviceSpaceAbs(caretRectangle);

            updateNativeCaretLocation(AccessibleCaretLocationNotifier.this.currentHwnd,
                    (int) caretRectangle.getX(), (int) caretRectangle.getY(),
                    (int) caretRectangle.getWidth(), (int) caretRectangle.getHeight());
        });
    }
}
