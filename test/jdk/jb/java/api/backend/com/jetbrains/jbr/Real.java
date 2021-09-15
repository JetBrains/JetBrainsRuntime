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

package com.jetbrains.jbr;

import java.util.function.Consumer;

public class Real {

    public static class ServiceImpl {
        JBR2Way get2Way() {
            return a -> {};
        }
        JBR2Way passthrough(JBR2Way a) {
            if (a != null) a.accept(a);
            return a;
        }
        boolean isSelf(ServiceImpl a) {
            return a == this;
        }
        JBRLazyNumber sum(JBRLazyNumber a, JBRLazyNumber b) {
            return () -> a.get() + b.get();
        }
        void testMethodNameConflict(JBR2Way a) {}
        void testMethodNameConflict(JBRLazyNumber a) {}
    }

    @FunctionalInterface
    public interface JBR2Way extends Consumer<Object> {}

    @FunctionalInterface
    public interface JBRLazyNumber {
        int get();
    }
}
