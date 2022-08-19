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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;

import static java.lang.invoke.MethodHandles.Lookup;

/**
 * JBR API is a collection of JBR-specific features that are accessed by client though
 * {@link com.jetbrains.JBR jetbrains.api} module. Actual implementation is linked by
 * JBR at runtime by generating {@linkplain Proxy proxy objects}.
 * Mapping between interfaces and implementation code is defined in
 * {@linkplain com.jetbrains.bootstrap.JBRApiBootstrap#MODULES registry classes}.
 * <p>
 * This class has most basic methods for working with JBR API and cache of generated proxies.
 * <p>
 * <h2>How to add a new service</h2>
 * <ol>
 *     <li>Create your service interface in module {@link com.jetbrains.JBR jetbrains.api}:
 *         <blockquote><pre>{@code
 *         package com.jetbrains;
 *
 *         interface StringOptimizer {
 *             void optimize(String string);
 *         }
 *         }</pre></blockquote>
 *     </li>
 *     <li>Create an implementation inside JBR:
 *         <blockquote><pre>{@link java.lang.String java.lang.String}:{@code
 *         private static void optimizeInternal(String string) {
 *             string.hash = 0;
 *             string.hashIsZero = true;
 *         }
 *         }</pre></blockquote>
 *     </li>
 *     <li>Register your service in corresponding
 *     {@linkplain com.jetbrains.bootstrap.JBRApiBootstrap#MODULES module registry class}:
 *         <blockquote><pre>{@link com.jetbrains.base.JBRApiModule}:{@code
 *         .service("com.jetbrains.StringOptimizer", null)
 *             .withStatic("optimize", "java.lang.String", "optimizeInternal")
 *         }</pre></blockquote>
 *     </li>
 *     <li>You can also bind the interface to implementation class
 *     (without actually implementing the interface):
 *         <blockquote><pre>{@link java.lang.String java.lang.String}:{@code
 *         private static class StringOptimizerImpl {
 *
 *             private void optimize(String string) {
 *                 string.hash = 0;
 *                 string.hashIsZero = true;
 *             }
 *         }
 *         }</pre></blockquote>
 *         <blockquote><pre>{@link com.jetbrains.base.JBRApiModule}:{@code
 *         .service("com.jetbrains.StringOptimizer", "java.lang.String$StringOptimizerImpl")
 *         }</pre></blockquote>
 *     </li>
 * </ol>
 * <h2>How to add a new proxy</h2>
 * Registering a proxy is similar to registering a service:
 * <blockquote><pre>{@code
 * .proxy("com.jetbrains.SomeProxy", "a.b.c.SomeProxyImpl")
 * }</pre></blockquote>
 * Note that unlike service, proxy <b>must</b> have a target type.
 * Also, proxy expects target object as a single constructor argument
 * and can only be instantiated by JBR, there's no API that would allow
 * user to directly create proxy object.
 */
public class JBRApi {
    static final boolean VERBOSE = Boolean.getBoolean("jetbrains.api.verbose");

    private static final Map<String, RegisteredProxyInfo> registeredProxyInfoByInterfaceName = new HashMap<>();
    private static final Map<String, RegisteredProxyInfo> registeredProxyInfoByTargetName = new HashMap<>();
    private static final ConcurrentMap<Class<?>, Proxy<?>> proxyByInterface = new ConcurrentHashMap<>();

    /**
     * lookup context inside {@code jetbrains.api} module
     */
    static Lookup outerLookup;
    /**
     * Known service and proxy interfaces extracted from {@link com.jetbrains.JBR.Metadata}
     */
    static Set<String> knownServices, knownProxies;

    public static void init(Lookup outerLookup) {
        JBRApi.outerLookup = outerLookup;
        try {
            Class<?> metadataClass = outerLookup.findClass("com.jetbrains.JBR$Metadata");
            Lookup lookup = MethodHandles.privateLookupIn(metadataClass, outerLookup);
            knownServices = Set.of((String[]) lookup.findStaticVarHandle(metadataClass,
                    "KNOWN_SERVICES", String[].class).get());
            knownProxies = Set.of((String[]) lookup.findStaticVarHandle(metadataClass,
                    "KNOWN_PROXIES", String[].class).get());
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
            knownServices = Set.of();
            knownProxies = Set.of();
        }
        if (VERBOSE) {
            System.out.println("JBR API init\nKNOWN_SERVICES = " + knownServices + "\nKNOWN_PROXIES = " + knownProxies);
        }
    }

    /**
     * @return fully supported service implementation for the given interface, or null
     * @apiNote this method is a part of internal {@link com.jetbrains.JBR.ServiceApi}
     * service, but is not directly exposed to user.
     */
    public static <T> T getService(Class<T> interFace) {
        Proxy<T> p = getProxy(interFace);
        return p != null && p.isSupported() ? p.getInstance() : null;
    }

    /**
     * @return proxy for the given interface, or {@code null}
     */
    @SuppressWarnings("unchecked")
    static <T> Proxy<T> getProxy(Class<T> interFace) {
        return (Proxy<T>) proxyByInterface.computeIfAbsent(interFace, i -> {
            RegisteredProxyInfo info = registeredProxyInfoByInterfaceName.get(i.getName());
            if (info == null) return null;
            ProxyInfo resolved = ProxyInfo.resolve(info);
            if (resolved == null) {
                if (VERBOSE) {
                    System.err.println("Couldn't resolve proxy info: " + i.getName());
                }
                return null;
            } else return new Proxy<>(resolved);
        });
    }

    /**
     * @return true if given class represents a proxy interface. Even if {@code jetbrains.api}
     * introduces new interfaces JBR is not aware of, these interfaces would still be detected
     * by this method.
     */
    static boolean isKnownProxyInterface(Class<?> clazz) {
        String name = clazz.getName();
        return registeredProxyInfoByInterfaceName.containsKey(name) ||
                knownServices.contains(name) || knownProxies.contains(name);
    }

    /**
     * Reverse lookup by proxy target type name.
     * @return user-side interface for given implementation target type name.
     */
    static Class<?> getProxyInterfaceByTargetName(String targetName) {
        RegisteredProxyInfo info = registeredProxyInfoByTargetName.get(targetName);
        if (info == null) return null;
        try {
            return Class.forName(info.interfaceName(), true,
                    (info.type().isPublicApi() ? outerLookup : info.apiModule()).lookupClass().getClassLoader());
        } catch (ClassNotFoundException e) {
            if (VERBOSE) e.printStackTrace();
            return null;
        }
    }

    public static InternalServiceBuilder internalServiceBuilder(Lookup interFace, String target) {
        return new InternalServiceBuilder(new RegisteredProxyInfo(
                interFace, interFace.lookupClass().getName(), target, ProxyInfo.Type.INTERNAL_SERVICE, new ArrayList<>()));
    }

    public static class InternalServiceBuilder {

        private final RegisteredProxyInfo info;

        private InternalServiceBuilder(RegisteredProxyInfo info) {
            this.info = info;
        }

        public InternalServiceBuilder withStatic(String methodName, String clazz) {
            return withStatic(methodName, clazz, methodName);
        }

        public InternalServiceBuilder withStatic(String interfaceMethodName, String clazz, String methodName) {
            info.staticMethods().add(
                    new RegisteredProxyInfo.StaticMethodMapping(interfaceMethodName, clazz, methodName));
            return this;
        }

        public Object build() {
            ProxyInfo info = ProxyInfo.resolve(this.info);
            if (info == null) return null;
            ProxyGenerator generator = new ProxyGenerator(info);
            if (!generator.areAllMethodsImplemented()) return null;
            generator.defineClasses();
            MethodHandle constructor = generator.findConstructor();
            generator.init();
            try {
                return constructor.invoke();
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Called by {@linkplain com.jetbrains.bootstrap.JBRApiBootstrap#MODULES registry classes}
     * to register a new mapping for corresponding modules.
     */
    public static ModuleRegistry registerModule(Lookup lookup, BiFunction<String, Module, Module> addExports) {
        addExports.apply(lookup.lookupClass().getPackageName(), outerLookup.lookupClass().getModule());
        return new ModuleRegistry(lookup);
    }

    public static class ModuleRegistry {

        private final Lookup lookup;
        private RegisteredProxyInfo lastProxy;

        private ModuleRegistry(Lookup lookup) {
            this.lookup = lookup;
        }

        private ModuleRegistry addProxy(String interfaceName, String target, ProxyInfo.Type type) {
            lastProxy = new RegisteredProxyInfo(lookup, interfaceName, target, type, new ArrayList<>());
            registeredProxyInfoByInterfaceName.put(interfaceName, lastProxy);
            if (target != null) {
                registeredProxyInfoByTargetName.put(target, lastProxy);
                validate2WayMapping(lastProxy, registeredProxyInfoByInterfaceName.get(target));
                validate2WayMapping(lastProxy, registeredProxyInfoByTargetName.get(interfaceName));
            }
            return this;
        }
        private static void validate2WayMapping(RegisteredProxyInfo p, RegisteredProxyInfo reverse) {
            if (reverse != null &&
                    (!p.interfaceName().equals(reverse.target()) || !p.target().equals(reverse.interfaceName()))) {
                throw new IllegalArgumentException("Invalid 2-way proxy mapping: " +
                        p.interfaceName() + " -> " + p.target() + " & " +
                        reverse.interfaceName() + " -> " + reverse.target());
            }
        }

        /**
         * Register new {@linkplain ProxyInfo.Type#PROXY proxy} mapping.
         * <p>
         * When {@code target} object is passed from JBR to client through service or any other proxy,
         * it's converted to corresponding {@code interFace} type by creating a proxy object
         * that implements {@code interFace} and delegates method calls to {@code target}.
         * @param interFace interface name in {@link com.jetbrains.JBR jetbrains.api} module.
         * @param target corresponding class/interface name in current JBR module.
         * @apiNote class name example: {@code pac.ka.ge.Outer$Inner}
         */
        public ModuleRegistry proxy(String interFace, String target) {
            Objects.requireNonNull(target);
            return addProxy(interFace, target, ProxyInfo.Type.PROXY);
        }

        /**
         * Register new {@linkplain ProxyInfo.Type#SERVICE service} mapping.
         * <p>
         * Service is a singleton, which may be accessed by client using {@link com.jetbrains.JBR} class.
         * @param interFace interface name in {@link com.jetbrains.JBR jetbrains.api} module.
         * @param target corresponding implementation class name in current JBR module, or null.
         * @apiNote class name example: {@code pac.ka.ge.Outer$Inner}
         */
        public ModuleRegistry service(String interFace, String target) {
            return addProxy(interFace, target, ProxyInfo.Type.SERVICE);
        }

        /**
         * Register new {@linkplain ProxyInfo.Type#CLIENT_PROXY client proxy} mapping.
         * This mapping type allows implementation of callbacks.
         * <p>
         * When {@code target} object is passed from client to JBR through service or any other proxy,
         * it's converted to corresponding {@code interFace} type by creating a proxy object
         * that implements {@code interFace} and delegates method calls to {@code target}.
         * @param interFace interface name in current JBR module.
         * @param target corresponding class/interface name in {@link com.jetbrains.JBR jetbrains.api} module.
         * @apiNote class name example: {@code pac.ka.ge.Outer$Inner}
         */
        public ModuleRegistry clientProxy(String interFace, String target) {
            Objects.requireNonNull(target);
            return addProxy(interFace, target, ProxyInfo.Type.CLIENT_PROXY);
        }

        /**
         * Register new 2-way mapping.
         * It creates both {@linkplain ProxyInfo.Type#PROXY proxy} and
         * {@linkplain ProxyInfo.Type#CLIENT_PROXY client proxy} between given interfaces.
         * <p>
         * It links together two given interfaces and allows passing such objects back and forth
         * between JBR and {@link com.jetbrains.JBR jetbrains.api} module through services and other proxy methods.
         * @param apiInterface interface name in {@link com.jetbrains.JBR jetbrains.api} module.
         * @param jbrInterface interface name in current JBR module.
         * @apiNote class name example: {@code pac.ka.ge.Outer$Inner}
         */
        public ModuleRegistry twoWayProxy(String apiInterface, String jbrInterface) {
            clientProxy(jbrInterface, apiInterface);
            proxy(apiInterface, jbrInterface);
            return this;
        }

        /**
         * Delegate interface "{@code methodName}" calls to static "{@code methodName}" in "{@code clazz}".
         * @see #withStatic(String, String, String)
         */
        public ModuleRegistry withStatic(String methodName, String clazz) {
            return withStatic(methodName, clazz, methodName);
        }

        /**
         * Delegate "{@code interfaceMethodName}" method calls to static "{@code methodName}" in "{@code clazz}".
         */
        public ModuleRegistry withStatic(String interfaceMethodName, String clazz, String methodName) {
            lastProxy.staticMethods().add(
                    new RegisteredProxyInfo.StaticMethodMapping(interfaceMethodName, clazz, methodName));
            return this;
        }
    }
}
