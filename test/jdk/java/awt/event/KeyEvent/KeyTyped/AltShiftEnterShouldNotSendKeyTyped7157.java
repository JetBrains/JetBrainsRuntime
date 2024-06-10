/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
 * Copyright 2024 JetBrains s.r.o.
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
 * @summary Checks that Alt+Shift+Enter does not generate KEY_TYPED event(s) on Windows and Linux (X11) (JBR-7157)
 * @author Nikita Provotorov
 * @requires (os.family == "windows" | os.family == "linux")
 * @key headful
 * @run main/othervm AltShiftEnterShouldNotSendKeyTyped7157
 */

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class AltShiftEnterShouldNotSendKeyTyped7157 extends Frame {
    private final TextArea textArea;

    public static void main(String[] args) throws Exception {
        if ("WLToolkit".equals(Toolkit.getDefaultToolkit().getClass().getSimpleName())) {
            System.out.println("The test isn't targeting at Wayland. Skipping it since WLToolkit is being used...");
            return;
        }

        final var robot = new Robot();
        final AtomicReference<AltShiftEnterShouldNotSendKeyTyped7157> windowRef = new AtomicReference<>(null);

        try {
            SwingUtilities.invokeAndWait(() -> {
                final var window = new AltShiftEnterShouldNotSendKeyTyped7157();
                windowRef.set(window);

                window.setVisible(true);
            });

            runTest(robot, windowRef.get());
        } finally {
            final var window = windowRef.getAndSet(null);
            if (window != null) {
                window.dispose();
            }
        }
    }

    private AltShiftEnterShouldNotSendKeyTyped7157() {
        super("JBR-7157");

        textArea = new TextArea();
        add(textArea, BorderLayout.CENTER);
        setSize(400, 400);
    }


    private static void runTest(final Robot robot, final AltShiftEnterShouldNotSendKeyTyped7157 window) throws Exception {
        final CompletableFuture<Void> focusGainedFuture = new CompletableFuture<>();
        final AtomicBoolean gotFocusLostEvent = new AtomicBoolean(false);
        final Collection<KeyEvent> allReceivedKeyTypedEvents = Collections.synchronizedList(new ArrayList<>());

        // Installing listeners and requesting focus
        SwingUtilities.invokeLater(() -> {
            window.textArea.addKeyListener(new KeyAdapter() {
                @Override
                public void keyTyped(KeyEvent e) {
                    System.err.println("Got KEY_TYPED event: " + e);
                    new Throwable("Stacktrace").printStackTrace();

                    allReceivedKeyTypedEvents.add(e);
                }
            });

            window.textArea.addFocusListener(new FocusListener() {
                @Override
                public void focusGained(FocusEvent e) {
                    focusGainedFuture.complete(null);
                }

                @Override
                public void focusLost(FocusEvent e) {
                    if (gotFocusLostEvent.compareAndExchange(false, true) == false) {
                        System.err.println("Unexpectedly lost focus! " + e);
                        new Throwable("Stacktrace").printStackTrace();
                    }
                }
            });

            if (window.textArea.isFocusOwner()) {
                focusGainedFuture.complete(null);
            } else {
                window.textArea.requestFocus();
            }
        });

        // Waiting for the focus
        focusGainedFuture.get(2, TimeUnit.SECONDS);

        robot.delay(100);

        // Pressing Alt+Shift+Enter
        robot.keyPress(KeyEvent.VK_ALT);
        try {
            robot.delay(100);

            robot.keyPress(KeyEvent.VK_SHIFT);
            try {
                robot.delay(100);

                robot.keyPress(KeyEvent.VK_ENTER);
                robot.delay(100);

                robot.keyRelease(KeyEvent.VK_ENTER);
                robot.delay(50);
            } finally {
                robot.keyRelease(KeyEvent.VK_SHIFT);
                robot.delay(50);
            }
        } finally {
            robot.keyRelease(KeyEvent.VK_ALT);
            robot.delay(100);
        }

        robot.waitForIdle();

        // CAS instead of just .get() to avoid false positive messages from the focus listener
        if (gotFocusLostEvent.compareAndExchange(false, true) == true) {
            throw new RuntimeException("Focus has been unexpectedly lost");
        }

        if (!allReceivedKeyTypedEvents.isEmpty()) {
            throw new RuntimeException("Got some KEY_TYPED events: " + allReceivedKeyTypedEvents);
        }
    }
}
