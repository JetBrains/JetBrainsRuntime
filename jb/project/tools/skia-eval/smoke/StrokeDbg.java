import java.awt.*; import java.awt.geom.*; import java.awt.image.*;
public class StrokeDbg {
  static BufferedImage draw(int type, Shape s, BasicStroke bs, boolean pure) { BufferedImage img = new BufferedImage(160, 120, type); Graphics2D g = img.createGraphics(); g.setColor(Color.WHITE); g.fillRect(0,0,160,120);
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON); if (pure) g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    g.setStroke(bs); g.setColor(Color.BLACK); g.draw(s); g.dispose(); return img; }
  static void cmp(String what, Shape s, BasicStroke bs, boolean pure) {
    BufferedImage a = draw(BufferedImage.TYPE_INT_ARGB_PRE, s, bs, pure), b = draw(BufferedImage.TYPE_3BYTE_BGR, s, bs, pure);
    int max=0, mx=0, my=0, over32=0; long inkA=0, inkB=0, sumPos=0, sumNeg=0;
    for (int y=0;y<120;y++) for (int x=0;x<160;x++) { int p=255-(a.getRGB(x,y)&255), q=255-(b.getRGB(x,y)&255); inkA+=p; inkB+=q; int d=Math.abs(p-q); if (d>32) over32++; if (p>q) sumPos+=p-q; else sumNeg+=q-p; if (d>max){max=d;mx=x;my=y;} }
    System.out.printf("%-34s max %3d at (%d,%d), >32: %d px, ink skia %d loops %d, skia darker by %d lighter by %d%n", what, max, mx, my, over32, inkA, inkB, sumPos, sumNeg);
    // a 9x5 window of (skia/loops) ink around the worst pixel
    for (int y=Math.max(0,my-2); y<=Math.min(119,my+2); y++) { StringBuilder sb=new StringBuilder("   "); for (int x=Math.max(0,mx-4); x<=Math.min(159,mx+4); x++) sb.append(String.format("%3d/%-3d ", 255-(a.getRGB(x,y)&255), 255-(b.getRGB(x,y)&255))); System.out.println(sb); }
  }
  public static void main(String[] args) {
    BasicStroke w3 = new BasicStroke(3f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND);
    cmp("horizontal line y=50.3, w3", new Line2D.Double(10, 50.3, 150, 50.3), w3, false);
    cmp("horizontal line y=50.3, w3, PURE", new Line2D.Double(10, 50.3, 150, 50.3), w3, true);
    cmp("diagonal line, w3", new Line2D.Double(10, 100, 150, 20), w3, false);
    cmp("diagonal line, w3, PURE", new Line2D.Double(10, 100, 150, 20), w3, true);
    Path2D.Double cubic = new Path2D.Double(); cubic.moveTo(10, 100); cubic.curveTo(40, -30, 90, 150, 150, 20);
    cmp("cubic alone, w3", cubic, w3, false);
    cmp("cubic alone, w3, PURE", cubic, w3, true);
    Path2D.Double joined = new Path2D.Double(); joined.moveTo(10, 100); joined.curveTo(40, -30, 90, 150, 150, 20); joined.lineTo(120, 110);
    cmp("cubic + line (round join), w3", joined, w3, false);
    cmp("circle w2", new Ellipse2D.Double(30, 25, 100, 60), new BasicStroke(2f), false);
    cmp("circle w2 PURE", new Ellipse2D.Double(30, 25, 100, 60), new BasicStroke(2f), true);
    cmp("circle w1 (thin)", new Ellipse2D.Double(30, 25, 100, 60), new BasicStroke(1f), false);
  }
}
