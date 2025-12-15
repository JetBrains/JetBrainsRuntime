/*
 * Copyright 2000-2025 JetBrains s.r.o.
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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.*;

import static java.lang.invoke.MethodHandles.Lookup;

/**
 * Proxy is needed to dynamically link JBR API interfaces and implementation at runtime.
 * Proxy implements or extends a base interface or class and delegates method calls to
 * target object or static methods. Calls may be delegated even to methods inaccessible by base type.
 * <p>
 * Mapping between interfaces and implementation code is defined using
 * {@link com.jetbrains.exported.JBRApi.Provided} and {@link com.jetbrains.exported.JBRApi.Provides} annotations.
 * <p>
 * When JBR API interface or imeplementation type is used as a method parameter or return type,
 * JBR API backend performs conversions to ensure each side receives object or correct type,
 * for example, given following types:
 * <blockquote><pre>{@code
 * interface A {
 *     B foo(B b);
 * }
 * interface B {
 *     void bar();
 * }
 * class AImpl {
 *     BImpl foo(BImpl b) {}
 * }
 * class BImpl {
 *     void bar() {}
 * }
 * }</pre></blockquote>
 * {@code A#foo(B)} will be mapped to {@code AImpl#foo(BImpl)}.
 * Conversion between {@code B} and {@code BImpl} will be done by JBR API backend.
 * Here's what generated proxies would look like:
 * <blockquote><pre>{@code
 * final class AProxy implements A {
 *     final AImpl target;
 *     AProxy(AImpl t) {
 *         target = t;
 *     }
 *     @Override
 *     B foo(B b) {
 *         BImpl ret = t.foo(b.target);
 *         return new BProxy(ret);
 *     }
 * }
 * final class BProxy implements B {
 *     final BImpl target;
 *     BProxy(BImpl t) {
 *         target = t;
 *     }
 *     @Override
 *     void bar() {
 *         t.bar();
 *     }
 * }
 * }</pre></blockquote>
 * <p>
 * Method signatures of base type and implementation are validated to ensure that proxy can
 * properly delegate call to the target implementation code. If there's no implementation found for some
 * interface methods, corresponding proxy is considered unsupported. Proxy is also considered unsupported
 * if any proxy used by it is unsupported.
 * <p>
 * Extensions are an exception to this rule. Methods of base type may be marked as extension methods,
 * which makes them optional for support checks. Moreover, extension must be explicitly enabled for a
 * proxy instance to enable usage of corresponding methods.
 */
class Proxy {
    /**
     * @see Proxy.Info#flags
     */
    static final int SERVICE = 1;

    private final Proxy inverse;
    private final Class<?> interFace, target;
    private final int flags;
    private volatile ProxyGenerator generator;
    private volatile Boolean supported;

    private volatile Set<Proxy> directDependencies = Set.of();
    private volatile Set<Enum<?>> supportedExtensions = Set.of();

    private volatile MethodHandle constructor;

    /**
     * Creates empty proxy.
     */
    static Proxy empty(Boolean supported) {
        return new Proxy(supported);
    }

    /**
     * Creates a new proxy (and possibly an inverse one) from {@link Info}.
     */
    static Proxy create(ProxyRepository repository,
                        Info info, Mapping[] specialization,
                        Info inverseInfo, Mapping[] inverseSpecialization) {
        return new Proxy(repository, info, specialization, null, inverseInfo, inverseSpecialization);
    }

    private Proxy(Boolean supported) {
        interFace = target = null;
        flags = 0;
        inverse = this;
        this.supported = supported;
    }

    private Proxy(ProxyRepository repository, Info info, Mapping[] specialization,
                  Proxy inverseProxy, Info inverseInfo, Mapping[] inverseSpecialization) {
        if (info != null) {
            interFace = info.interfaceLookup.lookupClass();
            target = info.targetLookup == null ? null : info.targetLookup.lookupClass();
            flags = info.flags;
            generator = new ProxyGenerator(repository, info, specialization);
        } else {
            interFace = target = null;
            flags = 0;
        }
        inverse = inverseProxy == null ? new Proxy(repository, inverseInfo, inverseSpecialization, this, null, null) : inverseProxy;
        if (inverse.getInterface() != null) directDependencies = Set.of(inverse);
    }

    /**
     * @return inverse proxy
     */
    Proxy inverse() {
        return inverse;
    }

    /**
     * @return interface class
     */
    Class<?> getInterface() {
        return interFace;
    }

    /**
     * @return target class
     */
    Class<?> getTarget() {
        return target;
    }

    /**
     * @return flags
     */
    int getFlags() {
        return flags;
    }

    /**
     * Checks if all methods are implemented for this proxy and all proxies it uses.
     * {@code null} means not known yet.
     */
    Boolean supported() {
        return supported;
    }

    private synchronized boolean generate() {
        if (supported != null) return supported;
        if (generator == null) return false;
        Set<Proxy> deps = new HashSet<>(directDependencies);
        supported = generator.generate();
        for (Map.Entry<Proxy, Boolean> e : generator.getDependencies().entrySet()) {
            if (e.getKey().generate()) deps.add(e.getKey());
            else if (e.getValue()) {
                supported = false;
                break;
            }
        }
        if (supported) {
            directDependencies = deps;
            supportedExtensions = generator.getSupportedExtensions();
        } else generator = null; // Release for gc
        return supported;
    }

    /**
     * Checks if specified extension is implemented by this proxy.
     * Implicitly runs bytecode generation.
     */
    boolean isExtensionSupported(Enum<?> extension) {
        if (supported == null) generate();
        return supportedExtensions.contains(extension);
    }

    private synchronized boolean define() {
        if (constructor != null) return true;
        if (!generate()) return false;
        constructor = generator.define((flags & SERVICE) != 0);
        if (constructor == null) {
            supported = false;
            return false;
        }
        for (Proxy p : directDependencies) {
            if (!p.define()) return false;
        }
        return true;
    }

    synchronized boolean init() {
        if (!define()) return false;
        if (generator != null) {
            ProxyGenerator gen = generator;
            generator = null;
            gen.init();
            for (Proxy p : directDependencies) p.init();
        }
        return supported;
    }

    /**
     * @return method handle for the constructor of this proxy.
     * First parameter is target object to which it would delegate method calls.
     * Second parameter is extensions bitfield (if extensions are enabled).
     */
    MethodHandle getConstructor() throws NullPointerException {
        if (JBRApi.LOG_DEPRECATED && interFace.isAnnotationPresent(Deprecated.class)) {
            Utils.log(Utils.BEFORE_BOOTSTRAP_DYNAMIC, System.err,
                    "Warning: using deprecated JBR API interface " + interFace.getCanonicalName());
        }
        return constructor;
    }

    /**
     * Proxy descriptor with all classes and lookup contexts resolved.
     * Contains all necessary information to create a {@linkplain Proxy proxy}.
     */
    static class Info {
        private record StaticMethod(String name, MethodType targetType) {}

        private final Map<StaticMethod, MethodHandle> staticMethods = new HashMap<>();
        final Lookup interfaceLookup;
        final Lookup targetLookup;
        private final int flags;

        Info(Lookup interfaceLookup, Lookup targetLookup, int flags) {
            this.interfaceLookup = interfaceLookup;
            this.targetLookup = targetLookup;
            this.flags = flags;
        }

        void addStaticMethod(String name, MethodHandle target) {
            staticMethods.put(new StaticMethod(name, target.type()), target);
        }

        MethodHandle getStaticMethod(String name, MethodType targetType) {
            return staticMethods.get(new StaticMethod(name, targetType));
        }
    }
}