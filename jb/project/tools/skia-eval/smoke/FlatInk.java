import java.awt.*; import java.awt.geom.*; import java.awt.image.*;
public class FlatInk {
    public static void main(String[] a) {
        double r = 45;
        for (float width : new float[] {0.6f, 1f, 2f, 3f}) {
            StringBuilder out = new StringBuilder(String.format("width %.1f:", width));
            Shape outline = new BasicStroke(width).createStrokedShape(new Ellipse2D.Double(20.3, 15.7, 2 * r, 2 * r));
            for (double flatness : new double[] {-1, 0.1, 0.02, 0.005}) {
                Shape s = outline; if (flatness > 0) { Path2D.Double p = new Path2D.Double(); p.append(outline.getPathIterator(null, flatness), false); s = p; }
                BufferedImage im = new BufferedImage(140, 140, BufferedImage.TYPE_INT_RGB); Graphics2D g = im.createGraphics();
                g.setColor(Color.WHITE); g.fillRect(0, 0, 140, 140); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE); g.setColor(Color.BLACK); g.fill(s); g.dispose();
                double ink = 0; for (int y = 0; y < 140; y++) for (int x = 0; x < 140; x++) ink += (255 - (im.getRGB(x, y) & 255)) / 255.0;
                out.append(String.format("  %s %+.2f %%", flatness < 0 ? "curves" : "flat " + flatness, (ink / (2 * Math.PI * r * width) - 1) * 100));
            }
            System.out.println(out);
        }
    }
}
