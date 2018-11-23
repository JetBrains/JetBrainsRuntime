/*
 * Copyright 2000-2017 JetBrains s.r.o.
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