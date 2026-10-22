import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.util.Random;

/**
 * Non-AA 1-pixel lines on the accelerated pipeline against the software loops, pixel by pixel:
 *
 *   LineConformance <max length> [lines] [seed]
 *
 * Draws `lines` (default 4000) lines with random integer end points, all eight octants, each in its
 * own cell of (max length + 2)^2 pixels, white on black, into a BufferedImage (the loops, the
 * reference) and into a VolatileImage of the default configuration (sheets of at most 4096 x 4096,
 * so every texture limit is met), and reports the lines with any differing pixel and the differing
 * pixels in total, plus the worst few lines. Run with -Dsun.java2d.uiScale=1.
 */
public class LineConformance {
    static final int SHEET = 4096;

    public static void main(String[] args) {
        int maxLength = Integer.parseInt(args[0]);
        int lines = args.length > 1 ? Integer.parseInt(args[1]) : 4000;
        long seed = args.length > 2 ? Long.parseLong(args[2]) : 1;
        int cell = maxLength + 2, cols = Math.max(1, SHEET / cell), perSheet = cols * Math.max(1, SHEET / cell);
        int[][] ends = new int[lines][4];
        Random r = new Random(seed);
        for (int[] e : ends) {
            e[0] = r.nextInt(maxLength + 1); e[1] = r.nextInt(maxLength + 1);
            e[2] = r.nextInt(maxLength + 1); e[3] = r.nextInt(maxLength + 1);
        }
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        int badLines = 0;
        long badPixels = 0;
        int[] diff = new int[lines];
        String surface = "";
        for (int first = 0; first < lines; first += perSheet) {
            int n = Math.min(perSheet, lines - first), rows = (n + cols - 1) / cols;
            int w = Math.min(n, cols) * cell, h = rows * cell;
            BufferedImage ref = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            draw(ref.createGraphics(), ends, first, n, cols, cell);
            VolatileImage vi = gc.createCompatibleVolatileImage(w, h);
            BufferedImage acc;
            do {
                vi.validate(gc);
                draw(vi.createGraphics(), ends, first, n, cols, cell);
                acc = vi.getSnapshot();
            } while (vi.contentsLost());
            surface = sun.java2d.SurfaceData.getPrimarySurfaceData(vi).getClass().getSimpleName();
            for (int j = 0; j < n; j++) {
                int x0 = (j % cols) * cell, y0 = (j / cols) * cell, d = 0;
                for (int y = y0; y < y0 + cell; y++) {
                    for (int x = x0; x < x0 + cell; x++) {
                        if ((ref.getRGB(x, y) & 0xffffff) != (acc.getRGB(x, y) & 0xffffff)) {
                            d++;
                        }
                    }
                }
                diff[first + j] = d;
                if (d > 0) {
                    badLines++;
                    badPixels += d;
                }
            }
            vi.flush();
        }
        System.out.printf("max length %d, %d lines: %d lines differ (%.2f %%), %d pixels differ (%s)%n",
                          maxLength, lines, badLines, 100.0 * badLines / lines, badPixels, surface);
        for (int shown = 0; shown < 5; shown++) {
            int k = 0;
            for (int i = 1; i < lines; i++) {
                if (diff[i] > diff[k]) k = i;
            }
            if (diff[k] == 0) break;
            int[] e = ends[k];
            System.out.printf("  (%d,%d)-(%d,%d): %d pixels%n", e[0], e[1], e[2], e[3], diff[k]);
            diff[k] = 0;
        }
        System.exit(0);
    }

    static void draw(Graphics2D g, int[][] ends, int first, int n, int cols, int cell) {
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, 1 << 14, 1 << 14);
        g.setColor(Color.WHITE);
        for (int j = 0; j < n; j++) {
            int ox = (j % cols) * cell + 1, oy = (j / cols) * cell + 1;
            int[] e = ends[first + j];
            g.drawLine(ox + e[0], oy + e[1], ox + e[2], oy + e[3]);
        }
        g.dispose();
    }
}
