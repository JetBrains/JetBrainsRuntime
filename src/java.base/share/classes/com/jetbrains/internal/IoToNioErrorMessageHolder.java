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

package com.jetbrains.internal;

import java.io.IOException;
import java.util.WeakHashMap;

/**
 * The only purpose of this class is to mimic exception messages of `java.io`
 * when `java.nio.file` is used as a backend.
 */
public class IoToNioErrorMessageHolder {
    private IoToNioErrorMessageHolder() { }

    /**
     * While an exception is being thrown and handled inside {@code catch}-blocks,
     * at least one strong reference exists in the stack.
     * GC won't bring harm if {@link #removeMessage(IOException)} is called inside a {@code catch}-block.
     */
    private static final WeakHashMap<IOException, String> messages = new WeakHashMap<>();

    public static void addMessage(IOException e, String message) {
        if (IoOverNio.IS_ENABLED_IN_GENERAL) {
            synchronized (messages) {
                messages.put(e, message);
            }
        }
    }

    public static String removeMessage(IOException e) {
        if (IoOverNio.IS_ENABLED_IN_GENERAL) {
            synchronized (messages) {
                return messages.remove(e);
            }
        }
        return null;
    }
}
