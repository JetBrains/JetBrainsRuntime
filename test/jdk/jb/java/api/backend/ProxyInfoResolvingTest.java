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
        r.service(ServiceWithoutImplementation.class.getName(), null)
                .withStatic("someMethod", "NoClass");
        requireNull(getProxy(ServiceWithoutImplementation.class));
        // Service without target class or static method mapping -> null
        r.service(EmptyService.class.getName(), null);
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
    }
}
