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

import sun.awt.AWTAccessor;
import javax.swing.*;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.ColorModel;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/*
 * @test
 * bug       JRE-210
 * @summary  JEditorPane's font metrics to honour switching to different GC scale
 * @author   anton.tarasov
 * @requires (os.family == "windows")
 * @run      main JEditorPaneGCSwitchTest
 */
public class JEditorPaneGCSwitchTest {
    static JEditorPane editorPane;
    static JFrame frame;
    volatile static CountDownLatch latch;
    final static Map<Float, Dimension> scale2size = new HashMap<>(2);

    static void initGUI() {
        editorPane = new JEditorPane() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
            }
        };
        try {
            // This HTML text has different bounds when put into GC's with different scales
            editorPane.setPage(JEditorPaneGCSwitchTest.class.getResource("JEditorPaneGCSwitchTest.html"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        editorPane.addPropertyChangeListener("page", (e) -> {
            if (frame != null) frame.dispose();
            frame = new JFrame("frame");
            frame.add(editorPane);
            frame.pack();
            frame.setVisible(true);
            latch.countDown();
        });
    }

    static void testSize(final float scale) {
        // Emulate showing on a device with the provided scale
        AWTAccessor.getComponentAccessor().setGraphicsConfiguration(editorPane, new MyGraphicsConfiguration(scale));

        EventQueue.invokeLater(() -> {
            Dimension d = editorPane.getPreferredSize();
            System.out.println(scale + " : " + d);
            scale2size.put(scale, d);
            latch.countDown();
        });
    }

    static void runSync(Runnable r) {
        try {
            latch = new CountDownLatch(1);
            SwingUtilities.invokeLater(() -> r.run());
            latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {
        /*
         * 1. Recreate the editor b/w device switch
         */
        runSync(() -> initGUI());

        runSync(() -> testSize(1f));

        runSync(() -> initGUI());

        runSync(() -> testSize(2f));

        if (scale2size.get(1f).equals(scale2size.get(2f))) {
            throw new RuntimeException("Test FAILED: [1] expected different editor size per scale!");
        }

        /*
         * 2. Keep the editor shown b/w device switch
         */
        scale2size.clear();

        runSync(() -> initGUI());

        runSync(() -> testSize(1f));

        runSync(() -> testSize(2f));

        frame.dispose();

        if (scale2size.get(1f).equals(scale2size.get(2f))) {
            throw new RuntimeException("Test FAILED: [2] expected different editor size per scale!");
        }
        System.out.println("Test PASSED");
    }

    static class MyGraphicsConfiguration extends GraphicsConfiguration {
        GraphicsConfiguration delegate;
        float scale;

        public MyGraphicsConfiguration(float scale) {
            this.delegate = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
            this.scale = scale;
        }

        @Override
        public GraphicsDevice getDevice() {
            return delegate.getDevice();
        }

        @Override
        public ColorModel getColorModel() {
            return delegate.getColorModel();
        }

        @Override
        public ColorModel getColorModel(int transparency) {
            return delegate.getColorModel(transparency);
        }

        @Override
        public AffineTransform getDefaultTransform() {
            return AffineTransform.getScaleInstance(scale, scale);
        }

        @Override
        public AffineTransform getNormalizingTransform() {
            return delegate.getNormalizingTransform();
        }

        @Override
        public Rectangle getBounds() {
            return delegate.getBounds();
        }
    }
}
