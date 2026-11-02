import java.awt.*; import java.awt.geom.*; import java.awt.image.BufferedImage;
public class NonAADbg {
    public static void main(String[] a) {
        int W = 160, H = 120; String which = a.length > 0 ? a[0] : "poly";
        BufferedImage skia = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB_PRE), loops = new BufferedImage(W, H, BufferedImage.TYPE_3BYTE_BGR);
        for (BufferedImage img : new BufferedImage[] {skia, loops}) {
            Graphics2D g = img.createGraphics(); g.setColor(Color.WHITE); g.fillRect(0, 0, W, H); g.setColor(Color.BLUE);
            switch (which) {
                case "poly" -> g.fill(new Polygon(new int[] {10, 150, 150, 80, 10}, new int[] {10, 20, 110, 60, 100}, 5));
                case "tri" -> g.fill(new Polygon(new int[] {10, 150, 10}, new int[] {10, 20, 100}, 3));
                case "oval" -> g.fill(new Ellipse2D.Double(20.5, 10.3, 60, 40));
                case "pathrect" -> { Path2D.Double r = new Path2D.Double(); r.moveTo(10.5, 10.5); r.lineTo(60.5, 10.5); r.lineTo(60.5, 40.5); r.lineTo(10.5, 40.5); r.closePath(); g.fill(r); }
                case "pathrectwide" -> { g.setStroke(new BasicStroke(3f)); Path2D.Double r = new Path2D.Double(); r.moveTo(10.5, 10.5); r.lineTo(60.5, 10.5); r.lineTo(60.5, 40.5); r.lineTo(10.5, 40.5); r.closePath(); g.fill(r); }
                case "pathrectint" -> { Path2D.Double r = new Path2D.Double(); r.moveTo(10, 10); r.lineTo(60, 10); r.lineTo(60, 40); r.lineTo(10, 40); r.closePath(); g.fill(r); }
                case "pathrectpure" -> { g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE); Path2D.Double r = new Path2D.Double(); r.moveTo(10.5, 10.5); r.lineTo(60.5, 10.5); r.lineTo(60.5, 40.5); r.lineTo(10.5, 40.5); r.closePath(); g.fill(r); }
                case "toval" -> { g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f)); g.setColor(new Color(200, 30, 30)); g.fill(new Ellipse2D.Double(25, 15, 70, 50)); }
                case "ovalwide" -> { g.setStroke(new BasicStroke(3f)); g.fill(new Ellipse2D.Double(25, 15, 70, 50)); }
                case "rect" -> g.fill(new Rectangle2D.Double(10.5, 10.5, 50, 30));
                case "rect25" -> g.fill(new Rectangle2D.Double(10.25, 10.25, 50, 30));
                case "rect75" -> g.fill(new Rectangle2D.Double(10.75, 10.75, 50, 30));
                case "polywide" -> { g.setStroke(new BasicStroke(3f)); g.fill(new Polygon(new int[] {10, 150, 150, 80, 10}, new int[] {10, 20, 110, 60, 100}, 5)); }
            }
            g.dispose();
        }
        int n = 0; StringBuilder rows = new StringBuilder();
        for (int y = 0; y < H; y++) { StringBuilder r = new StringBuilder(); boolean any = false;
            for (int x = 0; x < W; x++) { boolean s = skia.getRGB(x, y) != -1, l = loops.getRGB(x, y) != -1; if (s != l) { n++; any = true; r.append(x).append(s ? "S" : "J").append(' '); } }
            if (any) rows.append("y=").append(y).append(": ").append(r).append('\n'); }
        System.out.println(which + ": " + n + " differing (S = Skia only, J = loops only)"); System.out.print(rows.length() > 3000 ? rows.substring(0, 3000) + "...\n" : rows);
        // the loops' rows near the top vertex, as characters
        for (int y = 8; y <= 14; y++) { StringBuilder r = new StringBuilder(); for (int x = 6; x <= 26; x++) r.append(loops.getRGB(x, y) != -1 ? '#' : '.').append(skia.getRGB(x, y) != -1 ? '#' : '.').append(' '); System.out.println("y=" + y + " loops/skia pairs: " + r); }
    }
}
