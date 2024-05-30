/*
 * Copyright 2000-2023 JetBrains s.r.o.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

/*
 * @test
 * @modules java.base/com.jetbrains.internal:+open
 * @build com.jetbrains.* com.jetbrains.test.api.ProxyInfoResolving com.jetbrains.test.jbr.ProxyInfoResolving
 * @run main/othervm -Djetbrains.runtime.api.extendRegistry=true ProxyInfoResolvingTest
 */

import java.util.Map;

import static com.jetbrains.Util.*;
import static com.jetbrains.test.api.ProxyInfoResolving.*;
import static com.jetbrains.test.jbr.ProxyInfoResolving.*;

public class ProxyInfoResolvingTest {

    public static void main(String[] args) throws Throwable {
        init("ProxyInfoResolvingTest", Map.of());
        // No mapping defined -> unsupported
        requireUnsupported(getProxy(ProxyInfoResolvingTest.class));
        // Invalid JBR-side target class -> unsupported
        requireUnsupported(getProxy(InterfaceWithoutImplementation.class));
        // Invalid JBR-side target static method mapping -> unsupported
        requireUnsupported(getProxy(ServiceWithoutImplementation.class));
        // Valid proxy
        requireSupported(getProxy(ValidApi.class));
        // Class instead of interface for proxy
        requireSupported(getProxy(ProxyClass.class));
        // Class instead of interface for client proxy
        requireSupported(getProxy(ClientProxyClass.class));
        // Service without annotation -> unsupported
        requireUnsupported(getProxy(ServiceWithoutAnnotation.class));
        // Service with extension method
        requireSupported(getProxy(ServiceWithExtension.class));
    }
}
