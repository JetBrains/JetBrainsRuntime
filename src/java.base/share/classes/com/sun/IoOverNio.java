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

package com.sun;  // TODO better package

import jdk.internal.misc.VM;
import sun.security.action.GetPropertyAction;

public class IoOverNio {
    private IoOverNio() { }

    public static final ThreadLocal<Boolean> ALLOW_IO_OVER_NIO = ThreadLocal.withInitial(() -> true);

    public enum Debug {
        NO(false, false),
        ERROR(true, false),
        NO_ERROR(false, true),
        ALL(true, true);

        private final boolean writeErrors;
        private final boolean writeTraces;

        Debug(boolean writeErrors, boolean writeTraces) {
            this.writeErrors = writeErrors;
            this.writeTraces = writeTraces;
        }

        private boolean mayWriteAnything() {
            return VM.isBooted() && IoOverNio.ALLOW_IO_OVER_NIO.get();
        }

        public boolean writeErrors() {
            return writeErrors && mayWriteAnything();
        }

        public boolean writeTraces() {
            return writeTraces && mayWriteAnything();
        }
    }

    public static final Debug DEBUG;

    static {
        String value = GetPropertyAction.privilegedGetProperty("jbr.java.io.use.nio.debug");
        if (value == null) {
            DEBUG = Debug.NO;
        } else {
            switch (value) {
                case "error":
                    DEBUG = Debug.ERROR;
                    break;
                case "no_error":
                    DEBUG = Debug.NO_ERROR;
                    break;
                case "all":
                    DEBUG = Debug.ALL;
                    break;
                default:
                    DEBUG = Debug.NO;
                    break;
            }
        }
    }
}