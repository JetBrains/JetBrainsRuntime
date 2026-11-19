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

package com.jetbrains.internal.jbrapi;

import jdk.internal.misc.VM;

import java.io.PrintStream;
import java.util.function.Function;
import java.util.stream.Stream;

class Utils {

    static final Function<Stream<StackTraceElement>, Stream<StackTraceElement>>
            BEFORE_BOOTSTRAP_DYNAMIC = st -> st
            .dropWhile(e -> !e.getClassName().equals("com.jetbrains.exported.JBRApiSupport") ||
                    !e.getMethodName().equals("bootstrapDynamic")).skip(1)
            .dropWhile(e -> e.getClassName().startsWith("java.lang.invoke."));
    static final Function<Stream<StackTraceElement>, Stream<StackTraceElement>>
            BEFORE_JBR = st -> st
            .dropWhile(e -> !e.getClassName().equals("com.jetbrains.JBR"))
            .dropWhile(e -> e.getClassName().equals("com.jetbrains.JBR"));

    static void log(Function<Stream<StackTraceElement>, Stream<StackTraceElement>> preprocessor,
                                PrintStream out, String message) {
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        synchronized (out) {
            out.println(message);
            preprocessor.apply(Stream.of(st)).forEach(e -> out.println("\tat " + e));
        }
    }

    /**
     * System properties obtained this way are immune to runtime override via {@link System#setProperty(String, String)}.
     */
    static boolean property(String name, boolean defaultValue) {
        String value = VM.getSavedProperty(name);
        if (value != null) {
            if (value.equalsIgnoreCase("true")) return true;
            if (value.equalsIgnoreCase("false")) return false;
        }
        return defaultValue;
    }

    /**
     * System properties obtained this way are immune to runtime override via {@link System#setProperty(String, String)}.
     */
    static String property(String name, String defaultValue) {
        String value = VM.getSavedProperty(name);
        return value != null ? value : defaultValue;
    }
}
