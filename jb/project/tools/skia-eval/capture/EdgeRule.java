import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;

/** Software only: coverage of the pixels around the right edge of an AA-filled shape whose device-space right edge sits at 50 + f, under NORMALIZE and PURE. */
public class EdgeRule {
    public static void main(String[] a) {
        for (String shape : new String[] { "path", "rrect" }) for (String ctl : new String[] { "normalize", "pure" }) for (double f : new double[] { 0, 0.2, 0.25, 0.3, 0.5, 0.7, 0.75, 0.8 }) {
            BufferedImage im = new BufferedImage(80, 20, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = im.createGraphics();
            g.setColor(Color.BLACK); g.fillRect(0, 0, 80, 20);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, ctl.equals("pure") ? RenderingHints.VALUE_STROKE_PURE : RenderingHints.VALUE_STROKE_NORMALIZE);
            g.setColor(Color.WHITE);
            Shape s = shape.equals("path") ? new Path2D.Double(new Rectangle2D.Double(10, 2, 40 + f, 16)) : new RoundRectangle2D.Double(10, 2, 40 + f, 16, 4, 4);
            g.fill(s); g.dispose();
            StringBuilder sb = new StringBuilder();
            for (int x = 48; x <= 52; x++) sb.append(String.format("%3d%% ", Math.round((im.getRGB(x, 10) & 0xff) * 100f / 255)));
            System.out.printf("%-5s %-9s right edge at %5.2f: x48..52 = %s%n", shape, ctl, 50 + f, sb);
        }
    }
}
