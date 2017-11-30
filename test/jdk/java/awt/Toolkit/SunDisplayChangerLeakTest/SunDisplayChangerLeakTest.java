/*
 * Copyright 2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
