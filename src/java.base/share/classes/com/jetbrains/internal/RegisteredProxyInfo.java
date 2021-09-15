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
import java.util.List;

/**
 * Raw proxy info, as it was registered through {@link JBRApi.ModuleRegistry}.
 * Contains all necessary information to create a {@linkplain Proxy proxy}.
 */
class RegisteredProxyInfo {
    final MethodHandles.Lookup apiModule;
    final String interfaceName;
    final String target;
    final ProxyInfo.Type type;
    final List<StaticMethodMapping> staticMethods;

    RegisteredProxyInfo(MethodHandles.Lookup apiModule, String interfaceName, String target, ProxyInfo.Type type, List<StaticMethodMapping> staticMethods) {
        this.apiModule = apiModule;
        this.interfaceName = interfaceName;
        this.target = target;
        this.type = type;
        this.staticMethods = staticMethods;
    }

    static class StaticMethodMapping {
        final String interfaceMethodName, clazz, methodName;

        StaticMethodMapping(String interfaceMethodName, String clazz, String methodName) {
            this.interfaceMethodName = interfaceMethodName;
            this.clazz = clazz;
            this.methodName = methodName;
        }
    }
}
