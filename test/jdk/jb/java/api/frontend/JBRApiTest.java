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
 * @run shell build.sh
 * @run main JBRApiTest
 */

import com.jetbrains.JBR;

import java.awt.*;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Objects;

public class JBRApiTest {

    public static void main(String[] args) throws Exception {
        if (!JBR.getApiVersion().matches("\\w+\\.\\d+\\.\\d+\\.\\d+")) throw new Error("Invalid JBR API version");
        if (!JBR.isAvailable()) throw new Error("JBR API is not available");
        checkMetadata();
        testServices();
    }

    private static void checkMetadata() throws Exception {
        Class<?> metadata = Class.forName(JBR.class.getName() + "$Metadata");
        Field field = metadata.getDeclaredField("KNOWN_SERVICES");
        field.setAccessible(true);
        List<String> knownServices = List.of((String[]) field.get(null));
        if (!knownServices.contains("com.jetbrains.JBR$ServiceApi")) {
            throw new Error("com.jetbrains.JBR$ServiceApi was not found in known services of com.jetbrains.JBR$Metadata");
        }
    }

    private static void testServices() {
        Objects.requireNonNull((Dimension) JBR.getExtendedGlyphCache().getSubpixelResolution());
    }
}
