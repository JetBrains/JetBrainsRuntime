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
