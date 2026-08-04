/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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
 *
 */

import java.net.URL;
import java.nio.file.Path;
import java.security.CodeSource;

// See ../RelocatedAppJarCodeSource.java
//
// This class is loaded from the CDS archive. Its JAR file has been moved to a
// different directory since the archive was created, so the classpath location
// recorded in the archive no longer exists. The CodeSource must nevertheless
// refer to the JAR file at its current (moved) location.
//
// args[0] is the expected location of the JAR file that this class was loaded from.
// args[1], if present, is the expected location of the JAR file that holds
// RelocatedJarApp2.
public class RelocatedJarApp {
    public static void main(String args[]) throws Exception {
        checkCodeSource(RelocatedJarApp.class, args[0]);
        if (args.length > 1) {
            checkCodeSource(Class.forName("RelocatedJarApp2"), args[1]);
        }
    }

    private static void checkCodeSource(Class<?> c, String expectedJar) throws Exception {
        CodeSource cs = c.getProtectionDomain().getCodeSource();
        if (cs == null) {
            throw new RuntimeException("CodeSource of " + c.getName() + " is null");
        }

        URL loc = cs.getLocation();
        if (loc == null) {
            // Before JBR-9098, the VM tried to convert the (stale) dump-time
            // classpath location into a URL. That conversion failed, so every class
            // loaded from this JAR ended up with a null CodeSource location -- and
            // the failing file system lookup was repeated for every single class.
            throw new RuntimeException("CodeSource location of " + c.getName() + " is null");
        }

        Path actual = Path.of(loc.toURI()).toRealPath();
        Path expected = Path.of(expectedJar).toRealPath();
        if (!actual.equals(expected)) {
            throw new RuntimeException("CodeSource location of " + c.getName() + " is " +
                                       actual + " but should be " + expected);
        }

        System.out.println("CodeSource of " + c.getName() + " is correct: " + actual);
    }
}
