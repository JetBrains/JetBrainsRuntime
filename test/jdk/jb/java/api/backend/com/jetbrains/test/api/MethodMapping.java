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

import com.jetbrains.Provides;
import com.jetbrains.Provided;
import com.jetbrains.Service;

public class MethodMapping {
    @Provided
    public interface SimpleEmpty {}
    @Service
    @Provided
    public interface Plain {
        void a();
        boolean b();
        void c(String... a);
    }
    @Service
    @Provided
    public interface PlainFail extends Plain {}
    @Provides
    public interface ApiCallback {
        void hello();
    }
    @Provided
    @Provides
    public interface ApiTwoWay {
        void something();
    }
    @Provided
    @Provides
    public interface Conversion {
        SimpleEmpty convert(SimpleEmpty a, ApiCallback b, ApiTwoWay c);
    }
    @Provided
    public interface ConversionSelf extends Conversion {
        ConversionSelf convert(Object a, Object b, Object c);
    }
    @Provided
    public interface ConversionFail extends Conversion {
        void missingMethod();
    }
    @Provided
    public interface ArrayConversion {
        Conversion[] convert();
    }
    @Provided
    public interface GenericConversion {
        ImplicitGeneric<Conversion, ArrayConversion> convert();
    }
    public interface ImplicitGeneric<R, P> {
        R apply(P p);
    }
}
