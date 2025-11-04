/*
 * Copyright 2000-2025 JetBrains s.r.o.
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

import javax.swing.*;
import java.awt.*;
import java.awt.event.AWTEventListener;
import java.awt.event.WindowEvent;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/* @test
   @summary Check if threads aren't spawned indefinitely after creating a window
   @key headful
   @run main BugJBR9563
*/
public class BugJBR9563 {
    public static void main(String[] args) throws Exception {
        CountDownLatch windowOpened = new CountDownLatch(1);
        AtomicReference<JFrame> frameRef = new AtomicReference<>();

        AWTEventListener listener = e -> {
            if (e.getID() == WindowEvent.WINDOW_OPENED) {
                Window w = ((WindowEvent) e).getWindow();
                if (w instanceof JFrame f && "Test".equals(f.getTitle())) {
                    frameRef.set(f);
                    windowOpened.countDown();
                }
            }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.WINDOW_EVENT_MASK);

        try {
            SwingUtilities.invokeLater(() -> {
                JFrame f = new JFrame("Test");
                f.add(new JTextField(30));
                f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE); // unchanged
                f.pack();
                f.setLocationRelativeTo(null);
                f.setVisible(true);
            });

            Assert(windowOpened.await(5, TimeUnit.SECONDS), "Window did not open in time");

            EventQueue.invokeAndWait(() -> { /* no-op */ });

            Robot robot = new Robot();
            robot.setAutoWaitForIdle(true);
            robot.waitForIdle(); // waits for paints/repaints to finish

            Toolkit.getDefaultToolkit().sync();

            JFrame f = frameRef.get();
            Assert(f != null, "Frame is not captured");


            Set<String> threadsAfterRendering = getCurrentThreads();
            System.out.println("Threads after rendering: " + threadsAfterRendering);

            Callable<Boolean> task = () -> {
                System.out.println("Check threads state...");
                Set<String> threads = getCurrentThreads();
                List<String> newThreads = threads.stream().filter(it -> !threadsAfterRendering.contains(it)).toList();

                System.out.println("New threads list: " + newThreads);
                return newThreads.isEmpty();
            };

            List<Boolean> results = Collections.synchronizedList(new ArrayList<>());

            for (int i = 0; i < 10; i++) {
                System.out.println("Check threads state...");
                Set<String> threads = getCurrentThreads();
                List<String> newThreads = threads.stream().filter(it -> !threadsAfterRendering.contains(it)).toList();

                System.out.println("New threads list: " + newThreads);

                results.add(newThreads.isEmpty());
                Thread.sleep(1000);
            }

            boolean testResult = results.stream().reduce(true, (a, b) -> a && b);
            if (testResult) {
                System.out.println("Test passed");
            } else {
                System.out.println("Test failed");
                throw new RuntimeException("Test failed");
            }
        } finally {
            Toolkit.getDefaultToolkit().removeAWTEventListener(listener);
            JFrame f = frameRef.get();
            if (f != null) {
                EventQueue.invokeAndWait(f::dispose); // Dispose on EDT
            }
        }
    }

    private static Set<String> getCurrentThreads() {
        Map<Thread, StackTraceElement[]> allThreads = Thread.getAllStackTraces();

        return allThreads
                .keySet()
                .stream()
                .map(t -> t.threadId() + " " + t.getName())
                .collect(Collectors.toSet());
    }

    private static void Assert(boolean condition, String str) {
        if (!condition) {
            System.err.println("    ASSERT FAILED: " + str);
            throw new RuntimeException("Assert Failed: " + str);
        }
    }
}