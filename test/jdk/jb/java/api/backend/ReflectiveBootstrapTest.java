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
 * @build com.jetbrains.JBR
 * @run main ReflectiveBootstrapTest
 */

import com.jetbrains.JBR;

import java.lang.invoke.MethodHandles;
import java.util.Objects;

public class ReflectiveBootstrapTest {

    public static void main(String[] args) throws Exception {
        JBR.ServiceApi api = (JBR.ServiceApi) Class.forName("com.jetbrains.bootstrap.JBRApiBootstrap")
                .getMethod("bootstrap", MethodHandles.Lookup.class)
                .invoke(null, MethodHandles.privateLookupIn(JBR.class, MethodHandles.lookup()));
        Objects.requireNonNull(api, "JBR API bootstrap failed");
    }
}
