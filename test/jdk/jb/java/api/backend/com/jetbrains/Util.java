/*
 * Copyright 2000-2023 JetBrains s.r.o.
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

package com.jetbrains;

import com.jetbrains.internal.JBRApi;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class Util {

    public static ReflectedMethod getMethod(String className, String method, Class<?>... parameterTypes) {
        try {
            Method m = Class.forName(className, false, JBRApi.class.getClassLoader())
                    .getDeclaredMethod(method, parameterTypes);
            m.setAccessible(true);
            return (o, a) -> {
                try {
                    return m.invoke(o, a);
                } catch (IllegalAccessException e) {
                    throw new Error(e);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            };
        } catch (NoSuchMethodException | ClassNotFoundException e) {
            throw new Error(e);
        }
    }

    public static JBRApi.ModuleRegistry init() {
        return init(new String[0], new String[0]);
    }

    /**
     * Set known services & proxies at runtime and invoke internal
     * {@link JBRApi#init} bypassing {@link com.jetbrains.bootstrap.JBRApiBootstrap#bootstrap}
     * in order not to init normal JBR API modules.
     */
    public static JBRApi.ModuleRegistry init(String[] knownServices, String[] knownProxies) {
        JBR.Metadata.KNOWN_SERVICES = knownServices;
        JBR.Metadata.KNOWN_PROXIES = knownProxies;
        JBRApi.init(MethodHandles.lookup());
        return JBRApi.registerModule(MethodHandles.lookup(), JBR.class.getModule()::addExports);
    }

    private static final ReflectedMethod getProxy = getMethod(JBRApi.class.getName(), "getProxy", Class.class);
    public static Object getProxy(Class<?> interFace) throws Throwable {
        return getProxy.invoke(null, interFace);
    }

    public static void requireNull(Object o) {
        if (o != null) throw new RuntimeException("Value must be null");
    }

    @SafeVarargs
    public static void mustFail(ThrowingRunnable action, Class<? extends Throwable>... exceptionTypeChain) {
        try {
            action.run();
        } catch(Throwable exception) {
            Throwable e = exception;
            error: {
                for (Class<? extends Throwable> c : exceptionTypeChain) {
                    if (e == null || !c.isInstance(e)) break error;
                    e = e.getCause();
                }
                if (e == null) return;
            }
            throw new Error("Unexpected exception", exception);
        }
        throw new Error("Operation must fail, but succeeded");
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Throwable;
    }

    @FunctionalInterface
    public interface ReflectedMethod {
        Object invoke(Object obj, Object... args) throws Throwable;
    }
}
