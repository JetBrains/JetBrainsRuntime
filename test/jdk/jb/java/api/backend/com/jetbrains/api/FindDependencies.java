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
