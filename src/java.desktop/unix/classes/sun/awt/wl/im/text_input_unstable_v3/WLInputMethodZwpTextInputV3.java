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

import sun.awt.im.InputMethodAdapter;
import sun.util.logging.PlatformLogger;

import java.awt.*;
import java.awt.im.spi.InputMethodContext;
import java.util.Locale;


final class WLInputMethodZwpTextInputV3 extends InputMethodAdapter {

    // TODO: add logging everywhere
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.wl.im.text_input_unstable_v3.WLInputMethodZwpTextInputV3");


    public WLInputMethodZwpTextInputV3() throws AWTException {
        wlInitializeContext();
    }


    /* sun.awt.im.InputMethodAdapter methods section */

    @Override
    protected Component getClientComponent() {
        // TODO: implement
        return super.getClientComponent();
    }

    @Override
    protected boolean haveActiveClient() {
        // TODO: implement
        return super.haveActiveClient();
    }

    @Override
    protected void setAWTFocussedComponent(Component component) {
        // TODO: implement
        super.setAWTFocussedComponent(component);
    }

    @Override
    protected boolean supportsBelowTheSpot() {
        // TODO: implement
        return super.supportsBelowTheSpot();
    }

    @Override
    protected void stopListening() {
        // TODO: implement
        super.stopListening();
    }

    @Override
    public void notifyClientWindowChange(Rectangle location) {
        // TODO: implement
        super.notifyClientWindowChange(location);
    }

    @Override
    public void reconvert() {
        // TODO: implement
        super.reconvert();
    }

    @Override
    public void disableInputMethod() {
        // TODO: implement
    }

    @Override
    public String getNativeInputMethodInfo() {
        // TODO: implement
        return "";
    }


    /* java.awt.im.spi.InputMethod methods section */

    @Override
    public void setInputMethodContext(InputMethodContext context) {
        // TODO: implement
    }

    @Override
    public boolean setLocale(Locale locale) {
        // TODO: implement
        return false;
    }

    @Override
    public Locale getLocale() {
        // TODO: implement
        return null;
    }

    @Override
    public void setCharacterSubsets(Character.Subset[] subsets) {
        // TODO: implement
    }

    @Override
    public void setCompositionEnabled(boolean enable) {
        // TODO: implement
    }

    @Override
    public boolean isCompositionEnabled() {
        // TODO: implement
        return false;
    }

    @Override
    public void dispatchEvent(AWTEvent event) {
        // TODO: implement
    }

    @Override
    public void activate() {
        // TODO: implement
    }

    @Override
    public void deactivate(boolean isTemporary) {
        // TODO: implement
    }

    @Override
    public void hideWindows() {
        // TODO: implement
    }

    @Override
    public void removeNotify() {
        // TODO: implement
    }

    @Override
    public void endComposition() {
        // TODO: implement
    }

    @Override
    public void dispose() {
        wlDisposeContext();
    }

    @Override
    public Object getControlObject() {
        // TODO: implement
        return null;
    }


    /* java.lang.Object methods section */

    @SuppressWarnings("removal")
    @Override
    protected void finalize() throws Throwable {
        dispose();
        super.finalize();
    }

    @Override
    public String toString() {
        // TODO: implement
        return super.toString();
    }


    /* Implementation details section */

    // Since WLToolkit dispatches (almost) all native Wayland events on EDT, not on its thread,
    //   there's no need for this class to think about multithreading issues - all of its parts may only be executed
    //   on EDT.
    // If WLToolkit dispatched native Wayland events on its thread {@link sun.awt.wl.WLToolkit#isToolkitThread},
    //   this class would require the following modifications:
    //     - Guarding access to the fields with some synchronization primitives
    //     - Taking into account that zwp_text_input_v3_on* callbacks may even be called when the constructor doesn't
    //       even return yet (in the current implementation)
    //     - Reworking the implementation of {@link #disposeNativeContext(long)} so that it prevents
    //       use-after-free access errors to the destroyed native context from the native handlers of
    //       zwp_text_input_v3 native events.

    static {
        initIDs();
    }


    /* Wayland-side state section */

    // The fields in this section are prefixed with "wl" and aren't supposed to be modified by
    //   non-Wayland-related methods (whose names are prefixed with "wl" or "zwp_text_input_v3_"),
    //   though can be read by them.

    /** The reference must only be (directly) modified in {@link #wlInitializeContext()} and {@link #wlDisposeContext()}. */
    private InputContextState wlInputContextState = null;
    /** Accumulates changes to be sent. {@code null} means there are no changes to send yet. */
    private OutgoingChanges wlPendingChanges = null;
    /** Changes that have been committed but not yet applied by the compositor. {@code null} means there are no such changes at the moment. */
    private OutgoingBeingCommittedChanges wlBeingCommittedChanges = null;


    /* Wayland-side methods section */

    // The methods in this section implement the core logic of working with the "text-input-unstable-v3" protocol.

    private void wlInitializeContext() throws AWTException {
        assert(wlInputContextState == null);
        assert(wlPendingChanges == null);
        assert(wlBeingCommittedChanges == null);

        long nativeCtxPtr = 0;

        try {
            nativeCtxPtr = createNativeContext();
            if (nativeCtxPtr == 0) {
                throw new AWTException("nativeCtxPtr == 0");
            }

            wlInputContextState = new InputContextState(nativeCtxPtr);
        } catch (Throwable err) {
            if (nativeCtxPtr != 0) {
                disposeNativeContext(nativeCtxPtr);
                nativeCtxPtr = 0;
            }

            throw err;
        }
    }

    private void wlDisposeContext() {
        final var ctxToDispose = this.wlInputContextState;

        wlInputContextState = null;
        wlPendingChanges = null;
        wlBeingCommittedChanges = null;

        if (ctxToDispose != null && ctxToDispose.nativeContextPtr != 0) {
            disposeNativeContext(ctxToDispose.nativeContextPtr);
        }
    }

    /**
     * This method determines whether any of the pending state changes can be sent and committed.
     */
    private boolean wlCanSendChangesNow() {
        return wlInputContextState != null &&
               wlInputContextState.nativeContextPtr != 0 &&
               wlBeingCommittedChanges == null;
    }

    /**
     * Transforms the pending set of changes into a series of corresponding zwp_text_input_v3 requests
     *   followed by a {@code zwp_text_input_v3::commit} request.
     */
    private void wlSendPendingChangesNow() {
        assert(wlCanSendChangesNow());

        final OutgoingChanges changesToSend = wlPendingChanges;
        wlPendingChanges = null;

        if (wlInputContextState.getCurrentWlSurfacePtr() == 0) {
            // "After leave event, compositor must ignore requests from any text input instances until next enter event."
            // Thus, it doesn't make sense to send any requests, let's drop the change set.

            if (log.isLoggable(PlatformLogger.Level.FINE)) {
                log.fine("wlSendPendingChangesNow(): wlInputContextState.getCurrentWlSurfacePtr()=0. Dropping the change set {0}. this={1}.", changesToSend, this);
            }

            return;
        }

        // TODO: make sure the current AWT component belongs to the surface wlInputContextState.getCurrentWlSurfacePtr()

        if (log.isLoggable(PlatformLogger.Level.FINER)) {
            log.finer("wlSendPendingChangesNow(): sending the change set {0}. this={1}.", changesToSend, this);
        }

        if (changesToSend != null) {
            if (Boolean.TRUE.equals(changesToSend.getEnabledState())) {
                // TODO: check whether this WLInputMethod is actually activated
                zwp_text_input_v3_enable(wlInputContextState.nativeContextPtr);
            }

            if (changesToSend.getTextChangeCause() != null) {
                zwp_text_input_v3_set_text_change_cause(wlInputContextState.nativeContextPtr,
                                                        changesToSend.getTextChangeCause().intValue);
            }

            if (changesToSend.getContentTypeHint() != null && changesToSend.getContentTypePurpose() != null) {
                zwp_text_input_v3_set_content_type(wlInputContextState.nativeContextPtr,
                                                   changesToSend.getContentTypeHint(),
                                                   changesToSend.getContentTypePurpose().intValue);
            }

            if (changesToSend.getCursorRectangle() != null) {
                zwp_text_input_v3_set_cursor_rectangle(wlInputContextState.nativeContextPtr,
                                                       changesToSend.getCursorRectangle().x,
                                                       changesToSend.getCursorRectangle().y,
                                                       changesToSend.getCursorRectangle().width,
                                                       changesToSend.getCursorRectangle().height);
            }

            if (Boolean.FALSE.equals(changesToSend.getEnabledState())) {
                zwp_text_input_v3_disable(wlInputContextState.nativeContextPtr);
            }
        }

        zwp_text_input_v3_commit(wlInputContextState.nativeContextPtr);

        wlBeingCommittedChanges = wlInputContextState.syncWithCommittedOutgoingChanges(changesToSend);
    }

    /**
     * Schedules a new set of changes for sending (but doesn't send them).
     *
     * @see #wlSendPendingChangesNow()
     * @see #wlCanSendChangesNow()
     */
    private void wlScheduleContextNewChanges(final OutgoingChanges newOutgoingChanges) {
        if (newOutgoingChanges == null) {
            return;
        }

        this.wlPendingChanges = newOutgoingChanges.appendChangesFrom(this.wlPendingChanges);
    }


    /* JNI downcalls section */

    /** Initializes all static JNI references ({@code jclass}, {@code jmethodID}, etc.) required by this class for functioning. */
    private static native void initIDs();

    /** @return pointer to the newly created native context associated with {@code this}. */
    private native long createNativeContext() throws AWTException;
    /** Disposes the native context created previously via {@link #createNativeContext()}. */
    private static native void disposeNativeContext(long contextPtr);

    /*private native void zwp_text_input_v3_destroy(long contextPtr);*/ // No use-cases for this currently
    private native void zwp_text_input_v3_enable(long contextPtr);
    private native void zwp_text_input_v3_disable(long contextPtr);
    /*private native void zwp_text_input_v3_set_surrounding_text();*/   // Not supported currently
    private native void zwp_text_input_v3_set_cursor_rectangle(long contextPtr, int surfaceLocalX, int surfaceLocalY, int width, int height);
    private native void zwp_text_input_v3_set_content_type(long contextPtr, int hint, int purpose);
    private native void zwp_text_input_v3_set_text_change_cause(long contextPtr, int changeCause);
    private native void zwp_text_input_v3_commit(long contextPtr);


    /* JNI upcalls section */

    /** Called in response to {@code zwp_text_input_v3::enter} events. */
    private void zwp_text_input_v3_onEnter(long enteredWlSurfacePtr) {
        assert EventQueue.isDispatchThread();
    }

    /** Called in response to {@code zwp_text_input_v3::leave} events. */
    private void zwp_text_input_v3_onLeave(long leftWlSurfacePtr) {
        assert EventQueue.isDispatchThread();
    }

    /** Called in response to {@code zwp_text_input_v3::preedit_string} events. */
    private void zwp_text_input_v3_onPreeditString(byte[] preeditStrUtf8, int cursorBeginUtf8Byte, int cursorEndUtf8Byte) {
        assert EventQueue.isDispatchThread();
    }

    /** Called in response to {@code zwp_text_input_v3::commit_string} events. */
    private void zwp_text_input_v3_onCommitString(byte[] commitStrUtf8) {
        assert EventQueue.isDispatchThread();
    }

    /** Called in response to {@code zwp_text_input_v3::delete_surrounding_text} events. */
    private void zwp_text_input_v3_onDeleteSurroundingText(long numberOfUtf8BytesBeforeToDelete, long numberOfUtf8BytesAfterToDelete) {
        assert EventQueue.isDispatchThread();
    }

    /** Called in response to {@code zwp_text_input_v3::done} events. */
    private void zwp_text_input_v3_onDone(long doneSerial) {
        assert EventQueue.isDispatchThread();

        if (wlPendingChanges != null && wlInputContextState.getCurrentWlSurfacePtr() != 0 && wlCanSendChangesNow()) {
            wlSendPendingChangesNow();
        }
    }
}
