import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.io.File;
import java.util.List;

/**
 * Stroked shapes through the current pipeline, one sheet per run:
 *   java StrokeCompare render <label>        -> strokes-<label>.png (software when no GPU property is set)
 *   java StrokeCompare compose <ref> <a> <b> -> strokes-compare.png: ref | a | diff(a) | b | diff(b), per row
 * Rows: 1 px / 3 px strokes of oval, round rect, pill, rotated round rect, cubic path, at 1x and 2x.
 */
public class StrokeCompare {
    static final int CELL = 96;
    static final String[] ROWS = {"oval 1px 1x", "oval 3px 1x", "rrect 1px 1x", "rrect 3px 1x", "pill 1px 1x", "rrect30 1px 1x", "path 1px 1x",
                                  "oval 1px 2x", "oval 3px 2x", "rrect 1px 2x", "rrect 3px 2x", "pill 1px 2x", "rrect30 1px 2x", "path 1px 2x"};

    static void paintRow(Graphics2D g, int row) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_NORMALIZE);
        g.setColor(Color.WHITE); g.fillRect(0, 0, CELL, CELL);
        g.setColor(new Color(40, 60, 120));
        String name = ROWS[row];
        boolean twoX = name.endsWith("2x");
        float w = name.contains("3px") ? 3f : 1f;
        if (twoX) g.scale(2, 2);
        g.setStroke(new BasicStroke(w));
        double s = twoX ? 0.5 : 1.0;   // same device-space size at both scales
        Shape shape;
        if (name.startsWith("oval")) shape = new Ellipse2D.Double(10.5 * s, 12.5 * s, 70 * s, 54 * s);
        else if (name.startsWith("rrect30")) { g.rotate(Math.toRadians(30), 48 * s, 48 * s); shape = new RoundRectangle2D.Double(16 * s, 24 * s, 64 * s, 44 * s, 14 * s, 14 * s); }
        else if (name.startsWith("rrect")) shape = new RoundRectangle2D.Double(10.5 * s, 14.5 * s, 72 * s, 60 * s, 18 * s, 18 * s);
        else if (name.startsWith("pill")) shape = new RoundRectangle2D.Double(8.5 * s, 30.5 * s, 78 * s, 30 * s, 60 * s, 60 * s);
        else { Path2D.Double p = new Path2D.Double(); p.moveTo(8 * s, 70 * s); p.curveTo(30 * s, -10 * s, 60 * s, 110 * s, 88 * s, 20 * s); shape = p; }
        g.draw(shape);
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("compose")) { compose(args[1], args[2], args[3]); return; }
        String label = args[1];
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        BufferedImage sheet = new BufferedImage(CELL, CELL * ROWS.length, BufferedImage.TYPE_INT_RGB);
        boolean gpu = Boolean.getBoolean("sun.java2d.skia") || Boolean.getBoolean("sun.java2d.metal");
        for (int r = 0; r < ROWS.length; r++) {
            BufferedImage cell;
            if (gpu) {
                VolatileImage vi = gc.createCompatibleVolatileImage(CELL, CELL, Transparency.OPAQUE);
                vi.validate(gc);
                Graphics2D g = vi.createGraphics(); paintRow(g, r); g.dispose();
                cell = vi.getSnapshot();
                if (r == 0) System.out.println("surface " + sun.java2d.SurfaceData.getPrimarySurfaceData(vi).getClass().getName());
            } else {
                cell = new BufferedImage(CELL, CELL, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = cell.createGraphics(); paintRow(g, r); g.dispose();
            }
            sheet.getGraphics().drawImage(cell, 0, r * CELL, null);
        }
        ImageIO.write(sheet, "png", new File("strokes-" + label + ".png"));
        System.out.println("wrote strokes-" + label + ".png");
    }

    static int maxDiff(int p, int q) { int m = 0; for (int s = 0; s < 24; s += 8) m = Math.max(m, Math.abs(((p >> s) & 255) - ((q >> s) & 255))); return m; }

    static void compose(String ref, String a, String b) throws Exception {
        BufferedImage R = ImageIO.read(new File("strokes-" + ref + ".png")), A = ImageIO.read(new File("strokes-" + a + ".png")), B = ImageIO.read(new File("strokes-" + b + ".png"));
        int S = 2, LABEL = 110, W = LABEL + 5 * CELL * S + 6 * 4, H = ROWS.length * (CELL * S + 4);
        BufferedImage out = new BufferedImage(W, H + 24, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics(); g.setColor(Color.DARK_GRAY); g.fillRect(0, 0, W, H + 24);
        g.setColor(Color.WHITE); g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        String[] cols = {ref, a, "diff " + a, b, "diff " + b};
        for (int c = 0; c < 5; c++) g.drawString(cols[c], LABEL + c * (CELL * S + 4) + 4, 16);
        System.out.printf("%-16s %-22s %-22s%n", "row", a + " max/px>32", b + " max/px>32");
        for (int r = 0; r < ROWS.length; r++) {
            int y = 24 + r * (CELL * S + 4);
            g.drawString(ROWS[r], 4, y + CELL);
            BufferedImage[] imgs = {R, A, null, B, null};
            int[] stats = new int[4];
            for (int c = 0; c < 5; c++) {
                BufferedImage cell = new BufferedImage(CELL, CELL, BufferedImage.TYPE_INT_RGB);
                for (int yy = 0; yy < CELL; yy++) for (int xx = 0; xx < CELL; xx++) {
                    int rgb;
                    if (c == 2 || c == 4) {
                        BufferedImage X = c == 2 ? A : B; int d = maxDiff(R.getRGB(xx, r * CELL + yy), X.getRGB(xx, r * CELL + yy));
                        int k = c == 2 ? 0 : 2; stats[k] = Math.max(stats[k], d); if (d > 32) stats[k + 1]++;
                        int v = Math.min(255, d * 4); rgb = d == 0 ? 0x202020 : (v << 16) | ((255 - v) << 8) / 2;
                    } else rgb = imgs[c].getRGB(xx, r * CELL + yy);
                    cell.setRGB(xx, yy, rgb);
                }
                g.drawImage(cell, LABEL + c * (CELL * S + 4), y, CELL * S, CELL * S, null);
            }
            System.out.printf("%-16s %3d / %-18d %3d / %d%n", ROWS[r], stats[0], stats[1], stats[2], stats[3]);
        }
        ImageIO.write(out, "png", new File("strokes-compare.png"));
        System.out.println("wrote strokes-compare.png");
    }
}
