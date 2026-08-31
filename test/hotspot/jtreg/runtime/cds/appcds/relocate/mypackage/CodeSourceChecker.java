/* Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2026 JetBrains s.r.o.
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
 */

package mypackage;

import java.net.URL;
import java.nio.file.Path;
import java.security.CodeSource;

public class CodeSourceChecker {
    public static void main(String[] args) throws Exception {
        if (args.length % 2 != 0) {
            throw new IllegalArgumentException("class/path pairs expected");
        }

        for (int i = 0; i < args.length; i += 2) {
            verifyCodeSourceLocation(Class.forName(args[i]), args[i + 1]);
        }
    }

    private static void verifyCodeSourceLocation(Class<?> clazz, String expectedJar) throws Exception {
      CodeSource codeSource = clazz.getProtectionDomain().getCodeSource();

      if (codeSource == null || codeSource.getLocation() == null) {
          throw new RuntimeException("No CodeSource for " + clazz.getName());
      }

      Path expectedPath = Path.of(expectedJar).toRealPath();
      Path actualPath = Path.of(codeSource.getLocation().toURI()).toRealPath();

      if (!actualPath.equals(expectedPath)) {
          throw new RuntimeException( "Unexpected CodeSource for " + clazz.getName() + ": expected " + expectedPath + ", actual " + actualPath);
      }

      System.out.println("CodeSource of " + clazz.getName() + " is correct: " + actualPath);
    }
}
