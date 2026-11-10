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

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import java.text.AttributedString;

/**
 * @test
 * @summary An attributed string with a line terminator on Skia's paragraph route: SkParagraph lays a second, empty
 *          line out and hands its painter a null text blob, which the painter that splits colour glyphs off (the
 *          raster surfaces' swizzled canvas, composites other than SrcOver) dereferenced — the VM crashed in
 *          SkTextBlob::Iter. Drawn on a raster surface under SrcOver and Src, and the text must still be drawn.
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.skia.raster=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.layout=skia SkiaParagraphNewlineTest
 */
public class SkiaParagraphNewlineTest {
    public static void main(String[] args) throws Exception {
        long before = paragraphs();
        for (String text : new String[] {"x\n", "x   \n", "\n", "x\r\n", "x ", "a\nb"}) {
            for (AlphaComposite comp : new AlphaComposite[] {AlphaComposite.SrcOver, AlphaComposite.Src}) {
                BufferedImage image = new BufferedImage(120, 40, BufferedImage.TYPE_INT_ARGB_PRE);
                Graphics2D g = image.createGraphics();
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, 120, 40);
                g.setColor(Color.BLACK);
                g.setComposite(comp);
                AttributedString as = new AttributedString(text);
                as.addAttribute(TextAttribute.FONT, new Font(Font.SANS_SERIF, Font.PLAIN, 13));
                g.drawString(as.getIterator(), 10, 25);
                g.dispose();
                boolean ink = false;
                for (int y = 0; y < 40 && !ink; y++) for (int x = 0; x < 120 && !ink; x++) ink = (image.getRGB(x, y) & 0xff) < 128;
                if (!ink && text.trim().length() > 0) {
                    throw new RuntimeException("nothing drawn for \"" + text.replace("\n", "\\n") + "\"");
                }
            }
        }
        if (paragraphs() == before) {
            throw new RuntimeException("nothing drawn by the paragraph route: the test would prove nothing");
        }
        System.out.println("PASSED");
    }

    static long paragraphs() throws Exception {
        return (long) Class.forName("sun.java2d.skia.SKEnvironment").getMethod("paragraphsBySkia").invoke(null);
    }
}
