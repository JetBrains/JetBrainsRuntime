/*
 * Copyright 2021 JetBrains s.r.o.
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

import com.apple.eawt.Application;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @test
 * @summary Regression test for JBR-3666 Child window stays on default space
 *          when full-screen mode is activated for parent window on macOS
 * @key headful
 * @requires (os.family == "mac")
 * @modules java.desktop/com.apple.eawt
 */

public class FullScreenChildWindowShownBefore {
    private static final CompletableFuture<Boolean> dialogShown = new CompletableFuture<>();

    private static Robot robot;
    private static JFrame frame;
    private static JDialog dialog;

    public static void main(String[] args) throws Exception {
        robot = new Robot();
        try {
            SwingUtilities.invokeAndWait(FullScreenChildWindowShownBefore::initUI);
            dialogShown.get(5, TimeUnit.SECONDS);
            SwingUtilities.invokeAndWait(() -> Application.getApplication().requestToggleFullScreen(frame));
            robot.delay(1000); // wait for transition to full screen to finish
            ensureVisible(frame);
            ensureVisible(dialog);
        } finally {
            SwingUtilities.invokeAndWait(FullScreenChildWindowShownBefore::disposeUI);
        }
    }

    private static void initUI() {
        frame = new JFrame("FullScreenChildWindowShownBefore");
        frame.setSize(100, 100);
        frame.setLocation(100, 100);
        frame.setVisible(true);

        dialog = new JDialog(frame, false);
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                dialogShown.complete(true);
            }
        });
        dialog.setSize(100, 100);
        dialog.setLocation(100, 300);
        dialog.setVisible(true);
    }

    private static void disposeUI() {
        if (frame != null) frame.dispose();
    }

    private static void ensureVisible(Window window) throws Exception {
        AtomicReference<Point> location = new AtomicReference<>();
        AtomicBoolean movementDetected = new AtomicBoolean();
        SwingUtilities.invokeAndWait(() -> {
            if (window.isVisible()) {
                Rectangle bounds = window.getBounds();
                Insets insets = window.getInsets();
                bounds.x += insets.left;
                bounds.y += insets.top;
                bounds.width -= insets.left + insets.right;
                bounds.height -= insets.top + insets.bottom;
                if (!bounds.isEmpty()) {
                    location.set(new Point((int) bounds.getCenterX(), (int) bounds.getCenterY()));
                    window.addMouseMotionListener(new MouseMotionAdapter() {
                        @Override
                        public void mouseMoved(MouseEvent e) {
                            movementDetected.set(true);
                        }
                    });
                }
            }
        });
        Point target = location.get();
        if (target != null) {
            robot.mouseMove(target.x, target.y);
            robot.delay(100);
            robot.mouseMove(target.x + 1, target.y + 1);
            robot.delay(1000);
            if (movementDetected.get()) {
                return;
            }
        }
        throw new RuntimeException(window + " isn't visible");
    }
}
