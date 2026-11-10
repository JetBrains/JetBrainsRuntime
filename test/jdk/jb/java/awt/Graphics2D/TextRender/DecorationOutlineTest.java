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
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.Map;

/**
 * @test
 * @summary Under the system decoration convention (sun.java2d.skia.text.decorations=system, the default: the
 *          underline and strikethrough on the platform text stack's rows, CoreText's on macOS) TextLayout's outline
 *          holds the decorations TextLayout draws, as 6751616 (UnderlinePositionTest) requires — the outline took
 *          Java's nominal bands and the drawn underline lay outside it. Spaces only, so the pixels are the
 *          decorations': drawn in red, the outline's bounds filled over them, no red may remain; underline and
 *          strikethrough, three fonts, sizes 9 to 72, on the software loops, the Skia GPU pipe and its raster surface.
 * @run main/othervm -Djava.awt.headless=true DecorationOutlineTest loops
 * @run main/othervm -Djava.awt.headless=true -Dsun.java2d.skia.raster=true DecorationOutlineTest raster
 */

/*
 * @test id=gpu
 * @summary The same on the Skia GPU pipe and on the platform's own pipeline, at 1x and 2x: the outline's bands are the
 *          ones drawn at the layout's device scale (the render context's transform).
 * @requires (os.family == "mac" | os.family == "windows")
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true DecorationOutlineTest gpu
 * @run main/othervm -Dsun.java2d.uiScale=2 -Dsun.java2d.skia=true DecorationOutlineTest gpu
 * @run main/othervm -Dsun.java2d.uiScale=1 DecorationOutlineTest gpu
 * @run main/othervm -Dsun.java2d.uiScale=2 DecorationOutlineTest gpu
 */
public class DecorationOutlineTest {
    static final int W = 600, H = 160;

    public static void main(String[] args) {
        String mode = args[0];
        String[] families = System.getProperty("os.name").startsWith("Mac")
                ? new String[] {"Helvetica Neue", "Menlo", Font.DIALOG} : new String[] {"Segoe UI", "Consolas", Font.DIALOG};
        StringBuilder failures = new StringBuilder();
        int cases = 0;
        for (String family : families) {
            for (float size : new float[] {9, 11, 13, 14, 16, 18, 22, 27, 36, 48, 72}) {
                for (TextAttribute decoration : new TextAttribute[] {TextAttribute.UNDERLINE, TextAttribute.STRIKETHROUGH}) {
                    Object on = decoration == TextAttribute.UNDERLINE ? TextAttribute.UNDERLINE_ON : TextAttribute.STRIKETHROUGH_ON;
                    Font font = new Font(Map.of(TextAttribute.FAMILY, family, TextAttribute.SIZE, size, decoration, on));
                    int left = leftOver(mode, font);
                    cases++;
                    if (left > 0) {
                        failures.append(family).append(' ').append(size).append(' ').append(decoration == TextAttribute.UNDERLINE ? "underline" : "strikethrough")
                                .append(": ").append(left).append(" drawn pixels outside the outline; ");
                    }
                }
            }
        }
        System.out.println(mode + ": " + cases + " cases");
        if (failures.length() > 0) {
            throw new RuntimeException(mode + ": " + failures);
        }
        System.out.println("PASSED");
    }

    /** The decoration's pixels the outline's bounds do not cover. */
    static int leftOver(String mode, Font font) {
        BufferedImage image;
        Graphics2D g;
        java.awt.image.VolatileImage vi = null;
        if (mode.equals("gpu")) {
            vi = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().createCompatibleVolatileImage(W, H);
            g = vi.createGraphics();
        } else {
            image = new BufferedImage(W, H, mode.equals("raster") ? BufferedImage.TYPE_INT_ARGB_PRE : BufferedImage.TYPE_INT_RGB);
            g = image.createGraphics();
            vi = null;
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, W, H);
            return draw(g, font, image, null);
        }
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        return draw(g, font, null, vi);
    }

    static int draw(Graphics2D g, Font font, BufferedImage image, java.awt.image.VolatileImage vi) {
        TextLayout tl = new TextLayout("           ", font, g.getFontRenderContext());
        Rectangle2D bounds = tl.getOutline(null).getBounds();
        g.translate(10, 100);
        g.setColor(Color.RED);
        tl.draw(g, 0, 0);
        g.setColor(Color.BLUE);
        g.fill(bounds);
        g.dispose();
        BufferedImage result = vi != null ? vi.getSnapshot() : image;
        int red = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int p = result.getRGB(x, y);
            if (((p >> 16) & 0xff) > 0x80 && (p & 0xff) < 0x80 && ((p >> 8) & 0xff) < 0x80) red++;
        }
        return red;
    }
}
