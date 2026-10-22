import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;

/**
 * End-to-end frame time of one primitive kind, drawn and synchronised on one thread, so nothing of
 * a benchmark harness (paint timing, a polling thread, the queue lock) is in the number:
 *
 *   SyncProbe lines|rects <length> diagonal|horizontal pixel|full [count]
 *
 * Each frame draws `count` (default 1000) primitives of distinct colours into an 800 x 600
 * VolatileImage and then waits for the GPU: `pixel` copies one pixel into a 1x1 BufferedImage (the
 * pipeline must finish the frame, almost nothing is transferred), `full` takes getSnapshot() (the
 * whole image is read back, as RenderPerfTest's marker check does). Prints the median and p90 of 600
 * frames after 100 warm-up frames.
 */
public class SyncProbe {
    public static void main(String[] args) {
        boolean lines = args[0].equals("lines");
        int length = Integer.parseInt(args[1]);
        boolean diagonal = args[2].equals("diagonal");
        boolean pixelSync = args[3].equals("pixel");
        int count = args.length > 4 ? Integer.parseInt(args[4]) : 1000;
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        VolatileImage vi = gc.createCompatibleVolatileImage(800, 600);
        BufferedImage sync = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D sg = sync.createGraphics();
        long[] t = new long[600];
        for (int i = 0; i < 700; i++) {
            long s = System.nanoTime();
            vi.validate(gc);
            Graphics2D g = vi.createGraphics();
            for (int k = 0; k < count; k++) {
                g.setColor(new Color((k * 7) & 255, i % 256, (k * 13) & 255));
                int x = (k * 37) % 400, y = (k * 11) % 280;
                if (lines) {
                    g.drawLine(x, y, x + length, diagonal ? y + length * 3 / 4 : y);
                } else {
                    g.fillRect(x, y, length, diagonal ? length * 3 / 4 : 1);
                }
            }
            g.dispose();
            if (pixelSync) {
                sg.drawImage(vi, 0, 0, 1, 1, 5, 5, 6, 6, null);
                sync.getRGB(0, 0);
            } else {
                vi.getSnapshot().getRGB(0, 0);
            }
            if (i >= 100) {
                t[i - 100] = System.nanoTime() - s;
            }
        }
        java.util.Arrays.sort(t);
        System.out.printf("%s %d %s %s sync: median %.2f ms, p90 %.2f ms per frame%n", args[0], length, args[2], args[3],
                          t[300] / 1e6, t[540] / 1e6);
        System.exit(0);
    }
}
