/*
 * Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2025, JetBrains s.r.o.. All rights reserved.
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

import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;

/*
 * @test
 * @summary Verifies that the program starts with the right toolkit
 * @requires os.family == "linux"
 * @run main/othervm -Dawt.toolkit.name=auto -Dtoolkit=auto AutoToolkit
 * @run main/othervm -Dawt.toolkit.name=WLToolkit -Dtoolkit=WLToolkit AutoToolkit
 * @run main/othervm -Dawt.toolkit.name=XToolkit -Dtoolkit=XToolkit AutoToolkit
 * @run main/othervm -Dawt.toolkit.name=garbage -Dtoolkit=garbage AutoToolkit
 * @run main/othervm -Dawt.toolkit.name= -Dtoolkit= AutoToolkit
 * @run main/othervm AutoToolkit
 */
public class AutoToolkit {
    public static void main(String[] args) throws Exception {
        String toolkitProp = System.getProperty("toolkit");
        String toolkitReal = System.getProperty("awt.toolkit.name");
        Toolkit tk = Toolkit.getDefaultToolkit();
        String tkName = tk.getClass().getName();
        tkName = tkName.substring(tkName.lastIndexOf('.') + 1);
        boolean expectWayland = System.getenv("WAYLAND_DISPLAY") != null;
        boolean isHeadless = GraphicsEnvironment.isHeadless();

        System.out.printf("Started with -Dawt.toolkit.name=%s, got %s; Toolkit.getDefaultToolkit() == %s%n",
                toolkitProp, toolkitReal, tkName);

        if (isHeadless) {
            if (!tkName.equals("HeadlessToolkit")) {
                throw new RuntimeException("Expected HeadlessToolkit because we are in the headless mode, got " + tkName);
            }
            System.out.println("Test PASSED");
            return;
        }

        switch (toolkitProp) {
            case "auto" -> {
                if (expectWayland) {
                    if (!tkName.equals("WLToolkit")) {
                        throw new RuntimeException("Expected WLToolkit, got " + tkName);
                    }
                    if (!toolkitReal.equals("WLToolkit")) {
                        throw new RuntimeException("Expected WLToolkit property value, got " + tkName);
                    }
                } else {
                    if (!tkName.equals("XToolkit")) {
                        throw new RuntimeException("Expected XToolkit, got " + tkName);
                    }
                    if (!toolkitReal.equals("XToolkit")) {
                        throw new RuntimeException("Expected XToolkit property value, got " + tkName);
                    }
                }
            }
            case "WLToolkit" -> {
                if (!tkName.equals("WLToolkit")) {
                    throw new RuntimeException("Expected WLToolkit, got " + tkName);
                }
            }
            case "XToolkit" -> {
                if (!tkName.equals("XToolkit")) {
                    throw new RuntimeException("Expected XToolkit, got " + tkName);
                }
            }
            case null, default -> {
                if (!tkName.equals("XToolkit")) {
                    throw new RuntimeException("Expected XToolkit, got " + tkName);
                }
                if (!toolkitReal.equals("XToolkit")) {
                    throw new RuntimeException("Expected XToolkit property value, got " + tkName);
                }
            }
        }
        System.out.println("Test PASSED");
    }
}
