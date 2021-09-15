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

package com.jetbrains.api;

import java.util.function.Consumer;

public class Real {

    public interface Service {
        Api2Way get2Way();
        Api2Way passthrough(Api2Way a);
        boolean isSelf(Service a);
        ApiLazyNumber sum(ApiLazyNumber a, ApiLazyNumber b);
        /**
         * When generating bridge class, convertible method parameters are changed to Objects,
         * which may cause name collisions
         */
        void testMethodNameConflict(Api2Way a);
        void testMethodNameConflict(ApiLazyNumber a);
    }

    @FunctionalInterface
    public interface Api2Way extends Consumer<Object> {}

    @FunctionalInterface
    public interface ApiLazyNumber {
        int get();
    }
}
