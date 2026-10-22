import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;

/** AA fill only, integer coordinates at 2x: all four edges, with STROKE_CONTROL normalize vs pure, round rect vs plain rect vs path; coverage of the outer pixel as % of the fill colour. */
public class RRectEdgeProbe2 {
    public static void main(String[] args) throws Exception {
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        for (String shape : new String[] { "rrect", "rect", "path-rect", "ellipse" }) for (String ctl : new String[] { "normalize", "pure" }) {
            BufferedImage sw = new BufferedImage(120, 60, BufferedImage.TYPE_INT_RGB);
            paint(sw.createGraphics(), shape, ctl);
            VolatileImage vi = gc.createCompatibleVolatileImage(120, 60, Transparency.OPAQUE);
            paint(vi.createGraphics(), shape, ctl);
            BufferedImage hw = vi.getSnapshot();
            // fill spans device x 8..89, y 8..47 (integer logical 4..45 x 4..24 at 2x); outer pixels: left x7, right x90, top y7, bottom y48 (mid-edge samples)
            System.out.printf("%-10s %-9s  sw L/R/T/B outer: %3d%% %3d%% %3d%% %3d%%   hw: %3d%% %3d%% %3d%% %3d%%   (inner last pixel hw R: %3d%%)%n", shape, ctl,
                cov(sw, 7, 30), cov(sw, 90, 30), cov(sw, 50, 7), cov(sw, 50, 48), cov(hw, 7, 30), cov(hw, 90, 30), cov(hw, 50, 7), cov(hw, 50, 48), cov(hw, 89, 30));
        }
    }
    static int cov(BufferedImage im, int x, int y) { int b = im.getRGB(x, y) & 0xff; return Math.round((b - 0x1c) * 100f / (0x58 - 0x1c)); }
    static void paint(Graphics2D g, String shape, String ctl) {
        g.setColor(new Color(0x191A1C)); g.fillRect(0, 0, 120, 60);
        g.scale(2, 2);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, ctl.equals("pure") ? RenderingHints.VALUE_STROKE_PURE : RenderingHints.VALUE_STROKE_NORMALIZE);
        g.setColor(new Color(0x233558));
        Shape s = switch (shape) {
            case "rrect" -> new RoundRectangle2D.Double(4, 4, 41, 20, 8, 8);
            case "rect" -> new Rectangle2D.Double(4, 4, 41, 20);
            case "ellipse" -> new Ellipse2D.Double(4, 4, 41, 20);
            default -> { Path2D p = new Path2D.Double(); p.moveTo(4, 4); p.lineTo(45, 4); p.lineTo(45, 24); p.lineTo(4, 24); p.closePath(); yield p; }
        };
        g.fill(s);
        g.dispose();
    }
}
