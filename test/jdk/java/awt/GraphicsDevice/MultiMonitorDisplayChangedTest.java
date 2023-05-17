/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2023, JetBrains s.r.o.. All rights reserved.
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

/**
 * @test
 * @key headful
 * @requires os.family == "mac"
 * @summary check if there is no displayChanged notification on popup window
 *          appearance in multi-monitor configuration
 * @modules java.desktop/sun.awt
 * @modules java.desktop/sun.java2d
 * @run main/othervm -Dsun.java2d.metal=true MultiMonitorDisplayChangedTest
 * @run main/othervm -Dsun.java2d.opengl=true MultiMonitorDisplayChangedTest
 */

import sun.awt.DisplayChangedListener;
import sun.java2d.SunGraphicsEnvironment;

import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public class MultiMonitorDisplayChangedTest {
    public static void main(String[] args) throws InterruptedException {
        final ArrayList<JFrame> frames = new ArrayList<>();
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        AtomicReference<Boolean> displayChangedRef = new AtomicReference<>(false);

        SunGraphicsEnvironment env =
                (SunGraphicsEnvironment)SunGraphicsEnvironment.getLocalGraphicsEnvironment();
        env.addDisplayChangedListener(new DisplayChangedListener() {
            @Override
            public void displayChanged() {
                displayChangedRef.set(true);
            }

            @Override
            public void paletteChanged() {
            }
        });

        final GraphicsDevice[] devices = ge.getScreenDevices();
        final CountDownLatch initLatch = new CountDownLatch(devices.length);


        SwingUtilities.invokeLater(() -> {
            for (int i = 0; i < devices.length; i++) {
                GraphicsDevice device = devices[i];
                Rectangle bounds = device.getDefaultConfiguration().getBounds();
                JFrame frame = new JFrame("Monitor " + (i + 1));
                frame.addComponentListener(new ComponentAdapter() {
                    @Override
                    public void componentShown(ComponentEvent e) {
                        initLatch.countDown();
                    }
                });

                JPanel panel = new JPanel();
                panel.setPreferredSize(new Dimension(300, 200));
                frame.add(panel);
                frame.setBounds(bounds);
                frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
                frame.pack();
                frames.add(frame);
                frame.setVisible(true);
            }
        });

        initLatch.await();

        CountDownLatch popupLatch = new CountDownLatch(frames.size());

        SwingUtilities.invokeLater(() -> {
            for (int i = 0; i < frames.size(); i++) {
                final JFrame frame = frames.get(i);
                JPopupMenu popupMenu = new JPopupMenu();
                popupMenu.addPopupMenuListener(new PopupMenuListener() {
                    @Override
                    public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                        popupLatch.countDown();
                    }
                    @Override
                    public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                    }
                    @Override
                    public void popupMenuCanceled(PopupMenuEvent e) {
                    }
                });
                popupMenu.add(new JMenuItem("Popup Menu Item" + (i + 1)));
                popupMenu.show(frame, 50, 50);
            }
        });

        popupLatch.await();
        SwingUtilities.invokeLater(() -> frames.forEach(Window::dispose));
        if (displayChangedRef.get()) {
            throw new RuntimeException("displayChanged() has been called");
        }
    }
}