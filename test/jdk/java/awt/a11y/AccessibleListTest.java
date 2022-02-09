/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2021, JetBrains s.r.o.. All rights reserved.
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

/*
 * @test
 * @bug 8271846
 * @summary Test implementation of AccessibleList interface
  * @author Artem.Semenov@jetbrains.com
 * @run main AccessibleListTest
 * @requires (os.family == "windows" | os.family == "mac")
 */

import javax.swing.JList;

import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleList;
import javax.accessibility.AccessibleTable;

public class AccessibleListTest {
    private static boolean testResult = false;
    private static String exceptionString = null;
    private static final String[] NAMES = {"One", "Two", "Three", "Four", "Five"};
    private static final boolean OK = true;
    private static final boolean NO_OK = false;
    private static int noOkCount = 0;

    public static void main(String[] args) throws Exception {
        AccessibleListTest.runTest();

        if (!testResult) {
            throw new RuntimeException(AccessibleListTest.exceptionString);
        }
    }

    private static void countersControl(final boolean isOk, final String methodName) {
        if (isOk) {
            System.out.println(methodName + ": ok");
        } else {
            noOkCount += 1;
            System.out.println(methodName + ": fail");
        }
    }

    public static void runTest() {
        JList<String> list = new JList<>(NAMES);

        AccessibleContext ac = list.getAccessibleContext();
        if (ac == null) {
            return;
        }

        AccessibleTable at = ac.getAccessibleTable();
        if ((at == null) && !(at instanceof AccessibleList)) {
            return;
        }

        AccessibleList al = ((AccessibleList)at);

int size = al.getSize();
if (size == 5) {
    countersControl(OK, "getSize()");
} else {
    countersControl(NO_OK, "getSize()");
}

if (noOkCount == 0) {
    AccessibleListTest.testResult = true;
    System.out.println("All methods: Ok");
} else {
    AccessibleListTest.exceptionString = "Test failed. " + String.valueOf(noOkCount) + " methods is no ok";
}
    }
}
