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

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.font.GlyphVector;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/*
 * @test
 * @summary regression test on JRE-467 Wrong rendering of variation sequences
 * @run main/othervm Font467 font467_screenshot1.png font467_screenshot2.png
 */

/*
 * Description: The test draws the letter 'a' on the first step and 'a' with a variation selector. Because variation
 * sequence being used is not standard, it is expected two identical letters 'a' will be rendered in both cases.
 *
 */
public class Font467 {

    // A font supporting Unicode variation selectors is required
    private static Font FONT;

    private static String SCREENSHOT_FILE_NAME1, SCREENSHOT_FILE_NAME2;

    public static void main(String[] args) throws Exception {

        String fontFileName = Font467.class.getResource("fonts/DejaVuSans.ttf").getFile();
        if (args.length > 0)
            SCREENSHOT_FILE_NAME1 = args[0];
        if (args.length > 1)
            SCREENSHOT_FILE_NAME2 = args[1];

        try {
            GraphicsEnvironment ge =
                    GraphicsEnvironment.getLocalGraphicsEnvironment();
            ge.registerFont(Font.createFont(Font.TRUETYPE_FONT, new File(fontFileName)));
        } catch (IOException | FontFormatException e) {
            e.printStackTrace();
        }

        FONT = new Font("DejaVu Sans", Font.PLAIN, 12);
        BufferedImage bufferedImage1 = drawImage("a");
        BufferedImage bufferedImage2 = drawImage("a\ufe00");

        if (!imagesAreEqual(bufferedImage1, bufferedImage2)) {
            try {
                ImageIO.write(bufferedImage1, "png", new File(System.getProperty("test.classes")
                        + File.separator + SCREENSHOT_FILE_NAME1));
                ImageIO.write(bufferedImage2, "png", new File(System.getProperty("test.classes")
                        + File.separator + SCREENSHOT_FILE_NAME2));
            } catch (IOException | NullPointerException ex) {
                ex.printStackTrace();
            }
            throw new RuntimeException("Expected: screenshots must be equal");
        }
    }

    private static BufferedImage drawImage(String text) {
        int HEIGHT = 50;
        int WIDTH = 20;

        BufferedImage bufferedImage = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics g = bufferedImage.createGraphics();
        Graphics2D g2d = (Graphics2D) g;

        g2d.setColor(Color.white);
        g2d.fillRect(0, 0, WIDTH, HEIGHT);

        g2d.setFont(FONT);
        g2d.setColor(Color.black);
        GlyphVector gv = FONT.layoutGlyphVector(g2d.getFontRenderContext(), text.toCharArray(), 0,
                text.length(), Font.LAYOUT_LEFT_TO_RIGHT);
        g2d.drawGlyphVector(gv, 5, 10);
        return bufferedImage;
    }

    private static boolean imagesAreEqual(BufferedImage i1, BufferedImage i2) {
        if (i1.getWidth() != i2.getWidth() || i1.getHeight() != i2.getHeight()) return false;
        for (int i = 0; i < i1.getWidth(); i++) {
            for (int j = 0; j < i1.getHeight(); j++) {
                if (i1.getRGB(i, j) != i2.getRGB(i, j)) return false;
            }
        }
        return true;
    }
}