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

import com.sun.jna.Library;
import com.sun.jna.Native;


/* @test
 * @library ../../../lib/jna.jar
 * @summary regression test on JRE-394. Actuall behaviour is to cache env on runtime classes loading
 * @run main/othervm SetEnv394
 */

public class SetEnv394 {

    // Inside the Environment class...
    public interface WinLibC extends Library {
        int _putenv(String name);
    }

    public interface LinuxLibC extends Library {
        int setenv(String name, String value, int overwrite);
    }

    static public class POSIX {
        static Object libc;

        static {
            if (System.getProperty("os.name").contains("Linux"))
                libc = Native.loadLibrary("c", SetEnv394.LinuxLibC.class);

            else if (System.getProperty("os.name").contains("Mac OS X"))
                libc = Native.loadLibrary("c", SetEnv394.LinuxLibC.class);

            else if (System.getProperty("os.name").contains("Windows"))
                libc = Native.loadLibrary("msvcrt", SetEnv394.WinLibC.class);

            else
                throw new RuntimeException("Unsupported OS: " + System.getProperty("os.name"));
        }

        int setenv(String name, String value, int overwrite) {
            if (libc instanceof SetEnv394.LinuxLibC) {
                return ((SetEnv394.LinuxLibC) libc).setenv(name, value, overwrite);
            } else {
                return ((SetEnv394.WinLibC) libc)._putenv(name + "=" + value);
            }
        }
    }

    private static SetEnv394.POSIX libc = new SetEnv394.POSIX();

    private static final String ENV_VARIABLE = "V_394";
    private static final String ENV_VALUE = "VALUE394";

    public static void main(String[] args) {
        System.getenv(ENV_VARIABLE); // Cache environment variables
        System.out.println("Setting env variable");

        int result = libc.setenv(ENV_VARIABLE, ENV_VALUE, 1);
        if (result != 0)
            throw new RuntimeException("Setting was unsuccessful. Result: " + result);

        String setActualValue = System.getenv(ENV_VARIABLE);
        if (setActualValue != null)
            throw new RuntimeException("Actual value of the varibale \'" + ENV_VARIABLE + "\' is \'" + setActualValue
                    + "\', expecting null");
    }
}