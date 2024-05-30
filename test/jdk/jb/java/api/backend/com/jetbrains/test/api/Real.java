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

package com.jetbrains.test.api;

import com.jetbrains.Extension;
import com.jetbrains.Extensions;
import com.jetbrains.Provides;
import com.jetbrains.Provided;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

public class Real {

    @com.jetbrains.Service
    @Provided
    public interface Service {
        Proxy getProxy();
        Proxy passthrough(Proxy a);
        ClientImpl passthrough(ClientImpl a);
        Api2Way get2Way();
        Api2Way passthrough(Api2Way a);
        ApiLazyNumber sum(ApiLazyNumber a, ApiLazyNumber b);
        /**
         * Convertible method parameters are changed to Objects, which may cause name collisions
         */
        void testMethodNameConflict(Api2Way a);
        void testMethodNameConflict(ApiLazyNumber a);
        List<Api2Way> testList(List<Api2Way> list);
    }

    @Provided
    public interface Proxy extends IntSupplier {
        @Extension(Extensions.FOO)
        void foo();
        @Extension(Extensions.BAR)
        void bar();
    }

    @Provides
    public static class ClientImpl {
        int getAsInt() {
            return 562;
        }
    }

    @Provided
    @Provides
    @FunctionalInterface
    public interface Api2Way extends Consumer<Object> {}

    @Provided
    @Provides
    @FunctionalInterface
    public interface ApiLazyNumber {
        int get();
    }
}
