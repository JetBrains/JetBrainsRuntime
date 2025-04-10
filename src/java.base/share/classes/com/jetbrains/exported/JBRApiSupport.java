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

import com.jetbrains.internal.jbrapi.JBRApi;

import java.lang.annotation.Annotation;
import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.Function;

/**
 * Supporting exported API for JBR API backend. Not intended to be used by JBR or client code directly.
 */
public class JBRApiSupport {
    private JBRApiSupport() {}

    /**
     * Initializes JBR API.
     * @param apiInterface internal {@code JBR.ServiceApi} interface
     * @param serviceAnnotation {@code @Service} annotation class
     * @param providedAnnotation {@code @Provided} annotation class
     * @param providesAnnotation {@code @Provides} annotation class
     * @param knownExtensions map of known extension enums and classes defining them
     * @param extensionExtractor receives method, returns its extension enum, or null
     * @return implementation for {@code JBR.ServiceApi} interface
     */
    @SuppressWarnings("rawtypes")
    public static synchronized Object bootstrap(Class<?> apiInterface,
                                                Class<? extends Annotation> serviceAnnotation,
                                                Class<? extends Annotation> providedAnnotation,
                                                Class<? extends Annotation> providesAnnotation,
                                                Map<Enum<?>, Class[]> knownExtensions,
                                                Function<Method, Enum<?>> extensionExtractor) {
        if (!JBRApi.ENABLED) return null;
        JBRApi.init(
                null,
                serviceAnnotation,
                providedAnnotation,
                providesAnnotation,
                knownExtensions,
                extensionExtractor);
        return JBRApi.getService(apiInterface);
    }

    /**
     * Bootstrap method for JBR API dynamic invocations.
     */
    public static CallSite bootstrapDynamic(MethodHandles.Lookup caller, String name, MethodType type) {
        return new ConstantCallSite(JBRApi.bindDynamic(caller, name, type));
    }

    /**
     * JBR API proxy class. Every generated proxy class implements this interface.
     */
    public interface Proxy {
        /**
         * We really don't want to clash with other possible method names.
         * @return target object
         */
        Object $getProxyTarget();
    }
}
