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

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * @test
 * @summary Regression test for JBR-3726 Modal windows 'disappear' on minimize in KDE
 * @key headful
 * @requires os.family == "linux"
 * @modules java.desktop/java.awt:open
 *          java.desktop/sun.awt
 *          java.desktop/sun.awt.X11:open
 */

public class ModalDialogMinimizeOnKDE {
    private static final CompletableFuture<Boolean> dialogShown = new CompletableFuture<>();
    private static JFrame frame;
    private static JDialog dialog;

    public static void main(String[] args) throws Exception {
        if (!isKDE()) {
            System.out.println("This test is only valid for KDE");
            return;
        }
        Robot robot = new Robot();
        try {
            SwingUtilities.invokeLater(ModalDialogMinimizeOnKDE::initUI);
            dialogShown.get(5, TimeUnit.SECONDS);
            robot.delay(1000);
            minimize(dialog);
            robot.delay(1000);
            if (frame.getState() != Frame.ICONIFIED) {
                throw new RuntimeException("Parent frame isn't minimized");
            }
        } finally {
            SwingUtilities.invokeAndWait(ModalDialogMinimizeOnKDE::disposeUI);
        }
    }

    private static void initUI() {
        frame = new JFrame("ModalDialogMinimizeOnKDE");
        frame.setSize(300, 200);
        frame.setVisible(true);
        dialog = new JDialog(frame, true);
        dialog.setSize(200, 100);
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                dialogShown.complete(true);
            }
        });
        dialog.setVisible(true);
    }

    private static void disposeUI() {
        if (frame != null) frame.dispose();
    }

    private static boolean isKDE() throws Exception {
        Class<?> xwmCls = Class.forName("sun.awt.X11.XWM");

        Field kdeField = xwmCls.getDeclaredField("KDE2_WM");
        kdeField.setAccessible(true);

        Method wmidMethod = xwmCls.getDeclaredMethod("getWMID");
        wmidMethod.setAccessible(true);

        return kdeField.get(null).equals(wmidMethod.invoke(null));
    }

    // We need to simulate user minimizing the dialog using button in title bar.
    // As we don't know the location of the button (and it can depend on KDE version),
    // we'll just use Xlib to minimize the window.
    private static void minimize(JDialog dialog) throws Exception {
        Class<?> toolkitCls = Class.forName("sun.awt.SunToolkit");
        Method lockMethod = toolkitCls.getDeclaredMethod("awtLock");
        Method unlockMethod = toolkitCls.getDeclaredMethod("awtUnlock");

        Class<?> xToolkitClass = Class.forName("sun.awt.X11.XToolkit");
        Method displayMethod = xToolkitClass.getDeclaredMethod("getDisplay");

        Class<?> wrapperClass = Class.forName("sun.awt.X11.XlibWrapper");
        Method iconifyMethod = wrapperClass.getDeclaredMethod("XIconifyWindow",
                long.class, long.class, long.class);
        iconifyMethod.setAccessible(true);

        Class<?> windowClass = Class.forName("sun.awt.X11.XBaseWindow");
        Method windowMethod = windowClass.getDeclaredMethod("getWindow");
        Method screenMethod = windowClass.getDeclaredMethod("getScreenNumber");
        screenMethod.setAccessible(true);

        Field peerField = Component.class.getDeclaredField("peer");
        peerField.setAccessible(true);
        Object peer = peerField.get(dialog);

        lockMethod.invoke(null);
        try {
            iconifyMethod.invoke(null,
                    displayMethod.invoke(null),
                    windowMethod.invoke(peer),
                    screenMethod.invoke(peer));
        } finally {
            unlockMethod.invoke(null);
        }
    }
}
