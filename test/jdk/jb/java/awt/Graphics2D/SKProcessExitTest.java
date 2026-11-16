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

import java.awt.Color;
import java.awt.Frame;
import java.awt.Robot;
import java.util.List;
import java.util.concurrent.TimeUnit;

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jtreg.SkippedException;
import sun.java2d.skia.SKEnvironment;

/*
 * @test
 * @key headful
 * @summary Regression test for JBR-10654: a process that rendered with Skia exits cleanly, on Dawn
 *          and with -Dsun.java2d.d3d=true.
 * @requires os.family == "windows"
 * @library /test/lib
 * @modules java.desktop/sun.java2d java.desktop/sun.java2d.skia
 * @run main/othervm SKProcessExitTest dawn
 * @run main/othervm SKProcessExitTest d3d
 */

/*
 * Exit code 0 and nothing on stderr, each in a child JVM: on Dawn, and with
 * -Dsun.java2d.d3d=true as well, where the toolkit's shutdown must not start the Direct3D queue
 * (Skia owns the screen, no Direct3D device exists). The child must have rendered on the API of
 * its mode; where that API does not start (Skia does not start then) there is nothing to test.
 */

/** The Direct3D render queue's flusher thread (D3DRenderQueue): running at exit only if the queue was started. */
private static final String D3D_FLUSHER = "Java2D Queue Flusher";

/** What the child prints when Skia did not start, for the parent to skip on. */
private static final String NOT_ENABLED = "Skia is not enabled";

private static final int IMAGE_SIZE = 64;

// the child's window: small, but shown and presented
private static final int FRAME_WIDTH = 200;
private static final int FRAME_HEIGHT = 100;

/** Time for the window to be presented before it is disposed. */
private static final int PRESENT_DELAY_MS = 300;

/** How long the test's shutdown hook waits for the toolkit's shutdown hook, which runs alongside, to do its work. */
private static final long TOOLKIT_SHUTDOWN_WAIT_MS = 1000;

/** The child must be gone within this: one hung at exit, what the test guards against, is killed and fails the test. */
private static final long EXIT_TIMEOUT_S = 60;

void main(String[] args) throws Exception {
    if (args.length > 1 && args[0].equals("child")) {
        child();
        return;
    }
    String mode = args[0];
    List<String> options = mode.equals("d3d") ? List.of("-Dsun.java2d.d3d=true") : List.of();
    List<String> command = PipelineComparison.skiaChildCommand(options, getClass(), "child", mode);
    Process child = ProcessTools.createTestJavaProcessBuilder(command).start();
    OutputAnalyzer output = new OutputAnalyzer(child);   // reads the child's output as it comes
    boolean exited = child.waitFor(EXIT_TIMEOUT_S, TimeUnit.SECONDS);
    if (!exited) {
        // a child that hangs at exit must not outlive the test
        child.descendants().forEach(ProcessHandle::destroyForcibly);
        child.destroyForcibly();
        child.waitFor();
    }
    System.out.println("child stdout:\n" + output.getStdout());
    System.out.println("child stderr:\n" + output.getStderr());
    System.out.println("child exit code: " + (exited ? child.exitValue() : "none"));
    if (output.getStdout().contains(NOT_ENABLED)) {
        throw new SkippedException("Skia is not enabled: nothing to test");
    }
    String api = "Dawn";
    String renderedOn = output.firstMatch("rendered on (.*)", 1);
    if (renderedOn != null && !renderedOn.contains(" Graphite/" + api + " on ")) {
        throw new SkippedException(api + " unavailable here (" + renderedOn + "): nothing to test");
    }
    if (!exited) {
        throw new RuntimeException("the child did not exit within " + EXIT_TIMEOUT_S + " s");
    }
    output.shouldHaveExitValue(0);
    output.shouldContain("rendered on");
    output.stderrShouldBeEmptyIgnoreVMWarnings();
    output.shouldContain("D3D queue at exit: false");
    System.out.println("PASSED");
}

private void child() throws Exception {
    if (!SKEnvironment.isSkiaEnabled()) {
        System.out.println(NOT_ENABLED);
        return;
    }
    PipelineComparison.rendered(PipelineComparison.defaultConfiguration(), IMAGE_SIZE, IMAGE_SIZE, graphics -> {
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, IMAGE_SIZE, IMAGE_SIZE);
    });
    Frame frame = new Frame("SKProcessExitTest");
    frame.setSize(FRAME_WIDTH, FRAME_HEIGHT);
    frame.setVisible(true);
    Robot robot = new Robot();
    robot.waitForIdle();
    robot.delay(PRESENT_DELAY_MS);
    frame.dispose();
    System.out.println("rendered on " + SKEnvironment.runningOn());
    // runs alongside the toolkit's shutdown hook: whether that started the Direct3D queue
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        try {
            Thread.sleep(TOOLKIT_SHUTDOWN_WAIT_MS);
        } catch (InterruptedException ignored) {
        }
        boolean d3dQueueRunning = Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.getName().equals(D3D_FLUSHER));
        System.out.println("D3D queue at exit: " + d3dQueueRunning);
        System.out.flush();
    }));
    System.exit(0);
}
