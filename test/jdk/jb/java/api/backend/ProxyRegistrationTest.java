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

/*
 * @test
 * @modules java.base/com.jetbrains.internal:+open
 * @build com.jetbrains.*
 * @run main ProxyRegistrationTest
 */

import com.jetbrains.internal.JBRApi;

import static com.jetbrains.Util.*;

public class ProxyRegistrationTest {

    public static void main(String[] args) {
        JBRApi.ModuleRegistry r = init();
        // Only service may not have target type
        r.service("s");
        mustFail(() -> r.proxy("i"), IllegalArgumentException.class);
        mustFail(() -> r.proxy("i", null), NullPointerException.class);
        mustFail(() -> r.clientProxy("i", null), NullPointerException.class);
        mustFail(() -> r.twoWayProxy("i", null), NullPointerException.class);
        // Invalid 2-way mapping
        r.proxy("a", "b");
        mustFail(() -> r.clientProxy("b", "c"), IllegalArgumentException.class);
        mustFail(() -> r.clientProxy("c", "a"), IllegalArgumentException.class);
    }
}
