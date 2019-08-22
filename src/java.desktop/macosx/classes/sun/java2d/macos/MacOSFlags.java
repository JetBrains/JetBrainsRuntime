/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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

package sun.java2d.macos;

import java.security.PrivilegedAction;

public class MacOSFlags {

    /**
     * Description of command-line flags.  All flags with [true|false]
     * values
     *      metalEnabled: usage: "-Dsun.java2d.metal=[true|false]"
     */

    private static boolean metalEnabled;

    static {
        initJavaFlags();
        initNativeFlags();
    }

    private static native boolean initNativeFlags();

    private static boolean getBooleanProp(String p, boolean defaultVal) {
        String propString = System.getProperty(p);
        boolean returnVal = defaultVal;
        if (propString != null) {
            if (propString.equals("true") ||
                propString.equals("t") ||
                propString.equals("True") ||
                propString.equals("T") ||
                propString.equals("")) // having the prop name alone
            {                          // is equivalent to true
                returnVal = true;
            } else if (propString.equals("false") ||
                       propString.equals("f") ||
                       propString.equals("False") ||
                       propString.equals("F"))
            {
                returnVal = false;
            }
        }
        return returnVal;
    }


    private static boolean getPropertySet(String p) {
        String propString = System.getProperty(p);
        return (propString != null) ? true : false;
    }

    private static void initJavaFlags() {
        java.security.AccessController.doPrivileged(
                (PrivilegedAction<Object>) () -> {
                    metalEnabled = getBooleanProp("sun.java2d.metal", false);
                    return null;
                });
    }

    public static boolean isMetalEnabled() {
        return metalEnabled;
    }
}
