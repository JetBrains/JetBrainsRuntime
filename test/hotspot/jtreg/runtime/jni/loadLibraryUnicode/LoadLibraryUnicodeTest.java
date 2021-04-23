/*
 * Copyright 2021 JetBrains s.r.o.
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

/* @test
 * @bug 8195129
 * @summary regression test for 8195129,
 *          verifies the ability to System.load() from a location containing non-Latin characters
 * @requires (os.family == "windows") | (os.family == "mac") | (os.family == "linux")
 * @library /test/lib
 * @build LoadLibraryUnicode
 * @run main/native LoadLibraryUnicodeTest
 */

import jdk.test.lib.process.ProcessTools;

public class LoadLibraryUnicodeTest {

    public static void main(String args[]) throws Exception {
        String nativePathSetting = "-Dtest.nativepath=" + getSystemProperty("test.nativepath");
        ProcessBuilder pb = ProcessTools.createTestJvm(nativePathSetting, LoadLibraryUnicode.class.getName());
        pb.environment().put("LC_ALL", "en_US.UTF-8");
        ProcessTools.executeProcess(pb)
                    .outputTo(System.out)
                    .errorTo(System.err)
                    .shouldHaveExitValue(0);
    }

    // Utility method to retrieve and validate system properties
    public static String getSystemProperty(String propertyName) throws Error {
        String systemProperty = System.getProperty(propertyName, "").trim();
        System.out.println(propertyName + " = " + systemProperty);
        if (systemProperty.isEmpty()) {
            throw new Error("TESTBUG: property " + propertyName + " is empty");
        }
        return systemProperty;
    }
}
