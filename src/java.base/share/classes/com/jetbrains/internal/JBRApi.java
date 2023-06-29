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

import jdk.internal.loader.ClassLoaders;

import java.io.Serial;
import java.lang.annotation.Annotation;
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
 * {@linkplain com.jetbrains.registry.JBRApiRegistry registry class}.
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
 *     <li>Register your service in corresponding module of the
 *     {@linkplain com.jetbrains.registry.JBRApiRegistry registry class}:
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
    public static final boolean ENABLED = System.getProperty("jetbrains.api.enabled", "true").equalsIgnoreCase("true");
    static final boolean VERBOSE = Boolean.getBoolean("jetbrains.api.verbose");

    private static final ProxyRepository proxyRepository = new ProxyRepository();

    /**
     * lookup context inside {@code jetbrains.api} module
     */
    static Lookup outerLookup;
    /**
     * JBR API version currently supported by this runtime
     */
    static String implVersion;

    static Class<? extends Annotation> serviceAnnotation, proxyAnnotation, clientAnnotation;

    @SuppressWarnings("unchecked")
    public static void init(Lookup outerLookup, String implVersion) {
        JBRApi.outerLookup = outerLookup;
        JBRApi.implVersion = implVersion;
        try {
            Class<?> metadataClass = outerLookup.findClass("com.jetbrains.JBR$Metadata");
            Lookup lookup = MethodHandles.privateLookupIn(metadataClass, outerLookup);
            var knownServices = (String[]) lookup.findStaticVarHandle(metadataClass,
                    "KNOWN_SERVICES", String[].class).get();
            var knownProxies = (String[]) lookup.findStaticVarHandle(metadataClass,
                    "KNOWN_PROXIES", String[].class).get();
            proxyRepository.addKnownProxyNames(knownServices);
            proxyRepository.addKnownProxyNames(knownProxies);
            if (VERBOSE) {
                System.out.println("JBR API init\n  KNOWN_SERVICES = " +
                        Arrays.toString(knownServices) + "\n  KNOWN_PROXIES = " + Arrays.toString(knownProxies));
            }
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
        }
        try { // These annotations may not be available on old JBR API
            serviceAnnotation = (Class<? extends Annotation>) outerLookup.findClass("com.jetbrains.Service");
            proxyAnnotation   = (Class<? extends Annotation>) outerLookup.findClass("com.jetbrains.Proxy");
            clientAnnotation  = (Class<? extends Annotation>) outerLookup.findClass("com.jetbrains.Client");
        } catch (ClassNotFoundException | IllegalAccessException e) {
            if (VERBOSE) e.printStackTrace();
        }
    }

    /**
     * @return JBR API version supported by current implementation.
     */
    public static String getImplVersion() {
        return implVersion;
    }

    /**
     * @return fully supported service implementation for the given interface, or null
     * @apiNote this method is a part of internal {@link com.jetbrains.JBR.ServiceApi}
     * service, but is not directly exposed to user.
     */
    @SuppressWarnings("unchecked")
    public static <T> T getService(Class<T> interFace) {
        Proxy p = proxyRepository.getProxies(interFace, null).byInterface();
        if (p != null && p.isSupported()) {
            try {
                return (T) p.getServiceConstructor().invoke();
            } catch (JBRApi.ServiceNotAvailableException e) {
                if (JBRApi.VERBOSE) {
                    new Exception("Service not available", e).printStackTrace();
                }
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }
        return null;
    }

    public static InternalServiceBuilder internalServiceBuilder(Lookup interFace, String... targets) {
        return new InternalServiceBuilder(new RegisteredProxyInfo(
                interFace, interFace.lookupClass().getName(), targets, ProxyInfo.Type.INTERNAL_SERVICE, new ArrayList<>()));
    }

    public static class InternalServiceBuilder {

        private final RegisteredProxyInfo info;

        private InternalServiceBuilder(RegisteredProxyInfo info) {
            this.info = info;
        }

        public InternalServiceBuilder withStatic(String interfaceMethodName, String methodName, String... classes) {
            info.staticMethods().add(
                    new RegisteredProxyInfo.StaticMethodMapping(interfaceMethodName, methodName, classes));
            return this;
        }

        public Object build() {
            ProxyInfo info = ProxyInfo.resolve(this.info);
            if (info == null) return null;
            ProxyGenerator generator = new ProxyGenerator(proxyRepository, info, null);
            if (!generator.generate()) return null;
            generator.defineClasses();
            generator.init();
            try {
                return generator.findServiceConstructor(generator.findWrapperConstructor()).invoke();
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Called by {@code JBRApiModule} classes to make module usable by JBR API.
     */
    public static Module linkModule(Lookup lookup) {
        if (!JBRApi.ENABLED) throw new IllegalStateException("JBR API is disabled");
        if (VERBOSE) {
            System.out.println("Linking JBR API module " + lookup.lookupClass().getName());
        }
        proxyRepository.registerModule(lookup);
        // Bridges are generated in the same package as JBRApiModule classes (like com.jetbrains.desktop)
        // So we need to export this package to the API module so that proxies could call bridge methods.
        return outerLookup.lookupClass().getModule();
    }

    /**
     * Creates a builder to register mapping for new JBR API module.
     */
    public static ModuleRegistry registerModule(String moduleClassName) {
        if (!JBRApi.ENABLED) throw new IllegalStateException("JBR API is disabled");
        String moduleName = null;
        if (moduleClassName != null) {
            try {
                moduleName = Class.forName(moduleClassName, true, ClassLoaders.platformClassLoader()).getModule().getName();
            } catch (ClassNotFoundException e) {
                if (VERBOSE) e.printStackTrace();
            }
        }
        if (VERBOSE) {
            System.out.println("Registering JBR API module " + moduleName);
        }
        return new ModuleRegistry(proxyRepository, moduleName);
    }

    public static class ModuleRegistry {

        private final ProxyRepository repository;
        private final String moduleName;
        private RegisteredProxyInfo lastProxy;

        private ModuleRegistry(ProxyRepository repository, String moduleName) {
            this.repository = repository;
            this.moduleName = moduleName;
        }

        /**
         * Register new {@linkplain ProxyInfo.Type#PROXY proxy} mapping.
         * <p>
         * When {@code target} object is passed from JBR to client through service or any other proxy,
         * it's converted to corresponding {@code interFace} type by creating a proxy object
         * that implements {@code interFace} and delegates method calls to {@code target}.
         * @param interFace interface name in {@link com.jetbrains.JBR jetbrains.api} module.
         * @param targets corresponding class/interface names in current JBR module, first found will be used. This must not be empty.
         * @apiNote class name example: {@code pac.ka.ge.Outer$Inner}
         */
        public ModuleRegistry proxy(String interFace, String... targets) {
            if (targets.length == 0) throw new IllegalArgumentException("Proxy must have at least one target");
            lastProxy = repository.registerProxy(moduleName, ProxyInfo.Type.PROXY, interFace, targets);
            return this;
        }

        /**
         * Register new {@linkplain ProxyInfo.Type#SERVICE service} mapping.
         * <p>
         * Service is a singleton, which may be accessed by client using {@link com.jetbrains.JBR} class.
         * @param interFace interface name in {@link com.jetbrains.JBR jetbrains.api} module.
         * @param targets corresponding implementation class names in current JBR module, first found will be used.
         * @apiNote class name example: {@code pac.ka.ge.Outer$Inner}
         */
        public ModuleRegistry service(String interFace, String... targets) {
            lastProxy = repository.registerProxy(moduleName, ProxyInfo.Type.SERVICE, interFace, targets);
            return this;
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
            lastProxy = repository.registerProxy(moduleName, ProxyInfo.Type.CLIENT_PROXY, interFace, target);
            return this;
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
         * Delegate "{@code interfaceMethodName}" method calls to first found static "{@code methodName}" in "{@code classes}".
         */
        public ModuleRegistry withStatic(String interfaceMethodName, String methodName, String... classes) {
            lastProxy.staticMethods().add(
                    new RegisteredProxyInfo.StaticMethodMapping(interfaceMethodName, methodName, classes));
            return this;
        }
    }

    /**
     * Thrown by service implementations indicating that the service is not available for some reason
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
