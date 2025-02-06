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
import sun.nio.ch.FileChannelImpl;

import java.io.Closeable;
import java.nio.file.FileSystems;

/**
 * Internal methods to control the feature of using {@link java.nio.file} inside {@link java.io}.
 */
public class IoOverNio {
    /**
     * Preferences of debug logging.
     */
    public static final Debug DEBUG;
    public static final boolean IS_ENABLED_IN_GENERAL =
            System.getProperty("jbr.java.io.use.nio", "true").equalsIgnoreCase("true");
    private static final ThreadLocal<Integer> ALLOW_IN_THIS_THREAD = new ThreadLocal<>();

    static {
        String value = System.getProperty("jbr.java.io.use.nio.debug", "");
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

    private IoOverNio() {
    }

    public static boolean isAllowedInThisThread() {
        return ALLOW_IN_THIS_THREAD.get() == null;
    }

    /**
     * This method helps to prevent infinite recursion in specific areas like class loading.
     * An attempt to access {@link FileSystems#getDefault()} during class loading
     * would require loading the class of the default file system provider,
     * which would recursively require the access to {@link FileSystems#getDefault()}.
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
     *
     * <hr/>
     *
     * An implementation note.
     * There may be a temptation to call {@link FileSystems#getDefault()} at some very early stage of loading JVM.
     * However, it won't be enough.
     * {@link FileSystems#getDefault()} forces an immediate load only of
     * the corresponding instance of {@link java.nio.file.spi.FileSystemProvider}.
     * Meanwhile, a correct operation of a NIO filesystem requires also implementations of {@link java.nio.file.Path},
     * {@link java.nio.file.FileSystem}, {@link java.nio.file.attribute.BasicFileAttributes} and many other classes.
     * Without this method, the classloader still can dive into an infinite recursion.
     */
    public static ThreadLocalCloseable disableInThisThread() {
        Integer value = ALLOW_IN_THIS_THREAD.get();
        if (value == null) {
            value = 0;
        }
        ++value;
        ALLOW_IN_THIS_THREAD.set(value);
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
            return VM.isBooted() && isAllowedInThisThread();
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
            Integer value = ALLOW_IN_THIS_THREAD.get();
            --value;
            if (value == 0) {
                value = null;
            }
            ALLOW_IN_THIS_THREAD.set(value);
        }
    }

    /**
     * Since it's complicated to change Java API, some new function arguments are transferred via thread local variables.
     * This variable allows to set {@code parent} in {@link FileChannelImpl} and therefore to avoid closing the file
     * descriptor too early.
     * <p>
     * The problem was found with the test {@code jtreg:test/jdk/java/io/FileDescriptor/Sharing.java}.
     */
    public static final ThreadLocal<Closeable> PARENT_FOR_FILE_CHANNEL_IMPL = new ThreadLocal<>();
}