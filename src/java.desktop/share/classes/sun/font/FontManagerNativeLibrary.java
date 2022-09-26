/*
 * Copyright (c) 2007, 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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

package sun.font;

import sun.awt.OSInfo;
import sun.util.logging.PlatformLogger;

import java.nio.file.Path;
import java.util.Set;

@SuppressWarnings("removal")
public class FontManagerNativeLibrary {
    static {
        java.security.AccessController.doPrivileged(
                                    new java.security.PrivilegedAction<Object>() {
            public Object run() {
               /* REMIND do we really have to load awt here? */
               System.loadLibrary("awt");
               if (OSInfo.getOSType() == OSInfo.OSType.WINDOWS) {
                   /* Ideally fontmanager library should not depend on
                      particular implementation of the font scaler.
                      However, freetype scaler is basically small wrapper on
                      top of freetype library (that is used in binary form).

                      This wrapper is compiled into fontmanager and this make
                      fontmanger library depending on freetype library.

                      On Windows DLL's in the JRE's BIN directory cannot be
                      found by windows DLL loading as that directory is not
                      on the Windows PATH.

                      To avoid link error we have to load freetype explicitly
                      before we load fontmanager.

                      NB: consider moving freetype wrapper part to separate
                          shared library in order to avoid dependency. */
                   System.loadLibrary("freetype");
               }
               System.loadLibrary("fontmanager");

               checkLibraries();
               return null;
            }
        });
    }

    /*
     * Call this method to ensure libraries are loaded.
     *
     * Method acts as trigger to ensure this class is loaded
     * (and therefore initializer code is executed).
     * Actual loading is performed by static initializer.
     * (no need to execute doPrivileged block more than once)
     */
    public static void load() {}


    private static class Holder {
        private static final PlatformLogger errorLog = PlatformLogger.getLogger("sun.font.FontManagerNativeLibrary");
    }
    public static void checkLibraries() {
        final String osName = System.getProperty("os.name");
        final boolean isMacOS = osName.startsWith("Mac");
        final boolean isLinux = osName.startsWith("Linux");

        if (!(isMacOS || isLinux)) return;

        final String[] loadedLibraries = loadedLibraries();

        final String libawtName = isMacOS ? "libawt.dylib" : "libawt.so";
        final String bootPath = System.getProperty("sun.boot.library.path");
        final Path basePath = bootPath != null
                ? Path.of(bootPath)
                : getBasePath(libawtName, loadedLibraries);

        if (basePath == null) {
            // Nothing to check if the native code failed to report where libawt is
            return;
        }

        final String libraryPathEnvVarName = isMacOS
                ? "DYLD_LIBRARY_PATH"
                : "LD_LIBRARY_PATH";
        final Set<String> ourLibraries = isMacOS
                ? Set.of("libawt_lwawt.dylib", "libfontmanager.dylib", "libfreetype.dylib")
                : Set.of("libawt_xawt.so", "libfontmanager.so");

        boolean warnedAlready = false;
        for (String pathName: loadedLibraries) {
            if (pathName == null)
                continue;
            final Path libPath = Path.of(pathName);
            final Path libFileName = libPath.getFileName();
            if (ourLibraries.contains(libFileName.toString())) {
                final Path libDirectory = libPath.getParent();
                if (!basePath.equals(libDirectory)) {
                    Holder.errorLog.severe("Library '" + libFileName +
                            "' loaded from '" + libDirectory + "' instead of '" +
                            basePath + "'");
                    if (!warnedAlready) {
                        warnedAlready = true;
                        final String libraryPathOverride = System.getenv(libraryPathEnvVarName);
                        if (libraryPathOverride != null) {
                            Holder.errorLog.severe("Note: " + libraryPathEnvVarName +
                                    " is set to '" + libraryPathOverride + "'");
                        }
                    }
                }
            }
        }
    }

    private static Path getBasePath(String libawtName, String[] libs) {
        Path basePath = null;
        for (String path: libs) {
            if (path.endsWith(libawtName)) {
                basePath = Path.of(path).getParent().normalize();
            }
        }
        return basePath;
    }

    private static native String[] loadedLibraries();
}
