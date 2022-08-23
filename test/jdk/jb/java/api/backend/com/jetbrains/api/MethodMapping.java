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

public class MethodMapping {
    public interface SimpleEmpty {}
    public interface Plain {
        void a();
        boolean b();
        void c(String... a);
    }
    public interface PlainFail extends Plain {}
    public interface ApiCallback {
        void hello();
    }
    public interface ApiTwoWay {
        void something();
    }
    public interface Conversion {
        SimpleEmpty convert(Plain a, ApiCallback b, ApiTwoWay c);
    }
    public interface ConversionSelf extends Conversion {
        ConversionSelf convert(Object a, Object b, Object c);
    }
    public interface ConversionFail extends Conversion {
        void missingMethod();
    }
}
