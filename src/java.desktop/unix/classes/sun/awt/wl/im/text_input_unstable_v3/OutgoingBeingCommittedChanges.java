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

import java.util.Objects;


/**
 * This class is intended to keep changes after they get committed and until they actually get applied.
 *
 * @param changeSet changes that have been sent and committed to the compositor,
 *                  but not yet confirmed by it (via a {@code zwp_text_input_v3::done} event).
 *                  Must not be {@code null}.
 * @param commitCounter the number of times a {@code zwp_text_input_v3::commit} request has been issued to
 *                      the corresponding {@link InputContextState}.
 *
 * @see OutgoingChanges
 */
record OutgoingBeingCommittedChanges(OutgoingChanges changeSet, long commitCounter) {
    public OutgoingBeingCommittedChanges {
        Objects.requireNonNull(changeSet, "changeSet");
    }
}
