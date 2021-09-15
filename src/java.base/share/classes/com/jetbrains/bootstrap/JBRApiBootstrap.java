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

package com.jetbrains.bootstrap;

import com.jetbrains.internal.JBRApi;
import jdk.internal.loader.ClassLoaders;

import java.lang.invoke.MethodHandles;

/**
 * Bootstrap class, used to initialize {@linkplain JBRApi JBR API}.
 */
public class JBRApiBootstrap {
    private JBRApiBootstrap() {}

    /**
     * Classes that register JBR API implementation for their modules.
     */
    private static final String[] MODULES = {
            "com.jetbrains.base.JBRApiModule",
            "com.jetbrains.desktop.JBRApiModule"
    };

    /**
     * Called from static initializer of {@link com.jetbrains.JBR}.
     * @param outerLookup lookup context inside {@code jetbrains.api} module
     * @return implementation for {@link com.jetbrains.JBR.ServiceApi} interface
     */
    public static synchronized Object bootstrap(MethodHandles.Lookup outerLookup) {
        if (!System.getProperty("jetbrains.api.enabled", "true").equalsIgnoreCase("true")) return null;
        try {
            Class<?> apiInterface = outerLookup.findClass("com.jetbrains.JBR$ServiceApi");
            if (!outerLookup.hasFullPrivilegeAccess() ||
                    outerLookup.lookupClass().getPackage() != apiInterface.getPackage()) {
                throw new IllegalArgumentException("Lookup must be full-privileged from the com.jetbrains package: " +
                        outerLookup.lookupClass().getName());
            }
            JBRApi.init(outerLookup);
            ClassLoader classLoader = ClassLoaders.platformClassLoader();
            for (String m : MODULES) Class.forName(m, true, classLoader);
            return JBRApi.getService(apiInterface);
        } catch(ClassNotFoundException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

}
