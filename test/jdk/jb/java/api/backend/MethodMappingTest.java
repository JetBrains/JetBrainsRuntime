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
 * @build com.jetbrains.* com.jetbrains.test.api.MethodMapping com.jetbrains.test.jbr.MethodMapping
 * @run main/othervm -Djetbrains.runtime.api.extendRegistry=true MethodMappingTest
 */

import java.util.Map;

import static com.jetbrains.Util.*;
import static com.jetbrains.test.api.MethodMapping.*;
import static com.jetbrains.test.jbr.MethodMapping.*;

public class MethodMappingTest {

    public static void main(String[] args) throws Throwable {
        init("MethodMappingTest", Map.of());
        // Simple empty interface
        requireSupported(getProxy(SimpleEmpty.class));
        requireUnsupported(getProxy(SimpleEmptyImpl.class));
        requireUnsupported(inverse(getProxy(SimpleEmpty.class)));
        requireSupported(inverse(getProxy(SimpleEmptyImpl.class)));
        // Plain method mapping
        requireSupported(getProxy(Plain.class));
        requireUnsupported(getProxy(PlainFail.class));
        // Callback (client proxy)
        requireSupported(getProxy(Callback.class));
        // 2-way
        requireSupported(getProxy(ApiTwoWay.class));
        requireSupported(getProxy(JBRTwoWay.class));
        // Conversion
        requireSupported(getProxy(Conversion.class));
        requireSupported(getProxy(ConversionImpl.class));
        requireSupported(getProxy(ConversionSelf.class));
        requireUnsupported(getProxy(ConversionFail.class));
        requireSupported(getProxy(ArrayConversion.class));
        requireSupported(getProxy(GenericConversion.class));
    }
}
