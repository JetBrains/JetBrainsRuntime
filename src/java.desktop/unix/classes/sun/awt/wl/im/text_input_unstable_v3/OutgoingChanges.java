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
 * This class is intended to accumulate changes for an {@link InputContextState} until
 *   they're sent via the set of requests
 *   {@code zwp_text_input_v3::enable}, {@code zwp_text_input_v3::disable}, {@code zwp_text_input_v3::set_*}
 *   and commited via {@code zwp_text_input_v3::commit}.
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
 * All the properties this class includes are nullable where {@code null} means absence of this property change.
 * In other words, if a property is null, the corresponding {@code zwp_text_input_v3::set_...} shouldn't be
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
    private Rectangle newCursorRectangle = null;


    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder(256);
        sb.append("OutgoingChanges@").append(System.identityHashCode(this));
        sb.append('[');
        sb.append("newEnabled=").append(newEnabled);
        sb.append(", newTextChangeCause=").append(newTextChangeCause);
        sb.append(", newContentTypeHint=").append(newContentTypeHint);
        sb.append(", newContentTypePurpose=").append(newContentTypePurpose);
        sb.append(", newCursorRectangle=").append(newCursorRectangle);
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


    public OutgoingChanges setCursorRectangle(Rectangle newCursorRectangle) {
        this.newCursorRectangle = newCursorRectangle;
        return this;
    }

    public Rectangle getCursorRectangle() { return newCursorRectangle; }


    public OutgoingChanges appendChangesFrom(OutgoingChanges src) {
        if (src == null) return this;

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


    public boolean isEmpty() {
        return (getEnabledState() == null && getTextChangeCause() == null && getContentTypeHint() == null && getCursorRectangle() == null);
    }
}
