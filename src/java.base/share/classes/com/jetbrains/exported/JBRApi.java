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

package com.jetbrains.exported;

import jdk.internal.reflect.CallerSensitive;
import jdk.internal.reflect.Reflection;

import java.io.Serial;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * JBR API utility class.
 */
public class JBRApi {
    private JBRApi() {}

    /**
     * Marks classes and interfaces whose implementation is provided by JBR API client.
     * These types must not be inherited by JBR unless explicitly marked with {@link Provides}.
     */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Provided {
        /**
         * Binding target.
         * This is a fully qualified name of corresponding {@code @Provides} class/interface,
         * e.g. {@code com.jetbrains.My.Nested.Class}.
         * Package {@code com.jetbrains} may be omitted, e.g. {@code My.Nested.Class}, {@code MyClass#myMethod}.
         */
        String value();
    }

    /**
     * Marks classes, interfaces and static methods which provide their functionality to JBR API.
     */
    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Provides {
        /**
         * Binding target.
         * For type binding this is a fully qualified name of corresponding {@code @Provided} class/interface,
         * e.g. {@code com.jetbrains.My.Nested.Class}.
         * For static method binding this is a fully qualified name of corresponding {@code @Provided} class/interface with optional
         * method name appended after '#', e.g. {@code com.jetbrains.MyClass#myMethod}.
         * If method name is omitted, name of annotated method itself is used.
         * In all cases, package {@code com.jetbrains} may be omitted, e.g. {@code My.Nested.Class}, {@code MyClass#myMethod}.
         */
        String value();
    }

    /**
     * Marks JBR API service.
     */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Service {}

    /**
     * Creates new internal service instance of caller class type.
     * Intended usage: {@code public static final MyService INSTANCE = JBRApi.internalService();}
     * @return internal service instance
     */
    @CallerSensitive
    @SuppressWarnings("unchecked")
    public static <T> T internalService() {
        var caller = (Class<T>) Reflection.getCallerClass();
        if (caller == null) throw new IllegalCallerException("No caller frame");
        return com.jetbrains.internal.jbrapi.JBRApi.getInternalService(caller);
    }

    /**
     * If a JBR API service has a defined implementation class, it is instantiated via static factory method
     * {@code create()} or a no-arg constructor. {@link ServiceNotAvailableException}
     * may be thrown from that constructor or factory method to indicate that service is unavailable for some reason.
     * Exception is not propagated to user, but rather {@code null} is returned for that service.
     * Optional message and cause can be specified, but only used for logging (when explicitly enabled by VM flag).
     */
    public static class ServiceNotAvailableException extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        public ServiceNotAvailableException() { super(); }

        public ServiceNotAvailableException(String message) { super(message); }

        public ServiceNotAvailableException(String message, Throwable cause) { super(message, cause); }

        public ServiceNotAvailableException(Throwable cause) { super(cause); }
    }
}
