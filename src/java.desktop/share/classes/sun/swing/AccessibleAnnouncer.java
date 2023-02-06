/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2022, JetBrains s.r.o.. All rights reserved.
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

package sun.swing;

import sun.awt.AWTThreading;

import javax.accessibility.Accessible;
import java.lang.annotation.Native;

/**
  * This class provides the ability to speak a given string using screen readers.
 *
 * @author Artem Semenov
 */
public class AccessibleAnnouncer {

    /**
     * messages do not interrupt the current speech, they are spoken after the screen reader has spoken the current phrase
     */
    @Native public static final int ANNOUNCE_WITHOUT_INTERRUPTING_CURRENT_OUTPUT = 0;

    /**
     * messages interrupt the current speech, but only when the focus is on the window of the calling application
     */
    @Native public static final int ANNOUNCE_WITH_INTERRUPTING_CURRENT_OUTPUT = 1;

    private AccessibleAnnouncer() {}

    /**
     * This method makes an announcement with the specified priority
     *
     * @param str      string for announcing
     * @param priority priority for announcing
     */
    public static void announce(final String str, final int priority) throws Exception {
        announce(null, str, priority);
    }

    /**
     * This method makes an announcement with the specified priority from an accessible to which the announcing relates
     *
     * @param a      an accessible to which the announcing relates
     * @param str      string for announcing
     * @param priority priority for announcing
     */
    public static void announce(Accessible a, final String str, final int priority) throws Exception {
        if (str == null ||
        priority != ANNOUNCE_WITHOUT_INTERRUPTING_CURRENT_OUTPUT &&
        priority != ANNOUNCE_WITH_INTERRUPTING_CURRENT_OUTPUT) {
            throw new IllegalArgumentException("Invalid parameters passed for declaration");
        }

        AWTThreading.executeWaitToolkit(new Runnable() {
            @Override
            public void run() {
                nativeAnnounce(a, str, priority);
            }
        });
    }

    private static native void nativeAnnounce(Accessible a, final String str, final int priority);
}
