/*
 * Copyright 2017-2023 JetBrains s.r.o.
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
import sun.awt.DisplayChangedListener;
import sun.awt.image.BufferedImageGraphicsConfig;
import sun.awt.image.SunVolatileImage;
import sun.java2d.SunGraphicsEnvironment;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.util.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/*
  https://youtrack.jetbrains.com/issue/JRE-577
  @test
  @summary SunDisplayChanger should not prevent listeners from gc'ing
  @author anton.tarasov
  @run main/othervm -Xmx64m -Dswing.bufferPerWindow=true SunDisplayChangerLeakTest
*/
public class SunDisplayChangerLeakTest {
    static int frameCountDown = 20;
    static final Map<Image, Void> STRONG_MAP = new HashMap<>();
    static final GraphicsConfiguration BI_GC = BufferedImageGraphicsConfig.getConfig(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB));

    static volatile boolean passed;

    /**
     * Shows a frame, then refs its back buffer.
     */
    static void showFrame(CountDownLatch latch) {
        JFrame frame = new JFrame("frame") {
            @Override
            public VolatileImage createVolatileImage(int w, int h) {
                // Back the frame buffer by BufferedImage so that it's allocated in RAM
                VolatileImage img = new SunVolatileImage(BI_GC, w, h, Transparency.TRANSLUCENT, new ImageCapabilities(false));
                STRONG_MAP.put(img, null);

                EventQueue.invokeLater(() -> {
                    dispose(); // release the frame buffer
                    latch.countDown();
                });
                return img;
            }
        };
        frame.setSize(500, 500);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    public static void main(String[] args) throws InterruptedException {
        while(--frameCountDown >= 0) {
            CountDownLatch latch = new CountDownLatch(1);
            EventQueue.invokeLater(() -> showFrame(latch));
            latch.await();
        }

        SunGraphicsEnvironment env = (SunGraphicsEnvironment)SunGraphicsEnvironment.getLocalGraphicsEnvironment();
        DisplayChangedListener strongRef;
        env.addDisplayChangedListener(strongRef = new DisplayChangedListener() {
            @Override
            public void displayChanged() {
                // Now we're in the process of iterating over a local copy of the DisplayChangedListener's internal map.
                // Let's force OOME and make sure the local copy doesn't prevent the listeners from gc'ing.

                int strongSize = STRONG_MAP.size();
                System.out.println("strong size: " + strongSize);

                // Release the images
                Map<Image, Void> weakMap = new WeakHashMap<>(STRONG_MAP);
                STRONG_MAP.clear();

                List<Object> garbage = new ArrayList<>();
                try {
                    while (true) {
                        garbage.add(new int[1000000]);
                    }
                } catch (OutOfMemoryError e) {
                    garbage.clear();
                    System.out.println("OutOfMemoryError");
                }
                int weakSize = weakMap.size();
                System.out.println("weak size: " + weakSize);

                passed = weakSize < strongSize;
            }

            @Override
            public void paletteChanged() {
                System.out.println(new int[1000]);
            }
        });
        assert strongRef != null; // make it "used" to please javac

        // call the above listener
        env.displayChanged();

        if (!passed) throw new RuntimeException("Test FAILED");

        System.out.println("Test PASSED");
    }
}
