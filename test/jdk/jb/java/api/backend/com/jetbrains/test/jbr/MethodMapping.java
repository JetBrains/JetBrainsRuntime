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

public class MethodMapping {
    @Provides
    public static class SimpleEmptyImpl {}
    @Provides
    public static class PlainImpl {
        public void a() {}
        public boolean b() {
            return false;
        }
    }
    @Provides
    public static class PlainFailImpl {
        public void a() {}
        public boolean b() {
            return false;
        }
    }
    @Provided
    public interface Callback {
        void hello();
    }
    @Provided
    @Provides
    public interface JBRTwoWay {
        void something();
    }
    @Provided
    @Provides
    public interface ConversionImpl {
        default SimpleEmptyImpl convert(SimpleEmptyImpl a, Callback b, JBRTwoWay c) {
            return null;
        }
    }
    @Provides
    public static class ConversionSelfImpl implements ConversionImpl {
        public ConversionSelfImpl convert(Object a, Object b, Object c) {
            return null;
        }
    }
    @Provides
    public static class ConversionFailImpl implements ConversionImpl {}
    @Provides
    public static class ArrayConversionImpl {
        ConversionImpl[] convert() {
            return null;
        }
    }
    @Provides
    public static class GenericConversionImpl {
        com.jetbrains.test.api.MethodMapping.ImplicitGeneric<ConversionImpl, ArrayConversionImpl> convert() {
            return null;
        }
    }
}
