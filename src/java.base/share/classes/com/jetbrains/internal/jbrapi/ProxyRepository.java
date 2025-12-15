/*
 * Copyright 2023-2025 JetBrains s.r.o.
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

import jdk.internal.access.SharedSecrets;
import jdk.internal.loader.BootLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;

import static java.lang.invoke.MethodHandles.Lookup;

/**
 * Proxy repository keeps track of all generated proxies.
 * @see ProxyRepository#getProxy(Class, Mapping[])
 */
class ProxyRepository {
    private static final Proxy NONE = Proxy.empty(null), INVALID = Proxy.empty(false);

    private final Map<Key, Proxy> proxies = new HashMap<>();
    private final Registry registry;
    private final ClassLoader classLoader;
    private final Class<? extends Annotation> serviceAnnotation, providedAnnotation, providesAnnotation;
    private final Module annotationsModule;
    final Function<Method, Enum<?>> extensionExtractor;

    ProxyRepository(Registry registry,
                    ClassLoader classLoader,
                    Class<? extends Annotation> serviceAnnotation,
                    Class<? extends Annotation> providedAnnotation,
                    Class<? extends Annotation> providesAnnotation,
                    Function<Method, Enum<?>> extensionExtractor) {
        this.registry = registry;
        this.classLoader = classLoader;
        this.serviceAnnotation = serviceAnnotation;
        this.providedAnnotation = providedAnnotation;
        this.providesAnnotation = providesAnnotation;
        this.extensionExtractor = extensionExtractor;
        annotationsModule = serviceAnnotation == null ? null : serviceAnnotation.getModule();
    }

    String getVersion() {
        return registry.version;
    }

    synchronized Proxy getProxy(Class<?> clazz, Mapping[] specialization) {
        Key key = new Key(clazz, specialization);
        Proxy p = proxies.get(key);
        if (p == null) {
            Mapping[] inverseSpecialization = specialization == null ? null :
                    Stream.of(specialization).map(m -> m == null ? null : m.inverse()).toArray(Mapping[]::new);
            Key inverseKey = null;

            Registry.Entry entry = registry.entries.get(key.clazz().getCanonicalName());
            if (entry != null) { // This is a registered proxy
                Proxy.Info infoByInterface = entry.resolve(this),
                        infoByTarget = entry.inverse != null ? entry.inverse.resolve(this) : null;
                inverseKey = infoByTarget != null && infoByTarget.interfaceLookup != null ?
                        new Key(infoByTarget.interfaceLookup.lookupClass(), inverseSpecialization) : null;
                if ((infoByInterface == null && infoByTarget == null) ||
                        infoByInterface == Registry.Entry.INVALID ||
                        infoByTarget == Registry.Entry.INVALID) {
                    p = INVALID;
                } else {
                    p = Proxy.create(this, infoByInterface, specialization, infoByTarget, inverseSpecialization);
                }
            } else if (!Arrays.equals(specialization, inverseSpecialization)) { // Try implicit proxy
                inverseKey = new Key(clazz, inverseSpecialization);
                Lookup lookup = SharedSecrets.getJavaLangInvokeAccess().lookupIn(clazz);
                Proxy.Info info = new Proxy.Info(lookup, lookup, 0);
                p = Proxy.create(this, info, specialization, info, inverseSpecialization);
            } else p = NONE;
            proxies.put(key, p);
            if (inverseKey != null) proxies.put(inverseKey, p.inverse());
        }
        return p;
    }

    private record Key(Class<?> clazz, Mapping[] specialization) {

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Key key = (Key) o;
            if (!clazz.equals(key.clazz)) return false;
            return Arrays.equals(specialization, key.specialization);
        }

        @Override
        public int hashCode() {
            int result = clazz.hashCode();
            result = 31 * result + Arrays.hashCode(specialization);
            return result;
        }

        @Override
        public String toString() {
            return clazz.getName() + "<" + Arrays.toString(specialization) + ">";
        }
    }

    /**
     * Registry contains all information about mapping between JBR API interfaces and implementation.
     * This mapping information can be {@linkplain Entry#resolve(ProxyRepository) resolved} into {@link Proxy.Info}.
     */
    static class Registry {

        private record StaticKey(String methodName, String targetMethodDescriptor) {}
        private record StaticValue(String targetType, String targetMethodName) {}

        private class Entry {
            private static final Proxy.Info INVALID = new Proxy.Info(null, null, 0);

            private final Map<StaticKey, StaticValue> staticMethods = new HashMap<>();
            private String type, target;
            private Entry inverse;
            private int flags;

            private Entry(String type) { this.type = type; }

            private Proxy.Info resolve(ProxyRepository repository) {
                if (type == null) return null;
                Lookup l, t;
                try {
                    l = resolveType(type, repository.classLoader);
                    t = target != null ? resolveType(target, repository.classLoader) : null;
                } catch (ClassNotFoundException e) {
                    if (JBRApi.VERBOSE) {
                        System.err.println(type + " not eligible");
                        e.printStackTrace(System.err);
                    }
                    return INVALID;
                }
                if (l.lookupClass().isAnnotation() || l.lookupClass().isEnum() || l.lookupClass().isRecord()) {
                    if (JBRApi.VERBOSE) {
                        System.err.println(type + " not eligible: not a class or interface");
                    }
                    return INVALID;
                }
                if (Modifier.isFinal(l.lookupClass().getModifiers()) || l.lookupClass().isSealed()) {
                    if (JBRApi.VERBOSE) {
                        System.err.println(type + " not eligible: invalid type (final/sealed)");
                    }
                    return INVALID;
                }
                if (target == null) flags |= Proxy.SERVICE;
                if (needsAnnotation(repository, l.lookupClass())) {
                    if (!hasAnnotation(l.lookupClass(), repository.providedAnnotation)) {
                        if (JBRApi.VERBOSE) {
                            System.err.println(type + " not eligible: no @Provided annotation");
                        }
                        return INVALID;
                    }
                    if (!hasAnnotation(l.lookupClass(), repository.serviceAnnotation)) flags &= ~Proxy.SERVICE;
                }
                Proxy.Info info;
                if (t != null) {
                    if (needsAnnotation(repository, t.lookupClass()) && !hasAnnotation(t.lookupClass(), repository.providesAnnotation)) {
                        if (JBRApi.VERBOSE) {
                            System.err.println(target + " not eligible: no @Provides annotation");
                        }
                        return INVALID;
                    }
                    info = new Proxy.Info(l, t, flags);
                } else info = new Proxy.Info(l, null, flags);
                for (var method : staticMethods.entrySet()) {
                    String methodName = method.getKey().methodName;
                    String targetMethodDescriptor = method.getKey().targetMethodDescriptor;
                    String targetType = method.getValue().targetType;
                    String targetMethodName = method.getValue().targetMethodName;
                    try {
                        Lookup lookup = resolveType(targetType, repository.classLoader);
                        MethodType mt = MethodType.fromMethodDescriptorString(targetMethodDescriptor, repository.classLoader);
                        MethodHandle handle = lookup.findStatic(lookup.lookupClass(), targetMethodName, mt);
                        info.addStaticMethod(methodName, handle);
                    } catch (ClassNotFoundException | IllegalArgumentException | TypeNotPresentException |
                             NoSuchMethodException | IllegalAccessException | ClassCastException e) {
                        if (JBRApi.VERBOSE) {
                            System.err.println(targetType + "#" + targetMethodName + " cannot be resolved");
                            e.printStackTrace(System.err);
                        }
                    }
                }
                return info;
            }

            private static Lookup resolveType(String type, ClassLoader classLoader) throws ClassNotFoundException {
                Class<?> c = null;
                try {
                    c = Class.forName(type, false, classLoader);
                } catch (ClassNotFoundException e) {
                    String dollars = type.replace('.', '$');
                    for (int i = 0;; i++) {
                        i = type.indexOf('.', i);
                        if (i == -1) break;
                        String name = type.substring(0, i) + dollars.substring(i);
                        try {
                            c = Class.forName(name, false, classLoader);
                            break;
                        } catch (ClassNotFoundException ignore) {}
                    }
                    if (c == null) throw e;
                }
                return SharedSecrets.getJavaLangInvokeAccess().lookupIn(c);
            }

            @Override
            public String toString() { return type; }
        }

        static class Builtin {
            static final Registry PRIVATE, PUBLIC;
            static {
                try {
                    PRIVATE = new Registry(BootLoader.findResourceAsStream("java.base", "META-INF/jbrapi.private"));
                    PUBLIC = new Registry(BootLoader.findResourceAsStream("java.base", "META-INF/jbrapi.public"));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        private final Map<String, Entry> entries = new HashMap<>();
        private final String version;

        Registry(InputStream inputStream) {
            String version = null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                String s;
                while ((s = reader.readLine()) != null) {
                    String[] tokens = s.split(" ");
                    switch (tokens[0]) {
                        case "VERSION" -> version = tokens[1];
                        case "STATIC" -> {
                            Entry entry = entries.computeIfAbsent(tokens[4], Entry::new);
                            StaticValue target = new StaticValue(tokens[1], tokens[2]);
                            StaticValue prev = entry.staticMethods.put(new StaticKey(tokens[5], tokens[3]), target);
                            if (prev != null && !prev.equals(target)) {
                                throw new RuntimeException("Conflicting mapping: " +
                                        target.targetType + "#" + target.targetMethodName + " <- " +
                                        tokens[4] + "#" + tokens[5] + " -> " +
                                        prev.targetType + "#" + prev.targetMethodName);
                            }
                        }
                        default -> {
                            Entry a = entries.computeIfAbsent(tokens[1], Entry::new);
                            Entry b = entries.computeIfAbsent(tokens[2], Entry::new);
                            if ((a.inverse != null || b.inverse != null) && (a.inverse != b || b.inverse != a)) {
                                throw new RuntimeException("Conflicting mapping: " +
                                        tokens[1] + " <-> " + tokens[2] +
                                        ", " + a + " -> " + a.inverse +
                                        ", " + b + " -> " + b.inverse);
                            }
                            a.inverse = b;
                            b.inverse = a;
                            a.target = tokens[2];
                            b.target = tokens[1];
                            switch (tokens[0]) {
                                case "SERVICE" -> {
                                    a.type = null;
                                    b.flags |= Proxy.SERVICE;
                                }
                                case "PROVIDES" -> a.type = null;
                                case "PROVIDED" -> b.type = null;
                            }
                        }
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            this.version = version;
        }

        private boolean needsAnnotation(ProxyRepository repository, Class<?> c) {
            return repository.annotationsModule != null && repository.annotationsModule.equals(c.getModule());
        }

        private static boolean hasAnnotation(Class<?> c, Class<? extends Annotation> a) {
            return c.getAnnotation(a) != null;
        }
    }
}
