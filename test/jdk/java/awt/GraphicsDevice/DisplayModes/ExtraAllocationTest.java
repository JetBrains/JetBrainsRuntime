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

import java.awt.Dimension;
import java.awt.DisplayMode;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.image.VolatileImage;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import jdk.jfr.Recording;
import java.io.IOException;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;
import jtreg.SkippedException;

/*
 * @test
 * @key headful
 *
 * @summary Test verifies that there is no extra allocation after display mode switch
 *
 * @library /test/lib
 * @build jtreg.SkippedException
 * @run main/othervm -Xmx750M ExtraAllocationTest
 */

public class ExtraAllocationTest {
    private static final int MAX_MODES = 10;
    private static final int W = 500;
    private static final int H = 500;
    static JFrame f = null;
    static long th = ((long) W * H * 32) / (8 * 2);
    public static void main(String[] args) throws InterruptedException, InvocationTargetException,
            IOException
    {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice d = ge.getDefaultScreenDevice();
        GraphicsConfiguration gc = d.getDefaultConfiguration();

        if (!d.isDisplayChangeSupported()) {
            throw new SkippedException("Display mode change is not supported by " + d.getIDstring());
        }
        if (!isVolatileImageAccelerated(gc)) {
            // Without an accelerated volatile image the software backup surface is the
            // regular painting path, so a window-sized DataBufferInt is allocated even
            // when nothing extra is allocated, and the check below cannot tell them apart.
            throw new SkippedException("Volatile images are not accelerated on " + gc);
        }

        Recording recording = new Recording();
        recording.enable("jdk.ObjectAllocationOutsideTLAB");
        recording.start();
        SwingUtilities.invokeAndWait(() -> {
            f = new JFrame();
            f.add(new JPanel());
            f.setPreferredSize(new Dimension(W, H));
            f.pack();
            f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            f.setVisible(true);
        });

        final DisplayMode originalDisplayMode = d.getDisplayMode();

        DisplayMode[] modes = d.getDisplayModes();
        int modesCount = Math.min(modes.length, MAX_MODES);

        for (int i = 0; i < modesCount; i++) {
            DisplayMode mode = modes[i];
            try {
                d.setDisplayMode(mode);
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
            } finally {
                d.setDisplayMode(originalDisplayMode);
            }
            Thread.sleep(2000);
        }
        f.setVisible(false);
        f.dispose();
        Thread.sleep(1000);
        Path path = Path.of("recording.jfr");
        recording.dump(path);
        recording.close();

        for (RecordedEvent event : RecordingFile.readAllEvents(path)) {
            if ("jdk.ObjectAllocationOutsideTLAB".equalsIgnoreCase(event.getEventType().getName())) {
                RecordedStackTrace stackTrace = event.getStackTrace();
                if (stackTrace == null) {
                    continue;
                }
                for (RecordedFrame recordedFrame : stackTrace.getFrames()) {
                    if (recordedFrame.isJavaFrame() &&
                            "java.awt.image.DataBufferInt".equals(
                                    recordedFrame.getMethod().getType().getName()) &&
                            event.getLong("allocationSize") > th)
                    {
                        System.err.println(event);
                        throw new RuntimeException("Extra allocation detected: " +
                                event.getLong("allocationSize"));
                    }
                }
            }
        }
    }

    /**
     * Tells whether volatile images of this configuration are backed by an accelerated
     * surface. If they are not, every volatile image falls back to a software backup
     * surface and the allocation this test looks for is a normal one.
     */
    private static boolean isVolatileImageAccelerated(GraphicsConfiguration gc) {
        VolatileImage vi = gc.createCompatibleVolatileImage(W, H);
        try {
            vi.validate(gc);
            return vi.getCapabilities().isAccelerated();
        } finally {
            vi.flush();
        }
    }
}
