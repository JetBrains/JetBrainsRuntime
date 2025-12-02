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

package sun.awt.wl.im.text_input_unstable_v3;

import sun.util.logging.PlatformLogger;

import javax.swing.event.CaretEvent;
import javax.swing.event.CaretListener;
import javax.swing.text.JTextComponent;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.EventQueue;
import java.awt.Rectangle;
import java.awt.TextComponent;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.TextEvent;
import java.awt.event.TextListener;
import java.lang.ref.WeakReference;
import java.util.Objects;


/**
 * This class is intended to track all the cases when a new {@code zwp_text_input_v3::set_cursor_rectangle} request
 * may have to be issued. Here are the examples of such cases:
 * <ul>
 * <li>The caret position has changed ;
 * <li>The component has been moved/resized ;
 * <li>The component's window has been moved/resized ;
 * <li>The component's text has been changed ;
 * </ul>
 */
class ClientComponentCaretPositionTracker implements ComponentListener, CaretListener, TextListener
{
    // See java.text.MessageFormat for the formatting syntax
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.wl.im.text_input_unstable_v3.ClientComponentCaretPositionTracker");


    public ClientComponentCaretPositionTracker(WLInputMethodZwpTextInputV3 im) {
        this.im = new WeakReference<>(Objects.requireNonNull(im, "im"));
    }


    public void startTracking(final Component component) {
        assert EventQueue.isDispatchThread() : "Method must only be invoked on EDT";

        if (log.isLoggable(PlatformLogger.Level.FINER)) {
            log.finer(
                String.format("startTracking(component=%s): im=%s, this=%s.", component, getOwnerIm(), this),
                new Throwable("Stacktrace")
            );
        }

        stopTrackingCurrentComponent();

        if (component == null) {
            return;
        }

        trackedComponent = new WeakReference<>(component);

        lastKnownClientWindowBounds = null;

        try {
            // Moving and changing the size causes a possible change of caret position
            component.addComponentListener(this);

            if (component instanceof JTextComponent jtc) {
                jtc.addCaretListener(this);
                isCaretListenerInstalled = true;
            } else if (component instanceof TextComponent tc) {
                tc.addTextListener(this);
                isTextListenerInstalled = true;
            }

            if (log.isLoggable(PlatformLogger.Level.FINEST)) {
                log.finest("startTracking(...): updated this={0}.", this);
            }
        } catch (Exception err) {
            stopTrackingCurrentComponent();
            throw err;
        }
    }

    public void stopTrackingCurrentComponent() {
        assert EventQueue.isDispatchThread() : "Method must only be invoked on EDT";

        if (log.isLoggable(PlatformLogger.Level.FINER)) {
            log.finer(String.format("stopTrackingCurrentComponent(): this=%s.", this), new Throwable("Stacktrace"));
        }

        final Component trackedComponentStrong = getTrackedComponentIfTracking();
        if (trackedComponentStrong == null) {
            return;
        }

        if (isTextListenerInstalled) {
            isTextListenerInstalled = false;
            try {
                ((TextComponent)trackedComponentStrong).removeTextListener(this);
            } catch (Exception err) {
                if (log.isLoggable(PlatformLogger.Level.WARNING)) {
                    log.warning(String.format("stopTrackingCurrentComponent(): exception occurred while removing the text listener from %s.", trackedComponentStrong), err);
                }
            }
        }

        if (isCaretListenerInstalled) {
            isCaretListenerInstalled = false;
            try {
                ((JTextComponent)trackedComponentStrong).removeCaretListener(this);
            } catch (Exception err) {
                if (log.isLoggable(PlatformLogger.Level.WARNING)) {
                    log.warning(String.format("stopTrackingCurrentComponent(): exception occurred while removing the caret listener from %s.", trackedComponentStrong), err);
                }
            }
        }

        try {
            trackedComponentStrong.removeComponentListener(this);
        } catch (Exception err) {
            if (log.isLoggable(PlatformLogger.Level.WARNING)) {
                log.warning(String.format("stopTrackingCurrentComponent(): exception occurred while removing the component listener from %s.", trackedComponentStrong), err);
            }
        }

        lastKnownClientWindowBounds = null;

        updatesAreDeferred = false;

        if (trackedComponent != null) {
            trackedComponent.clear();
            trackedComponent = null;
        }
    }

    public Component getTrackedComponentIfTracking() {
        assert EventQueue.isDispatchThread() : "Method must only be invoked on EDT";

        final Component trackedComponentStrong;
        if (trackedComponent == null) {
            trackedComponentStrong = null;
        } else {
            trackedComponentStrong = trackedComponent.get();
        }

        if (trackedComponentStrong == null) {
            isTextListenerInstalled = false;
            isCaretListenerInstalled = false;

            lastKnownClientWindowBounds = null;

            updatesAreDeferred = false;

            if (trackedComponent != null) {
                trackedComponent.clear();
                trackedComponent = null;
            }
        }

        return trackedComponentStrong;
    }


    public void deferUpdates() {
        assert EventQueue.isDispatchThread() : "Method must only be invoked on EDT";

        if (log.isLoggable(PlatformLogger.Level.FINER)) {
            log.finer(String.format("deferUpdates(): this=%s.", this), new Throwable("Stacktrace"));
        }

        updatesAreDeferred = true;
    }

    public void resumeUpdates(final boolean discardDeferredUpdates) {
        assert EventQueue.isDispatchThread() : "Method must only be invoked on EDT";

        if (log.isLoggable(PlatformLogger.Level.FINER)) {
            log.finer(String.format("resumeUpdates(%b): this=%s.", discardDeferredUpdates, this), new Throwable("Stacktrace"));
        }

        if (getTrackedComponentIfTracking() == null) return;

        updatesAreDeferred = false;
        hasDeferredUpdates = hasDeferredUpdates && !discardDeferredUpdates;

        if (hasDeferredUpdates) {
            updateNotify();
        }
    }

    public boolean areUpdatesDeferred() {
        assert EventQueue.isDispatchThread() : "Method must only be invoked on EDT";

        return updatesAreDeferred;
    }


    /* Listening callbacks */

    /** This method is intended to be called from the owning IM's {@link java.awt.im.spi.InputMethod#dispatchEvent(AWTEvent)}. */
    public void onIMDispatchEvent(AWTEvent event) {
        assert EventQueue.isDispatchThread() : "Method must only be invoked on EDT";

        if (log.isLoggable(PlatformLogger.Level.FINER)) {
            log.finer("onIMDispatchEvent(event={0}): this={1}.", event, this);
        }

        final int eventId = event.getID();

        if (eventId >= MouseEvent.MOUSE_FIRST && eventId <= MouseEvent.MOUSE_LAST) {
            // MouseEvent or MouseWheelEvent
            if (!isCaretListenerInstalled || eventId == MouseEvent.MOUSE_WHEEL) {
                // We expect no mouse events except MouseWheelEvent can change the physical position of the caret
                //   without changing its logical position inside the document. The logical position is handled by caretUpdate.

                // The event hasn't been handled by the component yet, so the caret position couldn't have been changed yet.
                // Hence, we have to postpone the updating request.
                EventQueue.invokeLater(this::updateNotify);
            }
        }

        if (eventId >= KeyEvent.KEY_FIRST && eventId <= KeyEvent.KEY_LAST) {
            if ( !isCaretListenerInstalled && (!isTextListenerInstalled || eventId != KeyEvent.KEY_TYPED) ) {
                EventQueue.invokeLater(this::updateNotify);
            }
        }
    }

    /** This method is intended to be called from the owning IM's {@link java.awt.im.spi.InputMethod#notifyClientWindowChange(Rectangle)}. */
    public void onIMNotifyClientWindowChange(Rectangle location) {
        assert EventQueue.isDispatchThread() : "Method must only be invoked on EDT";

        if (log.isLoggable(PlatformLogger.Level.FINER)) {
            log.finer("onIMNotifyClientWindowChange(location={0}): this={1}.", location, this);
        }

        if (location != null) {
            // null means the window has become iconified or invisible, so no need to try to update the caret position.

            lastKnownClientWindowBounds = location;

            updateNotify();
        }
    }

    // ComponentListener

    @Override
    public void componentHidden(ComponentEvent e) {}

    @Override
    public void componentMoved(ComponentEvent e) {
        updateNotify();
    }

    @Override
    public void componentResized(ComponentEvent e) {
        updateNotify();
    }

    @Override
    public void componentShown(ComponentEvent e) {
        updateNotify();
    }

    // CaretListener

    @Override
    public void caretUpdate(CaretEvent e) {
        updateNotify();
    }

    // TextListener

    @Override
    public void textValueChanged(TextEvent e) {
        updateNotify();
    }


    /* java.lang.Object methods section */
    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder(1024);
        sb.append("ClientComponentCaretPositionTracker@").append(System.identityHashCode(this));
        sb.append('{');
        sb.append("isCaretListenerInstalled=").append(isCaretListenerInstalled);
        sb.append(", isTextListenerInstalled=").append(isTextListenerInstalled);
        sb.append(", lastKnownClientWindowBounds=").append(lastKnownClientWindowBounds);
        sb.append(", updatesAreDeferred=").append(updatesAreDeferred);
        sb.append(", hasDeferredUpdates=").append(hasDeferredUpdates);
        sb.append(", trackedComponent=").append(trackedComponent == null ? "null" : trackedComponent.get());
        sb.append('}');
        return sb.toString();
    }


    /* Implementation details */

    private final WeakReference<WLInputMethodZwpTextInputV3> im;
    private WeakReference<Component> trackedComponent = null;
    private boolean isCaretListenerInstalled = false;
    private boolean isTextListenerInstalled = false;
    private Rectangle lastKnownClientWindowBounds = null;
    private boolean updatesAreDeferred = false;
    private boolean hasDeferredUpdates = false;


    private WLInputMethodZwpTextInputV3 getOwnerIm() {
        assert EventQueue.isDispatchThread() : "Method must only be invoked on EDT";

        final WLInputMethodZwpTextInputV3 thisImStrong;
        if (this.im == null) {
            thisImStrong = null;
        } else {
            thisImStrong = this.im.get();
        }

        if (thisImStrong == null) {
            stopTrackingCurrentComponent();
        }

        return thisImStrong;
    }

    private void updateNotify() {
        if (log.isLoggable(PlatformLogger.Level.FINEST)) {
            log.finest(String.format("updateNotify(): this=%s.", this), new Throwable("Stacktrace"));
        }

        if (getTrackedComponentIfTracking() == null) return;

        if (updatesAreDeferred) {
            hasDeferredUpdates = true;
            return;
        }
        hasDeferredUpdates = false;

        final var imToNotify = getOwnerIm();
        if (imToNotify != null) {
            imToNotify.wlUpdateCursorRectangle(false);
        }
    }
}

