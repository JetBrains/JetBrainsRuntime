import java.awt.*;
import java.awt.geom.*;
import java.awt.image.*;

/** Where on a filled circle's edge does the GPU deviate from Marlin? Prints the max deviation per 15-degree sector. */
public class ArcCoverageProbe {
    public static void main(String[] a) throws Exception {
        int W = 260, H = 260; double r = 100, cx = 130.25, cy = 130.25;
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        boolean stroke = a.length > 0 && a[0].equals("stroke");
        BufferedImage ref = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        paint(ref.createGraphics(), r, cx, cy, stroke);
        VolatileImage vi = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
        paint(vi.createGraphics(), r, cx, cy, stroke);
        BufferedImage act = vi.getSnapshot(); System.out.println("surface " + sun.java2d.SurfaceData.getPrimarySurfaceData(vi).getClass().getSimpleName() + " lost " + vi.contentsLost());
        int[] max = new int[24]; int[] sumAbs = new int[24]; int[] n = new int[24]; int total = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int d = Math.abs((ref.getRGB(x, y) & 255) - (act.getRGB(x, y) & 255));
            if (d == 0) continue;
            double ang = Math.toDegrees(Math.atan2(y + 0.5 - cy, x + 0.5 - cx)); if (ang < 0) ang += 360;
            int s = (int) (ang / 15) % 24; max[s] = Math.max(max[s], d); sumAbs[s] += d; n[s]++; total = Math.max(total, d);
        }
        System.out.println((stroke ? "stroke" : "fill") + " max " + total + "; per 15-degree sector: max (mean over differing px)");
        for (int s = 0; s < 24; s++) System.out.printf("  %3d-%3d: %3d (%.1f, %d px)%n", s * 15, s * 15 + 15, max[s], n[s] == 0 ? 0 : sumAbs[s] / (double) n[s], n[s]);
    }
    static void paint(Graphics2D g, double r, double cx, double cy, boolean stroke) {
        g.setColor(Color.BLACK); g.fillRect(0, 0, 260, 260);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        Shape s = new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r);
        if (stroke) { g.setStroke(new BasicStroke(1f)); g.draw(s); } else g.fill(s);
        g.dispose();
    }
}
