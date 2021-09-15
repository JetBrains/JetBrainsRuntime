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
