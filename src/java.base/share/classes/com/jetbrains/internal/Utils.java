package com.jetbrains.internal;

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
