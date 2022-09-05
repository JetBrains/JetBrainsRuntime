/*
 * Copyright 2000-2021 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
                lookup = MethodHandles.privateLookupIn(Class.forName(clazz, true, lookup.lookupClass().getClassLoader()), lookup);
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
