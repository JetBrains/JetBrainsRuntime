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
 * @build com.jetbrains.JBR
 * @run main/othervm -Djetbrains.runtime.api.verbose=true -Djetbrains.runtime.api.verifyBytecode=true BootstrapTest new
 * @run main/othervm -Djetbrains.runtime.api.verbose=true -Djetbrains.runtime.api.verifyBytecode=true BootstrapTest old
 */

import com.jetbrains.JBR;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

public class BootstrapTest {

    public static void main(String[] args) throws Throwable {
        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(JBR.class, MethodHandles.lookup());
        JBR.ServiceApi api;
        if (!args[0].equals("old")) {
            Class<?> bootstrap = Class.forName("com.jetbrains.exported.JBRApiSupport");
            Objects.requireNonNull(bootstrap, "JBRApi class not accessible");
            api = (JBR.ServiceApi) (Object) lookup
                    .findStatic(bootstrap, "bootstrap", MethodType.methodType(Object.class, Class.class,
                            Class.class, Class.class, Class.class, Map.class, Function.class))
                    .invokeExact(JBR.ServiceApi.class, (Class<?>) null, (Class<?>) null, (Class<?>) null,
                            Map.of(), (Function<Method, Enum<?>>) m -> null);
        } else {
            Class<?> bootstrap = Class.forName("com.jetbrains.bootstrap.JBRApiBootstrap");
            Objects.requireNonNull(bootstrap, "JBRApiBootstrap class not accessible");
            api = (JBR.ServiceApi) (Object) lookup
                    .findStatic(bootstrap, "bootstrap", MethodType.methodType(Object.class, MethodHandles.Lookup.class))
                    .invokeExact(lookup);
        }
        Objects.requireNonNull(api, "JBR API bootstrap failed");
    }
}
