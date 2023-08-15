/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
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
import jdk.test.lib.process.ProcessTools;
import jdk.test.lib.process.OutputAnalyzer;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.nio.file.Files;
import java.nio.file.Path;

public class WakefieldTestDriver {
    final static int DEFAULT_NUMBER_OF_SCREENS = 1;
    final static int DEFAULT_SCREEN_WIDTH      = 1280;
    final static int DEFAULT_SCREEN_HEIGHT     = 800;
    static int testTimeoutSeconds = 10;
    static void usage() {
        System.out.println(
                """
                WakefieldTestDriver [-resolution NxWxH] [-timeout SECONDS] ClassName [args]
                where
                N         - number of Weston outputs (screens); defaults to 1
                W         - width of each screen in pixels; defaults to 1280
                H         - height of each screen in pixels; defaults to 800
                ClassName - class to execute
                args      - arguments to give to the class""");
    }

    public static void main(String args[]) throws Exception {
        if (args.length < 1) {
            usage();
            throw new RuntimeException("Insufficient arguments to the test driver");
        }

        checkEnv(System.getenv());

        final List<String> jvmArgs = new ArrayList<String>();
        jvmArgs.add("-Dawt.toolkit.name=WLToolkit");
        jvmArgs.add("-Xcheck:jni");

        int nScreens     = DEFAULT_NUMBER_OF_SCREENS;
        int screenWidth  = DEFAULT_SCREEN_WIDTH;
        int screenHeight = DEFAULT_SCREEN_HEIGHT;

        for (int i = 0; i < args.length; ++i) {
            if (args[i].equals("-resolution") && i + 1 < args.length) {
                final String arg = args[i + 1];
                if (Character.isDigit(arg.charAt(0))) {
                    try {
                        final int firstXIndex = arg.indexOf("x", 0);
                        final int secondXIndex = arg.indexOf("x", firstXIndex + 1);
                        nScreens = Integer.valueOf(arg.substring(0, firstXIndex));
                        screenWidth = Integer.valueOf(arg.substring(firstXIndex + 1, secondXIndex));
                        screenHeight = Integer.valueOf(arg.substring(secondXIndex + 1, arg.length()));
                    } catch (IndexOutOfBoundsException | NumberFormatException ignored) {
                        usage();
                        throw new RuntimeException("Error parsing the first argument of the test driver");
                    }
                }
                ++i;
                continue;
            }

            if (args[i].equals("-timeout") && i + 1 < args.length) {
                final String arg = args[i + 1];
                testTimeoutSeconds = Integer.valueOf(arg);
                ++i;
                continue;
            }

            for (int j = i; j < args.length; ++j) {
                jvmArgs.add(args[j]);
            }
            break;
        }

        final String socketName = SOCKET_NAME_PREFIX + ProcessHandle.current().pid();
        final Process westonProcess = launchWeston(nScreens, screenWidth, screenHeight, socketName);
        try {
            System.out.println("Running test with WAYLAND_DISPLAY=" + socketName);
            final ProcessBuilder pb = ProcessTools.createTestJvm(jvmArgs);
            final Map<String, String> env = pb.environment();
            env.put("WAYLAND_DISPLAY", socketName);

            final Process p = pb.start();
            final OutputAnalyzer output = new OutputAnalyzer(p);
            final boolean exited = p.waitFor(testTimeoutSeconds, TimeUnit.SECONDS);
            if (!exited) p.destroy();
            System.out.println("Test finished. Output: [[[");
            System.out.println(output.getOutput());
            System.out.println("]]]");
            if (!exited) {
                throw new RuntimeException("Test timed out after " + testTimeoutSeconds + " seconds");
            }

            if (exited && output.getExitValue() != 0) {
                throw new RuntimeException("Test finished with non-zero exit code");
            }
        } finally {
            if (westonProcess != null) {
                final OutputAnalyzer westonOutput = new OutputAnalyzer(westonProcess);
                westonProcess.destroy();

                System.out.println("Weston output: [[[");
                System.out.println(westonOutput.getOutput());
                System.out.println("]]]");
            }
        }
    }

    static void checkEnv(Map<String, String> env) {
        if (!env.containsKey("DISPLAY")) {
            throw new RuntimeException("DISPLAY environment variable must be set to run this test driver");
        }

        if (!env.containsKey("XDG_RUNTIME_DIR")) {
            throw new RuntimeException("XDG_RUNTIME_DIR environment variable must be set to run this test driver; pass -e:XDG_RUNTIME_DIR to jtreg");
        }

        if (!env.containsKey("LIBWAKEFIELD")) {
            throw new RuntimeException("Set LIBWAKEFIELD environment variable to full path to libwakefield.so");
        }

        if (!Files.exists(Path.of(env.get("LIBWAKEFIELD")))) {
            throw new RuntimeException("LIBWAKEFIELD (" + env.get("LIBWAKEFIELD") + " does not point to an executable");
        }
    }

    static final String SOCKET_NAME_PREFIX = "wakefield-";

    static Process launchWeston(int nScreens, int width, int height, String socketName) throws IOException {
        final List<String> args = new ArrayList<String>();
        args.add("weston");
        args.add("--backend=x11-backend.so");
        args.add("--socket=" + socketName);
        args.add("--output-count=" + nScreens);
        args.add("--width=" + width);
        args.add("--height=" + height);
        args.add("--use-pixman");
        args.add("--idle-time=0");
        args.add("--logger-scopes=log,wakefield");
        //args.add("--log=weston-server-log.txt"); // Log to stderr, its easier to manage this way.
        args.add("--no-config");
        args.add("--modules=" + System.getenv("LIBWAKEFIELD"));

        System.out.println("Running " + String.join(" ", args));

        return new ProcessBuilder(args).redirectErrorStream(true).start();
    }
}

