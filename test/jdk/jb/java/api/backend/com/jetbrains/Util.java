/*
 * Copyright 2000-2021 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
