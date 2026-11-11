import java.awt.*; import java.awt.geom.*; import java.awt.image.*; import java.util.*;

/**
 * Exact stroke coverage — the points within half the width of the device-space curve, 16 x 16 samples per pixel against a
 * finely flattened curve — against a rendering: max and mean coverage error, ink, centroid shift per case (round caps and
 * joins, so the definition is exact; closed smooth curves need no caps). Arms by properties: the loops (none),
 * -Dsun.java2d.skia.raster=true (Skia's stroker), plus -Dsun.java2d.skia.strokes=java (Java's outline); -Ddump=prefix
 * writes exact | rendered | difference (red: too much ink) at 4x. Ledger 2026-11-11, the stroke accuracy reference.
 */
public class StrokeRef {
    static final int W = 170, H = 130, S = 16;

    record Case(String name, Shape shape, AffineTransform tx, float width) {}

    public static void main(String[] a) {
        Path2D.Double cubic = new Path2D.Double(); cubic.moveTo(10, 100); cubic.curveTo(40, -30, 90, 150, 150, 20); cubic.lineTo(120, 110);
        Case[] cases = {
            new Case("round-joined cubic, width 3", cubic, new AffineTransform(), 3f),
            new Case("rotated oval, width 2", new Ellipse2D.Double(30, 25, 100, 60), AffineTransform.getRotateInstance(0.3, 80, 60), 2f),
            new Case("oval under scale 2, width 1 (2 px)", new Ellipse2D.Double(10, 8, 60, 45), AffineTransform.getScaleInstance(2, 2), 1f),
            new Case("thin circle, width 1", new Ellipse2D.Double(20.3, 15.7, 90, 90), new AffineTransform(), 1f),
            new Case("thin circle, width 0.6", new Ellipse2D.Double(20.3, 15.7, 90, 90), new AffineTransform(), 0.6f),
        };
        String mode = System.getProperty("sun.java2d.skia.raster") == null ? "loops (Marlin)"
                : "skia".equals(System.getProperty("sun.java2d.skia.strokes", "skia")) ? "Skia raster, Skia's stroker" : "Skia raster, Java's stroker outline";
        System.out.println(mode);
        for (Case c : cases) {
            double[][] exact = exact(c);
            double[][] got = render(c);
            double maxErr = 0, sumAbs = 0, inkExact = 0, inkGot = 0, ex = 0, ey = 0, gx = 0, gy = 0; int n = 0;
            for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
                double e = exact[y][x], g = got[y][x];
                inkExact += e; inkGot += g; ex += e * (x + 0.5); ey += e * (y + 0.5); gx += g * (x + 0.5); gy += g * (y + 0.5);
                if (e > 0 || g > 0) { maxErr = Math.max(maxErr, Math.abs(g - e)); sumAbs += Math.abs(g - e); n++; }
            }
            if (System.getProperty("dump") != null) dump(c.name, exact, got);
            System.out.printf("  %-36s max %3.0f levels, mean %5.2f levels over %d px, ink %+.2f %%, centroid shift (%+.3f, %+.3f) px%n", c.name, maxErr * 255, sumAbs / n * 255, n, (inkGot / inkExact - 1) * 100, gx / inkGot - ex / inkExact, gy / inkGot - ey / inkExact);
        }
    }

    static void dump(String name, double[][] exact, double[][] got) {
        int z = 4;
        BufferedImage im = new BufferedImage(W * z * 3, H * z, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < H * z; y++) for (int x = 0; x < W * z; x++) {
            int e = (int) Math.round(255 * (1 - exact[y / z][x / z])), g = (int) Math.round(255 * (1 - got[y / z][x / z]));
            im.setRGB(x, y, e * 0x10101);
            im.setRGB(x + W * z, y, g * 0x10101);
            int d = (int) Math.round(255 * (got[y / z][x / z] - exact[y / z][x / z]));   // red: too much ink, blue: too little
            im.setRGB(x + 2 * W * z, y, d > 0 ? 0xff0000 | (255 - Math.min(255, 3 * d)) * 0x101 : 0x0000ff | (255 - Math.min(255, -3 * d)) * 0x10100);
        }
        try { javax.imageio.ImageIO.write(im, "png", new java.io.File(System.getProperty("dump") + "-" + name.replaceAll("[^a-z0-9]+", "_") + ".png")); } catch (Exception e) { throw new RuntimeException(e); }
    }

    static double[][] render(Case c) {
        BufferedImage im = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = im.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.transform(c.tx);
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(c.width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(c.shape);
        g.dispose();
        double[][] cov = new double[H][W];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) cov[y][x] = (255 - (im.getRGB(x, y) & 255)) / 255.0;
        return cov;
    }

    static double[][] exact(Case c) {
        // the device-space curve, flattened finely
        java.util.List<double[]> segs = new ArrayList<>();
        PathIterator it = c.shape.getPathIterator(c.tx, 0.0005);
        double[] p = new double[6]; double sx = 0, sy = 0, lx = 0, ly = 0;
        while (!it.isDone()) {
            int t = it.currentSegment(p);
            if (t == PathIterator.SEG_MOVETO) { sx = lx = p[0]; sy = ly = p[1]; }
            else if (t == PathIterator.SEG_LINETO) { segs.add(new double[] {lx, ly, p[0], p[1]}); lx = p[0]; ly = p[1]; }
            else if (t == PathIterator.SEG_CLOSE) { segs.add(new double[] {lx, ly, sx, sy}); lx = sx; ly = sy; }
            it.next();
        }
        double scale = Math.sqrt(Math.abs(c.tx.getDeterminant()));
        double r = c.width * scale / 2, r2 = r * r;
        // bucket segments into cells of 4 px
        int cw = 4, gx = W / cw + 2, gy = H / cw + 2;
        java.util.List<double[]>[][] grid = new ArrayList[gy][gx];
        for (double[] s : segs) {
            int x0 = (int) Math.floor((Math.min(s[0], s[2]) - r) / cw), x1 = (int) Math.floor((Math.max(s[0], s[2]) + r) / cw);
            int y0 = (int) Math.floor((Math.min(s[1], s[3]) - r) / cw), y1 = (int) Math.floor((Math.max(s[1], s[3]) + r) / cw);
            for (int yy = Math.max(0, y0); yy <= Math.min(gy - 1, y1); yy++) for (int xx = Math.max(0, x0); xx <= Math.min(gx - 1, x1); xx++) {
                if (grid[yy][xx] == null) grid[yy][xx] = new ArrayList<>();
                grid[yy][xx].add(s);
            }
        }
        double[][] cov = new double[H][W];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            java.util.List<double[]> cell = grid[y / cw][x / cw];
            if (cell == null) continue;
            int in = 0;
            for (int j = 0; j < S; j++) for (int i = 0; i < S; i++) {
                double px = x + (i + 0.5) / S, py = y + (j + 0.5) / S;
                for (double[] s : cell) if (dist2(px, py, s) <= r2) { in++; break; }
            }
            cov[y][x] = in / (double) (S * S);
        }
        return cov;
    }

    static double dist2(double px, double py, double[] s) {
        double dx = s[2] - s[0], dy = s[3] - s[1], len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - s[0]) * dx + (py - s[1]) * dy) / len2));
        double ex = s[0] + t * dx - px, ey = s[1] + t * dy - py;
        return ex * ex + ey * ey;
    }
}
