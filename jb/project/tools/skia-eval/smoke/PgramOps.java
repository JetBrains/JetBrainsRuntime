import java.awt.*; import java.awt.geom.*; import java.awt.image.*; import java.util.Random;
/** The pipes' AA parallelogram ops (fill and draw, axis-aligned and under a rotation) against exact coverage, composited per shape. */
public class PgramOps {
    static final int W = 400, H = 300, S = 24;
    record Item(Shape fill, AffineTransform tx) {}
    public static void main(String[] a) {
        GraphicsConfiguration gc = a.length > 0 ? null : GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        String pipe = a.length > 0 ? "loops" : System.getProperty("sun.java2d.skia") != null ? "Skia GPU pipe" : "Metal";
        for (String mode : new String[] {"fill axis-aligned", "fill rotated", "draw axis-aligned", "draw rotated"}) {
            boolean rotated = mode.endsWith("rotated"), draw = mode.startsWith("draw");
            Random rnd = new Random(11); Item[] items = new Item[40];
            for (int i = 0; i < items.length; i++) {
                double x = 20 + rnd.nextDouble() * 340, y = 20 + rnd.nextDouble() * 240, w = 3 + rnd.nextDouble() * 24, h = 3 + rnd.nextDouble() * 14;
                AffineTransform t = rotated ? AffineTransform.getRotateInstance(rnd.nextDouble() * Math.PI, x + w / 2, y + h / 2) : new AffineTransform();
                Rectangle2D r = new Rectangle2D.Double(x, y, w, h);
                // the geometry the op covers: the rect, or its 1-px stroke outline, under the transform
                Shape covered = t.createTransformedShape(draw ? new BasicStroke(1f).createStrokedShape(r) : r);
                items[i] = new Item(covered, t); rects[i] = r;
            }
            BufferedImage im = render(gc, items, draw);
            double max = 0, sum = 0; int n = 0; worst = 0;
            if (System.getProperty("where") != null) for (int i = 0; i < 3; i++) System.out.println("    rect " + rects[i]);
            for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
                double clear = 1;
                for (Item it : items) { if (!it.fill.intersects(x, y, 1, 1)) continue; int in = 0;
                    for (int j = 0; j < S; j++) for (int i = 0; i < S; i++) if (it.fill.contains(x + (i + 0.5) / S, y + (j + 0.5) / S)) in++;
                    clear *= 1 - in / (double) (S * S); }
                double e = 1 - clear, got = (255 - (im.getRGB(x, y) & 255)) / 255.0;
                if ((e > 0 && e < 1) || (got > 0 && got < 1)) { if (Math.abs(got - e) * 255 > 30 && System.getProperty("where") != null && worst++ < 6) System.out.printf("    (%d,%d) exact %.3f got %.3f%n", x, y, e, got); max = Math.max(max, Math.abs(got - e)); sum += Math.abs(got - e); n++; }
            }
            System.out.printf("%-14s %-18s edge pixels %5d, max %3.0f, mean %.2f levels off exact%n", pipe, mode, n, max * 255, sum / n * 255);
        }
    }
    static Rectangle2D[] rects = new Rectangle2D[40];
    static int worst = 0;
    static void paint(Graphics2D g, Item[] items, boolean draw) {
        g.setColor(Color.WHITE); g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON); g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(Color.BLACK);
        AffineTransform base = g.getTransform();
        for (int i = 0; i < items.length; i++) { g.setTransform(base); g.transform(items[i].tx); if (draw) g.draw(rects[i]); else g.fill(rects[i]); }
        g.dispose();
    }
    static BufferedImage render(GraphicsConfiguration gc, Item[] items, boolean draw) {
        if (gc == null) { BufferedImage im = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB); paint(im.createGraphics(), items, draw); return im; }
        VolatileImage vi = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE); BufferedImage im;
        do { vi.validate(gc); paint(vi.createGraphics(), items, draw); im = vi.getSnapshot(); } while (vi.contentsLost());
        return im;
    }
}
