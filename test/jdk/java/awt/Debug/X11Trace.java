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

/* @test
 * @summary Verifies the -Dsun.awt.x11.trace option
 * @requires (os.family == "linux")
 * @library /test/lib
 * @run main X11Trace
 */
import java.awt.AWTException;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import jdk.test.lib.process.ProcessTools;
import jdk.test.lib.process.OutputAnalyzer;

public class X11Trace {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("runtest")) {
            runTest();
        } else {
            OutputAnalyzer oa = ProcessTools.executeTestJvm("-Dsun.awt.x11.trace=log,timestamp,stats,td=0", X11Trace.class.getName(), "runtest");
            oa.shouldContain("held AWT lock for").shouldContain("AWT Lock usage statistics").shouldHaveExitValue(0);

            oa = ProcessTools.executeTestJvm("-Dsun.awt.x11.trace=log", X11Trace.class.getName(), "runtest");
            oa.shouldContain("held AWT lock for").shouldNotContain("AWT Lock usage statistics").shouldHaveExitValue(0);

            oa = ProcessTools.executeTestJvm("-Dsun.awt.x11.trace=log,timestamp,stats,td=0,out:mylog", X11Trace.class.getName(), "runtest");
            oa.shouldHaveExitValue(0).stderrShouldBeEmpty();

            final String logFileContents = Files.readString(Paths.get("mylog"));
            OutputAnalyzer logOA = new OutputAnalyzer(logFileContents);
            logOA.shouldContain("held AWT lock for").shouldContain("AWT Lock usage statistics");
        }
    }

    public static void delay(int ms) {
        try {
            Thread.sleep(ms);
        } catch(InterruptedException e) {
        }
    }

    public static void runTest() {
        runSwing(() -> {
            final JFrame frame = new JFrame("test");
            frame.setVisible(true);
            delay(500);
            frame.dispose();
        });
    }

    private static void runSwing(Runnable r) {
        try {
            SwingUtilities.invokeAndWait(r);
        } catch (InterruptedException e) {
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }
}

