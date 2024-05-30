/*
 * Copyright 2023 JetBrains s.r.o.
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

import jdk.internal.access.SharedSecrets;
import jdk.internal.loader.BootLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.stream.Stream;

import static java.lang.invoke.MethodHandles.Lookup;

/**
 * Proxy repository keeps track of all generated proxies.
 * @see ProxyRepository#getProxy(Class, Mapping[])
 */
class ProxyRepository {
    private static final Proxy NONE = Proxy.empty(null), INVALID = Proxy.empty(false);

    private final Registry registry = new Registry();
    private final Map<Key, Proxy> proxies = new HashMap<>();

    void init(InputStream extendedRegistryStream,
              Class<? extends Annotation> serviceAnnotation,
              Class<? extends Annotation> providedAnnotation,
              Class<? extends Annotation> providesAnnotation) {
        registry.initAnnotations(serviceAnnotation, providedAnnotation, providesAnnotation);
        if (extendedRegistryStream != null) registry.readEntries(extendedRegistryStream);
    }

    String getVersion() {
        return registry.version;
    }

    synchronized Proxy getProxy(Class<?> clazz, Mapping[] specialization) {
        Key key = new Key(clazz, specialization);
        Proxy p = proxies.get(key);
        if (p == null) {
            registry.updateClassLoader(clazz.getClassLoader());
            Mapping[] inverseSpecialization = specialization == null ? null :
                    Stream.of(specialization).map(m -> m == null ? null : m.inverse()).toArray(Mapping[]::new);
            Key inverseKey = null;

            Registry.Entry entry = registry.entries.get(key.clazz().getCanonicalName());
            if (entry != null) { // This is a registered proxy
                Proxy.Info infoByInterface = entry.resolve(),
                        infoByTarget = entry.inverse != null ? entry.inverse.resolve() : null;
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
     * This mapping information can be {@linkplain Entry#resolve() resolved} into {@link Proxy.Info}.
     */
    private static class Registry {

        private record StaticKey(String methodName, String targetMethodDescriptor) {}
        private record StaticValue(String targetType, String targetMethodName) {}

        private class Entry {
            private static final Proxy.Info INVALID = new Proxy.Info(null, null, 0);

            private final Map<StaticKey, StaticValue> staticMethods = new HashMap<>();
            private String type, target;
            private Entry inverse;
            private int flags;

            private Entry(String type) { this.type = type; }

            private Proxy.Info resolve() {
                if (type == null) return null;
                Lookup l, t;
                try {
                    l = resolveType(type, classLoader);
                    t = target != null ? resolveType(target, classLoader) : null;
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
                if (needsAnnotation(l.lookupClass())) {
                    if (!hasAnnotation(l.lookupClass(), providedAnnotation)) {
                        if (JBRApi.VERBOSE) {
                            System.err.println(type + " not eligible: no @Provided annotation");
                        }
                        return INVALID;
                    }
                    if (!hasAnnotation(l.lookupClass(), serviceAnnotation)) flags &= ~Proxy.SERVICE;
                }
                Proxy.Info info;
                if (t != null) {
                    if (needsAnnotation(t.lookupClass()) && !hasAnnotation(t.lookupClass(), providesAnnotation)) {
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
                        Lookup lookup = resolveType(targetType, classLoader);
                        MethodType mt = MethodType.fromMethodDescriptorString(targetMethodDescriptor, classLoader);
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

        private Class<? extends Annotation> serviceAnnotation, providedAnnotation, providesAnnotation;
        private Module annotationsModule;
        private ClassLoader classLoader;
        private final Map<String, Entry> entries = new HashMap<>();
        private final String version;

        private Registry() {
            try (InputStream registryStream = BootLoader.findResourceAsStream("java.base", "META-INF/jbrapi.registry")) {
                version = readEntries(registryStream);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        private void initAnnotations(Class<? extends Annotation> serviceAnnotation,
                         Class<? extends Annotation> providedAnnotation,
                         Class<? extends Annotation> providesAnnotation) {
            this.serviceAnnotation = serviceAnnotation;
            this.providedAnnotation = providedAnnotation;
            this.providesAnnotation = providesAnnotation;
            annotationsModule = serviceAnnotation == null ? null : serviceAnnotation.getModule();
            if (annotationsModule != null) classLoader = annotationsModule.getClassLoader();
        }

        private String readEntries(InputStream inputStream) {
            String version = null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                String s;
                while ((s = reader.readLine()) != null) {
                    String[] tokens = s.split(" ");
                    switch (tokens[0]) {
                        case "TYPE" -> {
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
                            switch (tokens[3]) {
                                case "SERVICE" -> {
                                    a.type = null;
                                    b.flags |= Proxy.SERVICE;
                                }
                                case "PROVIDES" -> a.type = null;
                                case "PROVIDED" -> b.type = null;
                            }
                            if (tokens.length > 4 && tokens[4].equals("INTERNAL")) {
                                a.flags |= Proxy.INTERNAL;
                                b.flags |= Proxy.INTERNAL;
                            }
                        }
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
                            if (tokens.length > 6 && tokens[6].equals("INTERNAL")) entry.flags |= Proxy.INTERNAL;
                        }
                        case "VERSION" -> version = tokens[1];
                    }
                }
            } catch (IOException e) {
                entries.clear();
                throw new RuntimeException(e);
            } catch (RuntimeException | Error e) {
                entries.clear();
                throw e;
            }
            return version;
        }

        private synchronized void updateClassLoader(ClassLoader newLoader) {
            // New loader is descendant of current one -> update
            for (ClassLoader cl = newLoader;; cl = cl.getParent()) {
                if (cl == classLoader) {
                    classLoader = newLoader;
                    return;
                }
                if (cl == null) break;
            }
            // Current loader is descendant of the new one -> leave
            for (ClassLoader cl = classLoader;; cl = cl.getParent()) {
                if (cl == newLoader) return;
                if (cl == null) break;
            }
            // Independent classloaders -> error? Or maybe reset cache and start fresh?
            throw new RuntimeException("Incompatible classloader");
        }

        private boolean needsAnnotation(Class<?> c) {
            return annotationsModule != null && annotationsModule.equals(c.getModule());
        }

        private static boolean hasAnnotation(Class<?> c, Class<? extends Annotation> a) {
            return c.getAnnotation(a) != null;
        }
    }
}
