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

import java.awt.Rectangle;
import java.util.Objects;


/**
 * This class encapsulates the entire state of an input context represented by an instance of {@code zwp_text_input_v3}.
 *
 * @see StateOfEnabled
 */
final class InputContextState {
    /** pointer to a native context {@code zwp_text_input_v3} */
    public final long nativeContextPtr;


    public InputContextState(long nativeContextPtr) {
        assert(nativeContextPtr != 0);

        this.nativeContextPtr = nativeContextPtr;
    }


    /** @return 0 if the input context hasn't entered a surface yet. Otherwise, the native pointer to the surface. */
    public long getCurrentWlSurfacePtr() {
        return currentWlSurfacePtr;
    }

    public void setCurrentWlSurfacePtr(long currentWlSurfacePtr) {
        this.currentWlSurfacePtr = currentWlSurfacePtr;
    }


    /**
     * Notifies the InputContext that a set of changes has been sent and committed to the compositor
     *   via a {@code zwp_text_input_v3::commit} request. The InputContext reacts by incrementing its commit counter.
     *
     * @param changes represents the set of changes that have been sent and followed by a 'commit' request.
     *                Must not be {@code null} (but can be empty, which means only the 'commit' request has been issued).
     *
     * @return a new instance of {@link OutgoingBeingCommittedChanges} consisting of
     *         the passed changes and the new value of the commit counter.
     *
     * @throws NullPointerException if {@code changes} is {@code null}.
     *
     * @see OutgoingChanges
     */
    public OutgoingBeingCommittedChanges syncWithCommittedOutgoingChanges(final OutgoingChanges changes) {
        Objects.requireNonNull(changes, "changes");

        // zwp_text_input_v3::done natively uses uint32_t for the serial,
        //   so it can't get greater than 0xFFFFFFFF.
        this.commitCounter = (this.commitCounter + 1) % 0x100000000L;

        return new OutgoingBeingCommittedChanges(changes, this.commitCounter);
    }

    public long getCommitCounter() {
        return commitCounter;
    }


    /**
     * This class represents the extended state of an {@code InputContextState} that only exists when the context
     *   is enabled.
     *
     * @param textChangeCause the property set via a {@code zwp_text_input_v3::set_text_change_cause} request. Must not be {@code null}.
     * @param contentHint the property set via a {@code zwp_text_input_v3::set_content_type} request.
     * @param contentPurpose the property set via a {@code zwp_text_input_v3::set_content_type} request. Must not be {@code null}.
     * @param cursorRectangle the property set via a {@code zwp_text_input_v3::set_cursor_rectangle} request.
     *                        {@code null} means "the text input does not support describing the cursor area".
     */
    public record StateOfEnabled(
        // zwp_text_input_v3::set_text_change_cause
        ChangeCause textChangeCause,
        // zwp_text_input_v3::set_content_type.hint
        int contentHint,
        // zwp_text_input_v3::set_content_type.purpose
        ContentPurpose contentPurpose,
        // zwp_text_input_v3::set_cursor_rectangle
        Rectangle cursorRectangle
    ) {
        public StateOfEnabled {
            Objects.requireNonNull(textChangeCause, "textChangeCause");
            Objects.requireNonNull(contentPurpose, "contentPurpose");
        }
    }

    public StateOfEnabled getCurrentStateOfEnabled() {
        return stateOfEnabled;
    }

    public boolean isEnabled() {
        return getCurrentStateOfEnabled() != null;
    }

    /**
     * NB: if you want to call setEnabledState(null), consider using {@code wlHandleContextGotDisabled()} of
     *     the owning {@link WLInputMethodZwpTextInputV3}.
     *
     * @param newState {@code null} to mark the InputContext as disabled,
     *                 otherwise the InputContext will be marked as enabled and having the state as
     *                 specified in the parameter.
     */
    public void setEnabledState(StateOfEnabled newState) {
        this.stateOfEnabled = newState;
    }


    public void syncWithAppliedIncomingChanges(final JavaPreeditString appliedPreeditString, final JavaCommitString appliedCommitString, final long doneSerial) {
        this.latestAppliedPreeditString = Objects.requireNonNull(appliedPreeditString, "appliedPreeditString");
        this.latestAppliedCommitString = Objects.requireNonNull(appliedCommitString, "appliedCommitString");
        this.latestDoneSerial = doneSerial;
    }

    public JavaPreeditString getLatestAppliedPreeditString() {
        return latestAppliedPreeditString;
    }

    public JavaCommitString getLatestAppliedCommitString() {
        return latestAppliedCommitString;
    }

    public long getLatestDoneSerial() {
        return latestDoneSerial;
    }


    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder(512);
        sb.append("InputContextState@").append(System.identityHashCode(this));
        sb.append('{');
        sb.append("nativeContextPtr=0x").append(Long.toHexString(nativeContextPtr));
        sb.append(", currentWlSurfacePtr=0x").append(Long.toHexString(currentWlSurfacePtr));
        sb.append(", commitCounter=").append(commitCounter);
        sb.append(", latestDoneSerial=").append(latestDoneSerial);
        sb.append(", stateOfEnabled=").append(stateOfEnabled);
        sb.append(", latestAppliedPreeditString=").append(latestAppliedPreeditString);
        sb.append(", latestAppliedCommitString=").append(latestAppliedCommitString);
        sb.append('}');
        return sb.toString();
    }


    // zwp_text_input_v3::enter.surface / zwp_text_input_v3::leave.surface
    private long currentWlSurfacePtr = 0;
    // zwp_text_input_v3::commit
    /**
     * How many times changes to this context have been committed (through {@code zwp_text_input_v3::commit}).
     * Essentially, it means the most actual version of the context's state.
     */
    private long commitCounter = 0;
    // zwp_text_input_v3::done.serial
    /**
     * The {@code serial} parameter of the latest {@code zwp_text_input_v3::done} event received.
     * Essentially, it means the latest version of the context's state known/confirmed by the compositor.
     */
    private long latestDoneSerial = 0;
    /** {@code null} if the InputContextState is disabled. */
    private StateOfEnabled stateOfEnabled = null;
    /**
     * The latest preedit string applied as a result of the latest {@code zwp_text_input_v3::done} event received.
     * Must never be {@code null} ; if a {@code zwp_text_input_v3::done} event wasn't preceded by a
     * {@code zwp_text_input_v3::preedit_string} event, the field should be set to {@link PropertiesInitials#PREEDIT_STRING}.
     */
    private JavaPreeditString latestAppliedPreeditString = PropertiesInitials.PREEDIT_STRING;
    /**
     * The latest commit string applied as a result of the latest {@code zwp_text_input_v3::done} event received.
     * Must never be {@code null} ; if a {@code zwp_text_input_v3::done} event wasn't preceded by a
     * {@code zwp_text_input_v3::commit_string} event, the field should be set to {@link PropertiesInitials#COMMIT_STRING}.
     */
    private JavaCommitString latestAppliedCommitString = PropertiesInitials.COMMIT_STRING;
}
