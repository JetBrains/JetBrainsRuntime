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
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
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
import jdk.jfr.consumer.RecordingFile;

/*
 * @test
 * @key headful
 *
 * @summary Test verifies that there is no extra allocation after display mode switch
 *
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

        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice d = ge.getDefaultScreenDevice();

        if (d.isDisplayChangeSupported()) {
            DisplayMode[] modes = d.getDisplayModes();
            int modesCount = Math.min(modes.length, MAX_MODES);

            for (int i = 0; i < modesCount; i++) {
                DisplayMode mode = modes[i];
                try {
                    d.setDisplayMode(mode);
                } catch (IllegalArgumentException e) {
                    e.printStackTrace();
                }
                Thread.sleep(2000);
            }
        }
        f.setVisible(false);
        f.dispose();
        Thread.sleep(1000);
        Path path = Path.of("recording.jfr");
        recording.dump(path);
        recording.close();
        for (RecordedEvent event : RecordingFile.readAllEvents(path)) {
            if ("jdk.ObjectAllocationOutsideTLAB".equalsIgnoreCase(event.getEventType().getName())) {
                for (RecordedFrame recordedFrame :event.getStackTrace().getFrames()) {
                    if (recordedFrame.isJavaFrame() &&
                            "java.awt.image.DataBufferInt".equals(
                                    recordedFrame.getMethod().getType().getName()) &&
                            event.getLong("allocationSize") > th)
                    {
                        throw new RuntimeException("Extra allocation detected: " +
                                event.getLong("allocationSize"));
                    }
                }
            }
        }
    }
}
