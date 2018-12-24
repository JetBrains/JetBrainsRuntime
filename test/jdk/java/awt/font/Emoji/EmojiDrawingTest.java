/*
 * Copyright 2000-2018 JetBrains s.r.o.
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

/*
 * @test
 * @summary Emoji glyphs drawing on macOS
 * @requires os.family == "mac"
 */

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URL;
import javax.imageio.ImageIO;

public class EmojiDrawingTest {
    private static final String EMOJI = new String(Character.toChars(0x1f600));
    private static Font FONT = new Font("Menlo", Font.PLAIN, 12);
    private static final int IMAGE_WIDTH = 20;
    private static final int IMAGE_HEIGHT = 20;
    private static final int GLYPH_X = 2;
    private static final int GLYPH_Y = 15;

    public static void main(String[] args) throws Exception {
        BufferedImage actual = createImage();
        if (!matchesOneOfExpected(actual)) {
            File file = File.createTempFile("emoji", ".png");
            ImageIO.write(actual, "PNG", file);
            throw new RuntimeException("Unexpected painting on image: " + file);
        }
    }

    private static boolean matchesOneOfExpected(BufferedImage actualImage) throws Exception {
        URL url;
        for (int i = 1; (url = EmojiDrawingTest.class.getResource("emoji" + i + ".png")) != null; i++) {
            BufferedImage expectedImage = ImageIO.read(url);
            if (imagesAreEqual(actualImage, expectedImage)) {
                return true;
            }
        }
        return false;
    }

    private static BufferedImage createImage() {
        BufferedImage image = new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.white);
        g.fillRect(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);
        g.setFont(FONT);
        g.drawString(EMOJI, GLYPH_X, GLYPH_Y);
        g.dispose();
        return image;
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
