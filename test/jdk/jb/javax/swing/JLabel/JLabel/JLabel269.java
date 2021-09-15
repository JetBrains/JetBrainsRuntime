/*
 * Copyright 2000-2017 JetBrains s.r.o.
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

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import javax.swing.BoxLayout;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.plaf.FontUIResource;

/* @test
 * @summary regression test on JRE-269
 * @run main/othervm -Djavax.swing.rebaseCssSizeMap=true JLabel269 -verbose
 */
public class JLabel269 {

    private static boolean verbose = false;

    static class JLabelTest extends JLabel {
        JLabelTest(String label) {
            super(label);
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(Color.BLACK);
            super.paintComponent(g);
        }
    }

    public static void main(String[] args) {

        String labelText = "<html><body><code>A</code></body></html>";

        verbose = Arrays.asList(args).contains("-verbose");

        JFrame mainFrame = new JFrame();

        JPanel container = new JPanel();
        container.setLayout(new BoxLayout(container, BoxLayout.X_AXIS));

        JPanel p1 = new JPanel();
        JLabel l1 = new JLabelTest(labelText);
        l1.setFont(new FontUIResource("Tahoma", Font.PLAIN, 36));
        p1.add(l1);
        container.add(p1);

        JPanel p2 = new JPanel();
        JLabel l2 = new JLabelTest(labelText);
        l2.setFont(new FontUIResource("Tahoma", Font.PLAIN, 72));
        p2.add(l2);
        container.add(p2);

        mainFrame.add(container);
        mainFrame.pack();
        mainFrame.setVisible(true);

        BufferedImage bi = new BufferedImage(
                l1.getWidth(), l1.getHeight(), BufferedImage.TYPE_INT_ARGB);
        l1.paint(bi.getGraphics());
        int height1 = maxCharHeight(bi, l1.getWidth(), l1.getHeight());

        bi = new BufferedImage(
                l2.getWidth(), l2.getHeight(), BufferedImage.TYPE_INT_ARGB);
        l2.paint(bi.getGraphics());
        int height2 = maxCharHeight(bi, l2.getWidth(), l2.getHeight());

        mainFrame.dispose();

        if (Math.abs(height2 - 2 * height1) > 2) {
            throw new RuntimeException("Heights of \"<code>A</code>\" for 36pt and for 72pt "
                    + "must differ by half (+/- 2pxs)");
        }
    }

    private static int maxCharHeight(BufferedImage bufferedImage, int width, int height) {
        int rgb;
        int maxHeight = 0;
        for (int col = 0; col < width; col++) {
            for (int row = 0; row < height; row++) {
                try {
                    // remove transparance
                    rgb = bufferedImage.getRGB(col, row) & 0x00FFFFFF;
                } catch (ArrayIndexOutOfBoundsException e) {
                    return maxHeight;
                }

                if (verbose)
                    System.out.print((rgb == 0xFFFFFF) ? " ." : " X");

                if (rgb != 0xFFFFFF && maxHeight < height - row)
                    maxHeight = height - row;
            }
            if (verbose)
                System.out.println("maxHeight=" + maxHeight);
        }
        return maxHeight;
    }
}