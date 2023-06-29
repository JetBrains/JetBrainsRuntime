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

import java.lang.invoke.MethodHandles;
import java.util.*;
import java.util.stream.Stream;

class ProxyRepository {
    private static final Proxy UNSUPPORTED = new Proxy();

    private final Map<String, MethodHandles.Lookup> moduleLookups = new HashMap<>();
    private final Set<String> knownProxyInterfaceNames = new HashSet<>(); // All proxies including services.

    private final Map<String, RegisteredProxyInfo> registeredProxyInfoByInterfaceName = new HashMap<>();
    private final Map<String, RegisteredProxyInfo> registeredProxyInfoByTargetName = new HashMap<>();
    private final Map<Key, Pair> proxies = new HashMap<>();

    void addKnownProxyNames(String... names) {
        knownProxyInterfaceNames.addAll(Arrays.asList(names));
    }

    void registerModule(MethodHandles.Lookup lookup) {
        if (!lookup.hasFullPrivilegeAccess()) throw new IllegalArgumentException("Lookup must be full-privileged");
        if (moduleLookups.putIfAbsent(lookup.lookupClass().getModule().getName(), lookup) != null) {
            throw new IllegalArgumentException("Module lookup already registered");
        }
    }

    RegisteredProxyInfo registerProxy(String moduleName, ProxyInfo.Type type, String interfaceName, String... targets) {
        MethodHandles.Lookup lookup = moduleLookups.get(moduleName);
        if (lookup == null) throw new NullPointerException("Module lookup not found: " + moduleName);
        RegisteredProxyInfo info = new RegisteredProxyInfo(lookup, interfaceName, targets, type, new ArrayList<>());
        registeredProxyInfoByInterfaceName.put(interfaceName, info);
        for (String target : targets) {
            registeredProxyInfoByTargetName.put(target, info);
        }
        if (targets.length == 1) {
            validate2WayMapping(info, registeredProxyInfoByInterfaceName.get(targets[0]));
            validate2WayMapping(info, registeredProxyInfoByTargetName.get(interfaceName));
        }
        return info;
    }

    private static void validate2WayMapping(RegisteredProxyInfo p, RegisteredProxyInfo reverse) {
        if (reverse != null &&
                (!p.interfaceName().equals(reverse.targets()[0]) || !p.targets()[0].equals(reverse.interfaceName()))) {
            throw new IllegalArgumentException("Invalid 2-way proxy mapping: " +
                    p.interfaceName() + " -> " + p.targets()[0] + " & " +
                    reverse.interfaceName() + " -> " + reverse.targets()[0]);
        }
    }

    synchronized Pair getProxies(Class<?> clazz, Mapping[] specialization) {
        Key key = new Key(clazz, specialization);
        Pair p = proxies.get(key);
        if (p == null) {
            Proxy byInterface = null, byTarget = null;
            Mapping[] inverseSpecialization = specialization == null ? null :
                    Stream.of(specialization).map(m -> m == null ? null : m.inverse()).toArray(Mapping[]::new);

            RegisteredProxyInfo infoByInterface = registeredProxyInfoByInterfaceName.get(key.clazz().getName());
            if (infoByInterface != null) byInterface = createRegisteredProxy(infoByInterface, specialization);
            else if (knownProxyInterfaceNames.contains(key.clazz().getName())) byInterface = UNSUPPORTED;

            RegisteredProxyInfo infoByTarget = registeredProxyInfoByTargetName.get(key.clazz().getName());
            if (infoByTarget != null) byTarget = createRegisteredProxy(infoByTarget, inverseSpecialization);

            if (byInterface != null || byTarget != null) {
                Class<?> inverse = null;
                if (byTarget != null && byTarget.getInterface() != null) inverse = byTarget.getInterface();
                else if (byInterface != null && byInterface.getTarget() != null) inverse = byInterface.getTarget();
                if (inverse != null) proxies.put(new Key(inverse, inverseSpecialization), new Pair(byTarget, byInterface));
            } else {
                Key inverseKey = new Key(clazz, inverseSpecialization);
                byInterface = createImplicitProxy(key);
                byTarget = createImplicitProxy(inverseKey);
                if (byInterface == null || byTarget == null) byInterface = byTarget = null;
                proxies.putIfAbsent(inverseKey, new Pair(byTarget, byInterface));
            }
            proxies.put(key, p = new Pair(byInterface, byTarget));
        }
        return p;
    }

    private Proxy createRegisteredProxy(RegisteredProxyInfo registeredProxyInfo, Mapping[] specialization) {
        ProxyInfo resolved = ProxyInfo.resolve(registeredProxyInfo);
        if (resolved == null) {
            if (JBRApi.VERBOSE) {
                System.err.println("Couldn't resolve proxy info: " +
                        registeredProxyInfo.interfaceName() + " -> " + Arrays.toString(registeredProxyInfo.targets()));
            }
            return UNSUPPORTED;
        }
        return new Proxy(this, resolved, specialization);
    }

    private Proxy createImplicitProxy(Key key) {
        if (key.specialization() == null) return null;
        MethodHandles.Lookup lookup = moduleLookups.get(key.clazz().getModule().getName());
        if (lookup == null) {
            if (JBRApi.VERBOSE) {
                System.err.println("Couldn't find module lookup for implicit proxy: " +
                        key.clazz().getName() + " (" + key.clazz().getModule().getName() + ")");
            }
            return null;
        }
        try {
            lookup = MethodHandles.privateLookupIn(key.clazz(), lookup);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        return new Proxy(this, lookup, key.specialization());
    }

    record Pair(Proxy byInterface, Proxy byTarget) {}

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
    }

}
