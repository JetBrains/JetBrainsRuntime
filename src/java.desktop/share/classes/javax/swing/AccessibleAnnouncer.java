/*
 * Copyright 2022 JetBrains s.r.o.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

package javax.swing;

import javax.accessibility.Accessible;
import java.lang.annotation.Native;

/**
  * This class provides the ability to speak a given string using screen readers.
 *
 * @author Artem Semenov
 */
public class AccessibleAnnouncer {

    /**
     * Low priority messages do not interrupt the current speech, they are spoken after the screen reader has spoken the current phrase
     */
    @Native public static final int ACCESSIBLE_PRIORITY_LOW = 0;

    /**
     * Medium priority messages interrupt the current speech, but only when the focus is on the window of the calling application
     */
    @Native public static final int ACCESSIBLE_PRIORITY_MEDIUM = 1;

    /**
     * High priority messages interrupt current speech anyway
     */
    @Native public static final int ACCESSIBLE_PRIORITY_HIGH = 2;

    private AccessibleAnnouncer() {}

    /**
     * This method makes an announcement with the specified priority
     *
     * @param str      string for announcing
     * @param priority priority for announsing
     */
    public static void announce(final String str, final int priority) {
        announce(null, str, priority);
    }

    /**
     * This method makes an announcement with the specified priority from an accessible to which the announcing relates
     *
     * @param a      an accessible to which the announcing relates
     * @param str      string for announcing
     * @param priority priority for announsing
     */
    public static native void announce(Accessible a, final String str, final int priority);
}
