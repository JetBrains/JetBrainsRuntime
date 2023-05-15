/*
 * Copyright 2000-2023 JetBrains s.r.o.
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
 * @summary Verifies that WindowMove test service of JBR is supported
 *          on Linux and not supported elsewhere.
 * @key headful
 * @run main WindowMoveTest
 */

import com.jetbrains.JBR;

public class WindowMoveTest {

    public static void main(String[] args) throws Exception {
        final String os = System.getProperty("os.name");
        if ("linux".equalsIgnoreCase(os)) {
            if (!JBR.isWindowMoveSupported()) {
                throw new RuntimeException("JBR.isWindowMoveSupported says it is NOT supported on Linux");
            }
            // Use: JBR.getWindowMove().startMovingTogetherWithMouse(jframe, MouseEvent.BUTTON1);
        } else {
            if (JBR.isWindowMoveSupported()) {
                throw new RuntimeException("JBR.isWindowMoveSupported says it's supported on " + os + "where it is NOT implemented");
            }
        }
    }
}
