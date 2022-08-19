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

import java.lang.reflect.*;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/**
 * This class collects {@linkplain Proxy proxy} dependencies.
 * <p>
 * Dependencies of a class {@code C} are other classes that are
 * used by {@code C} (i.e. supertypes, classes that appear in method
 * parameters, return types) and all their dependencies. Any class
 * is also considered a dependency of itself.
 * <p>
 * Dependencies allow JBR to validate whole set of interfaces for
 * a particular feature instead of treating them as separate entities.
 * <h2>Example</h2>
 * Suppose we implemented some feature and added some API for it:
 * <blockquote><pre>{@code
 * interface SomeFeature {
 *     SomeOtherObject createSomeObject(int magicNumber);
 * }
 * interface SomeOtherObject {
 *     int getMagicNumber();
 * }
 * }</pre></blockquote>
 * And then used it:
 * <blockquote><pre>{@code
 * if (JBR.isSomeFeatureSupported()) {
 *     SomeOtherObject object = JBR.getSomeFeature().createSomeObject(123);
 *     int magic = object.getMagicNumber();
 * }
 * }</pre></blockquote>
 * Suppose JBR was able to find implementation for {@code SomeFeature.createSomeObject()},
 * but not for {@code SomeOtherObject.getMagicNumber()}. So {@code JBR.getSomeFeature()}
 * would succeed and return service instance, but {@code createSomeObject()} would fail,
 * because JBR wasn't able to find implementation for {@code SomeOtherObject.getMagicNumber()}
 * and therefore couldn't create proxy for {@code SomeOtherObject} class.
 * <p>
 * To avoid such issues, not only proxy interface itself, but all proxies that are accessible
 * from current proxy interface must have proper implementation.
 */
class ProxyDependencyManager {

    private static final ConcurrentMap<Class<?>, Set<Class<?>>> cache = new ConcurrentHashMap<>();

    /**
     * @return all proxy interfaces that are used (directly or indirectly) by given interface, including itself.
     */
    static Set<Class<?>> getProxyDependencies(Class<?> interFace) {
        Set<Class<?>> dependencies = cache.get(interFace);
        if (dependencies != null) return dependencies;
        step(null, interFace);
        return cache.get(interFace);
    }

    /**
     * Collect dependencies for given class and store them into cache.
     */
    private static void step(Node parent, Class<?> clazz) {
        if (!clazz.getPackageName().startsWith("com.jetbrains") && !JBRApi.isKnownProxyInterface(clazz)) return;
        if (parent != null && parent.findAndMergeCycle(clazz) != null) {
            return;
        }
        Set<Class<?>> cachedDependencies = cache.get(clazz);
        if (cachedDependencies != null) {
            if (parent != null) parent.cycle.dependencies.addAll(cachedDependencies);
            return;
        }
        Node node = new Node(parent, clazz);
        ClassUsagesFinder.visitUsages(clazz, c -> step(node, c));
        Class<?> correspondingProxyInterface = JBRApi.getProxyInterfaceByTargetName(clazz.getName());
        if (correspondingProxyInterface != null) {
            step(node, correspondingProxyInterface);
        }
        if (parent != null && parent.cycle != node.cycle) {
            parent.cycle.dependencies.addAll(node.cycle.dependencies);
        }
        if (node.cycle.origin.equals(clazz)) {
            // Put collected dependencies into cache only when we exit from the cycle
            // Otherwise cache will contain incomplete data
            for (Class<?> c : node.cycle.members) {
                cache.put(c, node.cycle.dependencies);
                if (JBRApi.VERBOSE) {
                    System.out.println("Found dependencies for " + c.getName() + ": " + node.cycle.dependencies);
                }
            }
        }
    }

    /**
     * Graph node, one per visited class
     */
    private static class Node {
        private final Node parent;
        private final Class<?> clazz;
        private Cycle cycle;

        private Node(Node parent, Class<?> clazz) {
            this.parent = parent;
            this.clazz = clazz;
            cycle = new Cycle(clazz);
        }

        /**
         * When classes form dependency cycle, they share all their dependencies.
         * If cycle was found, merge all found dependencies for nodes that form the cycle.
         */
        private Cycle findAndMergeCycle(Class<?> clazz) {
            if (this.clazz.equals(clazz)) return cycle;
            if (parent == null) return null;
            Cycle c = parent.findAndMergeCycle(clazz);
            if (c != null && c != cycle) {
                c.members.addAll(cycle.members);
                c.dependencies.addAll(cycle.dependencies);
                cycle = c;
            }
            return c;
        }
    }

    /**
     * Cycle info. For the sake of elegant code, single node
     * also forms a cycle with itself as a single member and dependency.
     */
    private static class Cycle {
        /**
         * Origin is the first visited class from that cycle.
         */
        private final Class<?> origin;
        private final Set<Class<?>> members = new HashSet<>();
        private final Set<Class<?>> dependencies = new HashSet<>();

        private Cycle(Class<?> origin) {
            this.origin = origin;
            members.add(origin);
            if (JBRApi.isKnownProxyInterface(origin)) {
                dependencies.add(origin);
            }
        }
    }

    /**
     * Utility class that collects direct class usages using reflection
     */
    private static class ClassUsagesFinder {

        private static void visitUsages(Class<?> c, Consumer<Class<?>> action) {
            collect(c.getGenericSuperclass(), action);
            for (java.lang.reflect.Type t : c.getGenericInterfaces()) collect(t, action);
            for (Field f : c.getDeclaredFields()) collect(f.getGenericType(), action);
            for (Method m : c.getDeclaredMethods()) {
                collect(m.getGenericParameterTypes(), action);
                collect(m.getGenericReturnType(), action);
                collect(m.getGenericExceptionTypes(), action);
            }
        }

        private static void collect(java.lang.reflect.Type type, Consumer<Class<?>> action) {
            if (type instanceof Class<?> c) {
                while (c.isArray()) c = Objects.requireNonNull(c.getComponentType());
                if (!c.isPrimitive()) action.accept(c);
            } else if (type instanceof TypeVariable<?> v) {
                collect(v.getBounds(), action);
            } else if (type instanceof WildcardType w) {
                collect(w.getUpperBounds(), action);
                collect(w.getLowerBounds(), action);
            } else if (type instanceof ParameterizedType p) {
                collect(p.getActualTypeArguments(), action);
                collect(p.getRawType(), action);
                collect(p.getOwnerType(), action);
            } else if (type instanceof GenericArrayType a) {
                collect(a.getGenericComponentType(), action);
            }
        }

        private static void collect(java.lang.reflect.Type[] types, Consumer<Class<?>> action) {
            for (java.lang.reflect.Type t : types) collect(t, action);
        }
    }
}
