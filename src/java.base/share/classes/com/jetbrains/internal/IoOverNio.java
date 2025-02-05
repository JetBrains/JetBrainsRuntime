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

import jdk.internal.misc.VM;
import sun.security.action.GetPropertyAction;

import java.nio.file.FileSystems;

/**
 * Internal methods to control the feature of using {@link java.nio.file} inside {@link java.io}.
 */
public class IoOverNio {
    /** Preferences of debug logging. */
    public static final Debug DEBUG;
    public static final boolean IS_ENABLED_IN_GENERAL =
            GetPropertyAction.privilegedGetBooleanProp("jbr.java.io.use.nio", true, null);
    private static final ThreadLocal<Boolean> ALLOW_IN_THIS_THREAD = ThreadLocal.withInitial(() -> true);

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

    private IoOverNio() {
    }

    public static boolean isAllowedInThisThread() {
        return ALLOW_IN_THIS_THREAD.get();
    }

    /**
     * This method helps to prevent infinite recursion in specific areas like class loading.
     * An attempt to access {@link FileSystems#getDefault()} during class loading
     * would require loading the class of the default file system provider,
     * which would recursively require the access to {@link FileSystems#getDefault()}.
     * <p>
     * Beware that this function does not support correctly nested invocations.
     * However, it doesn't bring any problem in practice.
     * <p>
     * Usage:
     * <pre>
     * {@code
     * @SuppressWarnings("try")
     * void method() {
     *     try (var ignored = IoOverNio.disableInThisThread()) {
     *         // do something here.
     *     }
     * }
     * }
     * </pre>
     * </p>
     */
    public static ThreadLocalCloseable disableInThisThread() {
        ALLOW_IN_THIS_THREAD.set(false);
        return ThreadLocalCloseable.INSTANCE;
    }

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
            return VM.isBooted() && IoOverNio.ALLOW_IN_THIS_THREAD.get();
        }

        public boolean writeErrors() {
            return writeErrors && mayWriteAnything();
        }

        public boolean writeTraces() {
            return writeTraces && mayWriteAnything();
        }
    }

    public static class ThreadLocalCloseable implements AutoCloseable {
        private static final ThreadLocalCloseable INSTANCE = new ThreadLocalCloseable();

        private ThreadLocalCloseable() {
        }

        @Override
        public void close() {
            ALLOW_IN_THIS_THREAD.set(true);
        }
    }
}