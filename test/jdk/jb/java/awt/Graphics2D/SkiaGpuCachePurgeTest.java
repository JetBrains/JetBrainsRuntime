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
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.util.Random;

/**
 * @test
 * @summary Graphite's resource caches give back what they have not used for a while
 *          (sun.java2d.skia.gpuCachePurgeSeconds, default 5), also while nothing is drawn: Graphite purges a
 *          purgeable resource only when its budget forces it (256 MB the recorder's) unless its client asks, and
 *          the backend never asked, so over RenderPerfTest the recorder kept 194 MB of upload and vertex buffers
 *          it had finished with and the process ran 260 MB above Metal. Frames of 1000 antialiased ovals
 *          (RenderPerfTest's FlatOvalAA) queued without a read-back fill the caches; after an idle while they must
 *          hold under an eighth of that, and the first frame drawn again must be drawn as before.
 * @key headful
 * @requires (os.family == "mac")
 * @modules java.desktop/sun.java2d.skia
 * @run main/othervm -Dsun.java2d.uiScale=1 -Dsun.java2d.skia=true SkiaGpuCachePurgeTest
 */
public class SkiaGpuCachePurgeTest {
    static final int W = 800, H = 600, FRAMES = 400;

    public static void main(String[] args) throws Exception {
        if (cacheBytes() < 0) {
            System.out.println("Skia is unavailable: nothing to test");
            return;
        }
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        VolatileImage target = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
        BufferedImage first = frame(gc, target, 0, true);
        long peak = 0;
        for (int f = 1; f <= FRAMES; f++) {
            frame(gc, target, f, false);   // no read-back: the frames queue up on the GPU as an application's do
            if (f % 50 == 0) peak = Math.max(peak, cacheBytes());
        }
        if (peak < 8 << 20) throw new RuntimeException("the frames filled Graphite's caches with only " + (peak >> 20) + " MB: the test does not reach them");
        long idle = 0;
        for (int s = 0; s < 12; s++) {   // nothing drawn meanwhile
            Thread.sleep(1000);
            idle = cacheBytes();
            System.out.printf("idle %2d s: the caches hold %.1f MB%n", s + 1, idle / 1048576.0);
        }
        BufferedImage again = frame(gc, target, 0, true);
        int differing = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) if (again.getRGB(x, y) != first.getRGB(x, y)) differing++;
        System.out.printf("%d frames: the caches held up to %.1f MB, %.1f MB after 12 s idle; the first frame drawn again: %d pixels differ%n",
                FRAMES, peak / 1048576.0, idle / 1048576.0, differing);
        StringBuilder failures = new StringBuilder();
        if (idle > peak / 8) failures.append(String.format("the caches still hold %.1f of %.1f MB after 12 s idle; ", idle / 1048576.0, peak / 1048576.0));
        if (differing > 0) failures.append(differing).append(" pixels of the first frame differ when drawn again after the idle while; ");
        if (failures.length() > 0) throw new RuntimeException(failures.toString());
    }

    /** One frame: a new software image blitted (its upload), 1000 antialiased ovals, text; its snapshot if asked. */
    static BufferedImage frame(GraphicsConfiguration gc, VolatileImage target, int f, boolean snapshot) {
        Random rnd = new Random(f);
        BufferedImage source = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) source.setRGB(x, y, rnd.nextInt());
        BufferedImage pixels = null;
        do {
            target.validate(gc);
            Graphics2D g = target.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, W, H);
            g.drawImage(source, 10, 10, null);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            for (int i = 0; i < 1000; i++) {
                g.setColor(new Color(rnd.nextInt(0xffffff)));
                g.fillOval(rnd.nextInt(W - 24), rnd.nextInt(H - 24), 24, 24);
            }
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(new Font(Font.DIALOG, Font.PLAIN, 12 + f % 7));
            g.setColor(Color.BLACK);
            for (int i = 0; i < 20; i++) g.drawString("frame " + f + " line " + i + " The quick brown fox", 10, 540 + (i % 4) * 14);
            g.dispose();
            if (snapshot) pixels = target.getSnapshot();
        } while (target.contentsLost());
        return pixels;
    }

    static long cacheBytes() throws Exception {
        return (long) Class.forName("sun.java2d.skia.SKEnvironment").getMethod("gpuCacheBytes").invoke(null);
    }
}
