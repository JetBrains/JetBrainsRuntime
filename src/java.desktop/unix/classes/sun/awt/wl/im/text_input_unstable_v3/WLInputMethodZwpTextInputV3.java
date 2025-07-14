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
import java.util.Objects;


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
        this.awtNativeImIsExplicitlyDisabled = true;
        wlDisableContextNow();

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
        this.awtNativeImIsExplicitlyDisabled = true;
        wlDisableContextNow();
    }

    @Override
    public String getNativeInputMethodInfo() {
        // TODO: implement
        return "";
    }


    /* java.awt.im.spi.InputMethod methods section */

    @Override
    public void setInputMethodContext(InputMethodContext context) {
        this.awtImContext = context;
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
        this.awtActivationStatus = AWTActivationStatus.ACTIVATED;
        this.awtNativeImIsExplicitlyDisabled = false;

        // It may be wrong to invoke this only if awtActivationStatus was DEACTIVATED.
        // E.g. if there was a call chain [activate -> disableInputMethod -> activate].
        // So let's enable the context here regardless of the previous value of awtActivationStatus.
        if (wlContextHasToBeEnabled() && wlContextCanBeEnabledNow()) {
            wlEnableContextNow();
        }
    }

    @Override
    public void deactivate(boolean isTemporary) {
        final boolean wasActive = (this.awtActivationStatus == AWTActivationStatus.ACTIVATED);
        this.awtActivationStatus = isTemporary ? AWTActivationStatus.DEACTIVATED_TEMPORARILY : AWTActivationStatus.DEACTIVATED;

        if (wasActive) {
            wlDisableContextNow();
        }
    }

    @Override
    public void hideWindows() {
        // TODO: implement
    }

    @Override
    public void removeNotify() {
        // "The method is only called when the input method is inactive."
        assert(this.awtActivationStatus != AWTActivationStatus.ACTIVATED);

        wlDisableContextNow();
    }

    @Override
    public void endComposition() {
        // TODO: implement
    }

    @Override
    public void dispose() {
        awtActivationStatus = AWTActivationStatus.DEACTIVATED;
        awtNativeImIsExplicitlyDisabled = false;
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


    /* AWT-side state section */

    // The fields in this section are prefixed with "awt" and aren't supposed to be modified by
    //   Wayland-related methods (whose names are prefixed with "wl" or "zwp_text_input_v3_"),
    //   though can be read by them.

    private enum AWTActivationStatus {
        ACTIVATED,               // #activate()
        DEACTIVATED,             // #deactivate(false)
        DEACTIVATED_TEMPORARILY  // #deactivate(true)
    }

    /** {@link #activate()} / {@link #deactivate(boolean)} */
    private AWTActivationStatus awtActivationStatus = AWTActivationStatus.DEACTIVATED;
    /** {@link #stopListening()}, {@link #disableInputMethod()} / {@link #activate()} */
    private boolean awtNativeImIsExplicitlyDisabled = false;
    /** {@link #setInputMethodContext(InputMethodContext)} */
    private InputMethodContext awtImContext = null;


    /* AWT-side methods section */

    private static void awtFillWlContentTypeOf(Component component, OutgoingChanges out) {
        assert(component != null);
        assert(out != null);

        assert(EventQueue.isDispatchThread());

        // TODO: there's no dedicated AWT/Swing API for that, but we can make a few guesses, e.g.
        //       (component instanceof JPasswordField) ? ContentPurpose.PASSWORD
        out.setContentType(ContentHint.NONE.intMask, ContentPurpose.NORMAL);
    }

    private static Rectangle awtGetWlCursorRectangleOf(Component component) {
        assert(component != null);

        assert(EventQueue.isDispatchThread());

        // TODO: real implementation
        return new Rectangle(0, 0, 1, 1);
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
                if (this.awtActivationStatus != AWTActivationStatus.ACTIVATED) {
                    throw new IllegalStateException("Attempt to enable an input context while the owning WLInputMethodZwpTextInputV3 is not active. WLInputMethodZwpTextInputV3.awtActivationStatus=" + this.awtActivationStatus);
                }
                if (this.awtNativeImIsExplicitlyDisabled) {
                    throw new IllegalStateException("Attempt to enable an input context while it must stay disabled.");
                }
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


    private boolean wlContextHasToBeEnabled() {
        return awtActivationStatus == AWTActivationStatus.ACTIVATED &&
               !awtNativeImIsExplicitlyDisabled &&
               !wlInputContextState.isEnabled();
    }

    private boolean wlContextCanBeEnabledNow() {
        return awtActivationStatus == AWTActivationStatus.ACTIVATED &&
               !awtNativeImIsExplicitlyDisabled &&
               wlInputContextState.getCurrentWlSurfacePtr() != 0;
    }

    private void wlEnableContextNow() {
        // The method's implementation is based on the following assumptions:
        //   1. Enabling an input context from the "text-input-unstable-v3" protocol's point of view can be done at any moment,
        //      even when there are committed changes, which the compositor hasn't applied yet,
        //      i.e. even when (this.wlBeingCommittedChanges != null).
        //      The protocol specification doesn't seem to contradict this assumption, and otherwise it would significantly
        //      complicate the machinery of scheduling changes in general and enabling, disabling routines in particular.
        //   2. Committed 'enable' request comes into effect immediately and doesn't hinder any following requests to be sent
        //      right after, even though a corresponding 'done' event hasn't been received.
        //      This assumption has been made because the protocol doesn't specify whether compositors should
        //      respond to committed 'enable' requests with a 'done' event, and, in practice,
        //      Mutter responds with a 'done' event while KWin - doesn't.
        //      The corresponding ticket: https://gitlab.freedesktop.org/wayland/wayland-protocols/-/issues/250.

        if (awtActivationStatus != AWTActivationStatus.ACTIVATED) {
            throw new IllegalStateException("Attempt to enable an input context while the owning InputMethod is not active. InputMethod=" + this);
        }
        if (awtNativeImIsExplicitlyDisabled) {
            throw new IllegalStateException("Attempt to enable an input context while it must stay disabled");
        }
        if (wlInputContextState.getCurrentWlSurfacePtr() == 0) {
            throw new IllegalStateException("Attempt to enable an input context which hasn't entered any surface");
        }

        assert(wlContextCanBeEnabledNow());

        // This way we guarantee the context won't accidentally get disabled because such a change has been scheduled earlier.
        // Anyway we consider any previously scheduled changes outdated because an 'enable' request is supposed to
        //   reset the state of the input context.
        wlPendingChanges = null;

        if (wlInputContextState.isEnabled()) {
            if (wlBeingCommittedChanges == null) {
                // We can skip sending a new 'enable' request only if there's currently nothing being committed.
                // This way we can guarantee the context won't accidentally get disabled afterward or
                //   be keeping outdated state.

                return;
            }
        }

        final var changeSet =
            new OutgoingChanges()
              .setEnabledState(true)
              // Just to signal the compositor we're supporting set_text_change_cause API
              .setTextChangeCause(PropertiesInitials.TEXT_CHANGE_CAUSE)
              // It's really important not to send null for the cursor rectangle
              .setCursorRectangle(Objects.requireNonNull(awtGetWlCursorRectangleOf(getClientComponent()),
                                                         "awtGetWlCursorRectangleOf(getClientComponent())"));
        awtFillWlContentTypeOf(getClientComponent(), changeSet);

        wlScheduleContextNewChanges(changeSet);
        assert(wlPendingChanges != null);

        // Pretending there are no committed, but not applied yet changes, so that wlCanSendChangesNow() is true.
        // We can do that because the assumption #1 and because any previously committed changes get lost when a
        // 'enable' request is committed:
        //   "This request resets all state associated with previous enable, disable,
        //    set_surrounding_text, set_text_change_cause, set_content_type, and set_cursor_rectangle requests [...]"
        wlBeingCommittedChanges = null;

        assert(wlCanSendChangesNow());
        wlSendPendingChangesNow();

        // See the assumption #2 above.
        wlSyncWithAppliedOutgoingChanges();
    }

    private void wlDisableContextNow() {
        // The method's implementation is based on the following assumptions:
        //   1. Disabling an input context from the "text-input-unstable-v3" protocol's point of view can be done at any moment,
        //      even when there are committed changes, which the compositor hasn't applied yet,
        //      i.e. even when (this.wlBeingCommittedChanges != null).
        //      The protocol specification doesn't seem to contradict this assumption, and otherwise it would significantly
        //      complicate the machinery of scheduling changes in general and enabling, disabling routines in particular.
        //   2. Committed 'disable' request comes into effect immediately and doesn't hinder any following requests to be sent
        //      right after, even though a corresponding 'done' event hasn't been received.
        //      This assumption has been made because the protocol doesn't specify whether compositors should
        //      respond to committed 'disable' requests with a 'done' event, and, in practice, neither Mutter nor KWin do that.
        //      The corresponding ticket: https://gitlab.freedesktop.org/wayland/wayland-protocols/-/issues/250.

        // This way we guarantee the context won't accidentally get enabled because such a change has been scheduled earlier.
        // Anyway we consider any previously scheduled changes outdated because a 'disable' request is supposed to
        //   reset the state of the input context.
        wlPendingChanges = null;

        if (wlInputContextState.getCurrentWlSurfacePtr() == 0) {
            // In this case it doesn't make sense to send any requests:
            //   "After leave event, compositor must ignore requests from any text input instances until next enter event."
            // The context is supposed to have been automatically implicitly disabled.

            // Any being committed changes are meaningless, so we can safely "forget" about them.
            wlBeingCommittedChanges = null;

            if (wlInputContextState.isEnabled()) {
                if (log.isLoggable(PlatformLogger.Level.WARNING)) {
                    log.warning("wlDisableContextNow(): the input context is marked as enabled although it's not focused on any surface. Explicitly marking it as disabled. wlInputContextState={0}", wlInputContextState);
                }
                wlHandleContextGotDisabled();
            }

            return;
        }

        if (!wlInputContextState.isEnabled()) {
            if (wlBeingCommittedChanges == null) {
                // We can skip sending a new 'disable' request only if there's currently nothing being committed.
                // This way we can guarantee the context won't accidentally get enabled afterward as a result of
                //   those changes' processing.

                return;
            }
        }

        assert(wlInputContextState.getCurrentWlSurfacePtr() != 0);

        wlScheduleContextNewChanges(new OutgoingChanges().setEnabledState(false));
        assert(wlPendingChanges != null);

        // Pretending there are no committed, but not applied yet changes, so that wlCanSendChangesNow() is true.
        // We can do that because the assumption #1 and because any previously committed changes get lost when a
        // 'disable' request is committed:
        //   "After an enter event or disable request all state information is invalidated and needs to be resent by the client."
        wlBeingCommittedChanges = null;

        assert(wlCanSendChangesNow());
        wlSendPendingChangesNow();

        // See the assumption #2 above.
        wlSyncWithAppliedOutgoingChanges();
    }

    private void wlHandleContextGotDisabled() {
        wlInputContextState.setEnabledState(null);

        try {
            // TODO: delete or commit the current preedit text in the current client component
        } catch (Exception err) {
            if (log.isLoggable(PlatformLogger.Level.WARNING)) {
                log.warning("wlHandleContextGotDisabled", err);
            }
        }
    }


    private void wlSyncWithAppliedOutgoingChanges() {
        final var changesToSyncWith = wlBeingCommittedChanges;
        wlBeingCommittedChanges = null;

        if (changesToSyncWith == null) {
            return;
        }

        if (Boolean.FALSE.equals(changesToSyncWith.changeSet().getEnabledState())) {
            wlHandleContextGotDisabled();
        } else if (Boolean.TRUE.equals(changesToSyncWith.changeSet().getEnabledState())) {
            // 'enable' request
            // "resets all state associated with previous enable, disable,
            //  set_surrounding_text, set_text_change_cause, set_content_type, and set_cursor_rectangle requests [...]"
            // So here we just convert the changeSet to a new StateOfEnabled and apply it.

            wlInputContextState.setEnabledState(new InputContextState.StateOfEnabled(
                Objects.requireNonNullElse(changesToSyncWith.changeSet().getTextChangeCause(), PropertiesInitials.TEXT_CHANGE_CAUSE),
                Objects.requireNonNullElse(changesToSyncWith.changeSet().getContentTypeHint(), PropertiesInitials.CONTENT_HINT),
                Objects.requireNonNullElse(changesToSyncWith.changeSet().getContentTypePurpose(), PropertiesInitials.CONTENT_PURPOSE),
                changesToSyncWith.changeSet().getCursorRectangle() == null ? PropertiesInitials.CURSOR_RECTANGLE : changesToSyncWith.changeSet().getCursorRectangle()
            ));
        } else if (wlInputContextState.isEnabled()) {
            // The changes are only supposed to update the current StateOfEnabled

            final var currentStateOfEnabled = wlInputContextState.getCurrentStateOfEnabled();

            wlInputContextState.setEnabledState(new InputContextState.StateOfEnabled(
                // "The value set with this request [...] must be applied and reset to initial at the next zwp_text_input_v3.commit request."
                Objects.requireNonNullElse(changesToSyncWith.changeSet().getTextChangeCause(), PropertiesInitials.TEXT_CHANGE_CAUSE),

                // "Values set with this request [...] will get applied on the next zwp_text_input_v3.commit request.
                //  Subsequent attempts to update them may have no effect."
                currentStateOfEnabled.contentHint(),
                currentStateOfEnabled.contentPurpose(),

                // "Values set with this request [...] will get applied on the next zwp_text_input_v3.commit request,
                //  and stay valid until the next committed enable or disable request."
                changesToSyncWith.changeSet().getCursorRectangle() == null ? currentStateOfEnabled.cursorRectangle() : changesToSyncWith.changeSet().getCursorRectangle()
            ));
        }
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

        try {
            if (log.isLoggable(PlatformLogger.Level.FINE)) {
                log.fine("zwp_text_input_v3_onEnter(enteredWlSurfacePtr={0}): this={1}.", enteredWlSurfacePtr, this);
            }

            // a native context can't be in the enabled state while not focused on any surface
            assert(!wlInputContextState.isEnabled());

            wlInputContextState.setCurrentWlSurfacePtr(enteredWlSurfacePtr);

            if (wlContextHasToBeEnabled() && wlContextCanBeEnabledNow()) {
                wlEnableContextNow();
            }
        } catch (Exception err) {
            log.severe("Failed to handle a zwp_text_input_v3::enter event (enteredWlSurfacePtr=" + enteredWlSurfacePtr + ").", err);
        }
    }

    /** Called in response to {@code zwp_text_input_v3::leave} events. */
    private void zwp_text_input_v3_onLeave(long leftWlSurfacePtr) {
        assert EventQueue.isDispatchThread();

        try {
            if (log.isLoggable(PlatformLogger.Level.FINE)) {
                log.fine("zwp_text_input_v3_onLeave(leftWlSurfacePtr={0}). this={1}.", leftWlSurfacePtr, this);
            }

            if (wlInputContextState.getCurrentWlSurfacePtr() != leftWlSurfacePtr) {
                if (log.isLoggable(PlatformLogger.Level.WARNING)) {
                    log.warning("zwp_text_input_v3_onLeave: leftWlSurfacePtr=={0} isn't equal to the currently known one {1}.",
                                leftWlSurfacePtr, wlInputContextState.getCurrentWlSurfacePtr());
                }
            }

            wlInputContextState.setCurrentWlSurfacePtr(0);
            wlHandleContextGotDisabled();
        } catch (Exception err) {
            log.severe("Failed to handle a zwp_text_input_v3::leave event (leftWlSurfacePtr=" + leftWlSurfacePtr + ").", err);
        }
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

        if (wlContextHasToBeEnabled() && wlContextCanBeEnabledNow()) {
            wlEnableContextNow();
        }
        if (wlPendingChanges != null && wlInputContextState.getCurrentWlSurfacePtr() != 0 && wlCanSendChangesNow()) {
            wlSendPendingChangesNow();
        }
    }
}
