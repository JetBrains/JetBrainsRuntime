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
 * @build com.jetbrains.* com.jetbrains.api.MethodMapping com.jetbrains.jbr.MethodMapping
 * @run main MethodMappingTest
 */

import com.jetbrains.internal.JBRApi;

import static com.jetbrains.Util.*;
import static com.jetbrains.api.MethodMapping.*;
import static com.jetbrains.jbr.MethodMapping.*;

public class MethodMappingTest {

    public static void main(String[] args) throws Throwable {
        JBRApi.ModuleRegistry r = init();
        // Simple empty interface
        r.proxy(SimpleEmpty.class.getName(), SimpleEmptyImpl.class.getName());
        requireImplemented(SimpleEmpty.class);
        // Plain method mapping
        r.proxy(PlainFail.class.getName(), PlainImpl.class.getName());
        r.service(Plain.class.getName(), PlainImpl.class.getName())
                .withStatic("c", MethodMappingTest.class.getName(), "main");
        requireNotImplemented(PlainFail.class);
        requireImplemented(Plain.class);
        // Callback (client proxy)
        r.clientProxy(Callback.class.getName(), ApiCallback.class.getName());
        requireImplemented(Callback.class);
        // 2-way
        r.twoWayProxy(ApiTwoWay.class.getName(), JBRTwoWay.class.getName());
        requireImplemented(ApiTwoWay.class);
        requireImplemented(JBRTwoWay.class);
        // Conversion
        r.twoWayProxy(Conversion.class.getName(), ConversionImpl.class.getName());
        r.proxy(ConversionSelf.class.getName(), ConversionSelfImpl.class.getName());
        r.proxy(ConversionFail.class.getName(), ConversionFailImpl.class.getName());
        requireImplemented(Conversion.class);
        requireImplemented(ConversionImpl.class);
        requireImplemented(ConversionSelf.class);
        requireNotImplemented(ConversionFail.class);
    }

    private static final ReflectedMethod methodsImplemented = getMethod("com.jetbrains.internal.Proxy", "areAllMethodsImplemented");
    private static void requireImplemented(Class<?> interFace) throws Throwable {
        if (!(boolean) methodsImplemented.invoke(getProxy(interFace))) {
            throw new Error("All methods must be implemented");
        }
    }
    private static void requireNotImplemented(Class<?> interFace) throws Throwable {
        if ((boolean) methodsImplemented.invoke(getProxy(interFace))) {
            throw new Error("Not all methods must be implemented");
        }
    }
}
