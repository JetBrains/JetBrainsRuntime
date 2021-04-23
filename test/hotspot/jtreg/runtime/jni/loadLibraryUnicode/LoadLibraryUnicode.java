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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import jdk.test.lib.Platform;
import jdk.test.lib.Asserts;

public class LoadLibraryUnicode {

    static native int giveANumber();

    private static final String NON_LATIN_PATH_NAME = "ka-\u1889-omega-\u03c9";

    public static void verifySystemLoad() throws Exception {
        String osDependentLibraryFileName = null;
        if (Platform.isLinux()) {
            osDependentLibraryFileName = "libLoadLibraryUnicode.so";
        } else if (Platform.isOSX()) {
            osDependentLibraryFileName = "libLoadLibraryUnicode.dylib";
        } else if (Platform.isWindows()) {
            osDependentLibraryFileName = "LoadLibraryUnicode.dll";
        } else {
            throw new Error("Unsupported OS");
        }

        String testNativePath = LoadLibraryUnicodeTest.getSystemProperty("test.nativepath");
        Path origLibraryPath = Paths.get(testNativePath).resolve(osDependentLibraryFileName);

        Path currentDirPath = Paths.get(".").toAbsolutePath();
        Path newLibraryPath = currentDirPath.resolve(NON_LATIN_PATH_NAME);
        Files.createDirectory(newLibraryPath);
        newLibraryPath = newLibraryPath.resolve(osDependentLibraryFileName);

        System.out.println(String.format("Copying '%s' to '%s'", origLibraryPath, newLibraryPath));
        Files.copy(origLibraryPath, newLibraryPath);

        System.out.println(String.format("Loading '%s'", newLibraryPath));
        System.load(newLibraryPath.toString());

        final int retval = giveANumber();
        Asserts.assertEquals(retval, 42);
    }

    public static void verifyExceptionMessage() throws Exception {
        Path currentDirPath = Paths.get(".").toAbsolutePath();
        Path newLibraryPath = currentDirPath.resolve(NON_LATIN_PATH_NAME).resolve("non-existent-library");

        System.out.println(String.format("Loading '%s'", newLibraryPath));
        try {
            System.load(newLibraryPath.toString());
        } catch(UnsatisfiedLinkError e) {
            // The name of the library may have been corrupted by encoding/decoding it improperly.
            // Verify that it is still the same.
            Asserts.assertTrue(e.getMessage().contains(NON_LATIN_PATH_NAME));
        }
    }

    public static void main(String[] args) throws Exception {
        verifySystemLoad();
        verifyExceptionMessage();
    }
}
