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

import java.util.Arrays;
import java.util.Objects;


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
     *                      (i.e. {@link #updatePreeditString(byte[], int, int)} hasn't been called);
     *         an instance of JavaPreeditString otherwise.
     * @see JavaPreeditString
     */
    public JavaPreeditString getPreeditString() {
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
     *                     (i.e. {@link #updateCommitString(byte[])}  hasn't been called);
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


    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        IncomingChanges that = (IncomingChanges) o;
        return doUpdatePreeditString == that.doUpdatePreeditString &&
               newPreeditStringCursorBeginUtf8Byte == that.newPreeditStringCursorBeginUtf8Byte &&
               newPreeditStringCursorEndUtf8Byte == that.newPreeditStringCursorEndUtf8Byte &&
               doUpdateCommitString == that.doUpdateCommitString &&
               Arrays.equals(newPreeditStringUtf8, that.newPreeditStringUtf8) &&
               Arrays.equals(newCommitStringUtf8, that.newCommitStringUtf8);
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
