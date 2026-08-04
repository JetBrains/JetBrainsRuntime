/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.dnd.*;
import java.awt.event.InputEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/*
  @test
  @key headful
  @requires (os.family == "windows")
  @summary Tests that a DnD source JVM does not hang when the target calls GetData during the drag.
  @library /test/lib
  @run main GetDataVmHangTest
 */
public class GetDataVmHangTest {

    static final Rectangle SOURCE_BOUNDS = new Rectangle(100, 100, 200, 100);
    static final Rectangle TARGET_BOUNDS = new Rectangle(200, 100, 200, 100);
    static final Point SOURCE_POINT = new Point(150, 150);
    static final Point TARGET_POINT = new Point(350, 150);

    static final String TARGET_READY_TOKEN = "drop target ready";

    static final int ROBOT_AUTO_DELAY_MS = 50;
    static final int ROBOT_EXTRA_DELAY_BEFORE_FIRST_CLICK_MS = 500;
    static final int ROBOT_MOUSE_JITTER_DURATION_MS = 3000;

    static final int SOURCE_CONVERT_DATA_TIMEOUT_MS = 2000;
    static final int SOURCE_DRAG_FINISHED_TIMEOUT_MS = 5000;

    static final int TARGET_GET_DATA_DELAY_MS = 500;
    static final int TARGET_PROCESS_TERMINATION_TIMEOUT_AFTER_SOURCE_IS_FINISHED_MS = 5000;

    static boolean toolkitThreadMightBeDeadlocked = false;

    public static void main(String[] args) throws Throwable {
        if (args.length == 0) {
            runDriver();
            return;
        }

        if (args.length == 1 && "source".equals(args[0])) {
            try {
                runDragSource();
                System.exit(0);
            } catch (Throwable e) {
                e.printStackTrace();
                if (toolkitThreadMightBeDeadlocked) {
                    Runtime.getRuntime().halt(1);
                } else {
                    System.exit(1);
                }
            }
        }

        if (args.length == 1 && "target".equals(args[0])) {
            try {
                runDropTargetSetup();
                while (System.in.read() != -1) {}
                System.exit(0);
            } catch (Throwable e) {
                e.printStackTrace();
                System.exit(1);
            }
        }

        throw new Error("Unrecognized args: " + Arrays.toString(args));
    }

    public static void runDriver() throws Throwable {
        Process targetProcess = ProcessTools.createTestJavaProcessBuilder(
                        "--add-opens=java.desktop/java.awt.dnd=ALL-UNNAMED",
                        "--add-opens=java.desktop/sun.awt.dnd=ALL-UNNAMED",
                        "--add-opens=java.desktop/sun.awt.windows=ALL-UNNAMED",
                        GetDataVmHangTest.class.getName(), "target")
                .start();
        try {
           String line = new BufferedReader(new InputStreamReader(targetProcess.getInputStream())).readLine();
           if (!TARGET_READY_TOKEN.equals(line)) {
               throw new RuntimeException("Failed to get ready token from target");
           }
           OutputAnalyzer targetOA = new OutputAnalyzer(targetProcess);
           OutputAnalyzer sourceOA = ProcessTools.executeTestJava(GetDataVmHangTest.class.getName(), "source");

           targetProcess.getOutputStream().close(); // signal the target to shut down
           if (!targetProcess.waitFor(TARGET_PROCESS_TERMINATION_TIMEOUT_AFTER_SOURCE_IS_FINISHED_MS, TimeUnit.MILLISECONDS)) {
               throw new RuntimeException("Target process failed to terminate");
           }

           sourceOA.stderrShouldBeEmptyIgnoreVMWarnings();
           sourceOA.shouldHaveExitValue(0);
           targetOA.stderrShouldBeEmptyIgnoreVMWarnings();
           targetOA.shouldHaveExitValue(0);
       } finally {
            targetProcess.destroyForcibly();
       }
    }

    static void runDragSource() throws Exception {
        Frame frame = new Frame("Drop source");
        frame.setBounds(SOURCE_BOUNDS);

        CountDownLatch dragCompletedLatch = new CountDownLatch(1);
        boolean[] convertDataDetected = {false};

        toolkitThreadMightBeDeadlocked = true;
        DragSource.getDefaultDragSource().createDefaultDragGestureRecognizer(frame, DnDConstants.ACTION_COPY, (DragGestureEvent dge) -> {
            dge.startDrag(null, new StringSelection("dnd dummy data"), new DragSourceAdapter() {
                private boolean firstDragOver = true;

                @Override
                public void dragOver(DragSourceDragEvent dsde) {
                    if (firstDragOver) {
                        firstDragOver = false;
                        // Wait for the target to call getData(),
                        // which we detect by looking for convertData() in source's toolkit thread stack.
                        // Only the EDT (which we're currently blocking) can end convertData(), so we won't miss it.
                        convertDataDetected[0] = waitForConvertDataInToolkitThreadStackTrace();
                    }
                }

                @Override
                public void dragDropEnd(DragSourceDropEvent dsde) {
                    dragCompletedLatch.countDown();
                }
            });
        });
        frame.setVisible(true);

        performDragWithRobot();

        if (!dragCompletedLatch.await(SOURCE_DRAG_FINISHED_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw new RuntimeException("Drag never completed, toolkit thread is likely stuck");
        }
        toolkitThreadMightBeDeadlocked = false;

        if (!convertDataDetected[0]) {
            throw new RuntimeException("Setup failed: convertData never detected on source's toolkit thread");
        }
    }

    static void performDragWithRobot() throws Exception {
        Robot robot = new Robot();
        robot.setAutoDelay(ROBOT_AUTO_DELAY_MS);
        robot.waitForIdle();
        robot.delay(ROBOT_EXTRA_DELAY_BEFORE_FIRST_CLICK_MS);

        robot.mouseMove(SOURCE_POINT.x, SOURCE_POINT.y);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseMove(TARGET_POINT.x, TARGET_POINT.y);
        for (int i = 0; i < ROBOT_MOUSE_JITTER_DURATION_MS / ROBOT_AUTO_DELAY_MS; ++i) {
            robot.mouseMove(TARGET_POINT.x, TARGET_POINT.y + (i % 2) * 10);
        }
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    }

    static boolean waitForConvertDataInToolkitThreadStackTrace() {
        Thread toolkitThread = Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> "AWT-Windows".equals(thread.getName())).findFirst().get();
        long waitEnd = System.currentTimeMillis() + SOURCE_CONVERT_DATA_TIMEOUT_MS;
        while (System.currentTimeMillis() < waitEnd) {
            if (Arrays.stream(toolkitThread.getStackTrace())
                    .anyMatch(frame -> frame.getMethodName().equals("convertData"))
            ) {
                return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException _) {
            }
        }
        return false;
    }

    static void runDropTargetSetup() throws Exception {
        Frame frame = new Frame("Drop target");
        frame.setBounds(TARGET_BOUNDS);
        new DropTarget(frame, new DropTargetAdapter() {
            @Override
            public void dragEnter(DropTargetDragEvent dtde) {
                try {
                    // Use reflection to call the private getData() method.
                    // This is simulating an Electron app, which will actually call getData() asynchronously in this manner.
                    Field dropTargetContextPeerField = DropTargetContext.class
                            .getDeclaredField("dropTargetContextPeer");
                    dropTargetContextPeerField.setAccessible(true);

                    Method getNativeDragContextMethod = Class.forName("sun.awt.dnd.SunDropTargetContextPeer")
                            .getDeclaredMethod("getNativeDragContext");
                    getNativeDragContextMethod.setAccessible(true);

                    Method getDataMethod = Class.forName("sun.awt.windows.WDropTargetContextPeer")
                            .getDeclaredMethod("getData", long.class, long.class);
                    getDataMethod.setAccessible(true);

                    Object dropTargetContextPeer = dropTargetContextPeerField.get(dtde.getDropTargetContext());
                    long nativeDragContext = (long) getNativeDragContextMethod.invoke(dropTargetContextPeer);

                    // return from dragEnter to unblock EDT, but call getData() asynchronously in 1s (like Electron apps)
                    new Thread(() -> {
                        final long CF_UNICODETEXT = 13; // from <Winuser.h>
                        try {
                            Thread.sleep(TARGET_GET_DATA_DELAY_MS);
                            getDataMethod.invoke(dropTargetContextPeer, nativeDragContext, CF_UNICODETEXT);
                        } catch (Throwable e) {
                            e.printStackTrace();
                            System.exit(1);
                        }
                    }).start();
                } catch (Throwable e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public void drop(DropTargetDropEvent dtde) {
                dtde.acceptDrop(DnDConstants.ACTION_COPY);
                dtde.dropComplete(true);
            }
        });
        frame.setVisible(true);
        new Robot().waitForIdle();

        System.out.println(TARGET_READY_TOKEN);
    }
}
