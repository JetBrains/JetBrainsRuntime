/*
 * Copyright 2000-2023 JetBrains s.r.o.
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

import java.lang.invoke.MethodHandle;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Proxy is needed to dynamically link JBR API interfaces and implementation at runtime.
 * It implements user-side interfaces and delegates method calls to actual implementation
 * code through {@linkplain java.lang.invoke.MethodHandle method handles}.
 * <p>
 * There are 3 type of proxy objects:
 * <ol>
 *     <li>{@linkplain ProxyInfo.Type#PROXY Proxy} - implements client-side interface from
 *        {@code jetbrains.api} and delegates calls to JBR-side target object and optionally static methods.</li>
 *     <li>{@linkplain ProxyInfo.Type#SERVICE Service} - singleton {@linkplain ProxyInfo.Type#PROXY proxy},
 *        may delegate calls only to static methods, without target object.</li>
 *     <li>{@linkplain ProxyInfo.Type#CLIENT_PROXY Client proxy} - reverse proxy, implements JBR-side interface
 *        and delegates calls to client-side target object by interface defined in {@code jetbrains.api}.
 *        May be used to implement callbacks which are created by client and called by JBR.</li>
 * </ol>
 * <p>
 * Method signatures of proxy interfaces and implementation are validated to ensure that proxy can
 * properly delegate call to the target implementation code. If there's no implementation found for some
 * interface methods, corresponding proxy is considered unsupported. Proxy is also considered unsupported
 * if any proxy used by it is unsupported, more about it at {@link ProxyDependencyManager}.
 * <p>
 * Mapping between interfaces and implementation code is defined in
 * {@linkplain com.jetbrains.bootstrap.JBRApiBootstrap#MODULES registry classes}.
 * @param <INTERFACE> interface type for this proxy.
 */
class Proxy<INTERFACE> {
    private final ProxyInfo info;

    private volatile ProxyGenerator generator;
    private volatile Boolean allMethodsImplemented;

    private volatile Boolean supported;

    private volatile Class<?> proxyClass;
    private volatile MethodHandle constructor;
    private volatile MethodHandle targetExtractor;

    private volatile INTERFACE instance;

    Proxy(ProxyInfo info) {
        this.info = info;
    }

    /**
     * @return {@link ProxyInfo} structure of this proxy
     */
    ProxyInfo getInfo() {
        return info;
    }

    private synchronized void initGenerator() {
        if (generator != null) return;
        generator = new ProxyGenerator(info);
        allMethodsImplemented = generator.areAllMethodsImplemented();
    }

    /**
     * Checks if implementation is found for all abstract interface methods of this proxy.
     */
    boolean areAllMethodsImplemented() {
        if (allMethodsImplemented != null) return allMethodsImplemented;
        synchronized (this) {
            if (allMethodsImplemented == null) initGenerator();
            return allMethodsImplemented;
        }
    }

    /**
     * Checks if all methods are {@linkplain #areAllMethodsImplemented() implemented}
     * for this proxy and all proxies it {@linkplain ProxyDependencyManager uses}.
     */
    boolean isSupported() {
        if (supported != null) return supported;
        synchronized (this) {
            if (supported == null) {
                Set<Class<?>> dependencies = ProxyDependencyManager.getProxyDependencies(info.interFace);
                for (Class<?> d : dependencies) {
                    Proxy<?> p = JBRApi.getProxy(d);
                    if (p == null || !p.areAllMethodsImplemented()) {
                        supported = false;
                        return false;
                    }
                }
                supported = true;
            }
            return supported;
        }
    }

    private synchronized void defineClasses() {
        if (constructor != null) return;
        initGenerator();
        generator.defineClasses();
        proxyClass = generator.getProxyClass();
        constructor = generator.findConstructor();
        targetExtractor = generator.findTargetExtractor();
    }

    /**
     * @return generated proxy class
     */
    Class<?> getProxyClass() {
        if (proxyClass != null) return proxyClass;
        synchronized (this) {
            if (proxyClass == null) defineClasses();
            return proxyClass;
        }
    }

    /**
     * @return method handle for the constructor of this proxy.
     * <ul>
     *     <li>For {@linkplain ProxyInfo.Type#SERVICE services}, constructor is no-arg.</li>
     *     <li>For non-{@linkplain ProxyInfo.Type#SERVICE services}, constructor is single-arg,
     *     expecting target object to which it would delegate method calls.</li>
     * </ul>
     */
    MethodHandle getConstructor() {
        if (constructor != null) return constructor;
        synchronized (this) {
            if (constructor == null) defineClasses();
            return constructor;
        }
    }

    /**
     * @return method handle for that extracts target object of the proxy, or null.
     */
    MethodHandle getTargetExtractor() {
        // targetExtractor may be null, so check constructor instead
        if (constructor != null) return targetExtractor;
        synchronized (this) {
            if (constructor == null) defineClasses();
            return targetExtractor;
        }
    }

    private synchronized void initClass(Set<Proxy<?>> actualUsages) {
        defineClasses();
        if (generator != null) {
            actualUsages.addAll(generator.getDirectProxyDependencies());
            generator.init();
            generator = null;
        }
    }
    private synchronized void initDependencyGraph() {
        defineClasses();
        if (generator == null) return;
        Set<Class<?>> dependencyClasses = ProxyDependencyManager.getProxyDependencies(info.interFace);
        Set<Proxy<?>> dependencies = new HashSet<>();
        Set<Proxy<?>> actualUsages = new HashSet<>();
        for (Class<?> d : dependencyClasses) {
            Proxy<?> p = JBRApi.getProxy(d);
            if (p != null) {
                dependencies.add(p);
                p.initClass(actualUsages);
            }
        }
        actualUsages.removeAll(dependencies);
        if (!actualUsages.isEmpty()) {
            // Should never happen, this is a sign of broken dependency search
            throw new RuntimeException("Some proxies are not in dependencies of " + info.interFace.getName() +
                    ", but are actually used by it: " +
                    actualUsages.stream().map(p -> p.info.interFace.getName()).collect(Collectors.joining(", ")));
        }
    }

    /**
     * @return instance for this {@linkplain ProxyInfo.Type#SERVICE service},
     * returns {@code null} for other proxy types.
     */
    @SuppressWarnings("unchecked")
    INTERFACE getInstance() {
        if (instance != null) return instance;
        if (info.type != ProxyInfo.Type.SERVICE) return null;
        synchronized (this) {
            if (instance == null) {
                initDependencyGraph();
                try {
                    instance = (INTERFACE) getConstructor().invoke();
                } catch (Throwable e) {
                    throw new RuntimeException(e);
                }
            }
            return instance;
        }
    }
}