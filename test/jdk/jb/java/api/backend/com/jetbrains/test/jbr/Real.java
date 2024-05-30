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

package com.jetbrains.test.jbr;

import com.jetbrains.Provided;
import com.jetbrains.Provides;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

public class Real {

    @Provides
    public static class ServiceImpl {
        ProxyImpl getProxy() {
            return new ProxyImpl();
        }
        ProxyImpl passthrough(ProxyImpl a) {
            return a;
        }
        Client passthrough(Client a) {
            return a;
        }
        JBR2Way get2Way() {
            return a -> {};
        }
        JBR2Way passthrough(JBR2Way a) {
            if (a != null) a.accept(a);
            return a;
        }
        JBRLazyNumber sum(JBRLazyNumber a, JBRLazyNumber b) {
            return () -> a.get() + b.get();
        }
        void testMethodNameConflict(JBR2Way a) {}
        void testMethodNameConflict(JBRLazyNumber a) {}
        List<JBR2Way> testList(List<JBR2Way> list) {
            if (list == null) {
                return new ArrayList<>();
            } else {
                list.clear();
                return list;
            }
        }
    }

    @Provides
    public static class ProxyImpl {
        int getAsInt() {
            return 265;
        }
        void foo() {}
    }

    @FunctionalInterface
    @Provided
    public interface Client extends IntSupplier {}

    @FunctionalInterface
    @Provided
    @Provides
    public interface JBR2Way extends Consumer<Object> {}

    @FunctionalInterface
    @Provided
    @Provides
    public interface JBRLazyNumber {
        int get();
    }
}
