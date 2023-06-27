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

import java.lang.invoke.MethodHandles;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static java.lang.invoke.MethodHandles.Lookup;

/**
 * Proxy info, like {@link RegisteredProxyInfo}, but with all classes and lookup contexts resolved.
 * Contains all necessary information to create a {@linkplain Proxy proxy}.
 */
class ProxyInfo {

    final Lookup apiModule;
    final Type type;
    final Lookup interFaceLookup;
    final Class<?> interFace;
    final Lookup target;
    final Map<String, StaticMethodMapping> staticMethods = new HashMap<>();

    private ProxyInfo(RegisteredProxyInfo i) {
        this.apiModule = i.apiModule();
        type = i.type();
        interFaceLookup = lookup(getInterfaceLookup(), i.interfaceName());
        interFace = interFaceLookup == null ? null : interFaceLookup.lookupClass();
        target = Stream.of(i.targets())
                .map(t -> lookup(getTargetLookup(), t))
                .filter(Objects::nonNull).findFirst().orElse(null);
        for (RegisteredProxyInfo.StaticMethodMapping m : i.staticMethods()) {
            Stream.of(m.classes())
                    .map(t -> lookup(getTargetLookup(), t))
                    .filter(Objects::nonNull).findFirst()
                    .ifPresent(l -> staticMethods.put(m.interfaceMethodName(), new StaticMethodMapping(l, m.methodName())));
        }
    }

    /**
     * Resolve all classes and lookups for given {@link RegisteredProxyInfo}.
     */
    static ProxyInfo resolve(RegisteredProxyInfo i) {
        ProxyInfo info = new ProxyInfo(i);
        if (info.interFace == null || (info.target == null && info.staticMethods.isEmpty())) return null;
        if (!info.interFace.isInterface()) {
            if (info.type == Type.CLIENT_PROXY) {
                throw new RuntimeException("Tried to create client proxy for non-interface: " + info.interFace);
            } else {
                return null;
            }
        }
        return info;
    }

    Lookup getInterfaceLookup() {
        return type == Type.CLIENT_PROXY || type == Type.INTERNAL_SERVICE ? apiModule : JBRApi.outerLookup;
    }

    Lookup getTargetLookup() {
        return type == Type.CLIENT_PROXY ? JBRApi.outerLookup : apiModule;
    }

    private Lookup lookup(Lookup lookup, String clazz) {
        String[] nestedClasses = clazz.split("\\$");
        clazz = "";
        for (int i = 0; i < nestedClasses.length; i++) {
            try {
                if (i != 0) clazz += "$";
                clazz += nestedClasses[i];
                lookup = MethodHandles.privateLookupIn(Class.forName(clazz, false, lookup.lookupClass().getClassLoader()), lookup);
            } catch (ClassNotFoundException | IllegalAccessException ignore) {
                return null;
            }
        }
        return lookup;
    }

    record StaticMethodMapping(Lookup lookup, String methodName) {}

    /**
     * Proxy type, see {@link Proxy}
     */
    enum Type {
        PROXY,
        SERVICE,
        CLIENT_PROXY,
        INTERNAL_SERVICE;

        public boolean isPublicApi() {
            return this == PROXY || this == SERVICE;
        }

        public boolean isService() {
            return this == SERVICE || this == INTERNAL_SERVICE;
        }
    }
}
