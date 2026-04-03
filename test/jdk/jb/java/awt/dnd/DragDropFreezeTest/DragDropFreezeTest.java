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
 * @summary EDT must not deadlock when Component.setCursor() is called while a
 *          cross-process drag-and-drop is active.
 *
 *          Root cause (JBR-10188): The AWT toolkit thread calls OleInitialize(NULL)
 *          (COM STA) and ::DoDragDrop(). During a cross-process COM call to the drop
 *          target the STA message pump dispatches only WM_OLE_* messages.
 *          An EDT call to setCursor/findHeavyweightUnderCursor uses
 *          AwtToolkit::InvokeFunction → ::SendMessage(WM_AWT_INVOKE_METHOD), which
 *          will never be dispatched by the STA pump, permanently blocking the EDT.
 *
 *          Test strategy:
 *          1. Spawn a child process that hosts a DropTarget whose dragEnter()
 *             sleeps for DRAG_ENTER_DELAY_MS — this keeps the main JVM's toolkit
 *             thread blocked inside DoDragDrop's cross-process COM call for that
 *             duration.
 *          2. Use Robot to drag from a source panel in the main JVM to the child
 *             window along an S-curve path with variable speed.
 *          3. Shortly after the drag enters the child window (COM call in flight),
 *             post a Component.setCursor() call onto the EDT.
 *          4. Assert that the EDT completes the call within EDT_RESPONSE_TIMEOUT_MS.
 *             A timeout means the EDT is frozen (deadlock detected).
 *
 * @run main/timeout=60 DragDropFreezeTest
 */

import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.dnd.*;
import java.awt.event.InputEvent;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Main test process.
 */
public class DragDropFreezeTest {

    // Child delays its dragEnter by this many ms to hold the COM call open.
    // Must be > EDT_RESPONSE_TIMEOUT_MS + scheduling slack.
    static final int DRAG_ENTER_DELAY_MS = 6000;

    // If the EDT does not complete Component.setCursor() within this time, we
    // declare a deadlock.
    static final int EDT_RESPONSE_TIMEOUT_MS = 3000;

    // Delay after mouse reaches child before posting the setCursor probe.
    // Gives the COM DragEnter call time to start before we probe.
    static final int PROBE_SCHEDULE_DELAY_MS = 400;

    private volatile Frame sourceFrame;
    private volatile Panel sourcePanel;

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "child".equals(args[0])) {
            SlowDropTargetChild.run();
            return;
        }
        new DragDropFreezeTest().runTest();
    }

    // -------------------------------------------------------------------------
    // Test driver
    // -------------------------------------------------------------------------

    void runTest() throws Exception {
        // 1. Build the drag-source UI.
        EventQueue.invokeAndWait(this::initSourceFrame);

        Robot robot = new Robot();
        robot.setAutoDelay(10);
        robot.waitForIdle();
        robot.delay(500);

        // Warm up the cursor manager (lazy singleton) so first use is not counted.
        EventQueue.invokeAndWait(() -> sourcePanel.setCursor(Cursor.getDefaultCursor()));
        robot.waitForIdle();

        // 2. Spawn the child process (slow drop target).
        Process child = spawnChild();
        Point childCenter;
        try {
            childCenter = readChildPosition(child, 10_000);
        } catch (Exception e) {
            child.destroyForcibly();
            throw new RuntimeException("Child process did not start in time", e);
        }

        robot.waitForIdle();
        robot.delay(500);

        Point srcCenter = getCenter(sourceFrame);

        // 3. Probe: post Component.setCursor() onto the EDT while the COM call is
        //    in flight.  The call chain is:
        //      Component.setCursor → updateCursorImmediately → _updateCursor →
        //      WGlobalCursorManager.findHeavyweightUnderCursor (native)  ← blocks here
        //      or WGlobalCursorManager.setCursor (native)                ← or here
        //    Both native methods use AwtToolkit::InvokeFunction →
        //    ::SendMessage(WM_AWT_INVOKE_METHOD) which the STA COM pump ignores.
        CountDownLatch edtLatch = new CountDownLatch(1);
        AtomicReference<Throwable> probeError = new AtomicReference<>();

        String failMessage = null;

        try {
            // 4. Initiate the drag.
            robot.mouseMove(srcCenter.x, srcCenter.y);
            robot.waitForIdle();
            robot.delay(300);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.delay(200);

            // 5. Move to the child window along an S-curve with variable speed.
            //    The path goes via two off-axis waypoints — one above the direct
            //    line, one below — so the Robot covers a longer distance and
            //    exercises slow, fast, and slow-approach speed phases.
            //    The drag gesture fires as soon as the threshold (≈4 px) is
            //    exceeded with the button held.
            //      Segment 1 (slow start):    src → arc1  25 steps × 50 ms ≈ 1.25 s
            //      Segment 2 (fast crossing): arc1 → arc2  40 steps × 20 ms ≈ 0.80 s
            //      Segment 3 (slow approach): arc2 → dst  30 steps × 45 ms ≈ 1.35 s
            //    Total path travel: ~3.4 s  (vs. a straight-line ~1.2 s)
            Point arc1 = new Point(srcCenter.x + (childCenter.x - srcCenter.x) / 3,
                                   srcCenter.y - 120);
            Point arc2 = new Point(srcCenter.x + 2 * (childCenter.x - srcCenter.x) / 3,
                                   srcCenter.y + 100);
            moveSegment(robot, srcCenter, arc1,        25, 50);  // slow start
            moveSegment(robot, arc1,      arc2,        40, 20);  // fast S-curve crossing
            moveSegment(robot, arc2,      childCenter, 30, 45);  // slow approach
            // Mouse is now over the child window.  OLE has called DragEnter on the
            // child's IDropTarget, which (on the child's EDT) sleeps DRAG_ENTER_DELAY_MS.
            // The main JVM's toolkit thread is therefore blocked inside DoDragDrop's
            // cross-process COM call for the next DRAG_ENTER_DELAY_MS milliseconds.

            // 6. Schedule the probe a short time after arrival so the COM call is
            //    definitely in flight.
            ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor(
                    r -> { Thread t = new Thread(r, "probe-scheduler"); t.setDaemon(true); return t; });
            sched.schedule(() ->
                EventQueue.invokeLater(() -> {
                    try {
                        // Alternate cursor to guarantee the update is not a no-op.
                        sourcePanel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                        // If we reach here, the call completed without blocking.
                        edtLatch.countDown();
                    } catch (Throwable t) {
                        probeError.set(t);
                        edtLatch.countDown();
                    }
                }),
                PROBE_SCHEDULE_DELAY_MS, TimeUnit.MILLISECONDS);
            sched.shutdown();

            // 7. Wait for the EDT to respond.
            boolean responded = edtLatch.await(EDT_RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!responded) {
                failMessage =
                    "EDT did not respond within " + EDT_RESPONSE_TIMEOUT_MS + " ms. " +
                    "Component.setCursor() is likely blocking in " +
                    "AwtToolkit::InvokeFunction → ::SendMessage(WM_AWT_INVOKE_METHOD) " +
                    "while the toolkit thread is inside DoDragDrop's COM STA blocking call " +
                    "to the cross-process drop target. " +
                    "See JBR-10188.";
            } else if (probeError.get() != null) {
                failMessage = "Unexpected exception on EDT during probe: " + probeError.get();
            }

        } finally {
            // 8. Cleanup — destroy child first so the COM call breaks and the toolkit
            //    thread (if blocked) is released before we attempt EventQueue operations.
            child.destroyForcibly();
            Thread.sleep(500);  // allow DoDragDrop to return after child exits

            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            robot.waitForIdle();

            // Dispose source frame.  Use a timeout-based approach in case the EDT is
            // still recovering; invokeAndWait could itself hang if the EDT is stuck.
            CountDownLatch disposeLatch = new CountDownLatch(1);
            EventQueue.invokeLater(() -> { sourceFrame.dispose(); disposeLatch.countDown(); });
            if (!disposeLatch.await(5, TimeUnit.SECONDS)) {
                System.err.println("WARNING: sourceFrame.dispose() did not complete in 5 s");
            }
        }

        if (failMessage != null) {
            throw new RuntimeException("TEST FAILED: " + failMessage);
        }
        System.out.println("TEST PASSED: EDT remained responsive during cross-process DnD.");
    }

    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------

    void initSourceFrame() {
        sourceFrame = new Frame("DnD Source — JBR-10188 test");
        sourcePanel = new Panel() {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(200, 150);
            }
        };
        sourcePanel.setBackground(Color.CYAN);

        DragSource.getDefaultDragSource().createDefaultDragGestureRecognizer(
                sourcePanel,
                DnDConstants.ACTION_COPY,
                e -> e.startDrag(DragSource.DefaultCopyDrop,
                                 new StringSelection("jbr-10188-test")));

        sourceFrame.add(sourcePanel);
        sourceFrame.pack();
        sourceFrame.setLocation(50, 300);
        sourceFrame.setVisible(true);
        sourceFrame.toFront();
    }

    /**
     * Moves the mouse from {@code from} to {@code to} in {@code steps} equal
     * increments, pausing {@code delayMs} milliseconds between each step.
     */
    static void moveSegment(Robot robot, Point from, Point to, int steps, int delayMs) {
        for (int i = 1; i <= steps; i++) {
            robot.mouseMove(
                from.x + (to.x - from.x) * i / steps,
                from.y + (to.y - from.y) * i / steps);
            robot.delay(delayMs);
        }
    }

    static Point getCenter(Component c) {
        Point loc = c.getLocationOnScreen();
        Dimension sz = c.getSize();
        return new Point(loc.x + sz.width / 2, loc.y + sz.height / 2);
    }

    // -------------------------------------------------------------------------
    // Child process management
    // -------------------------------------------------------------------------

    static Process spawnChild() throws IOException {
        String java = System.getProperty("java.home") +
                      File.separator + "bin" + File.separator + "java";
        String cp = System.getProperty("test.classes",
                    System.getProperty("java.class.path", "."));
        return new ProcessBuilder(java, "-cp", cp, "DragDropFreezeTest", "child")
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
    }

    /**
     * Reads "READY x y" from the child's stdout within {@code timeoutMs}.
     */
    static Point readChildPosition(Process child, long timeoutMs) throws Exception {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(child.getInputStream()));
        long deadline = System.currentTimeMillis() + timeoutMs;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("READY ")) {
                String[] parts = line.split(" ");
                return new Point(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
            }
            if (System.currentTimeMillis() > deadline) {
                break;
            }
        }
        throw new TimeoutException("Child did not send READY within " + timeoutMs + " ms");
    }
}

// =============================================================================
// Child process: slow DropTarget
// =============================================================================

/**
 * Hosts a DropTarget whose {@code dragEnter} sleeps for
 * {@link DragDropFreezeTest#DRAG_ENTER_DELAY_MS} milliseconds.
 *
 * <p>This simulates a cross-process drop target (e.g., Windows taskbar /
 * explorer.exe) that takes a long time to respond to a DragEnter COM call.
 * The main JVM's toolkit thread runs {@code ::DoDragDrop()} which makes a
 * blocking COM/RPC call to this child's {@code IDropTarget::DragEnter}.  As
 * long as the child's {@code dragEnter} has not returned, that COM call is
 * outstanding, and the main JVM's toolkit thread is waiting inside the OLE
 * STA blocking-call message pump — which dispatches only {@code WM_OLE_*}
 * messages, not {@code WM_AWT_INVOKE_METHOD}.
 */
class SlowDropTargetChild {

    static void run() throws Exception {
        Frame frame = new Frame("Slow Drop Target — JBR-10188 child");
        frame.setSize(200, 150);
        frame.setLocation(350, 300);
        frame.setBackground(Color.YELLOW);

        new DropTarget(frame, DnDConstants.ACTION_COPY, new DropTargetAdapter() {
            @Override
            public void dragEnter(DropTargetDragEvent e) {
                // This method runs on the child's AWT-EventQueue thread.
                // Sleeping here keeps the child's secondary-event-loop running
                // (WToolkit.startSecondaryEventLoop → MessageLoop), which in turn
                // keeps the native IDropTarget::DragEnter COM call outstanding.
                // The main JVM's ::DoDragDrop therefore remains blocked in its
                // cross-process COM call for the duration of this sleep.
                System.err.println("[child] dragEnter — sleeping "
                        + DragDropFreezeTest.DRAG_ENTER_DELAY_MS + " ms");
                try {
                    Thread.sleep(DragDropFreezeTest.DRAG_ENTER_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                e.acceptDrag(DnDConstants.ACTION_COPY);
                System.err.println("[child] dragEnter — done");
            }

            @Override
            public void drop(DropTargetDropEvent e) {
                e.acceptDrop(e.getDropAction());
                e.dropComplete(true);
            }
        }, true);

        frame.setVisible(true);
        Thread.sleep(300); // wait for frame to be fully realized

        Point loc = frame.getLocationOnScreen();
        Dimension sz = frame.getSize();
        // "READY cx cy" signals the parent with the window center coordinates.
        System.out.println("READY " + (loc.x + sz.width / 2) + " " + (loc.y + sz.height / 2));
        System.out.flush();

        // Stay alive until the parent destroys us.
        Thread.sleep(120_000);
    }
}
