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
import com.apple.eawt.FullScreenAdapter;
import com.apple.eawt.FullScreenUtilities;
import com.apple.eawt.event.FullScreenEvent;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @test
 * @summary Regression test for JBR-3633 Modal dialog is shown not at the same space as its parent
 * @key headful
 * @requires (os.family == "mac")
 * @modules java.desktop/com.apple.eawt
 *          java.desktop/com.apple.eawt.event
 * @compile MacSpacesUtil.java
 * @run main FullScreenInactiveDialog
 */

public class FullScreenInactiveDialog {
    private static final CompletableFuture<Boolean> shownAtFullScreen = new CompletableFuture<>();

    private static JFrame frame;
    private static JDialog dialog;

    public static void main(String[] args) throws Exception {
        try {
            SwingUtilities.invokeAndWait(FullScreenInactiveDialog::initUI);
            shownAtFullScreen.get(5, TimeUnit.SECONDS);
            MacSpacesUtil.switchToPreviousSpace();
            SwingUtilities.invokeLater(FullScreenInactiveDialog::openDialog);
            Thread.sleep(1000);
            if (MacSpacesUtil.isWindowVisible(dialog)) {
                throw new RuntimeException("Dialog is showing earlier than expected");
            }
            Desktop.getDesktop().requestForeground(false); // simulates clicking on app icon in dock or switch using Cmd+Tab
            Thread.sleep(1000); // wait for animation to finish
            if (!MacSpacesUtil.isWindowVisible(dialog)) {
                throw new RuntimeException("Dialog isn't showing when expected");
            }
            if (!MacSpacesUtil.isWindowVisible(frame)) {
                throw new RuntimeException("Frame isn't showing when expected");
            }
        } finally {
            SwingUtilities.invokeAndWait(FullScreenInactiveDialog::disposeUI);
        }
    }

    private static void initUI() {
        frame = new JFrame("FullScreenInactiveDialog");
        frame.setSize(300, 100);
        frame.setVisible(true);


        FullScreenUtilities.addFullScreenListenerTo(frame, new FullScreenAdapter() {
            @Override
            public void windowEnteredFullScreen(FullScreenEvent e) {
                shownAtFullScreen.complete(true);
            }
        });
        Application.getApplication().requestToggleFullScreen(frame);
    }

    private static void openDialog() {
        dialog = new JDialog(frame);
        dialog.setSize(100, 100);
        dialog.setVisible(true);
    }

    private static void disposeUI() {
        if (frame != null) frame.dispose();
    }
}
