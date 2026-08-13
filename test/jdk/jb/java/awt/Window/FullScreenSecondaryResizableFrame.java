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

import com.apple.eawt.Application;
import com.apple.eawt.FullScreenAdapter;
import com.apple.eawt.FullScreenUtilities;
import com.apple.eawt.event.FullScreenEvent;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @test
 * @summary Regression test for JBR-10155 A resizable frame shown while another frame is in native
 *          full screen must float over that space, not be moved into a new full-screen space.
 *          Regression from JDK-8139208 (JDK 22), which made CPlatformWindow.setVisible call
 *          setCanFullscreen(true) (-> NSWindowCollectionBehaviorFullScreenPrimary) before
 *          NSWindow.orderFront.
 * @key headful
 * @requires (os.family == "mac")
 * @modules java.desktop/com.apple.eawt
 *          java.desktop/com.apple.eawt.event
 * @compile MacSpacesUtil.java
 * @run main FullScreenSecondaryResizableFrame
 */

public class FullScreenSecondaryResizableFrame {
    private static final Rectangle SECONDARY_BOUNDS = new Rectangle(200, 200, 500, 350);
    private static final long SETTLE_MS = 1500;
    // Time given to the (unwanted) automatic full-screen transition of the secondary frame to happen.
    private static final long FULL_SCREEN_OBSERVATION_MS = 3000;

    private static final CompletableFuture<Boolean> ownerAtFullScreen = new CompletableFuture<>();
    private static final CompletableFuture<Boolean> secondaryShown = new CompletableFuture<>();
    private static final AtomicBoolean secondaryEnteredFullScreen = new AtomicBoolean();

    private static JFrame owner;
    private static JFrame secondary;

    public static void main(String[] args) throws Exception {
        try {
            SwingUtilities.invokeAndWait(FullScreenSecondaryResizableFrame::initOwner);
            ownerAtFullScreen.get(10, TimeUnit.SECONDS);
            Thread.sleep(SETTLE_MS); // let the full-screen transition settle

            Rectangle ownerBounds = owner.getBounds();
            Point probe = ownerProbePoint(ownerBounds);
            if (!MacSpacesUtil.isWindowVisibleAtPoint(owner, probe.x, probe.y)) {
                throw new RuntimeException("Bad test state: owner isn't visible at " + probe
                        + " before the secondary frame is shown (bounds=" + ownerBounds + ")");
            }

            SwingUtilities.invokeAndWait(FullScreenSecondaryResizableFrame::showSecondary);
            secondaryShown.get(10, TimeUnit.SECONDS);
            Thread.sleep(FULL_SCREEN_OBSERVATION_MS);

            // 1. The secondary frame must not have been put into full screen by macOS.
            if (secondaryEnteredFullScreen.get()) {
                throw new RuntimeException("Secondary frame entered full screen on its own");
            }

            // 2. ...and must have kept its requested bounds.
            Rectangle actual = secondary.getBounds();
            if (!actual.equals(SECONDARY_BOUNDS)) {
                throw new RuntimeException("Secondary frame bounds changed: expected " + SECONDARY_BOUNDS
                        + ", got " + actual);
            }

            // 3. The owner must still be on the current space, i.e. no space switch happened
            //    and the secondary frame floats above it.
            if (!MacSpacesUtil.isWindowVisibleAtPoint(owner, probe.x, probe.y)) {
                throw new RuntimeException("Full-screen owner is no longer visible at " + probe
                        + " - the secondary frame was moved to another space");
            }
            if (!MacSpacesUtil.isWindowVisible(secondary)) {
                throw new RuntimeException("Secondary frame isn't visible");
            }
        } finally {
            SwingUtilities.invokeAndWait(FullScreenSecondaryResizableFrame::disposeUI);
        }
    }

    private static void initOwner() {
        owner = new JFrame("FullScreenSecondaryResizableFrame owner");
        owner.setBounds(100, 100, 600, 400);
        FullScreenUtilities.addFullScreenListenerTo(owner, new FullScreenAdapter() {
            @Override
            public void windowEnteredFullScreen(FullScreenEvent e) {
                ownerAtFullScreen.complete(true);
            }
        });
        owner.setVisible(true);
        Application.getApplication().requestToggleFullScreen(owner);
    }

    private static void showSecondary() {
        secondary = new JFrame("FullScreenSecondaryResizableFrame secondary");
        secondary.setResizable(true); // key: resizable => setCanFullscreen(true) on show
        secondary.setBounds(SECONDARY_BOUNDS);
        secondary.addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                secondaryShown.complete(true);
            }
        });
        FullScreenUtilities.addFullScreenListenerTo(secondary, new FullScreenAdapter() {
            @Override
            public void windowEnteredFullScreen(FullScreenEvent e) {
                secondaryEnteredFullScreen.set(true);
            }
        });
        secondary.setVisible(true);
    }

    // A point on the full-screen owner that the secondary frame doesn't cover.
    private static Point ownerProbePoint(Rectangle ownerBounds) {
        int x = ownerBounds.x + ownerBounds.width * 3 / 4;
        int y = ownerBounds.y + ownerBounds.height * 3 / 4;
        if (SECONDARY_BOUNDS.contains(x, y)) {
            throw new RuntimeException("Bad test state: probe point " + x + "," + y
                    + " is covered by the secondary frame " + SECONDARY_BOUNDS
                    + " (screen too small for this test)");
        }
        return new Point(x, y);
    }

    private static void disposeUI() {
        if (secondary != null) secondary.dispose();
        if (owner != null) owner.dispose();
    }
}
