/*
 * Copyright 2000-2026 JetBrains s.r.o.
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
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Arrays;

/**
 * @test
 * @summary Text through Skia's text path on an INT_ARGB raster surface costs what it
 *          costs on INT_ARGB_PRE, within a factor: the operation's box is the glyphs',
 *          not the clip's, so the unpremultiplied image's mirror is written back for the
 *          text's pixels only. Before the fix every string wrote the whole 1200 x 900
 *          mirror back (about 150 times the INT_ARGB_PRE time on the Windows VM). Nothing
 *          to test where the Skia backend is unavailable (the raster surface is checked).
 * @key headful
 * @modules java.desktop/sun.java2d
 * @run main/othervm -Dsun.java2d.uiScale=1 -Djava.awt.headless=true -Dsun.java2d.skia.raster=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text=skia SkiaRasterTextCostTest
 */
public class SkiaRasterTextCostTest {
    static final int W = 1200, H = 900, STRINGS = 400, FRAMES = 9;
    static final String LINE = "The quick brown fox jumps over the lazy dog 0123456789 {}[]();";

    public static void main(String[] args) throws Exception {
        if (!sun.java2d.SurfaceData.getPrimarySurfaceData(new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)).getClass().getSimpleName().equals("SKRasterSurfaceData")) {
            throw new RuntimeException("no Skia raster surface: is the backend's library available?");
        }
        Font font = new Font(Font.DIALOG, Font.PLAIN, 13);
        double pre = frameMs(BufferedImage.TYPE_INT_ARGB_PRE, font), unpre = frameMs(BufferedImage.TYPE_INT_ARGB, font);
        double ratio = unpre / pre;
        System.out.printf("%d strings per frame: INT_ARGB_PRE %.2f ms, INT_ARGB %.2f ms, ratio %.2f (limit 3)%n", STRINGS, pre, unpre, ratio);
        if (ratio > 3) {
            throw new RuntimeException("text on an INT_ARGB raster surface takes " + ratio + " times the INT_ARGB_PRE time (limit 3): the mirror is written back beyond the text's box");
        }
        System.out.println("PASSED");
    }

    static double frameMs(int type, Font font) {
        BufferedImage img = new BufferedImage(W, H, type);
        String surface = sun.java2d.SurfaceData.getPrimarySurfaceData(img).getClass().getSimpleName();
        if (!surface.equals("SKRasterSurfaceData")) {
            throw new RuntimeException("image type " + type + ": surface " + surface + ", expected the Skia raster surface");
        }
        double[] ms = new double[FRAMES];
        java.util.Random rnd = new java.util.Random(1);
        for (int f = 0; f < FRAMES; f++) {
            Graphics2D g = img.createGraphics();
            g.setColor(Color.WHITE); g.fillRect(0, 0, W, H);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(font); g.setColor(Color.BLACK);
            rnd.setSeed(f);
            long t0 = System.nanoTime();
            for (int i = 0; i < STRINGS; i++) g.drawString(LINE, rnd.nextInt(W - 500), 16 + rnd.nextInt(H - 20));
            img.getRGB(0, 0);
            ms[f] = (System.nanoTime() - t0) / 1e6;
            g.dispose();
        }
        double[] tail = Arrays.copyOfRange(ms, 3, ms.length);
        Arrays.sort(tail);
        return tail[tail.length / 2];
    }
}
