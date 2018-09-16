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

package quality.text;

import org.junit.Test;
import quality.util.RenderUtil;

import java.awt.*;
import java.awt.image.BufferedImage;

public class MixedTextTest {

    @Test
    public void testLCDGray() throws Exception {
        final Font font = new Font("Menlo", Font.PLAIN, 50);
        BufferedImage bi = RenderUtil.capture(120, 120,
                graphics2D -> {
                    graphics2D.setFont(font);

                    graphics2D.setRenderingHint(
                            RenderingHints.KEY_TEXT_ANTIALIASING,
                            RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);


                    graphics2D.drawString("A", 0, 50);

                    graphics2D.setRenderingHint(
                            RenderingHints.KEY_TEXT_ANTIALIASING,
                            RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                    graphics2D.drawString("A", 15, 50);

                    graphics2D.setRenderingHint(
                            RenderingHints.KEY_TEXT_ANTIALIASING,
                            RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);

                    graphics2D.drawString("A", 30, 50);

                    graphics2D.setRenderingHint(
                            RenderingHints.KEY_TEXT_ANTIALIASING,
                            RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);


                    graphics2D.drawString("A", 45, 50);

                    graphics2D.setRenderingHint(
                            RenderingHints.KEY_TEXT_ANTIALIASING,
                            RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);

                    graphics2D.drawString("A", 60, 50);

                    graphics2D.setRenderingHint(
                            RenderingHints.KEY_TEXT_ANTIALIASING,
                            RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                    graphics2D.drawString("A", 75, 50);


                });

        RenderUtil.checkImage(bi, "text", "lcdgray.png");
    }

    @Test
    public void testCacheNoCacheLCD() throws Exception {
        final Font smallFont = new Font("Menlo", Font.PLAIN, 20);
        final Font bigFont = new Font("Menlo", Font.PLAIN, 50);
        BufferedImage bi = RenderUtil.capture(120, 120,
                graphics2D -> {
                    graphics2D.setFont(smallFont);

                    graphics2D.setRenderingHint(
                            RenderingHints.KEY_TEXT_ANTIALIASING,
                            RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);


                    graphics2D.drawString("A", 0, 50);

                    graphics2D.setFont(bigFont);

                    graphics2D.setRenderingHint(
                            RenderingHints.KEY_TEXT_ANTIALIASING,
                            RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);

                    graphics2D.drawString("A", 10, 50);

                    graphics2D.setFont(smallFont);

                    graphics2D.setRenderingHint(
                            RenderingHints.KEY_TEXT_ANTIALIASING,
                            RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);


                    graphics2D.drawString("A", 38, 50);

                    graphics2D.setFont(bigFont);

                    graphics2D.setRenderingHint(
                            RenderingHints.KEY_TEXT_ANTIALIASING,
                            RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);


                    graphics2D.drawString("A", 48, 50);
                });

        RenderUtil.checkImage(bi, "text", "cnclcd.png");
    }

}
