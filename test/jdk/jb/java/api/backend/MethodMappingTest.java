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
