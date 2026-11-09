/*
 * Copyright 2026 JetBrains s.r.o.
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
 * @key headful
 * @summary Regression test for JBR-10654: Toolkit.sync() and the toolkit's shutdown do not start the
 *          Skia backend in a process that never created a screen device.
 * @requires os.family == "windows"
 * @library /test/lib
 * @run main/othervm SkiaLazyStartTest
 */

import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

/**
 * The backend starts with the first screen device (Win32GraphicsEnvironment.makeScreenDevice):
 * a process that only uses the toolkit must not load libawt_skia and create a GPU device from
 * Toolkit.sync() or, with -Dsun.java2d.d3d=true, from the toolkit's shutdown hook. Each case is a
 * child JVM with verbose output, which names every start ("Skia rendering enabled: ..." or, without
 * the library, "Skia library is not available"); a child that creates the screen device is the
 * control that the line is printed at all.
 */
public class SkiaLazyStartTest {

    /** What verbose prints on stderr when the backend starts, with and without the library (SKEnvironment.start). */
    private static final Pattern STARTED = Pattern.compile("Skia rendering enabled|Skia library is not available");

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("child")) {
            child(args[1]);
            return;
        }
        List<String> failures = new ArrayList<>();
        check("screen", true, failures);
        check("sync", false, failures);
        check("exit", false, failures);
        if (!failures.isEmpty()) {
            throw new RuntimeException(String.join("; ", failures));
        }
        System.out.println("PASSED");
    }

    private static void check(String mode, boolean startExpected, List<String> failures) throws Exception {
        ProcessBuilder processBuilder = ProcessTools.createTestJavaProcessBuilder(
                "-Dsun.java2d.skia=true", "-Dsun.java2d.skia.verbose=true", "-Dsun.java2d.skia.pipelineCache=off",
                "-Dsun.java2d.d3d=true", SkiaLazyStartTest.class.getName(), "child", mode);
        OutputAnalyzer output = ProcessTools.executeProcess(processBuilder);
        System.out.println("child " + mode + " stdout:\n" + output.getStdout());
        System.out.println("child " + mode + " stderr:\n" + output.getStderr());
        output.shouldHaveExitValue(0);
        output.shouldContain("child done");
        boolean started = STARTED.matcher(output.getStderr()).find();
        if (started != startExpected) {
            failures.add(mode + ": the backend " + (started ? "started" : "did not start"));
        }
    }

    private static void child(String mode) {
        switch (mode) {
            case "screen" -> GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            case "sync" -> Toolkit.getDefaultToolkit().sync();
            case "exit" -> Toolkit.getDefaultToolkit();   // the toolkit's shutdown hook runs at exit
            default -> throw new IllegalArgumentException(mode);
        }
        System.out.println("child done");
    }
}
