/*
 * Copyright (c) 2026, JetBrains s.r.o.. All rights reserved.
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

import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Frame;
import java.awt.Robot;
import java.util.concurrent.atomic.AtomicReference;

/*
 * @test
 * @summary Verifies that entering and exiting full screen does not
 *          cause exceptions on the EDT
 * @key headful
 * @run main WLFullscreen
 */
public class WLFullscreen {
    public static void main(String[] args)  throws Exception {
        AtomicReference<Throwable> edtFailure = new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            if (SwingUtilities.isEventDispatchThread()) {
                edtFailure.compareAndSet(null, e);
                e.printStackTrace();
            }
        });
        Robot r = new Robot();
        Frame window = new Frame();
        try {
            window.setSize(500, 500);
            window.setTitle("WLFullscreen test");
            window.setBackground(Color.RED);
            window.setVisible(true);

            r.waitForIdle();
            r.delay(500);

            var gd = window.getGraphicsConfiguration().getDevice();
            if (!gd.isFullScreenSupported()) {
                System.out.println("Full screen mode not supported on this device: " + gd + ", skipping the test");
            } else {
                gd.setFullScreenWindow(window);

                r.waitForIdle();
                r.delay(500);
            }
        } finally {
            window.dispose();
        }

        if (edtFailure.get() != null) {
            throw new RuntimeException("Exception on EDT", edtFailure.get());
        }
    }
}
