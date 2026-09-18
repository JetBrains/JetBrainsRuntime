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

/**
 * @test
 * @key headful
 * @summary The apple.awt.windowSurfaceDisabled client property suppresses the
 *          window's Java2D rendering surface.
 * @requires (os.family == "mac")
 * @modules java.desktop/sun.awt
 *          java.desktop/sun.java2d
 *          java.desktop/sun.lwawt
 * @run main WindowSurfaceDisabledTest
 * @run main/othervm -Dsun.java2d.metal=false WindowSurfaceDisabledTest
 */

import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;

import sun.awt.AWTAccessor;
import sun.java2d.NullSurfaceData;
import sun.java2d.SurfaceData;
import sun.lwawt.LWWindowPeer;

public class WindowSurfaceDisabledTest {

    private static final String WINDOW_SURFACE_DISABLED = "apple.awt.windowSurfaceDisabled";

    public static void main(String[] args) throws Exception {
        testPropertyNotSet();
        testPropertySetBeforeShowing();
    }

    /**
     * Without the property the window gets its usual surface. Guards the other
     * cases against passing for the wrong reason: an empty-bounds window also
     * reports {@code NullSurfaceData}.
     */
    private static void testPropertyNotSet() throws Exception {
        JFrame frame = showFrame(null);
        try {
            check(frame, false, "property not set");
        } finally {
            dispose(frame);
        }
    }

    /** Set before the window is realized: no Java2D surface is created. */
    private static void testPropertySetBeforeShowing() throws Exception {
        JFrame frame = showFrame(Boolean.TRUE);
        try {
            check(frame, true, "property set before showing");
        } finally {
            dispose(frame);
        }
    }

    private static JFrame showFrame(Boolean surfaceDisabled) throws Exception {
        AtomicReference<JFrame> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame("WindowSurfaceDisabledTest");
            if (surfaceDisabled != null) {
                frame.getRootPane().putClientProperty(WINDOW_SURFACE_DISABLED, surfaceDisabled);
            }
            frame.setSize(300, 200);
            frame.setVisible(true);
            ref.set(frame);
        });
        return ref.get();
    }

    private static void check(JFrame frame, boolean expectDisabled, String what) {
        SurfaceData sd = surfaceDataOf(frame);
        boolean disabled = sd instanceof NullSurfaceData;
        if (disabled != expectDisabled) {
            throw new RuntimeException(what + ": expected surface "
                    + (expectDisabled ? "disabled" : "enabled")
                    + ", but the peer reports " + sd);
        }
    }

    private static SurfaceData surfaceDataOf(JFrame frame) {
        Object peer = AWTAccessor.getComponentAccessor().getPeer(frame);
        if (!(peer instanceof LWWindowPeer)) {
            throw new RuntimeException("Unexpected peer: " + peer);
        }
        return ((LWWindowPeer) peer).getSurfaceData();
    }

    private static void dispose(JFrame frame) throws Exception {
        SwingUtilities.invokeAndWait(frame::dispose);
    }
}
