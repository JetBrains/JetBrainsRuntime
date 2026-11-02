import java.awt.*; import java.awt.geom.*; import java.awt.image.*; import java.util.function.Consumer;
public class PaintDbg {
  static final int W=160,H=120;
  static BufferedImage draw(int type, Consumer<Graphics2D> d) { BufferedImage img=new BufferedImage(W,H,type); Graphics2D g=img.createGraphics(); g.setColor(Color.WHITE); g.fillRect(0,0,W,H); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON); d.accept(g); g.dispose(); return img; }
  static void cmp(String what, Consumer<Graphics2D> d, boolean dumpRow) {
    BufferedImage a=draw(BufferedImage.TYPE_INT_ARGB_PRE,d), b=draw(BufferedImage.TYPE_3BYTE_BGR,d);
    int maxI=0,xi=0,yi=0,maxE=0; for (int y=1;y<H-1;y++) for (int x=1;x<W-1;x++) { int p=a.getRGB(x,y),q=b.getRGB(x,y); int dd=0; for (int s=0;s<=16;s+=8) dd=Math.max(dd,Math.abs(((p>>s)&255)-((q>>s)&255)));
      boolean interior = b.getRGB(x-1,y)!=0xffffffff && b.getRGB(x+1,y)!=0xffffffff && b.getRGB(x,y-1)!=0xffffffff && b.getRGB(x,y+1)!=0xffffffff && q!=0xffffffff;
      if (interior) { if (dd>maxI){maxI=dd;xi=x;yi=y;} } else maxE=Math.max(maxE,dd); }
    System.out.printf("%-48s interior max %3d at (%d,%d), edge max %d%n", what, maxI, xi, yi, maxE);
    if (dumpRow) { int y=yi; StringBuilder sb=new StringBuilder("   row "+y+" x="+(xi-6)+"..: skia/loops R,G,B: "); for (int x=Math.max(1,xi-6); x<=Math.min(W-2,xi+6); x++) { int p=a.getRGB(x,y),q=b.getRGB(x,y); sb.append(String.format("[%d,%d,%d / %d,%d,%d] ", (p>>16)&255,(p>>8)&255,p&255,(q>>16)&255,(q>>8)&255,q&255)); } System.out.println(sb); }
  }
  public static void main(String[] a) {
    Color[] three={new Color(220,40,40), new Color(40,200,60,128), new Color(30,60,220)}; float[] stops={0f,0.35f,1f};
    cmp("GradientPaint, acyclic", g->{ g.setPaint(new GradientPaint(20,20,Color.RED,140,100,Color.BLUE,false)); g.fill(new Ellipse2D.Double(10,10,140,100)); }, true);
    cmp("GradientPaint, acyclic, big rect (no AA edge)", g->{ g.setPaint(new GradientPaint(20,20,Color.RED,140,100,Color.BLUE,false)); g.fill(new Rectangle2D.Double(0,0,W,H)); }, true);
    cmp("GradientPaint, cyclic", g->{ g.setPaint(new GradientPaint(40,60,Color.YELLOW,70,60,new Color(0,0,0,0),true)); g.fill(new Rectangle2D.Double(5.5,5.5,149,109)); }, true);
    cmp("Linear REFLECT 3 stops rotated", g->{ g.rotate(0.4,80,60); g.setPaint(new LinearGradientPaint(new Point2D.Float(40,30), new Point2D.Float(100,90), stops, three, MultipleGradientPaint.CycleMethod.REFLECT)); g.fill(new RoundRectangle2D.Double(20,15,120,90,24,24)); }, true);
    cmp("Linear REPEAT own transform", g->{ g.setPaint(new LinearGradientPaint(new Point2D.Float(0,0), new Point2D.Float(30,0), stops, three, MultipleGradientPaint.CycleMethod.REPEAT, MultipleGradientPaint.ColorSpaceType.SRGB, AffineTransform.getRotateInstance(0.7))); g.fill(new Ellipse2D.Double(10,10,140,100)); }, true);
    cmp("Radial focus NO_CYCLE", g->{ g.setPaint(new RadialGradientPaint(new Point2D.Float(80,60), 55f, new Point2D.Float(60,45), stops, three, MultipleGradientPaint.CycleMethod.NO_CYCLE)); g.fill(new Ellipse2D.Double(15,5,130,110)); }, true);
  }
}
