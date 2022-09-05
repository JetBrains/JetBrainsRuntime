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
 * @build com.jetbrains.* com.jetbrains.api.ProxyInfoResolving com.jetbrains.jbr.ProxyInfoResolving
 * @run main ProxyInfoResolvingTest
 */

import com.jetbrains.internal.JBRApi;

import java.util.Objects;

import static com.jetbrains.Util.*;
import static com.jetbrains.api.ProxyInfoResolving.*;
import static com.jetbrains.jbr.ProxyInfoResolving.*;

public class ProxyInfoResolvingTest {

    public static void main(String[] args) throws Throwable {
        JBRApi.ModuleRegistry r = init();
        // No mapping defined -> null
        requireNull(getProxy(ProxyInfoResolvingTest.class));
        // Invalid JBR-side target class -> null
        r.proxy(InterfaceWithoutImplementation.class.getName(), "absentImpl");
        requireNull(getProxy(InterfaceWithoutImplementation.class));
        // Invalid JBR-side target static method mapping -> null
        r.service(ServiceWithoutImplementation.class.getName())
                .withStatic("someMethod", "someMethod", "NoClass");
        requireNull(getProxy(ServiceWithoutImplementation.class));
        // Service without target class or static method mapping -> null
        r.service(EmptyService.class.getName());
        requireNull(getProxy(EmptyService.class));
        // Class passed instead of interface for client proxy -> error
        r.clientProxy(ClientProxyClass.class.getName(), ClientProxyClassImpl.class.getName());
        mustFail(() -> getProxy(ClientProxyClass.class), RuntimeException.class);
        // Class passed instead of interface for proxy -> null
        r.proxy(ProxyClass.class.getName(), ProxyClassImpl.class.getName());
        requireNull(getProxy(ProxyClass.class));
        // Valid proxy
        r.proxy(ValidApi.class.getName(), ValidApiImpl.class.getName());
        Objects.requireNonNull(getProxy(ValidApi.class));
        // Multiple implementations
        r.proxy(ValidApi2.class.getName(), "MissingClass", ValidApi2Impl.class.getName());
        Objects.requireNonNull(getProxy(ValidApi2.class));
    }
}
