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

public class MethodMapping {
    public static class SimpleEmptyImpl {}
    public static class PlainImpl {
        public void a() {}
        public boolean b() {
            return false;
        }
    }
    public interface Callback {
        void hello();
    }
    public interface JBRTwoWay {
        void something();
    }
    public interface ConversionImpl {
        default SimpleEmptyImpl convert(PlainImpl a, Callback b, JBRTwoWay c) {
            return null;
        }
    }
    public static class ConversionSelfImpl implements ConversionImpl {
        public ConversionSelfImpl convert(Object a, Object b, Object c) {
            return null;
        }
    }
    public static class ConversionFailImpl implements ConversionImpl {}
}
