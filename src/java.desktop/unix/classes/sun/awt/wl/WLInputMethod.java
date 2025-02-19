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

package sun.awt.wl;

import sun.awt.im.InputMethodAdapter;
import sun.util.logging.PlatformLogger;

import java.awt.*;
import java.awt.im.spi.InputMethodContext;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import javax.swing.Timer;

public final class WLInputMethod extends InputMethodAdapter {

    // TODO: add logging everywhere
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.wl.WLInputMethod");


    public WLInputMethod() throws AWTException {
        wlInitializeContext();

        assert(!isInstanceBroken());
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
        super.setAWTFocussedComponent(component);
    }

    @Override
    protected boolean supportsBelowTheSpot() {
        return super.supportsBelowTheSpot();
    }

    @Override
    protected void stopListening() {
        if (isInstanceBroken()) {
            logIgnoredCallOnBrokenInstance("stopListening");
            return;
        }

        super.stopListening();

        // Judging by how {@link sun.awt.im.InputContext} works with this method,
        //   the context has to be disabled right now, not asynchronously later,
        //   therefore {@link #wlDisableContextNowOrLater()} can not be used here.
        wlDisableContextNowOrReinitialize();
    }

    @Override
    public void notifyClientWindowChange(Rectangle location) {
        if (isInstanceBroken()) {
            logIgnoredCallOnBrokenInstance("notifyClientWindowChange", location);
            return;
        }

        super.notifyClientWindowChange(location);
    }

    @Override
    public void reconvert() {
        super.reconvert();
    }

    @Override
    public void disableInputMethod() {
        if (isInstanceBroken()) {
            logIgnoredCallOnBrokenInstance("disableInputMethod");
            return;
        }

        // This method seems to be never used in the codebase and can't be called by client's code because
        //   it's initially declared in a private package, so we can leave it empty, but just in case let's
        //   disable the context via the safer way.
        wlDisableContextNowOrLater();
    }

    @Override
    public String getNativeInputMethodInfo() {
        return "";
    }


    /* java.awt.im.spi.InputMethod methods section */

    @Override
    public void setInputMethodContext(InputMethodContext context) {
        this.awtImContext = context;
    }

    @Override
    public boolean setLocale(Locale locale) {
        return false;
    }

    @Override
    public Locale getLocale() {
        return null;
    }

    @Override
    public void setCharacterSubsets(Character.Subset[] subsets) {
    }

    @Override
    public void setCompositionEnabled(boolean enable) {
    }

    @Override
    public boolean isCompositionEnabled() {
        return false;
    }

    @Override
    public void dispatchEvent(AWTEvent event) {
        if (isInstanceBroken()) {
            logIgnoredCallOnBrokenInstance("dispatchEvent", event);
            return;
        }
    }

    @Override
    public void activate() {
        if (isInstanceBroken()) {
            logIgnoredCallOnBrokenInstance("activate");
            return;
        }

        awtActivationStatus = AWTActivationStatus.ACTIVATED;

        // It may be wrong to only invoke this if awtActivationStatus was DEACTIVATED.
        // E.g. if there was a call chain [activate -> disableInputMethod -> activate].
        // So let's enable the context here unconditionally.
        wlEnableContextLater();
    }

    @Override
    public void deactivate(final boolean isTemporary) {
        if (isInstanceBroken()) {
            logIgnoredCallOnBrokenInstance("deactivate", isTemporary);
            return;
        }

        final boolean wasActivated = (awtActivationStatus == AWTActivationStatus.ACTIVATED);
        awtActivationStatus = isTemporary ? AWTActivationStatus.DEACTIVATED_TEMPORARILY : AWTActivationStatus.DEACTIVATED;

        if (wasActivated) {
            // We can't use {@link #wlDisableContextNowOrLater()} here because the owning InputContext may supplement
            //   the call of {@link #deactivate(boolean)} with a call of {{@link #activate()} on a different InputMethod right after.
            wlDisableContextNowOrReinitialize();
        }
    }

    @Override
    public void hideWindows() {
        if (isInstanceBroken()) {
            logIgnoredCallOnBrokenInstance("hideWindows");
            return;
        }

        // There's no dedicated Wayland API for this operation, so we do this via disabling+enabling back the context

        wlDisableContextNowOrLater();
        if (awtActivationStatus == AWTActivationStatus.ACTIVATED) {
            wlEnableContextLater();
        }
    }

    @Override
    public void removeNotify() {
        if (isInstanceBroken()) {
            logIgnoredCallOnBrokenInstance("removeNotify");
            return;
        }

        // The method is called in response to {@link Component#enableInputMethods(boolean)} and {@link Component#removeNotify()},
        //   and it's guaranteed to be only called when the InputMethod is deactivated (see {@link java.awt.im.spi.InputMethod#removeNotify}).
        // Thus, there is most likely no need to disable the context at all (because it should have already been disabled),
        //   but just in case let's do it in the safer way.
        wlDisableContextNowOrLater();
    }

    @Override
    public void endComposition() {
        if (isInstanceBroken()) {
            logIgnoredCallOnBrokenInstance("endComposition");
            return;
        }

        // There's no dedicated Wayland API for this operation, so we do this via disabling+enabling back the context

        wlDisableContextNowOrLater();
        if (awtActivationStatus == AWTActivationStatus.ACTIVATED) {
            wlEnableContextLater();
        }
    }

    @Override
    public void dispose() {
        awtActivationStatus = AWTActivationStatus.DEACTIVATED;
        wlDisposeContext();
    }

    @Override
    public Object getControlObject() {
        return null;
    }


    /* java.lang.Object methods section (overriding some of its methods) */

    @SuppressWarnings("removal")
    @Override
    protected void finalize() throws Throwable {
        dispose();
        super.finalize();
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

    /**
     * The interface serves just as a namespace for all the types, constants
     * and helper (static) methods required for work with the "text-input-unstable-v3" protocol.
     * It has no declared non-static methods or subclasses/implementors.
     */
    private interface ZwpTextInputV3 {
        /** Reason for the change of surrounding text or cursor position */
        enum ChangeCause {
            INPUT_METHOD(0), // input method caused the change
            OTHER       (1); // something else than the input method caused the change

            public final int intValue;
            ChangeCause(int intValue) {
                this.intValue = intValue;
            }
        }

        /** Content hint is a bitmask to allow to modify the behavior of the text input */
        enum ContentHint {
            NONE               (0x0),   // no special behavior
            COMPLETION         (0x1),   // suggest word completions
            SPELLCHECK         (0x2),   // suggest word corrections
            AUTO_CAPITALIZATION(0x4),   // switch to uppercase letters at the start of a sentence
            LOWERCASE          (0x8),   // prefer lowercase letters
            UPPERCASE          (0x10),  // prefer uppercase letters
            TITLECASE          (0x20),  // prefer casing for titles and headings (can be language dependent)
            HIDDEN_TEXT        (0x40),  // characters should be hidden
            SENSITIVE_DATA     (0x80),  // typed text should not be stored
            LATIN              (0x100), // just Latin characters should be entered
            MULTILINE          (0x200); // the text input is multiline

            public final int intMask;
            ContentHint(int intMask) {
                this.intMask = intMask;
            }
        }

        /**
         * The content purpose allows to specify the primary purpose of a text input.
         * This allows an input method to show special purpose input panels with extra characters or to disallow some characters.
         */
        enum ContentPurpose {
            NORMAL  (0),  // default input, allowing all characters
            ALPHA   (1),  // allow only alphabetic characters
            DIGITS  (2),  // allow only digits
            NUMBER  (3),  // input a number (including decimal separator and sign)
            PHONE   (4),  // input a phone number
            URL     (5),  // input an URL
            EMAIL   (6),  // input an email address
            NAME    (7),  // input a name of a person
            PASSWORD(8),  // input a password (combine with sensitive_data hint)
            PIN     (9),  // input is a numeric password (combine with sensitive_data hint)
            DATE    (10), // input a date
            TIME    (11), // input a time
            DATETIME(12), // input a date and time
            TERMINAL(13); // input for a terminal

            public final int intValue;
            ContentPurpose(int intValue) {
                this.intValue = intValue;
            }
        }


        // zwp_text_input_v3::set_text_change_cause
        ChangeCause       INITIAL_VALUE_TEXT_CHANGE_CAUSE = ChangeCause.INPUT_METHOD;
        // zwp_text_input_v3::set_content_type.hint
        int               INITIAL_VALUE_CONTENT_HINT      = ContentHint.NONE.intMask;
        // zwp_text_input_v3::set_content_type.purpose
        ContentPurpose    INITIAL_VALUE_CONTENT_PURPOSE   = ContentPurpose.NORMAL;
        // zwp_text_input_v3::set_cursor_rectangle
        /**
         * The initial values describing a cursor rectangle are empty.
         * That means the text input does not support describing the cursor area.
         * If the empty values get applied, subsequent attempts to change them may have no effect.
         */
        Rectangle         INITIAL_VALUE_CURSOR_RECTANGLE  = null;
        // zwp_text_input_v3::preedit_string
        JavaPreeditString INITIAL_VALUE_PREEDIT_STRING    = new JavaPreeditString(null, 0, 0);
        // zwp_text_input_v3::commit_string
        JavaCommitString  INITIAL_VALUE_COMMIT_STRING     = new JavaCommitString(null);


        // Below are a few classes designed to maintain the state of an input context (represented by an instance of
        // {@code zwp_text_input_v3}).
        //
        // The state itself is stored in InputContextState.
        // The classes OutgoingChanges and OutgoingBeingCommittedChanges represent a set of changes the client (us)
        //   sends to the compositor. OutgoingChanges accumulates changes until they're committed via zwp_text_input_v3_commit,
        //   and OutgoingBeingCommittedChanges keeps changes after they get committed and until they actually get applied
        //   by the compositor. After that, the applied changes get reflected in the InputContextState.
        // The class IncomingChanges represent a set of changes that the client receives from the compositor through
        //   the events of zwp_text_input_v3.
        //
        // All the classes are designed as data structures with no business logic; the latter should be
        //   encapsulated by WLInputMethod class itself. However, the write-access to the fields is
        //   still only provided via methods (instead of having the fields public) just to ensure the validity of
        //   the changes and to better express their purposes.

        /**
         * This class encapsulates the entire state of an input context represented by an instance of
         * {@code zwp_text_input_v3}.
         * The modifier methods return {@code this} for method chaining.
         */
        final class InputContextState
        {
            /** {@link #createNativeContext()} / {@link #disposeNativeContext(long)} */
            public final long nativeContextPtr;


            public InputContextState(long nativeContextPtr) {
                assert (nativeContextPtr != 0);
                this.nativeContextPtr = nativeContextPtr;
            }

            public long getCurrentWlSurfacePtr() { return currentWlSurfacePtr; }


            // zwp_text_input_v3::commit + zwp_text_input_v3::done
            /**
             * How many times changes to this state have been committed to the compositor
             *  (through {@link #zwp_text_input_v3_commit(long)}) and then accepted by it.
             */
            private long version = 0;

            // zwp_text_input_v3::enable / zwp_text_input_v3::disable
            private boolean enabled = false;

            // zwp_text_input_v3::set_text_change_cause
            private ChangeCause textChangeCause = INITIAL_VALUE_TEXT_CHANGE_CAUSE;

            // zwp_text_input_v3::set_content_type.hint
            private int contentHint = INITIAL_VALUE_CONTENT_HINT;

            // zwp_text_input_v3::set_content_type.purpose
            private ContentPurpose contentPurpose = INITIAL_VALUE_CONTENT_PURPOSE;

            // zwp_text_input_v3::set_cursor_rectangle
            /**
             * The protocol uses the term "cursor" here, but it actually means a caret.
             * The rectangle is in surface local coordinates
             */
            private Rectangle caretRectangle = INITIAL_VALUE_CURSOR_RECTANGLE;

            // zwp_text_input_v3::enter.surface / zwp_text_input_v3::leave.surface
            private long currentWlSurfacePtr = 0;

            // zwp_text_input_v3::preedit_string
            /** Must never be {@code null}. */
            private JavaPreeditString preeditString = INITIAL_VALUE_PREEDIT_STRING;

            // zwp_text_input_v3::commit_string
            /** Must never be {@code null}. */
            private JavaCommitString commitString = INITIAL_VALUE_COMMIT_STRING;

            // zwp_text_input_v3::done
            private long lastDoneSerial = 0;
        }


        /**
         * This class is intended to accumulate changes for an {@link InputContextState} until
         *   they're sent via the set of methods {@code zwp_text_input_v3_set_*} and commited via
         *   {@link #zwp_text_input_v3_commit(long)}.
         * <p>
         * The reason of having to accumulate changes instead of applying them as soon as they appear is the following
         * part of the {@code zpw_text_input_v3::done(serial)} event specification:
         * {@code
         * When the client receives a done event with a serial different than the number of past commit requests,
         * it must proceed with evaluating and applying the changes as normal, except it should not change the
         * current state of the zwp_text_input_v3 object. All pending state requests [...]
         * on the zwp_text_input_v3 object should be sent and committed after receiving a
         * zwp_text_input_v3.done event with a matching serial.
         * }
         *<p>
         * All the properties this class includes are nullable where {@code null} means absent of this property change.
         * In other words, if a property is null, the corresponding {@code zwp_text_input_v3_set_*} shouldn't be
         * called when processing this instance of OutgoingChanges.
         * <p>
         * The modifier methods return {@code this} for method chaining.
         */
        final class OutgoingChanges
        {
            // zwp_text_input_v3::enable / zwp_text_input_v3::disable
            private Boolean newEnabled = null;

            // zwp_text_input_v3::set_text_change_cause
            private ChangeCause newTextChangeCause = null;

            // zwp_text_input_v3::set_content_type
            private Integer newContentTypeHint = null;
            private ContentPurpose newContentTypePurpose = null;

            // zwp_text_input_v3::set_cursor_rectangle
            private Rectangle newCaretRectangle = null;


            @Override
            public String toString() {
                final StringBuilder sb = new StringBuilder(256);
                sb.append("OutgoingChanges@").append(System.identityHashCode(this));
                sb.append('[');
                sb.append("newEnabled=").append(newEnabled);
                sb.append(", newTextChangeCause=").append(newTextChangeCause);
                sb.append(", newContentTypeHint=").append(newContentTypeHint);
                sb.append(", newContentTypePurpose=").append(newContentTypePurpose);
                sb.append(", newCaretRectangle=").append(newCaretRectangle);
                sb.append(']');
                return sb.toString();
            }


            public OutgoingChanges setEnabledState(Boolean newEnabled) {
                this.newEnabled = newEnabled;
                return this;
            }

            public Boolean getEnabledState() { return newEnabled; }


            public OutgoingChanges setTextChangeCause(ChangeCause newTextChangeCause) {
                this.newTextChangeCause = newTextChangeCause;
                return this;
            }

            public ChangeCause getTextChangeCause() { return newTextChangeCause; }


            /**
             * Both parameters have to be {@code null} or not null simultaneously.
             *
             * @throws NullPointerException if one of the parameters is {@code null} while the other one is not.
             */
            public OutgoingChanges setContentType(Integer newContentTypeHint, ContentPurpose newContentTypePurpose) {
                if (newContentTypeHint == null && newContentTypePurpose == null) {
                    this.newContentTypeHint = null;
                    this.newContentTypePurpose = null;
                } else {
                    final var contentHintAllMask =
                        ContentHint.NONE.intMask |
                        ContentHint.COMPLETION.intMask |
                        ContentHint.SPELLCHECK.intMask |
                        ContentHint.AUTO_CAPITALIZATION.intMask |
                        ContentHint.LOWERCASE.intMask |
                        ContentHint.UPPERCASE.intMask |
                        ContentHint.TITLECASE.intMask |
                        ContentHint.HIDDEN_TEXT.intMask |
                        ContentHint.SENSITIVE_DATA.intMask |
                        ContentHint.LATIN.intMask |
                        ContentHint.MULTILINE.intMask;

                    if ( (Objects.requireNonNull(newContentTypeHint, "newContentTypeHint") & ~contentHintAllMask) != 0 ) {
                        throw new IllegalArgumentException(String.format("newContentTypeHint=%d has invalid bits set", newContentTypeHint));
                    }

                    this.newContentTypeHint = newContentTypeHint;
                    this.newContentTypePurpose = Objects.requireNonNull(newContentTypePurpose, "newContentTypePurpose");
                }
                return this;
            }

            public Integer getContentTypeHint() { return newContentTypeHint; }
            public ContentPurpose getContentTypePurpose() { return newContentTypePurpose; }


            public OutgoingChanges setCursorRectangle(Rectangle newCaretRectangle) {
                this.newCaretRectangle = newCaretRectangle;
                return this;
            }

            public Rectangle getCursorRectangle() { return newCaretRectangle; }


            public boolean isEmpty() {
                return (getEnabledState() == null &&
                        getTextChangeCause() == null &&
                        getContentTypeHint() == null &&
                        getCursorRectangle() == null);
            }


            public OutgoingChanges appendChangesFrom(OutgoingChanges src) {
                if (src == null) return this;

                if (getEnabledState() == null) {
                    setEnabledState(src.getEnabledState());
                }
                if (getTextChangeCause() == null) {
                    setTextChangeCause(src.getTextChangeCause());
                }
                if (getContentTypeHint() == null) {
                    setContentType(src.getContentTypeHint(), src.getContentTypePurpose());
                }
                if (getCursorRectangle() == null) {
                    setCursorRectangle(src.getCursorRectangle());
                }

                return this;
            }
        }

        /**
         * This class is essentially a pair of:
         * <ul>
         *     <li>
         *         An OutgoingChanges that have been sent and committed to the compositor,
         *         but not yet confirmed by it.
         *         The property is nullable where {@code null} means there's no sent and commited
         *         but not yet confirmed changes at the moment.
         *     </li>
         *     <li>
         *         A commit counter which preserves the number of times various instances of OutgoingChanges
         *         have been committed.
         *     </li>
         * </ul>
         *
         * @see OutgoingChanges
         */
        final class OutgoingBeingCommittedChanges
        {
            public boolean hasBeingCommitedChanges() {
                return beingCommitedChanges != null;
            }

            public OutgoingChanges getChanges() {
                // TODO: always return a copy?
                return beingCommitedChanges;
            }

            public boolean checkIfChangesHaveBeenApplied(final long lastDoneSerial) {
                assert hasBeingCommitedChanges();

                return commitCounter == lastDoneSerial;
            }

            public OutgoingChanges clearIfChangesHaveBeenApplied(final long lastDoneSerial) {
                assert hasBeingCommitedChanges();

                if (!checkIfChangesHaveBeenApplied(lastDoneSerial)) {
                    return null;
                }

                final var result = beingCommitedChanges;
                beingCommitedChanges = null;

                return result;
            }

            public void acceptNewBeingCommitedChanges(OutgoingChanges changes) {
                assert !hasBeingCommitedChanges();

                // zwp_text_input_v3::done natively uses uint32_t for the serial,
                // so it can't get greater than 0xFFFFFFFF
                commitCounter = (commitCounter + 1) % 0x100000000L;

                beingCommitedChanges = changes;
            }


            // zwp_text_input_v3::commit + zwp_text_input_v3::done
            private long commitCounter = 0;
            // zwp_text_input_v3::set_* + zwp_text_input_v3::commit
            private OutgoingChanges beingCommitedChanges;
        }


        /**
         * This class accumulates changes received as
         * {@code zwp_text_input_v3::preedit_string}, {@code zwp_text_input_v3::commit_string} events until
         * a {@code zwp_text_input_v3::done} event is received.
         */
        final class IncomingChanges
        {
            public IncomingChanges updatePreeditString(byte[] newPreeditStringUtf8, int newPreeditStringCursorBeginUtf8Byte, int newPreeditStringCursorEndUtf8Byte) {
                this.doUpdatePreeditString = true;
                this.newPreeditStringUtf8 = newPreeditStringUtf8;
                this.newPreeditStringCursorBeginUtf8Byte = newPreeditStringCursorBeginUtf8Byte;
                this.newPreeditStringCursorEndUtf8Byte = newPreeditStringCursorEndUtf8Byte;
                this.cachedResultPreeditString = null;

                return this;
            }

            /**
             * @return {@code null} if there are no changes in the preedit string
             *                      (i.e. {@link #updatePreeditString(byte[], int, int) hasn't been called};
             *         an instance of JavaPreeditString otherwise.
             * @see ZwpTextInputV3.JavaPreeditString
             */
            public ZwpTextInputV3.JavaPreeditString getPreeditString() {
                if (cachedResultPreeditString != null) {
                    return cachedResultPreeditString;
                }

                cachedResultPreeditString = doUpdatePreeditString
                    ? JavaPreeditString.fromWaylandPreeditString(newPreeditStringUtf8, newPreeditStringCursorBeginUtf8Byte, newPreeditStringCursorEndUtf8Byte)
                    : null;

                return cachedResultPreeditString;
            }


            public IncomingChanges updateCommitString(byte[] newCommitStringUtf8) {
                this.doUpdateCommitString = true;
                this.newCommitStringUtf8 = newCommitStringUtf8;
                this.cachedResultCommitString = null;

                return this;
            }

            /**
             * @return {@code null} if there are no changes in the commit string
             *                     (i.e. {@link #updateCommitString(byte[])}  hasn't been called};
             *         an instance of JavaCommitString otherwise.
             * @see JavaCommitString
             */
            public JavaCommitString getCommitString() {
                if (cachedResultCommitString != null) {
                    return cachedResultCommitString;
                }

                cachedResultCommitString = doUpdateCommitString
                        ? JavaCommitString.fromWaylandCommitString(newCommitStringUtf8)
                        : null;

                return cachedResultCommitString;
            }


            /**
             * Resets the state to the initial so that
             * {@code this.reset().equals(new IncomingChanges())} returns {@code true}.
             */
            public IncomingChanges reset()
            {
                doUpdatePreeditString = false;
                newPreeditStringUtf8 = null;
                newPreeditStringCursorBeginUtf8Byte = 0;
                newPreeditStringCursorEndUtf8Byte = 0;
                cachedResultPreeditString = null;

                doUpdateCommitString = false;
                newCommitStringUtf8 = null;
                cachedResultCommitString = null;

                return this;
            }


            @Override
            public boolean equals(Object o) {
                if (o == null || getClass() != o.getClass()) return false;
                IncomingChanges that = (IncomingChanges) o;
                return doUpdatePreeditString == that.doUpdatePreeditString &&
                       newPreeditStringCursorBeginUtf8Byte == that.newPreeditStringCursorBeginUtf8Byte &&
                       newPreeditStringCursorEndUtf8Byte == that.newPreeditStringCursorEndUtf8Byte &&
                       doUpdateCommitString == that.doUpdateCommitString &&
                       Objects.deepEquals(newPreeditStringUtf8, that.newPreeditStringUtf8) &&
                       Objects.deepEquals(newCommitStringUtf8, that.newCommitStringUtf8);
            }

            @Override
            public int hashCode() {
                return Objects.hash(
                    doUpdatePreeditString,
                    Arrays.hashCode(newPreeditStringUtf8),
                    newPreeditStringCursorBeginUtf8Byte,
                    newPreeditStringCursorEndUtf8Byte,
                    doUpdateCommitString,
                    Arrays.hashCode(newCommitStringUtf8)
                );
            }


            // zwp_text_input_v3::preedit_string
            private boolean doUpdatePreeditString = false;
            private byte[] newPreeditStringUtf8 = null;
            private int newPreeditStringCursorBeginUtf8Byte = 0;
            private int newPreeditStringCursorEndUtf8Byte = 0;
            private JavaPreeditString cachedResultPreeditString = null;

            // zwp_text_input_v3::commit_string
            private boolean doUpdateCommitString = false;
            private byte[] newCommitStringUtf8 = null;
            private JavaCommitString cachedResultCommitString = null;
        }


        // Utility/helper classes and methods

        static int getLengthOfUtf8BytesWithoutTrailingNULs(final byte[] utf8Bytes) {
            int lastNonNulIndex = (utf8Bytes == null) ? -1 : utf8Bytes.length - 1;
            for (; lastNonNulIndex >= 0; --lastNonNulIndex) {
                if (utf8Bytes[lastNonNulIndex] != 0) {
                    break;
                }
            }

            return (lastNonNulIndex < 0) ? 0 : lastNonNulIndex + 1;
        }

        static String utf8BytesToJavaString(final byte[] utf8Bytes) {
            if (utf8Bytes == null) {
                return null;
            }

            return utf8BytesToJavaString(
                utf8Bytes,
                0,
                // Java's UTF-8 -> UTF-16 conversion doesn't like trailing NUL codepoints, so let's trim them
                getLengthOfUtf8BytesWithoutTrailingNULs(utf8Bytes)
            );
        }

        static String utf8BytesToJavaString(final byte[] utf8Bytes, final int offset, final int length) {
            return utf8Bytes == null ? null : new String(utf8Bytes, offset, length, StandardCharsets.UTF_8);
        }


        /**
         * This class represents the result of a conversion of a UTF-8 preedit string received in a
         * {@code zwp_text_input_v3::preedit_string} event to a Java UTF-16 string.
         * If {@link #cursorBeginCodeUnit} and/or {@link #cursorEndCodeUnit} point at UTF-16 surrogate pairs,
         *   they're guaranteed to point at the very beginning of them as long as {@link #fromWaylandPreeditString} is
         *   used to perform the conversion.
         * <p>
         * {@link #fromWaylandPreeditString} never returns {@code null}.
         * <p>
         * See the specification of {@code zwp_text_input_v3::preedit_string} event for more info about
         * cursor_begin, cursor_end values.
         *
         * @param text The preedit text string. Nullable where {@code null} essentially means the empty string.
         * @param cursorBeginCodeUnit UTF-16 equivalent of {@code preedit_string.cursor_begin}.
         * @param cursorEndCodeUnit UTF-16 equivalent of {@code preedit_string.cursor_end}.
         *                          It's not explicitly stated in the protocol specification, but it seems to be a valid
         *                          situation when cursor_end < cursor_begin, which means
         *                          the highlight extends to the right from the caret
         *                          (e.g., when the text gets selected with Shift + Left Arrow).
         */
        record JavaPreeditString(String text, int cursorBeginCodeUnit, int cursorEndCodeUnit) {
            // It's not explicitly stated in the protocol specification, but it seems to be a valid
            //   situation when cursor_end < cursor_begin, which means the highlight extends to the right from the caret
            //   (e.g., when the text is selected with Shift + Left Arrow).

            public static final JavaPreeditString EMPTY = new JavaPreeditString(null, 0, 0);

            public static JavaPreeditString fromWaylandPreeditString(
                final byte[] utf8Bytes,
                final int cursorBeginUtf8Byte,
                final int cursorEndUtf8Byte
            ) {
                // Java's UTF-8 -> UTF-16 conversion doesn't like trailing NUL codepoints, so let's trim them
                final int utf8BytesWithoutNulLength = getLengthOfUtf8BytesWithoutTrailingNULs(utf8Bytes);

                // cursorBeginUtf8Byte, cursorEndUtf8Byte normalized relatively to the valid values range.
                final int fixedCursorBeginUtf8Byte;
                final int fixedCursorEndUtf8Byte;
                if (cursorBeginUtf8Byte < 0 || cursorEndUtf8Byte < 0) {
                    fixedCursorBeginUtf8Byte = fixedCursorEndUtf8Byte = -1;
                } else {
                    // 0 <= cursorBeginUtf8Byte <= fixedCursorBeginUtf8Byte <= utf8BytesWithoutNulLength
                    fixedCursorBeginUtf8Byte = Math.min(cursorBeginUtf8Byte, utf8BytesWithoutNulLength);
                    // 0 <= cursorEndUtf8Byte <= fixedCursorEndUtf8Byte <= utf8BytesWithoutNulLength
                    fixedCursorEndUtf8Byte = Math.min(cursorEndUtf8Byte, utf8BytesWithoutNulLength);
                }

                final var resultText = utf8BytesToJavaString(utf8Bytes, 0, utf8BytesWithoutNulLength);

                if (fixedCursorBeginUtf8Byte < 0 || fixedCursorEndUtf8Byte < 0) {
                    return new JavaPreeditString(resultText, -1, -1);
                }

                if (resultText == null) {
                    assert(fixedCursorBeginUtf8Byte == 0);
                    assert(fixedCursorEndUtf8Byte == 0);

                    return JavaPreeditString.EMPTY;
                }

                final String javaPrefixBeforeCursorBegin = (fixedCursorBeginUtf8Byte == 0)
                                                           ? ""
                                                           : utf8BytesToJavaString(utf8Bytes, 0, fixedCursorBeginUtf8Byte);

                final String javaPrefixBeforeCursorEnd = (fixedCursorEndUtf8Byte == 0)
                                                         ? ""
                                                         : utf8BytesToJavaString(utf8Bytes, 0, fixedCursorEndUtf8Byte);

                return new JavaPreeditString(
                    resultText,
                    javaPrefixBeforeCursorBegin.length(),
                    javaPrefixBeforeCursorEnd.length()
                );
            }
        }

        record JavaCommitString(String text) {
            /** Never returns {@code null}. */
            public static JavaCommitString fromWaylandCommitString(byte[] utf8Bytes) {
                return new JavaCommitString(utf8BytesToJavaString(utf8Bytes));
            }
        }
    }


    /* Wayland-side state section */

    // The fields in this section are prefixed with "wl" and aren't supposed to be modified by
    //   non-Wayland-related methods (whose names are prefixed with "wl" or "zwp_text_input_v3_"),
    //   though can be read by them.

    private final class WLChangesAsyncSender {
        public WLChangesAsyncSender() {
            senderTimer = new Timer(SENDER_TIMER_PERIOD_MS, WLChangesAsyncSender.this::asyncSendingRoutine);
            senderTimer.setInitialDelay(SENDER_TIMER_INITIAL_DELAY_MS);
            senderTimer.setRepeats(true);
            senderTimer.setCoalesce(true);
        }

        public void start() {
            if (isWorking()) {
                return;
            }
            senderTimer.start();
        }

        public void stop() {
            if (!isWorking()) {
                return;
            }
            senderTimer.stop();
        }

        public boolean isWorking() {
            return senderTimer.isRunning();
        }


        private static final int SENDER_TIMER_PERIOD_MS = 100;
        // Not 0 just to avoid firing the listener synchronously
        private static final int SENDER_TIMER_INITIAL_DELAY_MS = 1;
        private final Timer senderTimer;

        private void asyncSendingRoutine(java.awt.event.ActionEvent e) {
            assert(EventQueue.isDispatchThread());

            if (WLInputMethod.this.wlCanSendChangesNow()) {
                WLInputMethod.this.wlSendPendingChangesNow();
            }

            if (WLInputMethod.this.wlPendingChanges1 == null && WLInputMethod.this.wlPendingChanges2 == null) {
                // Nothing to send (anymore)
                stop();
            }
        }
    }

    /** The reference must only be (directly) modified in {@link #wlInitializeContext()} and {@link #wlDisposeContext()}. */
    private ZwpTextInputV3.InputContextState wlInputContextState = null;
    /**
     * wlPendingChanges1 and wlPendingChanges2 form a queue of pending changes sets, where
     *   wlPendingChanges1 is the top of the queue.
     * <p>
     * See {@link ZwpTextInputV3.OutgoingChanges} for the explanation why there's a need to store changes set
     *   instead of sending them as soon as they appear.
     * <p>
     * It's not enough to keep only one set of changes, because the operation of resetting the Wayland context state
     *   in this protocol implies sending the sequence of Wayland requests disable+enable.
     */
    private ZwpTextInputV3.OutgoingChanges wlPendingChanges1 = null, wlPendingChanges2 = null;
    private final WLChangesAsyncSender wlChangesAsyncSender = new WLChangesAsyncSender();
    private ZwpTextInputV3.OutgoingBeingCommittedChanges wlBeingCommittedChanges = null;

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
    /** {@link #setInputMethodContext(InputMethodContext)} */
    private InputMethodContext awtImContext = null;


    /* Core methods section */

    private void wlInitializeContext() throws AWTException {
        assert(wlInputContextState == null);
        assert(wlPendingChanges1 == null);
        assert(wlPendingChanges2 == null);
        assert(!wlChangesAsyncSender.isWorking());
        assert(wlBeingCommittedChanges == null);

        wlBeingCommittedChanges = new ZwpTextInputV3.OutgoingBeingCommittedChanges();
        long nativeCtxPtr = 0;

        try {
            nativeCtxPtr = createNativeContext();
            if (nativeCtxPtr == 0) {
                throw new AWTException("nativeCtxPtr == 0");
            }

            wlInputContextState = new ZwpTextInputV3.InputContextState(nativeCtxPtr);
        } catch (Throwable err) {
            wlBeingCommittedChanges = null;
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
        wlPendingChanges1 = null;
        wlPendingChanges2 = null;
        wlChangesAsyncSender.stop();
        wlBeingCommittedChanges = null;

        if (ctxToDispose != null && ctxToDispose.nativeContextPtr != 0) {
            disposeNativeContext(ctxToDispose.nativeContextPtr);
        }
    }

    private void wlReinitializeContext() throws AWTException {
        wlDisposeContext();

        //  If wlInitializeContext fails to create a new context after the current has been destroyed,
        //    we'll get a possibly activated ({@link #activate}), but uninitialized WLInputMethod,
        //    so it won't be able to keep functioning.
        //  However, we shouldn't try to keep the current context if the creation of a new one has failed,
        //    because it will break the expectations of this method's users.
        //  If the call fails, this WLInputMethod turns into a broken state, i.e. {@link #isInstanceBroken()} returns true.
        //  In this case the caller of wlReinitializeContext should use {@link #handleBrokenInstance()}.
        wlInitializeContext();

        // Don't enable the context from this method because it's used in wlDisableContextNowOrReinitialize.
    }

    private boolean isInstanceBroken() {
        return wlInputContextState == null || wlInputContextState.nativeContextPtr == 0 || wlBeingCommittedChanges == null;
    }

    private void handleBrokenInstance() {
        assert(isInstanceBroken());

        try {
            ((sun.awt.im.InputContext)awtImContext).deregisterAndDisposeBrokenInputMethod(this);
        } catch (Exception err) {
            log.severe(
                String.format(
                    "handleBrokenInstance: failed to deregister broken WLInputMethod@%d from %s.",
                    System.identityHashCode(this),
                    awtImContext == null ? "null" : Objects.toIdentityString(awtImContext)
                ),
                err
            );

            dispose();
        }
    }

    private void logIgnoredCallOnBrokenInstance(String methodName) {
        if (log.isLoggable(PlatformLogger.Level.WARNING)) {
            log.warning(
                "{1}(): the call is ignored since this WLInputMethod@{0} is broken.",
                System.identityHashCode(this), methodName
            );
        }
    }

    private void logIgnoredCallOnBrokenInstance(String methodName, Object methodArg) {
        if (log.isLoggable(PlatformLogger.Level.WARNING)) {
            log.warning(
                "{1}({2}): the call is ignored since this WLInputMethod@{0} is broken.",
                System.identityHashCode(this), methodName, methodArg
            );
        }
    }

    private void logIgnoredCallOnBrokenInstance(String methodName, Object methodArg1, Object methodArg2) {
        if (log.isLoggable(PlatformLogger.Level.WARNING)) {
            log.warning(
                "{1}({2}, {3}): the call is ignored since this WLInputMethod@{0} is broken.",
                System.identityHashCode(this), methodName, methodArg1, methodArg2
            );
        }
    }

    private void logIgnoredCallOnBrokenInstance(String methodName, Object methodArg1, Object methodArg2, Object methodArg3) {
        if (log.isLoggable(PlatformLogger.Level.WARNING)) {
            log.warning(
                "{1}({2}, {3}, {4}): the call is ignored since this WLInputMethod@{0} is broken.",
                System.identityHashCode(this), methodName, methodArg1, methodArg2, methodArg3
            );
        }
    }


    private boolean wlCanSendChangesNow() {
        return wlInputContextState != null &&
               wlInputContextState.nativeContextPtr != 0 &&
               !wlBeingCommittedChanges.hasBeingCommitedChanges();
    }

    private void wlSendPendingChangesNow() {
        if (isInstanceBroken()) {
            logIgnoredCallOnBrokenInstance("wlSendPendingChangesNow");
            return;
        }

        assert(wlCanSendChangesNow());

        if (wlPendingChanges1 == null || wlPendingChanges1.isEmpty()) {
            wlPendingChanges1 = wlPendingChanges2;
            wlPendingChanges2 = null;
        }

        final ZwpTextInputV3.OutgoingChanges changesToSend = wlPendingChanges1;
        wlPendingChanges1 = wlPendingChanges2;
        wlPendingChanges2 = null;

        if (changesToSend == null) {
            // Nothing to send
            return;
        }

        if (Boolean.TRUE.equals(changesToSend.getEnabledState())) {
            // The changes imply sending an enable request.

            if (awtActivationStatus != AWTActivationStatus.ACTIVATED) {
                // A context should never be enabled if AWT has deactivated it

                if (log.isLoggable(PlatformLogger.Level.FINE)) {
                    log.fine("wlSendPendingChangesNow: the change set implies sending an enable request, but this WLInputMethod instance is deactivated. Omitting the enable request for {0}", changesToSend);
                }

                changesToSend.setEnabledState(null);
            }
        }

        if (changesToSend.isEmpty()) {
            // Nothing to send
            return;
        }

        if (wlInputContextState.getCurrentWlSurfacePtr() == 0) {
            // "After leave event, compositor must ignore requests from any text input instances until next enter event."
            // Thus, it doesn't make sense to send any requests, let's drop the change set.

            if (log.isLoggable(PlatformLogger.Level.FINE)) {
                log.fine("wlSendPendingChangesNow: wlInputContextState.getCurrentWlSurfacePtr()=0. Dropping the change set {0}", changesToSend);
            }

            return;
        }

        if (Boolean.TRUE.equals(changesToSend.getEnabledState())) {
            // All enable requests should be accompanied by property set requests for the following reasons:
            // 1. To let the compositor know working with which properties we support.
            // 2. Some properties (content_type) cannot be modified after enable + commit (until next enable + commit).

            if (changesToSend.getContentTypeHint() == null) {
                changesToSend.setContentType(ZwpTextInputV3.ContentHint.NONE.intMask, ZwpTextInputV3.ContentPurpose.NORMAL);
            }
            // TODO: update cursorRectangle unconditionally:
            //   It's worth calculating and providing the current cursor position to avoid showing IM popups at the wrong places
            if (changesToSend.getCursorRectangle() == null) {
                changesToSend.setCursorRectangle(new Rectangle(0, 0, 1, 1));
            }
            if (changesToSend.getTextChangeCause() == null) {
                changesToSend.setTextChangeCause(ZwpTextInputV3.ChangeCause.OTHER);
            }
        }

        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine("wlSendPendingChangesNow: sending the change set: {0}", changesToSend);
        }

        if (Boolean.TRUE.equals(changesToSend.getEnabledState())) {
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

        zwp_text_input_v3_commit(wlInputContextState.nativeContextPtr);

        wlBeingCommittedChanges.acceptNewBeingCommitedChanges(changesToSend);
    }

    private void wlSendPendingChangesLater() {
        if (isInstanceBroken()) {
            logIgnoredCallOnBrokenInstance("wlSendPendingChangesLater");
            return;
        }

        if (wlPendingChanges1 == null && wlPendingChanges2 == null) {
            // Nothing to send
            return;
        }

        wlChangesAsyncSender.start();
    }

    private void wlSendPendingChangesNowOrLater() {
        if (isInstanceBroken()) {
            logIgnoredCallOnBrokenInstance("wlSendPendingChangesNowOrLater");
            return;
        }

        if (wlCanSendChangesNow()) {
            wlSendPendingChangesNow();
        } else {
            wlSendPendingChangesLater();
        }
    }

    private void wlScheduleContextNewChanges(ZwpTextInputV3.OutgoingChanges newOutgoingChanges) {
        if (isInstanceBroken()) {
            logIgnoredCallOnBrokenInstance("wlScheduleContextNewChanges", newOutgoingChanges);
            return;
        }

        if (wlPendingChanges1 == null) {
            wlPendingChanges1 = wlPendingChanges2;
            wlPendingChanges2 = null;
        }

        if (wlPendingChanges1 == null) {
            wlPendingChanges1 = newOutgoingChanges;
            return;
        }
        if (wlPendingChanges2 == null) {
            wlPendingChanges2 = newOutgoingChanges;
            return;
        }

        final boolean haveUnmergeableChanges =
            (newOutgoingChanges.getEnabledState() != null &&
             wlPendingChanges2.getEnabledState()  != null &&
             newOutgoingChanges.getEnabledState() != wlPendingChanges2.getEnabledState());

        if (!haveUnmergeableChanges) {
            wlPendingChanges2 = newOutgoingChanges.appendChangesFrom(wlPendingChanges2);
            return;
        }

        final boolean newEnabledState = newOutgoingChanges.getEnabledState();

        //noinspection PointlessBooleanExpression
        if (newEnabledState == true) {
            //noinspection PointlessBooleanExpression
            assert(wlPendingChanges2.getEnabledState() == false);

            // Since we want to disable the context with wlPendingChanges2,
            //   it doesn't make sense to either enable or disable it with wlPendingChanges1.
            // In other words, we can ignore the value of wlPendingChanges1.getEnabledState() here, hence
            //   wlPendingChanges1 and wlPendingChanges2 have no unmergeable changes, hence we can merge them thus
            //   freeing up a slot for newOutgoingChanges.
            wlPendingChanges1 = wlPendingChanges2.appendChangesFrom(wlPendingChanges1);
            wlPendingChanges2 = newOutgoingChanges;
        } else {
            //noinspection PointlessBooleanExpression
            assert(wlPendingChanges2.getEnabledState() == true);

            // The same logic as in the positive branch is applicable here
            //   (but for newOutgoingChanges vs wlPendingChanges2 instead of wlPendingChanges2 vs wlPendingChanges1).
            wlPendingChanges2 = newOutgoingChanges.appendChangesFrom(wlPendingChanges2);
        }

        assert(wlGetContextLatestFutureEnabledState() == newEnabledState);
    }

    private boolean wlGetContextUpcomingOrCurrentEnabledState() {
        if (isInstanceBroken()) {
            return false;
        }

        if (wlBeingCommittedChanges.hasBeingCommitedChanges() &&
            wlBeingCommittedChanges.getChanges().getEnabledState() != null)
        {
            return wlBeingCommittedChanges.getChanges().getEnabledState();
        }
        return wlInputContextState.enabled;
    }

    private Boolean wlGetContextPendingEnabledState() {
        if (isInstanceBroken()) {
            return null;
        }

        if (wlPendingChanges2 != null && wlPendingChanges2.getEnabledState() != null) {
            return wlPendingChanges2.getEnabledState();
        }

        return (wlPendingChanges1 == null) ? null : wlPendingChanges1.getEnabledState();
    }

    private boolean wlGetContextLatestFutureEnabledState() {
        return Objects.requireNonNullElse(wlGetContextPendingEnabledState(), wlGetContextUpcomingOrCurrentEnabledState());
    }

    // There must NEVER be a method that sends enable requests immediately (even though that's technically possible):
    //   For example, sometimes AWT activates and then immediately
    //     (i.e. during dispatching/processing of the same EventQueue event) deactivates the last used input method.
    //   Basically, activation ({@link #activate()}) and deactivation ({@link #deactivate(boolean)}) operations
    //     imply sending "enable" and "disable" Wayland requests respectively.
    //   In this case, if we send an enable request immediately, we won't be able to send a disable request after,
    //     because we won't have received yet a confirmation from the compositor about the enable request
    //     (because pending Wayland events won't have had a chance to be dispatched/handled yet).
    //   If after that AWT decides to enable another instance of WLInputMethod, it will lead to an attempt to enable
    //     two instances of IM native context, which is directly prohibited by the protocol:
    //     "Clients must not enable more than one text input on the single seat and should disable the current
    //      text input before enabling the new one. At most one instance of text input may be in enabled state per
    //      instance, Requests to enable the another text input when some text input is active must be ignored
    //      by compositor."
    private void wlEnableContextLater() {
        if (isInstanceBroken()) {
            logIgnoredCallOnBrokenInstance("wlEnableContextLater");
            return;
        }

        assert(awtActivationStatus == AWTActivationStatus.ACTIVATED);

        if (wlPendingChanges1 == null) {
            wlPendingChanges1 = wlPendingChanges2;
            wlPendingChanges2 = null;
        }

        //noinspection PointlessBooleanExpression
        if (wlGetContextLatestFutureEnabledState() == false) {
            wlScheduleContextNewChanges(new ZwpTextInputV3.OutgoingChanges().setEnabledState(true));
        }

        //noinspection PointlessBooleanExpression
        assert(wlGetContextLatestFutureEnabledState() == true);

        if (wlPendingChanges1 == null) {
            // nothing to send
            return;
        }

        // This method is only intended to schedule sending of an enable request, not to send it immediately.
        wlSendPendingChangesLater();
    }

    private void wlDisableContextNowOrLater() {
        if (isInstanceBroken()) {
            logIgnoredCallOnBrokenInstance("wlDisableContextNowOrLater");
            return;
        }

        if (wlPendingChanges1 == null) {
            wlPendingChanges1 = wlPendingChanges2;
            wlPendingChanges2 = null;
        }

        //noinspection PointlessBooleanExpression
        if (wlGetContextLatestFutureEnabledState() == true) {
            wlScheduleContextNewChanges(new ZwpTextInputV3.OutgoingChanges().setEnabledState(false));
        }

        //noinspection PointlessBooleanExpression
        assert(wlGetContextLatestFutureEnabledState() == false);

        if (wlPendingChanges1 == null) {
            // nothing to send
            return;
        }

        wlSendPendingChangesNowOrLater();
    }

    private void wlDisableContextNowOrReinitialize() {
        if (isInstanceBroken()) {
            logIgnoredCallOnBrokenInstance("wlDisableContextNowOrReinitialize");
            return;
        }

        //noinspection PointlessBooleanExpression
        if (wlGetContextUpcomingOrCurrentEnabledState() == false) {
            // The context is already disabled, or the compositor is processing a disable request.

            wlPendingChanges2 = wlPendingChanges1 = null;
            return;
        }

        if (!wlCanSendChangesNow()) {
            try {
                wlReinitializeContext();
            } catch (AWTException err) {
                if (isInstanceBroken()) {
                    if (log.isLoggable(PlatformLogger.Level.WARNING)) {
                        log.warning(
                            String.format(
                                "wlDisableContextNowOrReinitialize: reinitialization failed. WLInputMethod@%d has broken and will be replaced with another later.",
                                System.identityHashCode(this)
                            ),
                            err
                        );
                    }

                    handleBrokenInstance();
                } else {
                    log.severe("wlDisableContextNowOrReinitialize: unknown error", err);
                }
            }

            return;
        }

        wlPendingChanges1 = new ZwpTextInputV3.OutgoingChanges()
                                .appendChangesFrom(wlPendingChanges2)
                                .appendChangesFrom(wlPendingChanges1)
                                .setEnabledState(false);
        wlPendingChanges2 = null;

        wlSendPendingChangesNow();

        //noinspection PointlessBooleanExpression
        assert(wlGetContextUpcomingOrCurrentEnabledState() == false);
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

        if (isInstanceBroken()) {
            logIgnoredCallOnBrokenInstance("zwp_text_input_v3_onEnter", enteredWlSurfacePtr);
            return;
        }
    }

    /** Called in response to {@code zwp_text_input_v3::leave} events. */
    private void zwp_text_input_v3_onLeave(long leftWlSurfacePtr) {
        assert EventQueue.isDispatchThread();

        if (isInstanceBroken()) {
            logIgnoredCallOnBrokenInstance("zwp_text_input_v3_onLeave", leftWlSurfacePtr);
            return;
        }
    }

    /** Called in response to {@code zwp_text_input_v3::preedit_string} events. */
    private void zwp_text_input_v3_onPreeditString(byte[] preeditStrUtf8, int cursorBeginUtf8Byte, int cursorEndUtf8Byte) {
        assert EventQueue.isDispatchThread();

        if (isInstanceBroken()) {
            logIgnoredCallOnBrokenInstance("zwp_text_input_v3_onPreeditString", preeditStrUtf8, cursorBeginUtf8Byte, cursorEndUtf8Byte);
            return;
        }
    }

    /** Called in response to {@code zwp_text_input_v3::commit_string} events. */
    private void zwp_text_input_v3_onCommitString(byte[] commitStrUtf8) {
        assert EventQueue.isDispatchThread();

        if (isInstanceBroken()) {
            logIgnoredCallOnBrokenInstance("zwp_text_input_v3_onCommitString", commitStrUtf8);
            return;
        }
    }

    /** Called in response to {@code zwp_text_input_v3::delete_surrounding_text} events. */
    private void zwp_text_input_v3_onDeleteSurroundingText(long numberOfUtf8BytesBeforeToDelete, long numberOfUtf8BytesAfterToDelete) {
        assert EventQueue.isDispatchThread();

        if (isInstanceBroken()) {
            logIgnoredCallOnBrokenInstance("zwp_text_input_v3_onDeleteSurroundingText", numberOfUtf8BytesBeforeToDelete, numberOfUtf8BytesAfterToDelete);
            return;
        }
    }

    /** Called in response to {@code zwp_text_input_v3::done} events. */
    private void zwp_text_input_v3_onDone(long doneSerial) {
        assert EventQueue.isDispatchThread();

        if (isInstanceBroken()) {
            logIgnoredCallOnBrokenInstance("zwp_text_input_v3_onDone", doneSerial);
            return;
        }
    }
}
