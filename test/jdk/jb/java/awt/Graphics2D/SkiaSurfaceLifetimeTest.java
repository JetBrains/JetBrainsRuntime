/*
 * Copyright 2026 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.image.VolatileImage;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

/**
 * @test
 * @summary A disposed window and a VolatileImage dropped without flush() are collected on the Skia pipe, as on
 *          Metal, and so give their textures back. Each Skia surface kept its Metal delegate in a static map until the
 *          Skia surface's own Disposer record ran (so that the Metal record could not free the native ops while Skia
 *          ops on them were queued), but the delegate reaches the Skia surface back — through the VolatileImage it
 *          was made for, or its layer's peer and window — so neither was ever collected: every dropped image's
 *          texture stayed (40 dropped 850 x 850 images, 190 MB; RenderPerfTest's new image per test, 250 MB over its
 *          suite), and every closed window with its component tree. Frames shown, painted and disposed, and images
 *          drawn and dropped, must no longer be reachable after collections, and the pipe must keep drawing.
 * @key headful
 * @requires (os.family == "mac")
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true SkiaSurfaceLifetimeTest
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.metal=true SkiaSurfaceLifetimeTest
 */
public class SkiaSurfaceLifetimeTest {
    static final int ROUNDS = 5;

    public static void main(String[] args) throws Exception {
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        List<WeakReference<Object>> frames = new ArrayList<>(), images = new ArrayList<>();
        for (int i = 0; i < ROUNDS; i++) {
            JFrame[] shown = new JFrame[1];
            SwingUtilities.invokeAndWait(() -> {
                JFrame frame = new JFrame("SkiaSurfaceLifetimeTest " + System.nanoTime());
                JLabel label = new JLabel("a window to be disposed");
                label.setPreferredSize(new Dimension(400, 300));
                frame.add(label);
                frame.pack();
                frame.setVisible(true);
                shown[0] = frame;
            });
            Thread.sleep(400);
            SwingUtilities.invokeAndWait(() -> shown[0].dispose());
            frames.add(new WeakReference<>(shown[0]));
            shown[0] = null;
            images.add(new WeakReference<>(drawn(gc, 256)));
        }
        long framesLeft = 0, imagesLeft = 0;
        for (int i = 0; i < 40; i++) {
            System.gc();
            Thread.sleep(100);
            framesLeft = frames.stream().filter(r -> r.get() != null).count();
            imagesLeft = images.stream().filter(r -> r.get() != null).count();
            if (framesLeft == 0 && imagesLeft == 0) break;
        }
        // the pipe draws on after the disposals: a new image gets its pixels
        VolatileImage after = drawn(gc, 64);
        int pixel = after.getSnapshot().getRGB(10, 10);
        System.out.printf("%d of %d disposed frames and %d of %d dropped VolatileImages still reachable; a new image's pixel %08x%n",
                framesLeft, ROUNDS, imagesLeft, ROUNDS, pixel);
        StringBuilder failures = new StringBuilder();
        if (framesLeft > 0) failures.append(framesLeft).append(" of ").append(ROUNDS).append(" disposed frames were never collected; ");
        if (imagesLeft > 0) failures.append(imagesLeft).append(" of ").append(ROUNDS).append(" dropped VolatileImages were never collected; ");
        if (pixel != 0xff2060a0) failures.append(String.format("a new image after the disposals drew %08x, not ff2060a0; ", pixel));
        if (failures.length() > 0) throw new RuntimeException(failures.toString());
    }

    static VolatileImage drawn(GraphicsConfiguration gc, int size) {
        VolatileImage image = gc.createCompatibleVolatileImage(size, size, Transparency.TRANSLUCENT);
        do {
            image.validate(gc);
            Graphics2D g = image.createGraphics();
            g.setColor(new Color(0x2060a0));
            g.fillRect(0, 0, size, size);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.ORANGE);
            g.fillOval(size / 4, size / 4, size / 2, size / 2);
            g.dispose();
            image.getSnapshot();
        } while (image.contentsLost());
        return image;
    }
}
