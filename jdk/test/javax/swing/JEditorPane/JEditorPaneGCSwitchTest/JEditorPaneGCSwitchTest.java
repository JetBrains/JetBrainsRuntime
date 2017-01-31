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
 * bug     JRE-210
 * @summary JEditorPane's font metrics to honour switching to different GC scale
 * @author  anton.tarasov
 * @run     main JEditorPaneGCSwitchTest
 */
public class JEditorPaneGCSwitchTest extends JPanel {
    volatile static CountDownLatch latch;
    final static Map<Float, Dimension> scale2size = new HashMap<>(2);

    static void test(final float scale) {
        JEditorPane editorPane = new JEditorPane();
        try {
            // This HTML text has different bounds when put into GC's with different scales
            editorPane.setPage(JEditorPaneGCSwitchTest.class.getResource("JEditorPaneGCSwitchTest.html"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        editorPane.addPropertyChangeListener("page", (e) -> getSize(editorPane, scale));
    }

    static void getSize(JEditorPane editorPane, float scale) {
        // Emulate showing on a device with the provided scale
        AWTAccessor.getComponentAccessor().setGraphicsConfiguration(editorPane, new MyGraphicsConfiguration(scale));
        // The size should take into account the font metrics
        Dimension d = editorPane.getPreferredSize();
        System.out.println(scale + " : " + d);
        scale2size.put(scale, d);
        latch.countDown();
    }

    public static void main(String[] args) throws InterruptedException {
        /*
         * SCALE 1.0
         */
        latch = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> test(1f));
        latch.await();

        /*
         * SCALE 2.0
         */
        latch = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> test(2f));
        latch.await();

        if (scale2size.get(1f).equals(scale2size.get(2f))) {
            throw new RuntimeException("Test FAILED: the editor size should differ!");
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
