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

import sun.awt.AWTAccessor;
import sun.awt.SunToolkit;
import sun.awt.im.InputMethodAdapter;
import sun.awt.wl.WLComponentPeer;
import sun.util.logging.PlatformLogger;

import java.awt.AWTEvent;
import java.awt.AWTException;
import java.awt.Component;
import java.awt.EventQueue;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.InputMethodEvent;
import java.awt.font.TextAttribute;
import java.awt.font.TextHitInfo;
import java.awt.im.InputMethodHighlight;
import java.awt.im.spi.InputMethodContext;
import java.lang.ref.WeakReference;
import java.text.AttributedString;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;


final class WLInputMethodZwpTextInputV3 extends InputMethodAdapter {

    // See java.text.MessageFormat for the formatting syntax
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.wl.im.text_input_unstable_v3.WLInputMethodZwpTextInputV3");


    public WLInputMethodZwpTextInputV3() throws AWTException {
        wlInitializeContext();
    }


    /* sun.awt.im.InputMethodAdapter methods section */

    @Override
    protected Component getClientComponent() {
        return super.getClientComponent();
    }

    @Override
    protected boolean haveActiveClient() {
        return super.haveActiveClient();
    }

    @Override
    protected void setAWTFocussedComponent(Component component) {
        // NB: think carefully before utilizing this method - most likely what's really needed is
        //     just getClientComponent.
        //     There's a difference between the current client component and the focussed component -
        //       check sun.awt.im.InputContext#currentClientComponent and #awtFocussedComponent respectively.
        super.setAWTFocussedComponent(component);
    }

    @Override
    protected boolean supportsBelowTheSpot() {
        // It's about whether this IM supports the AWT property "java.awt.im.style" set to "below-the-spot".
        // If we allowed it to take effect, AWT would be drawing its own preediting/composition window.
        // The problem is that we can't ask the system to hide its own composition windows due to lack of such API.
        return false;
    }

    @Override
    protected void stopListening() {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("stopListening(): this={0}.", this);
        }

        this.awtNativeImIsExplicitlyDisabled = true;
        wlDisableContextNow();

        super.stopListening();
    }

    @Override
    public void notifyClientWindowChange(Rectangle location) {
        if (log.isLoggable(PlatformLogger.Level.FINEST)) {
            log.finest("notifyClientWindowChange(location={0}): this={1}.", location, this);
        }

        awtClientComponentCaretPositionTracker.onIMNotifyClientWindowChange(location);
    }

    @Override
    public void reconvert() {
        throw new UnsupportedOperationException("The \"text-input-unstable-v3\" protocol doesn't support manual reconvertions");
    }

    @Override
    public void disableInputMethod() {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("disableInputMethod(): this={0}.", this);
        }

        this.awtNativeImIsExplicitlyDisabled = true;
        wlDisableContextNow();
    }

    @Override
    public String getNativeInputMethodInfo() {
        return wlInputContextState == null ? "" : "zwp_text_input_v3@0x" + Long.toHexString(wlInputContextState.nativeContextPtr);
    }


    /* java.awt.im.spi.InputMethod methods section */

    @Override
    public void setInputMethodContext(InputMethodContext context) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("setInputMethodContext(context={0}): this={1}.", context, this);
        }

        this.awtImContext = context;
    }

    @Override
    public boolean setLocale(Locale locale) {
        // There's no API to switch between keyboard layouts/input methods/etc.,
        //   so here's rather an artificial code pretending to switch between the locales claimed to be supported.
        // In practice it does nothing, but allows keeping the implicit contract between
        //   this method and InputMethodDescriptor#getAvailableLocales:
        //   "[...] the list of locales returned is typically
        //    a subset of the locales for which the corresponding input method's implementation of InputMethod.setLocale
        //    returns true."
        final Locale[] supportedLocales = WLInputMethodDescriptorZwpTextInputV3.getAvailableLocalesInternal();
        if (supportedLocales != null) {
            for (Locale supportedLocale : supportedLocales) {
                if (supportedLocale.equals(locale)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public Locale getLocale() {
        // There's no API for retrieving the current keyboard layout or the language of the input method
        return null;
    }

    @Override
    public void setCharacterSubsets(Character.Subset[] subsets) {
        // The protocol doesn't support limiting the character set.
    }

    @Override
    public void setCompositionEnabled(boolean enable) {
        // CInputMethod does the same
        throw new UnsupportedOperationException("The \"text-input-unstable-v3\" protocol doesn't support adjusting the composition state.");
    }

    @Override
    public boolean isCompositionEnabled() {
        // CInputMethod does the same
        throw new UnsupportedOperationException("The \"text-input-unstable-v3\" protocol doesn't provide information about the current composition state.");
    }

    @Override
    public void dispatchEvent(AWTEvent event) {
        if (log.isLoggable(PlatformLogger.Level.FINER)) {
            log.finer("dispatchEvent(event={0}): this={1}.", event, this);
        }

        awtClientComponentCaretPositionTracker.onIMDispatchEvent(event);
    }

    @Override
    public void activate() {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("activate(): this={0}.", this);
        }

        this.awtActivationStatus = AWTActivationStatus.ACTIVATED;
        this.awtNativeImIsExplicitlyDisabled = false;

        if (getClientComponent() != getPreviousClientComponent()) {
            // The if is required to make sure we don't accidentally lose a still actual preedit string
            this.awtCurrentClientLatestPostedPreeditString = null;
        }

        // It may be wrong to invoke this only if awtActivationStatus was DEACTIVATED.
        // E.g. if there was a call chain [activate -> disableInputMethod -> activate].
        // So let's enable the context here regardless of the previous value of awtActivationStatus.
        if (wlContextHasToBeEnabled() && wlContextCanBeEnabledNow()) {
            wlEnableContextNow();
        }

        this.awtClientComponentCaretPositionTracker.startTracking(getClientComponent());
    }

    @Override
    public void deactivate(boolean isTemporary) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("deactivate(isTemporary={0}): this={1}.", isTemporary, this);
        }

        final boolean wasActive = (this.awtActivationStatus == AWTActivationStatus.ACTIVATED);
        this.awtActivationStatus = isTemporary ? AWTActivationStatus.DEACTIVATED_TEMPORARILY : AWTActivationStatus.DEACTIVATED;
        this.awtPreviousClientComponent = new WeakReference<>(getClientComponent());

        this.awtClientComponentCaretPositionTracker.stopTrackingCurrentComponent();

        if (wasActive) {
            wlDisableContextNow();
        }
    }

    @Override
    public void hideWindows() {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("hideWindows(): this={0}.", this);
        }

        // "The method is only called when the input method is inactive."
        assert this.awtActivationStatus != AWTActivationStatus.ACTIVATED : "The method is called when the IM is active";

        // The protocol doesn't provide a separate method for hiding the IM window(s),
        //   but this effect can be achieved by disabling the native context.
        // Actually, it should have already been disabled (because the IM is inactive),
        //   so disabling here is performed just in case.
        wlDisableContextNow();
    }

    @Override
    public void removeNotify() {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("removeNotify(): this={0}.", this);
        }

        // "The method is only called when the input method is inactive."
        assert this.awtActivationStatus != AWTActivationStatus.ACTIVATED : "The method is called when the IM is active";

        wlDisableContextNow();
    }

    @Override
    public void endComposition() {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("endComposition(): this={0}.", this);
        }

        // Deleting the current preedit text
        awtPostIMESafely(JavaPreeditString.EMPTY, JavaCommitString.EMPTY);

        // Ending the composition on the native IM's side: the protocol doesn't provide a separate method for this,
        //   but we can achieve this by disabling + enabling back the native context.

        wlDisableContextNow();
        if (wlContextHasToBeEnabled() && wlContextCanBeEnabledNow()) {
            wlEnableContextNow();
        }
    }

    @Override
    public void dispose() {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("dispose(): this={0}.", this);
        }

        awtActivationStatus = AWTActivationStatus.DEACTIVATED;
        awtNativeImIsExplicitlyDisabled = false;
        awtCurrentClientLatestPostedPreeditString = null;
        awtPreviousClientComponent = null;
        awtClientComponentCaretPositionTracker.stopTrackingCurrentComponent();
        wlDisposeContext();
    }

    @Override
    public Object getControlObject() {
        return null;
    }


    /* java.lang.Object methods section */

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder(1024);
        sb.append("WLInputMethodZwpTextInputV3@").append(System.identityHashCode(this));
        sb.append('{');
        sb.append("awtActivationStatus=").append(awtActivationStatus);
        sb.append(", awtCurrentClientLatestPostedPreeditString=").append(awtCurrentClientLatestPostedPreeditString);
        sb.append(", wlInputContextState=").append(wlInputContextState);
        sb.append(", wlPendingChanges=").append(wlPendingChanges);
        sb.append(", wlBeingCommittedChanges=").append(wlBeingCommittedChanges);
        sb.append(", wlIncomingChanges=").append(wlIncomingChanges);
        sb.append('}');
        return sb.toString();
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
    /**
     * {@link #awtPostIMESafely(JavaPreeditString, JavaCommitString)}.
     * {@code null} if no preedit strings have been posted since latest {@link #activate}.
     */
    private JavaPreeditString awtCurrentClientLatestPostedPreeditString = null;
    /**
     * Holds the value of {@link #getClientComponent()} since the latest {@link #deactivate(boolean)}
     * It allows to determine whether the focused component has actually changed between the latest deactivate-activate.
     * Use {@link #getPreviousClientComponent()} to read the field.
     */
    private WeakReference<Component> awtPreviousClientComponent = null;
    private final ClientComponentCaretPositionTracker awtClientComponentCaretPositionTracker = new ClientComponentCaretPositionTracker(this);


    /* AWT-side methods section */

    private static void awtFillWlContentTypeOf(Component component, OutgoingChanges out) {
        assert component != null : "Component must not be null";
        assert out != null : "OutgoingChanges must not be null";

        assert EventQueue.isDispatchThread() : "Method must only be invoked on EDT";

        // TODO: there's no dedicated AWT/Swing API for that, but we can make a few guesses, e.g.
        //       (component instanceof JPasswordField) ? ContentPurpose.PASSWORD
        out.setContentType(ContentHint.NONE.intMask, ContentPurpose.NORMAL);
    }

    /**
     *  @return the location of the caret inside {@code component} in the format
     *          compatible with {@code zwp_text_input_v3::set_cursor_rectangle} API;
     */
    private static Rectangle awtGetWlCursorRectangleOf(Component component) {
        assert EventQueue.isDispatchThread() : "Method must only be invoked on EDT";

        Rectangle result = null;

        if (component.isShowing()) {
            Rectangle caretOnComponent = awtGetCaretOf(component);
            if (caretOnComponent == null) {
                // Fallback: the component's left-bottom corner
                var componentVisibleBounds = awtGetVisibleRectOf(component);
                if (componentVisibleBounds == null) {
                    caretOnComponent = new Rectangle(0, component.getHeight() - 1, 1, 1);
                } else {
                    caretOnComponent = new Rectangle(
                        componentVisibleBounds.x,
                        componentVisibleBounds.y + componentVisibleBounds.height - 1,
                        1,
                        1
                    );
                }
            }

            if (log.isLoggable(PlatformLogger.Level.FINEST)) {
                log.finest("awtGetWlCursorRectangleOf(component={0}): caretOnComponent={1}.", component, caretOnComponent);
            }

            result = awtConvertRectOnComponentToRectOnWlSurface(component, caretOnComponent);
        }

        if (result == null) {
            result = new Rectangle(0, 0, 1, 1);
        }

        if (log.isLoggable(PlatformLogger.Level.FINER)) {
            log.finer("awtGetWlCursorRectangleOf(component={0}): result={1}.", component, result);
        }

        return result;
    }

    /**
     * @return the location of the caret inside {@code visibleComponent} in the latter's coordinate system;
     *         or {@code null} if the location couldn't be determined.
     * @throws IllegalArgumentException if {@code visibleComponent} is {@code null} or isn't showing on the screen.
     */
    private static Rectangle awtGetCaretOf(Component visibleComponent) {
        assert EventQueue.isDispatchThread() : "Method must only be invoked on EDT";

        if (!Objects.requireNonNull(visibleComponent, "visibleComponent").isShowing()) {
            throw new IllegalArgumentException("visibleComponent must be showing");
        }

        // Trying the standard API for obtaining the location of the cursor
        final var imr = visibleComponent.getInputMethodRequests();
        if (imr != null) {
            final Rectangle caretOnScreen = imr.getTextLocation(null);
            if (caretOnScreen != null) {
                final Point caretPos = caretOnScreen.getLocation();
                javax.swing.SwingUtilities.convertPointFromScreen(caretPos, visibleComponent);

                // Clamping within the component's visible bounds
                Rectangle componentVisibleBounds = awtGetVisibleRectOf(visibleComponent);
                if (componentVisibleBounds == null) {
                    componentVisibleBounds = new Rectangle(0, 0, visibleComponent.getWidth(), visibleComponent.getHeight());
                }

                caretOnScreen.width = Math.max(1, Math.min(caretOnScreen.width, componentVisibleBounds.width));
                caretOnScreen.height = Math.max(1, Math.min(caretOnScreen.height, componentVisibleBounds.height));

                caretPos.x = Math.max(
                    componentVisibleBounds.x - caretOnScreen.width,
                    Math.min(caretPos.x + caretOnScreen.width, componentVisibleBounds.x + componentVisibleBounds.width) - caretOnScreen.width
                );
                caretPos.y = Math.max(
                    componentVisibleBounds.y - caretOnScreen.height,
                    Math.min(caretPos.y + caretOnScreen.height, componentVisibleBounds.y + componentVisibleBounds.height) - caretOnScreen.height
                );

                return new Rectangle(caretPos.x, caretPos.y, caretOnScreen.width, caretOnScreen.height);
            }
        }

        return null;
    }

    private static Rectangle awtGetVisibleRectOf(final Component component) {
        assert EventQueue.isDispatchThread() : "Method must only be invoked on EDT";

        if (component instanceof javax.swing.JComponent jComponent) {
            return jComponent.getVisibleRect();
        }
        return null;
    }

    /**
     * @return a rectangle equal to {@code rectOnComponent} but in the coordinate system of
     *         a {@code wl_surface} containing {@code component};
     *         or {@code null} if the rectangle couldn't be determined.
     */
    private static Rectangle awtConvertRectOnComponentToRectOnWlSurface(Component component, Rectangle rectOnComponent) {
        assert EventQueue.isDispatchThread() : "Method must only be invoked on EDT";

        Objects.requireNonNull(component, "component");

        final var wlSurfaceComponent = awtGetWlSurfaceComponentOf(component);
        if (wlSurfaceComponent == null) {
            assert !component.isShowing() : "Failed to find a peered parent for a component being shown";
            if (log.isLoggable(PlatformLogger.Level.WARNING)) {
                log.warning("awtConvertRectOnComponentToRectOnWlSurface: failed to find a peered parent for {0}.", component);
            }
            return null;
        }
        final var wlSurfaceComponentPeer = AWTAccessor.getComponentAccessor().getPeer(wlSurfaceComponent);
        if ( !(wlSurfaceComponentPeer instanceof WLComponentPeer wlSurface) ) {
            assert false : String.format("A peered component has a peer of an unexpected type: [%s]. The component: [%s]", wlSurfaceComponentPeer, wlSurfaceComponent);
            if (log.isLoggable(PlatformLogger.Level.WARNING)) {
                log.warning("awtConvertRectOnComponentToRectOnWlSurface: a peered component has a peer of an unexpected type: [{0}]. The component: [{1}].", wlSurfaceComponentPeer, wlSurfaceComponent);
            }
            return null;
        }

        final var componentRelPos = WLComponentPeer.getRelativeLocation(component, wlSurfaceComponent);
        if (componentRelPos == null) {
            assert false : String.format("Failed to determine the relative position of [%s] inside its peered ancestor [%s]", component, wlSurfaceComponent);
            if (log.isLoggable(PlatformLogger.Level.WARNING)) {
                log.warning("awtConvertRectOnComponentToRectOnWlSurface: failed to determine the relative position of [{0}] inside its peered ancestor [{1}].", component, wlSurfaceComponent);
            }
            return null;
        }

        final var result = rectOnComponent.getBounds();
        // AWT-like coordinates on 'component' to AWT-like coordinates on the wl_surface.
        result.translate(componentRelPos.x, componentRelPos.y);

        // AWT-like coordinates on the wl_surface to wl_surface-like coordinates on the wl_surface.
        result.x = wlSurface.javaUnitsToSurfaceUnits(result.x);
        result.y = wlSurface.javaUnitsToSurfaceUnits(result.y);
        result.width = wlSurface.javaUnitsToSurfaceSize(result.width);
        result.height = wlSurface.javaUnitsToSurfaceSize(result.height);

        return result;
    }

    /**
     * @return {@code component} if it's a heavyweight component peered by a {@code wl_surface};
     *         or its closest ancestor meeting these requirements.
     */
    private static Window awtGetWlSurfaceComponentOf(Component component) {
        assert EventQueue.isDispatchThread() : "Method must only be invoked on EDT";

        return WLComponentPeer.getToplevelFor(component);
    }


    private Component getPreviousClientComponent() {
        return awtPreviousClientComponent == null ? null : awtPreviousClientComponent.get();
    }


    // Although we wouldn't break any AWT/Swing rules,
    //   we can't dispatch InputMethodEvents synchronously instead of posting them to the event queue.
    // This is because IntelliJ can't properly handle InputEvents that go around the event queue directly to a component.
    // See https://youtrack.jetbrains.com/issue/IJPL-212367 for more info.
    /** @return {@code true} if a new InputMethodEvent has been successfully made and posted, {@code false} otherwise. */
    private boolean awtPostIMESafely(JavaPreeditString preeditString, JavaCommitString commitString) {
        if (log.isLoggable(PlatformLogger.Level.FINER)) {
            log.finer("awtPostIMESafely(preeditString={0}, commitString={1}): this={2}.", preeditString, commitString, this);
        }

        assert preeditString != null : "Pre-edit string must be present";
        assert commitString != null : "Commit string must be present";

        try {
            if (awtActivationStatus != AWTActivationStatus.ACTIVATED) {
                // Supposing an input method shouldn't interact with UI when not activated.

                if (log.isLoggable(PlatformLogger.Level.FINE)) {
                    log.fine("awtPostIMESafely(preeditString={0}, commitString={1}): ignoring an attempt to post a new InputMethodEvent from an inactive InputMethod={2}.",
                             preeditString, commitString, this);
                }

                return false;
            }

            final var clientComponent = getClientComponent();
            if (clientComponent == null) {
                // Nowhere to dispatch.

                if (log.isLoggable(PlatformLogger.Level.WARNING)) {
                    log.warning(
                        "awtPostIMESafely(preeditString={0}, commitString={1}): can''t post a new InputMethodEvent because there''s no client component to dispatch to, although this InputMethod is active. this={2}.",
                        preeditString, commitString, this
                    );
                }

                return false;
            }

            // Check out https://docs.oracle.com/javase/8/docs/technotes/guides/imf/spec.html#client-somponents
            //   for the info about "active" and "passive" clients.
            final var haveActiveClient = clientComponent.getInputMethodRequests() != null;

            // Don't dispatch preedit (a.k.a. composed) text to passive clients.
            //
            // The general AWT's IM subsystem creates and shows its own separate window to display
            //   preedit text if it tries to dispatch to a passive client.
            // This is done on the assumption that passive clients can't properly handle and/or display preedit text.
            // See sun.awt.im.InputMethodContext#dispatchInputMethodEvent for more info.
            //
            // In our case, when such a window appears, it steals the focus from the system IM popup, thus immediately cancelling
            //   the preediting session (at least on Wayland iBus).
            //   Luckily, AWT doesn't display the preediting window if the dispatched preedit text
            //   is empty (i.e. like there's nothing to display in that window).
            //
            // The other potential way to fix this might be to somehow fix sun.awt.im.CompositionArea so that it doesn't
            //   steal the focus when appears, but even if that worked, it could break smth on other platforms.
            if (!haveActiveClient) {
                if (preeditString.cursorBeginCodeUnit() == -1 && preeditString.cursorEndCodeUnit() == -1) {
                    preeditString = JavaPreeditString.EMPTY_NO_CARET;
                } else {
                    preeditString = JavaPreeditString.EMPTY;
                }
            }

            final var clientCurrentPreeditText = Objects.requireNonNullElse(this.awtCurrentClientLatestPostedPreeditString, JavaPreeditString.EMPTY);
            if ( commitString.equals(JavaCommitString.EMPTY) &&
                 preeditString.equals(JavaPreeditString.EMPTY) &&
                 clientCurrentPreeditText.equals(JavaPreeditString.EMPTY) )
            {
                // We suppose it doesn't really make sense to send an InputMethodEvent with no text if
                //   the current client component has no preedit text.
                // Moreover, it makes IDEA to show the quick search fields whenever the parent components get focused
                //   (they're supposed to be shown only when the parent components receive some non-empty text).
                //   It's purely a bug on IDEA side, but it seems to be easier to work around it here.
                //   A similar issue: https://youtrack.jetbrains.com/issue/IJPL-148817 .
                // NB: we shouldn't extend this logic to the cases when clientCurrentPreeditText is not empty
                //     because in those cases the differences may lie not only in the preedit text, but also in the caret position,
                //     text attributes and god knows what else.

                if (log.isLoggable(PlatformLogger.Level.FINE)) {
                    log.fine("awtPostIMESafely(preeditString={0}, commitString={1}): ignoring a redundant attempt to post a new empty InputMethodEvent to a component with no preedit text. this={2}.",
                             preeditString, commitString, this);
                }

                return false;
            }

            // Theoretically, we might be interested in the following attributes:
            //   * java.text.AttributedCharacterIterator.Attribute#LANGUAGE             (the language of the text)
            //   * java.text.AttributedCharacterIterator.Attribute#INPUT_METHOD_SEGMENT (the words inside the text)
            //   * java.text.AttributedCharacterIterator.Attribute#READING              (how to properly read the text)
            //   * java.awt.font.TextAttribute#INPUT_METHOD_HIGHLIGHT                   (whether and how to highlight the text)
            //   * java.awt.font.TextAttribute#INPUT_METHOD_UNDERLINE                   (whether and how to underline the text)
            //
            // But the protocol doesn't provide sufficient information to support anything but the last 2 ones.
            final var imeText = new AttributedString(commitString.text() + preeditString.text());
            final TextHitInfo imeCaret;
            if (preeditString.cursorBeginCodeUnit() == -1 && preeditString.cursorEndCodeUnit() == -1) {
                // "The parameters cursor_begin and cursor_end are counted in bytes relative to the beginning of the submitted text buffer.
                //  Cursor should be hidden when both are equal to -1."
                imeCaret = null;

                // Only basic highlighting
                awtInstallIMHighlightingInto(imeText, commitString.text().length(), preeditString.text().length(), 0, 0);
            } else {
                final int highlightBeginCodeUnitIndex =
                    Math.max(0, Math.min(preeditString.cursorBeginCodeUnit(), preeditString.text().length()));

                final int highlightEndCodeUnitIndex =
                    Math.max(0, Math.min(preeditString.cursorEndCodeUnit(), preeditString.text().length()));

                // Mutter doesn't seem to send preedit_string events with highlighting
                //   (i.e. they never have cursor_begin != cursor_end) at all.
                // KWin, however, always uses highlighting. Looking at how it changes when we navigate within the
                //   preedit text with arrow keys, it becomes clear KWin expects the caret to be put at the end
                //   of the highlighting and not at the beginning.
                // That's why highlightEndCodeUnitIndex is used here and not highlightBeginCodeUnitIndex.
                imeCaret = TextHitInfo.beforeOffset(highlightEndCodeUnitIndex);

                // cursor_begin and cursor_end
                //  "could be represented by the client as a line if both values are the same,
                //   or as a text highlight otherwise"
                if (highlightEndCodeUnitIndex == highlightBeginCodeUnitIndex) {
                    // Only basic highlighting
                    awtInstallIMHighlightingInto(imeText, commitString.text().length(), preeditString.text().length(), 0, 0);
                } else {
                    // Basic + the special highlighting mentioned above
                    awtInstallIMHighlightingInto(
                        imeText,
                        commitString.text().length(),
                        preeditString.text().length(),
                        highlightBeginCodeUnitIndex, highlightEndCodeUnitIndex
                    );
                }
            }

            final InputMethodEvent ime = new InputMethodEvent(
                clientComponent,
                InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
                imeText.getIterator(),
                commitString.text().length(),
                imeCaret,
                // no recommendation for a visible position within the current preedit text
                null
            );

            if (log.isLoggable(PlatformLogger.Level.FINEST)) {
                log.finest(
                    String.format("awtPostIMESafely(...): posting a new InputMethodEvent=%s. this=%s.", ime, this),
                    new Throwable("Stacktrace")
                );

                // JBR-9719: reset ime's text iterator after logging
                final var textIterToReset = ime.getText();
                if (textIterToReset != null) textIterToReset.first();
            }

            SunToolkit.postEvent(SunToolkit.targetToAppContext(clientComponent), ime);
            this.awtCurrentClientLatestPostedPreeditString = preeditString;

            return true;
        } catch (Exception err) {
            log.severe("Error occurred during constructing or posting a new InputMethodEvent.", err);
        }

        return false;
    }

    private void awtInstallIMHighlightingInto(
        final AttributedString imText,
        final int preeditTextBegin,
        final int preeditTextLength,
        int specialPreeditHighlightingBegin,
        int specialPreeditHighlightingEnd
    ) {
        if (preeditTextLength < 1) {
            return;
        }

        // NB: about InputMethodHighlight.*_RAW_TEXT_HIGHLIGHT vs InputMethodHighlight.*_CONVERTED_TEXT_HIGHLIGHT.
        //     The documentation says that CONVERTED should be used to highlight
        //     already converted text (e.g. hanzi in case of pinyin->hanzi conversion),
        //     and RAW for text before the conversion gets applied (e.g. pinyin in case of pinyin->hanzi conversion).
        //
        //     There's a problem: whether a preedit string is converted or not depends on the used IM engine.
        //     For example, the Windows's and macOS's system IM engines send non-converted preedit text.
        //     On Linuxes there are many engines: iBus, fcitx5, SCIM, etc:
        //       * IBus sends converted preedit text (and changes it if the user chooses a different conversion's candidate).
        //       * fcitx5 sends non-converted preedit text (i.e. behaves like Windows and macOS).
        //     We don't know if the preedit text is converted or not (the protocol doesn't provide such information),
        //       so we can't decide in general which highlighting style to use to look more native and consistent with
        //       other apps in the system.
        //
        //     TODO: a possible partial solution could be trying to determine what IM engine is being used
        //           (e.g. via environment variables).
        //     For now we're adjusting to iBus, because it seems to be the most widespread engine.
        final InputMethodHighlight IM_BASIC_HIGHLIGHTING = InputMethodHighlight.UNSELECTED_CONVERTED_TEXT_HIGHLIGHT;
        // I'm not sure if the "text highlight" mentioned in zwp_text_input_v3::preedit_string means the text
        //   should look selected.
        final InputMethodHighlight IM_SPECIAL_HIGHLIGHTING = InputMethodHighlight.SELECTED_CONVERTED_TEXT_HIGHLIGHT;

        if (specialPreeditHighlightingBegin == specialPreeditHighlightingEnd) {
            // Only basic highlighting.

            imText.addAttribute(
               TextAttribute.INPUT_METHOD_HIGHLIGHT,
               IM_BASIC_HIGHLIGHTING,
               preeditTextBegin, preeditTextBegin + preeditTextLength
            );

            return;
        }

        // Basic + special highlighting.

        if (specialPreeditHighlightingEnd < specialPreeditHighlightingBegin) {
            final var swapTemp = specialPreeditHighlightingEnd;
            specialPreeditHighlightingEnd = specialPreeditHighlightingBegin;
            specialPreeditHighlightingBegin = swapTemp;
        }

        assert specialPreeditHighlightingBegin >= 0 : "specialPreeditHighlightingBegin is invalid";
        assert specialPreeditHighlightingEnd <= preeditTextLength : "specialPreeditHighlightingEnd is out of range";

        //    v
        // |BASIC+SPECIAL...|
        //    ^
        if (specialPreeditHighlightingBegin > 0) {
            imText.addAttribute(
                TextAttribute.INPUT_METHOD_HIGHLIGHT,
                IM_BASIC_HIGHLIGHTING,
                preeditTextBegin,
                preeditTextBegin + specialPreeditHighlightingBegin
            );
        }

        // |...SPECIAL...|
        imText.addAttribute(
            TextAttribute.INPUT_METHOD_HIGHLIGHT,
            IM_SPECIAL_HIGHLIGHTING,
            preeditTextBegin + specialPreeditHighlightingBegin,
            preeditTextBegin + specialPreeditHighlightingEnd
        );

        //               v
        // |...SPECIAL+BASIC|
        //               ^
        if (specialPreeditHighlightingEnd < preeditTextLength) {
            imText.addAttribute(
                TextAttribute.INPUT_METHOD_HIGHLIGHT,
                IM_BASIC_HIGHLIGHTING,
                preeditTextBegin + specialPreeditHighlightingEnd,
                preeditTextBegin + preeditTextLength
            );
        }
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
    /** Changes that have been sent by the compositor but not yet applied on our side (because a zwp_text_input_v3::done event hasn't been received yet). */
    private IncomingChanges wlIncomingChanges = null;


    /* Wayland-side methods section */

    // The methods in this section implement the core logic of working with the "text-input-unstable-v3" protocol.

    private void wlInitializeContext() throws AWTException {
        assert wlInputContextState == null : "Must not initialize input context twice";
        assert wlPendingChanges == null : "Must not initialize pending changes twice";
        assert wlBeingCommittedChanges == null : "Must not initialize being-committed changes twice";
        assert wlIncomingChanges == null : "Must not initialize incoming changes twice";

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
        wlIncomingChanges = null;

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
        assert wlCanSendChangesNow() : "Must be able to send pending changes now";

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
        if (log.isLoggable(PlatformLogger.Level.FINER)) {
            log.finer("wlScheduleContextNewChanges(): scheduling the new change set {0}. this={1}.", newOutgoingChanges, this);
        }

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
        if (log.isLoggable(PlatformLogger.Level.FINER)) {
            log.finer(String.format("wlEnableContextNow(): this=%s.", this), new Throwable("Stacktrace"));
        }

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

        assert wlContextCanBeEnabledNow() : "Can't enable InputContext";

        // This way we guarantee the context won't accidentally get disabled because such a change has been scheduled earlier.
        // Anyway we consider any previously scheduled changes outdated because an 'enable' request is supposed to
        //   reset the state of the input context.
        wlPendingChanges = null;

        if (wlInputContextState.isEnabled()) {
            if (wlBeingCommittedChanges == null) {
                // We can skip sending a new 'enable' request only if there's currently nothing being committed.
                // This way we can guarantee the context won't accidentally get disabled afterward or
                //   be keeping outdated state.

                if (log.isLoggable(PlatformLogger.Level.FINE)) {
                    log.fine("wlEnableContextNow(): ignoring an attempt to enable an already enabled input context. this={0}.", this);
                }

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
        assert wlPendingChanges != null : "Must have non-empty pending changes";

        // Pretending there are no committed, but not applied yet changes, so that wlCanSendChangesNow() is true.
        // We can do that because the assumption #1 and because any previously committed changes get lost when a
        // 'enable' request is committed:
        //   "This request resets all state associated with previous enable, disable,
        //    set_surrounding_text, set_text_change_cause, set_content_type, and set_cursor_rectangle requests [...]"
        wlBeingCommittedChanges = null;

        assert wlCanSendChangesNow() : "Must be able to send pending changes now";
        wlSendPendingChangesNow();

        // See the assumption #2 above.
        wlSyncWithAppliedOutgoingChanges();
    }

    private void wlDisableContextNow() {
        if (log.isLoggable(PlatformLogger.Level.FINER)) {
            log.finer(String.format("wlDisableContextNow(): this=%s.", this), new Throwable("Stacktrace"));
        }

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
                    log.warning("wlDisableContextNow(): the input context is marked as enabled although it''s not focused on any surface. Explicitly marking it as disabled. wlInputContextState={0}.", wlInputContextState);
                }
                wlHandleContextGotDisabled();
            }

            if (log.isLoggable(PlatformLogger.Level.FINE)) {
                log.fine("wlDisableContextNow(): ignoring an attempt to enable an input context that isn''t focused on any wl_surface. this={0}.", this);
            }

            return;
        }

        if (!wlInputContextState.isEnabled()) {
            if (wlBeingCommittedChanges == null) {
                // We can skip sending a new 'disable' request only if there's currently nothing being committed.
                // This way we can guarantee the context won't accidentally get enabled afterward as a result of
                //   those changes' processing.

                if (log.isLoggable(PlatformLogger.Level.FINE)) {
                    log.fine("wlDisableContextNow(): ignoring an attempt to disable an already disabled input context. this={0}.", this);
                }

                return;
            }
        }

        assert wlInputContextState.getCurrentWlSurfacePtr() != 0 : "InputContext must have a valid current surface pointer";

        wlScheduleContextNewChanges(new OutgoingChanges().setEnabledState(false));
        assert wlPendingChanges != null : "Must have non-empty pending changes";

        // Pretending there are no committed, but not applied yet changes, so that wlCanSendChangesNow() is true.
        // We can do that because the assumption #1 and because any previously committed changes get lost when a
        // 'disable' request is committed:
        //   "After an enter event or disable request all state information is invalidated and needs to be resent by the client."
        wlBeingCommittedChanges = null;

        assert wlCanSendChangesNow() : "Must be able to send pending changes now";
        wlSendPendingChangesNow();

        // See the assumption #2 above.
        wlSyncWithAppliedOutgoingChanges();
    }

    private void wlHandleContextGotDisabled() {
        if (log.isLoggable(PlatformLogger.Level.FINER)) {
            log.finer("wlHandleContextGotDisabled(): this={0}.", this);
        }

        wlInputContextState.setEnabledState(null);

        try {
            // Erasing preedit text from the client component
            if (awtActivationStatus == AWTActivationStatus.ACTIVATED) {
                final boolean preeditIsEmpty =
                    (awtCurrentClientLatestPostedPreeditString == null)
                    || awtCurrentClientLatestPostedPreeditString.text().isEmpty();
                if (!preeditIsEmpty) {
                    awtPostIMESafely(JavaPreeditString.EMPTY, JavaCommitString.EMPTY);
                }
            }
        } catch (Exception err) {
            if (log.isLoggable(PlatformLogger.Level.WARNING)) {
                log.warning("wlHandleContextGotDisabled().", err);
            }
        }
    }


    private void wlSyncWithAppliedOutgoingChanges() {
        if (log.isLoggable(PlatformLogger.Level.FINER)) {
            log.finer("wlSyncWithAppliedOutgoingChanges(): this={0}.", this);
        }

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

        if (log.isLoggable(PlatformLogger.Level.FINEST)) {
            log.finest("wlSyncWithAppliedOutgoingChanges(): updated this={0}.", this);
        }
    }

    private IncomingChanges wlGetIncomingChanges() {
        if (wlIncomingChanges == null) {
            wlIncomingChanges = new IncomingChanges();
        }
        return wlIncomingChanges;
    }

    /** Called by {@link ClientComponentCaretPositionTracker} */
    boolean wlUpdateCursorRectangle(final boolean forceUpdate) {
        assert EventQueue.isDispatchThread() : "Method must only be invoked on EDT";

        if (log.isLoggable(PlatformLogger.Level.FINER)) {
            log.finer("wlUpdateCursorRectangle(): forceUpdate={0}, this={1}.", forceUpdate, this);
        }

        if (wlInputContextState.getCurrentWlSurfacePtr() == 0) {
            return false;
        }
        final var contextStateOfEnabled = wlInputContextState.getCurrentStateOfEnabled();
        if (contextStateOfEnabled == null) {
            // Equal to !wlInputContextState.isEnabled()
            return false;
        }

        final var currentClient = getClientComponent();
        if (currentClient == null) {
            return false;
        }

        final Rectangle newCursorRectangle;
        try {
            newCursorRectangle = awtGetWlCursorRectangleOf(currentClient);
        } catch (Exception err) {
            if (log.isLoggable(PlatformLogger.Level.WARNING)) {
                log.warning(String.format("Failed to obtain the caret position inside %s.", currentClient), err);
            }
            return false;
        }

        final boolean newCursorRectangleDiffers =
            (wlBeingCommittedChanges != null && wlBeingCommittedChanges.changeSet().getCursorRectangle() != null)
            ? !wlBeingCommittedChanges.changeSet().getCursorRectangle().equals(newCursorRectangle)
            : !Objects.equals(contextStateOfEnabled.cursorRectangle(), newCursorRectangle);

        if (log.isLoggable(PlatformLogger.Level.FINEST)) {
            log.finest("wlUpdateCursorRectangle(): newCursorRectangle={0}, newCursorRectangleDiffers={1}.", newCursorRectangle, newCursorRectangleDiffers);
        }

        if ( newCursorRectangle != null && (forceUpdate || newCursorRectangleDiffers) ) {
            wlScheduleContextNewChanges(new OutgoingChanges().setCursorRectangle(newCursorRectangle));
            if (wlCanSendChangesNow()) {
                wlSendPendingChangesNow();
            }

            return true;
        }

        // Dismiss the outdated data.
        if (wlPendingChanges != null && wlPendingChanges.getCursorRectangle() != null) {
            wlPendingChanges.setCursorRectangle(null);
            if (wlPendingChanges.isEmpty()) {
                wlPendingChanges = null;
            }
        }

        return false;
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
        assert EventQueue.isDispatchThread() : "Method must only be invoked on EDT";

        try {
            if (log.isLoggable(PlatformLogger.Level.FINE)) {
                log.fine("zwp_text_input_v3_onEnter(enteredWlSurfacePtr=0x{0}): this={1}.", Long.toHexString(enteredWlSurfacePtr), this);
            }

            // a native context can't be in the enabled state while not focused on any surface
            assert(!wlInputContextState.isEnabled());

            wlInputContextState.setCurrentWlSurfacePtr(enteredWlSurfacePtr);

            if (wlContextHasToBeEnabled() && wlContextCanBeEnabledNow()) {
                wlEnableContextNow();
            }
        } catch (Exception err) {
            log.severe("Failed to handle a zwp_text_input_v3::enter event (enteredWlSurfacePtr=0x" + Long.toHexString(enteredWlSurfacePtr) + ").", err);
        }
    }

    /** Called in response to {@code zwp_text_input_v3::leave} events. */
    private void zwp_text_input_v3_onLeave(long leftWlSurfacePtr) {
        assert EventQueue.isDispatchThread() : "Method must only be invoked on EDT";

        try {
            if (log.isLoggable(PlatformLogger.Level.FINE)) {
                log.fine("zwp_text_input_v3_onLeave(leftWlSurfacePtr=0x{0}). this={1}.", Long.toHexString(leftWlSurfacePtr), this);
            }

            if (wlInputContextState.getCurrentWlSurfacePtr() != leftWlSurfacePtr) {
                if (log.isLoggable(PlatformLogger.Level.WARNING)) {
                    log.warning("zwp_text_input_v3_onLeave: leftWlSurfacePtr==0x{0} isn''t equal to the currently known one 0x{1}.",
                                Long.toHexString(leftWlSurfacePtr), Long.toHexString(wlInputContextState.getCurrentWlSurfacePtr()));
                }
            }

            wlInputContextState.setCurrentWlSurfacePtr(0);
            wlHandleContextGotDisabled();
        } catch (Exception err) {
            log.severe("Failed to handle a zwp_text_input_v3::leave event (leftWlSurfacePtr=0x" + Long.toHexString(leftWlSurfacePtr) + ").", err);
        }
    }

    /** Called in response to {@code zwp_text_input_v3::preedit_string} events. */
    private void zwp_text_input_v3_onPreeditString(byte[] preeditStrUtf8, int cursorBeginUtf8Byte, int cursorEndUtf8Byte) {
        assert EventQueue.isDispatchThread() : "Method must only be invoked on EDT";

        try {
            if (log.isLoggable(PlatformLogger.Level.FINE)) {
                log.fine("zwp_text_input_v3_onPreeditString(cursorBeginUtf8Byte={0}, cursorEndUtf8Byte={1}, preeditStrUtf8={2}): this={3}.",
                         cursorBeginUtf8Byte, cursorEndUtf8Byte, Arrays.toString(preeditStrUtf8), this);
            }

            wlGetIncomingChanges().updatePreeditString(preeditStrUtf8, cursorBeginUtf8Byte, cursorEndUtf8Byte);

            if (log.isLoggable(PlatformLogger.Level.FINEST)) {
                log.finest("zwp_text_input_v3_onPreeditString(...): this.wlIncomingChanges={0}.", this.wlIncomingChanges);
            }
        } catch (Exception err) {
            log.severe("Failed to handle a zwp_text_input_v3::preedit_string event.", err);
        }
    }

    /** Called in response to {@code zwp_text_input_v3::commit_string} events. */
    private void zwp_text_input_v3_onCommitString(byte[] commitStrUtf8) {
        assert EventQueue.isDispatchThread() : "Method must only be invoked on EDT";

        try {
            if (log.isLoggable(PlatformLogger.Level.FINE)) {
                log.fine("zwp_text_input_v3_onCommitString(commitStrUtf8={0}): this={1}.",
                         Arrays.toString(commitStrUtf8), this);
            }

            wlGetIncomingChanges().updateCommitString(commitStrUtf8);

            if (log.isLoggable(PlatformLogger.Level.FINEST)) {
                log.finest("zwp_text_input_v3_onCommitString(...): this.wlIncomingChanges={0}.", this.wlIncomingChanges);
            }
        } catch (Exception err) {
            log.severe("Failed to handle a zwp_text_input_v3::commit_string event.", err);
        }
    }

    /** Called in response to {@code zwp_text_input_v3::delete_surrounding_text} events. */
    private void zwp_text_input_v3_onDeleteSurroundingText(long numberOfUtf8BytesBeforeToDelete, long numberOfUtf8BytesAfterToDelete) {
        assert EventQueue.isDispatchThread() : "Method must only be invoked on EDT";

        // TODO: support the surrounding text API (set_surrounding_text + set_text_change_cause | delete_surrounding text)
        //       at least for particular cases.
        //       What capabilities are required from the component:
        //         - (set_surrounding_text) track the caret's virtual position
        //         - (set_surrounding_text) track all text changes in the current component
        //         - (set_surrounding_text) retrieve the caret's virtual position
        //         - (set_surrounding_text) retrieve the component's text around the caret
        //         - (delete_surrounding_text) delete text at specific indices
        // We're currently not supporting the set_surronding_text / delete_surrounding_text API.
        // The main reason behind that is the lack of a suitable AWT/Swing API.
        // We expect the compositor never sends us such an event because we never send a set_surrounding_text request,
        //   this way notifying the compositor we don't support this API:
        // "The initial state for affected fields is empty, meaning that the text input does not support sending surrounding text."
    }

    /** Called in response to {@code zwp_text_input_v3::done} events. */
    private void zwp_text_input_v3_onDone(long doneSerial) {
        assert EventQueue.isDispatchThread() : "Method must only be invoked on EDT";

        try {
            if (log.isLoggable(PlatformLogger.Level.FINE)) {
                log.fine("zwp_text_input_v3_onDone(doneSerial={0}): this={1}.", doneSerial, this);
            }

            final var incomingChangesToApply = this.wlIncomingChanges;
            this.wlIncomingChanges = null;

            if (doneSerial == wlInputContextState.getCommitCounter()) {
                wlSyncWithAppliedOutgoingChanges();
            }

            // Processing preedit_string and commit_string events

            // "Values set with this event [...]
            //  must be applied and reset to initial on the next zwp_text_input_v3.done event.
            //  The initial value of text is an empty string, and cursor_begin, cursor_end and cursor_hidden are all 0."
            final JavaPreeditString preeditStringToApply;
            // "Values set with this event [...]
            //  must be applied and reset to initial on the next zwp_text_input_v3.done event.
            //  The initial value of text is an empty string."
            final JavaCommitString commitStringToApply;

            if (incomingChangesToApply == null) {
                preeditStringToApply = PropertiesInitials.PREEDIT_STRING;
                commitStringToApply = PropertiesInitials.COMMIT_STRING;
            } else {
                preeditStringToApply = Objects.requireNonNullElse(incomingChangesToApply.getPreeditString(), PropertiesInitials.PREEDIT_STRING);
                commitStringToApply = Objects.requireNonNullElse(incomingChangesToApply.getCommitString(), PropertiesInitials.COMMIT_STRING);
            }

            this.wlInputContextState.syncWithAppliedIncomingChanges(preeditStringToApply, commitStringToApply, doneSerial);

            // The deferring is needed to break the following infinite loop:
            //   +------------------------------------------------+
            //   |...                                             |
            //   |                                                | 8
            //   |zwp_text_input_v3::preedit_text(<the-same-data>)<----------+
            //   |                                                |          |
            //   |zwp_text_input_v3::done(...)                    |          |
            //   +-------+----------------------------------------+          |
            //          1|                                                   |
            //           v                                           +-------+------------------------------------+
            //    awtPostIMESafely(...)                              |zwp_text_input_v3::set_cursor_rectangle(...)|
            //          2|                                           |                                            |
            //           v                                           |zwp_text_input_v3::commit()                 |
            //    the component deletes its current composed text    +-------^------------------------------------+
            //           |                                                   |
            //          3|                                                   |
            //   +-------v---------------------------------------+ 4         |
            //   |the component fires a new caret position update|----------->
            //   +-------+---------------------------------------+           |
            //          5|                                                   |
            //           v                                                   |
            //    the component inserts the new composed text                |
            //           |            (equal to the previous)                |
            //          6|                                                   |
            //   +-------v---------------------------------------+ 7         |
            //   |the component fires a new caret position update|-----------+
            //   +-----------------------------------------------+
            final boolean doDeferCaretPositionUpdates = !this.awtClientComponentCaretPositionTracker.areUpdatesDeferred();

            if (log.isLoggable(PlatformLogger.Level.FINEST)) {
                log.finest("zwp_text_input_v3_onDone(...): doDeferCaretPositionUpdates={0}, preeditStringToApply={1}, commitStringToApply={2}.",
                           doDeferCaretPositionUpdates, preeditStringToApply, commitStringToApply);
            }

            if (doDeferCaretPositionUpdates) {
                this.awtClientComponentCaretPositionTracker.deferUpdates();
            }

            // From the zwp_text_input_v3::done event specification:
            //   "The application must proceed by evaluating the changes in the following order:
            //      1. Replace existing preedit string with the cursor.
            //      2. Delete requested surrounding text.
            //      3. Insert commit string with the cursor at its end.
            //      4. Calculate surrounding text to send.
            //      5. Insert new preedit text in cursor position.
            //      6. Place cursor inside preedit text."
            //
            // Steps 2, 4 are currently not supported (see zwp_text_input_v3_onDeleteSurroundingText for more info),
            //   and all the other steps seem feasible via just a single properly constructed InputMethodEvent.
            //
            // TODO: unconditional dispatching of the text makes it impossible for the caret to leave the component's
            //       visible area, which would be useful for scrolling.
            awtPostIMESafely(preeditStringToApply, commitStringToApply);

            if (doDeferCaretPositionUpdates) {
                // invokeLater is needed to handle a case when the client component decides to post the caret updates
                //   to the EventQueue instead of dispatching them right away (IDK if it happens in practice).
                // Nested invokeLater is needed because the IME event hasn't been dispatched yet (only posted).
                // This way we guarantee that all UI activities associated with it are finished before resuming.
                EventQueue.invokeLater(() -> EventQueue.invokeLater(() -> {
                    try {
                        this.awtClientComponentCaretPositionTracker.resumeUpdates(false);
                    } catch (Exception err) {
                        log.severe(
                            String.format("zwp_text_input_v3_onDone(doneSerial=%d): ClientComponentCaretPositionTracker.resumeUpdates() failed. this=%s.",
                                          doneSerial, this),
                            err
                        );
                    }
                }));
            }

            // Sending pending changes (if any)

            if (wlContextHasToBeEnabled() && wlContextCanBeEnabledNow()) {
                wlEnableContextNow();
            }
            if (wlPendingChanges != null && wlInputContextState.getCurrentWlSurfacePtr() != 0 && wlCanSendChangesNow()) {
                wlSendPendingChangesNow();
            }
        } catch (Exception err) {
            log.severe("Failed to handle a zwp_text_input_v3::done event (doneSerial=" + doneSerial + ").", err);
        }
    }
}
