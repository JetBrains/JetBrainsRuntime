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

import com.jetbrains.internal.jbrapi.JBRApi;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

public class Util {

    public static JBRApi api;
    private static Object proxyRepository;
    private static Method getProxy, inverse, generate;

    /**
     * Invoke internal {@link JBRApi#init} bypassing {@link com.jetbrains.exported.JBRApiSupport#bootstrap}.
     */
    public static void init(String registryName, Map<Enum<?>, Class<?>[]> extensionClasses) {
        try (InputStream in = new SequenceInputStream(
                new ByteArrayInputStream("PROVIDES com.jetbrains.internal.jbrapi.JBRApi com.jetbrains.JBR.ServiceApi\n".getBytes()),
                new FileInputStream(new File(System.getProperty("test.src", "."), registryName + ".registry")))) {
            Object api = JBRApi.init(in, JBR.ServiceApi.class, Service.class, Provided.class, Provides.class, extensionClasses, m -> {
                Extension e = m.getAnnotation(Extension.class);
                return e == null ? null : e.value();
            });
            Field f = api.getClass().getDeclaredField("target");
            f.setAccessible(true);
            Util.api = (JBRApi) f.get(api);
            proxyRepository = null;
            getProxy = inverse = generate = null;
        } catch (IOException | NoSuchFieldException | IllegalAccessException e) {
            throw new Error(e);
        }
    }

    public static Object getProxyRepository() throws Throwable {
        if (proxyRepository == null) {
            Field f = JBRApi.class.getDeclaredField("proxyRepository");
            f.setAccessible(true);
            proxyRepository = f.get(api);
        }
        return proxyRepository;
    }

    public static Object getProxy(Class<?> interFace) throws Throwable {
        var repo = getProxyRepository();
        if (getProxy == null) {
            getProxy = repo.getClass()
                    .getDeclaredMethod("getProxy", Class.class, Class.forName("com.jetbrains.internal.jbrapi.Mapping").arrayType());
            getProxy.setAccessible(true);
        }
        return getProxy.invoke(repo, interFace, null);
    }

    public static Object inverse(Object proxy) throws Throwable {
        if (inverse == null) {
            inverse = proxy.getClass().getDeclaredMethod("inverse");
            inverse.setAccessible(true);
        }
        return inverse.invoke(proxy);
    }

    public static boolean isSupported(Object proxy) throws Throwable {
        if (generate == null) {
            generate = proxy.getClass().getDeclaredMethod("generate");
            generate.setAccessible(true);
        }
        return (boolean) generate.invoke(proxy);
    }

    public static void requireSupported(Object proxy) throws Throwable {
        if (!isSupported(proxy)) throw new RuntimeException("Proxy must be supported");
    }

    public static void requireUnsupported(Object proxy) throws Throwable {
        if (isSupported(proxy)) throw new RuntimeException("Proxy must be unsupported");
    }

    public static void requireNull(Object o) {
        if (o != null) throw new RuntimeException("Value must be null");
    }

}
