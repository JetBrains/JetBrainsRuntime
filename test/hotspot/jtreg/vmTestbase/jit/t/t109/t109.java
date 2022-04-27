/*
 * Copyright (c) 2008, 2020, Oracle and/or its affiliates. All rights reserved.
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
package jit.t.t109;

import java.io.*;
import nsk.share.TestFailure;
import nsk.share.GoldChecker;

// THIS TEST IS LINE NUMBER SENSITIVE

// Uncaught exception, one jit'd frame on the stack, implicit exception.

public class t109
{
    public static final GoldChecker goldChecker = new GoldChecker( "t109" );

    public static void main(String argv[])
    {
        try {
            doit();
        } catch (Throwable t) {
                StringWriter sr = new StringWriter();
                t.printStackTrace(new PrintWriter(sr));
                t109.goldChecker.print(sr.toString().replace("\t", "        "));
        }
        t109.goldChecker.check();
    }

    public static void doit()
    {
        int i = 0;
        int j = 39;
        j /= i;
    }
}
