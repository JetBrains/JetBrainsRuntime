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

/*
 * @test
 * @summary JBR-3413 use timeout in CAccessibility.invokeAndWait
 * @author anton.tarasov@jetbrains.com
 * @compile --add-exports java.desktop/com.apple.concurrent=ALL-UNNAMED DeadlockTest.java
 * @run main/othervm/manual --add-exports java.desktop/com.apple.concurrent=ALL-UNNAMED DeadlockTest
 */

import javax.swing.*;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.apple.concurrent.Dispatch;

public class DeadlockTest {
    static final int COUNTER_THRESHOLD = 2;
    static final int WAIT_SECONDS = 10;

    static final AtomicInteger COUNTER_1 = new AtomicInteger(0);
    static final AtomicInteger COUNTER_2 = new AtomicInteger(0);

    static final CountDownLatch START_LATCH = new CountDownLatch(1);
    static final CountDownLatch TEST_LATCH = new CountDownLatch(1);
    static final JFrame FRAME = new JFrame("frame");

    public static void main(String[] args) throws InterruptedException {
        // System.setProperty("sun.lwawt.macosx.CAccessibility.invokeTimeoutSeconds", "1");

        EventQueue.invokeLater(DeadlockTest::showInstructions);
        START_LATCH.await();

        try {
            if (!TEST_LATCH.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new RuntimeException("Test FAILED!");
            }
            System.out.println("Test PASSED.");
        } finally {
            FRAME.dispose();
        }
    }

    static void showInstructions() {
        JFrame frame = new JFrame("Test Instructions");
        frame.setLayout(new BorderLayout());
        JLabel label = new JLabel("Start VoiceOver (Cmd + F5) and press \"Test\".");
        label.setBorder(BorderFactory.createEmptyBorder(20, 10, 20, 10));
        frame.add(label, BorderLayout.NORTH);
        JPanel panel = new JPanel();
        JButton testButton = new JButton("Test");
        panel.add(testButton);
        testButton.addActionListener(e -> {
            Timer t = new Timer(500, ev -> EventQueue.invokeLater(DeadlockTest::test));
            t.setRepeats(false);
            t.start();
            frame.dispose();
        });
        frame.add(panel, BorderLayout.SOUTH);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    /**
     * Transfers focus b/w two buttons and performs blocking execution on every focus gain.
     */
    static void test() {
        START_LATCH.countDown();

        JButton button1 = new JButton("button1");
        JButton button2 = new JButton("button2");

        Supplier<Boolean> condition = () -> {
            if (COUNTER_1.get() >= COUNTER_THRESHOLD &&
                COUNTER_2.get() >= COUNTER_THRESHOLD)
            {
                TEST_LATCH.countDown();
                return true;
            }
            return false;
        };

        button1.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                Dispatch.getInstance().getBlockingMainQueueExecutor().
                        execute(() -> System.out.println("button1: " + COUNTER_1.incrementAndGet() + ": " + Thread.currentThread().getName()));

                if (!condition.get()) EventQueue.invokeLater(button2::requestFocusInWindow);
            }
        });

        button2.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                Dispatch.getInstance().getBlockingMainQueueExecutor().
                        execute(() -> System.out.println("button2: " + COUNTER_2.incrementAndGet() + ": " + Thread.currentThread().getName()));

                if (!condition.get()) EventQueue.invokeLater(button1::requestFocusInWindow);
            }
        });

        FRAME.setLayout(new FlowLayout());
        FRAME.add(button1);
        FRAME.add(button2);
        FRAME.setLocationRelativeTo(null);
        FRAME.pack();
        FRAME.setVisible(true);
    }
}