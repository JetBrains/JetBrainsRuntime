import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;

/** An IDE-like tab (filled round rect, then a 1 px rounded border) at 2x, in variants; prints the pixels across the right border for software vs the pipeline. */
public class RRectEdgeProbe {
    public static void main(String[] args) throws Exception {
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        String[] variants = { "aa int", "aa int nostroke", "aa frac.5", "aa frac.25", "noaa int", "noaa frac.5", "aa int stroke-only" };
        for (String v : variants) {
            BufferedImage sw = new BufferedImage(120, 60, BufferedImage.TYPE_INT_RGB);
            paint(sw.createGraphics(), v);
            VolatileImage vi = gc.createCompatibleVolatileImage(120, 60, Transparency.OPAQUE);
            paint(vi.createGraphics(), v);
            BufferedImage hw = vi.getSnapshot();
            StringBuilder a = new StringBuilder(), b = new StringBuilder();
            for (int x = 84; x <= 94; x++) { a.append(String.format("%06x ", sw.getRGB(x, 30) & 0xffffff)); b.append(String.format("%06x ", hw.getRGB(x, 30) & 0xffffff)); }
            StringBuilder c = new StringBuilder(), d = new StringBuilder();
            for (int y = 4; y <= 12; y++) { c.append(String.format("%06x ", sw.getRGB(50, y) & 0xffffff)); d.append(String.format("%06x ", hw.getRGB(50, y) & 0xffffff)); }
            System.out.printf("%-20s right edge x84..94  sw: %s%n%-20s                    hw: %s%n%-20s top edge   y4..12   sw: %s%n%-20s                    hw: %s%n", v, a, "", b, "", c, "", d);
        }
    }
    static void paint(Graphics2D g, String v) {
        g.setColor(new Color(0x191A1C)); g.fillRect(0, 0, 120, 60);
        g.scale(2, 2);   // 2x like the IDE
        boolean aa = v.startsWith("aa");
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);
        double off = v.contains("frac.5") ? 0.5 : v.contains("frac.25") ? 0.25 : 0;
        // the tab: x 4..45 logical (8..90 device), 20 logical high, radius 4
        RoundRectangle2D r = new RoundRectangle2D.Double(4 + off, 4 + off, 41, 20, 8, 8);
        if (!v.contains("stroke-only")) { g.setColor(new Color(0x233558)); g.fill(r); }
        if (!v.contains("nostroke")) {
            g.setColor(new Color(0x2E4D89)); g.setStroke(new BasicStroke(1f));
            g.draw(new RoundRectangle2D.Double(4.5 + off, 4.5 + off, 40, 19, 8, 8));
        }
        g.dispose();
    }
}
