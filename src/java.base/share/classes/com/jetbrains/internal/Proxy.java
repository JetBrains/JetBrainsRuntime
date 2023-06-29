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
import java.lang.invoke.MethodHandles;
import java.util.Set;

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
 * if any proxy used by it is unsupported.
 * <p>
 * Mapping between interfaces and implementation code is defined in
 * {@linkplain com.jetbrains.registry.JBRApiRegistry registry class}.
 */
class Proxy {
    private static final MethodHandle UNINITIALIZED_HANDLE = MethodHandles.zero(Void.class);

    private final Class<?> interFace, target;
    private volatile ProxyGenerator generator;

    private volatile Boolean supported;

    private volatile Class<?> proxyClass;
    private volatile MethodHandle wrapperConstructor;
    private volatile MethodHandle targetExtractor;
    private volatile MethodHandle serviceConstructor;

    /**
     * Creates empty unsupported proxy.
     */
    Proxy() {
        interFace = target = null;
        supported = false;
    }

    /**
     * Creates a new implicit (specialized) proxy.
     */
    Proxy(ProxyRepository repository, MethodHandles.Lookup lookup, Mapping[] specialization) {
        interFace = target = lookup.lookupClass();
        generator = new ProxyGenerator(repository, lookup, specialization);
    }

    /**
     * Creates a new proxy from {@link ProxyInfo}.
     */
    Proxy(ProxyRepository repository, ProxyInfo info, Mapping[] specialization) {
        interFace = info.interFace;
        target = info.target == null ? null : info.target.lookupClass();
        generator = new ProxyGenerator(repository, info, specialization);
        if (info.type == ProxyInfo.Type.SERVICE) serviceConstructor = UNINITIALIZED_HANDLE;
    }

    /**
     * @return interface class.
     */
    Class<?> getInterface() {
        return interFace;
    }

    /**
     * @return target class.
     */
    Class<?> getTarget() {
        return target;
    }

    /**
     * Checks if all methods are implemented for this proxy and all proxies it uses.
     */
    boolean isSupported() {
        if (supported != null) return supported;
        synchronized (this) {
            if (supported == null) {
                supported = generator.generate();
                for (Proxy p : generator.getDirectProxyDependencies()) {
                    if (!p.isSupported()) {
                        supported = false;
                        break;
                    }
                }
                if (!supported) generator = null; // Release for gc
            }
            return supported;
        }
    }

    private synchronized void defineClasses() {
        if (proxyClass != null) return;
        if (!isSupported()) throw new IllegalStateException("Proxy not supported");
        proxyClass = generator.defineClasses();
        wrapperConstructor = generator.findWrapperConstructor();
        targetExtractor = generator.findTargetExtractor();
        if (serviceConstructor == UNINITIALIZED_HANDLE) serviceConstructor = generator.findServiceConstructor(wrapperConstructor);
        for (Proxy p : generator.getDirectProxyDependencies()) p.defineClasses();
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
     * @return method handle for the constructor of this proxy, or null.
     * Constructor is single-arg, expecting target object to which it would delegate method calls.
     */
    MethodHandle getWrapperConstructor() {
        // wrapperConstructor may be null, so check proxyClass instead
        if (proxyClass != null) return wrapperConstructor;
        synchronized (this) {
            defineClasses();
            return wrapperConstructor;
        }
    }

    /**
     * @return method handle for that extracts target object of the proxy, or null.
     */
    MethodHandle getTargetExtractor() {
        // targetExtractor may be null, so check proxyClass instead
        if (proxyClass != null) return targetExtractor;
        synchronized (this) {
            defineClasses();
            return targetExtractor;
        }
    }

    private synchronized void init() {
        defineClasses();
        if (generator != null) {
            generator.init();
            Set<Proxy> deps = generator.getDirectProxyDependencies();
            generator = null;
            for (Proxy p : deps) p.init();
        }
    }

    /**
     * @return method handle for the constructor of this service, or null. Constructor is no-arg.
     */
    MethodHandle getServiceConstructor() {
        // serviceConstructor may be null, so check proxyClass instead
        if (proxyClass != null) return serviceConstructor;
        synchronized (this) {
            init();
            return serviceConstructor;
        }
    }
}