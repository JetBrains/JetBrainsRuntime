import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;

/** Filled round rects and ovals against the software loops, by corner radius and scale: max and count of pixels beyond 8 levels, all at the arcs. */
public class ArcProbe {
    public static void main(String[] args) throws Exception {
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        System.out.println("shape      scale radius : max  >8px  >16px  (fill)  | stroked 1px: max >8px");
        for (int scale : new int[] { 1, 2 }) for (String shape : new String[] { "rrect", "oval" }) for (int radius : new int[] { 2, 4, 6, 8, 12, 16, 24 }) {
            if (shape.equals("oval") && radius != 8) continue;
            int[] f = run(gc, scale, shape, radius, false), s = run(gc, scale, shape, radius, true);
            System.out.printf("%-10s %dx    %2d     : %3d  %4d  %4d           | %3d  %4d%n", shape, scale, radius, f[0], f[1], f[2], s[0], s[1]);
        }
    }
    static int[] run(GraphicsConfiguration gc, int scale, String shape, int radius, boolean stroke) {
        int W = 160, H = 100;
        BufferedImage sw = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        paint(sw.createGraphics(), scale, shape, radius, stroke);
        VolatileImage vi = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
        paint(vi.createGraphics(), scale, shape, radius, stroke);
        BufferedImage hw = vi.getSnapshot();
        int max = 0, o8 = 0, o16 = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int p = sw.getRGB(x, y), q = hw.getRGB(x, y), d = 0;
            for (int sh = 0; sh <= 16; sh += 8) d = Math.max(d, Math.abs(((p >> sh) & 255) - ((q >> sh) & 255)));
            max = Math.max(max, d); if (d > 8) o8++; if (d > 16) o16++;
        }
        return new int[] { max, o8, o16 };
    }
    static void paint(Graphics2D g, int scale, String shape, int radius, boolean stroke) {
        g.setColor(new Color(0x191A1C)); g.fillRect(0, 0, 160, 100);
        g.scale(scale, scale);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x3574F0));
        Shape s = shape.equals("rrect") ? new RoundRectangle2D.Double(4, 4, 60, 36, radius * 2, radius * 2) : new Ellipse2D.Double(4, 4, 60, 36);
        if (stroke) { g.setStroke(new BasicStroke(1f)); g.draw(shape.equals("rrect") ? new RoundRectangle2D.Double(4.5, 4.5, 59, 35, radius * 2, radius * 2) : new Ellipse2D.Double(4.5, 4.5, 59, 35)); }
        else g.fill(s);
        g.dispose();
    }
}
