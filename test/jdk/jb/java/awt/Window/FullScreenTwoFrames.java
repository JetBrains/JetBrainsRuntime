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
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * @test
 * @summary Regression test for JBR-3706 Toggling full screen mode for two frames doesn't work on macOS
 *          if invoked without delay
 * @key headful
 * @requires (os.family == "mac")
 * @modules java.desktop/com.apple.eawt
 *          java.desktop/com.apple.eawt.event
 */

public class FullScreenTwoFrames {
    private static final CompletableFuture<Boolean> frame2Shown = new CompletableFuture<>();
    private static final CompletableFuture<Boolean> frame1AtFullScreen = new CompletableFuture<>();
    private static final CompletableFuture<Boolean> frame2AtFullScreen = new CompletableFuture<>();
    private static JFrame frame1;
    private static JFrame frame2;

    public static void main(String[] args) throws Exception {
        try {
            SwingUtilities.invokeAndWait(FullScreenTwoFrames::initUI);
            frame2Shown.get(5, TimeUnit.SECONDS);
            Application.getApplication().requestToggleFullScreen(frame1);
            Application.getApplication().requestToggleFullScreen(frame2);
            frame1AtFullScreen.get(5, TimeUnit.SECONDS);
            frame2AtFullScreen.get(5, TimeUnit.SECONDS);
        }
        finally {
            SwingUtilities.invokeAndWait(FullScreenTwoFrames::disposeUI);
        }
    }

    private static void initUI() {
        frame1 = new JFrame("FullScreenTwoFrames(1)");
        frame1.setBounds(100, 100, 200, 200);
        FullScreenUtilities.addFullScreenListenerTo(frame1, new FullScreenAdapter() {
            @Override
            public void windowEnteredFullScreen(FullScreenEvent e) {
                frame1AtFullScreen.complete(true);
            }
        });
        frame1.setVisible(true);
        frame2 = new JFrame("FullScreenTwoFrames(2)");
        frame2.setBounds(100, 400, 200, 200);
        frame2.addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                frame2Shown.complete(true);
            }
        });
        FullScreenUtilities.addFullScreenListenerTo(frame2, new FullScreenAdapter() {
            @Override
            public void windowEnteredFullScreen(FullScreenEvent e) {
                frame2AtFullScreen.complete(true);
            }
        });
        frame2.setVisible(true);
    }

    private static void disposeUI() {
        if (frame1 != null) frame1.dispose();
        if (frame2 != null) frame2.dispose();
    }
}
