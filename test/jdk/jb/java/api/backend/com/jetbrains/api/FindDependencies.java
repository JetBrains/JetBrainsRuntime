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

public class FindDependencies {
    public interface A {
        void f(AL l, AR r);
    }
    public interface AL {}
    public interface AR {
        void f(ARL l, ARR r);
    }
    public interface ARL {}
    public interface ARR {
        void f(ARRL l, ARRR r);
    }
    public interface ARRL {}
    public interface ARRR {}

    public interface BV {}
    public interface B1 {
        void f(BV bvs, B2 t, BV bve);
    }
    public interface B2 {
        void f(BV bvs, B3 t, BV bve);
    }
    public interface B3 {
        void f(B8 l, BV bvs, B4 t, B2 b, BV bve, B9 r);
    }
    public interface B4 {
        void f(BV bvs, B2 b, B5 t, BV bve);
    }
    public interface B5 {
        void f(BV bvs, B6 s, B3 b, B7 t, BV bve);
    }
    public interface B6 {
        void f(BV bvs, B5 b, BV bve);
    }
    public interface B7 {
        void f(BV v);
    }
    public interface B8 {
        void f(BV v);
    }
    public interface B9 {}

    public interface C1 {
        void f(C2 c);
    }
    public interface C2 {}
    public interface C5 {
        void f(C6 c);
    }
    public interface C6 {}
}
