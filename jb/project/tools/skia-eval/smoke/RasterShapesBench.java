import java.awt.*; import java.awt.geom.*; import java.awt.image.*; import java.util.*;
/** AA shapes into a BufferedImage: ms per frame, median of the last runs. java [-Dsun.java2d.skia.raster=true] RasterShapesBench [type] */
public class RasterShapesBench {
  public static void main(String[] a) {
    int type = a.length > 0 ? Integer.parseInt(a[0]) : BufferedImage.TYPE_INT_ARGB_PRE;
    int W = 1200, H = 900; BufferedImage img = new BufferedImage(W, H, type);
    System.out.println("surface " + sun.java2d.SurfaceData.getPrimarySurfaceData(img).getClass().getSimpleName() + ", type " + type);
    Random rnd = new Random(1);
    String[] names = {"rrect 24x24 fills x2000", "oval 40x40 fills x2000", "path (cubic blob) fills x500", "stroked rrect 2px x1000", "aa rect fills x2000 (converter)", "gradient rrect 24x24 fills x2000", "radial-gradient oval fills x500", "icon 16->20 bilinear x2000", "image 64x48 rotated bilinear x500", "image 64x48 scaled 1.5 bicubic x500", "text 13pt gray x400 strings", "text 13pt LCD x400 strings", "non-AA oval 40x40 fills x2000", "non-AA rrect 24x24 fills x2000", "non-AA rect fills x2000", "non-AA stroked rrect 2px x1000"};
    Font font = new Font(Font.DIALOG, Font.PLAIN, 13); String line = "The quick brown fox jumps over the lazy dog 0123456789 {}[]();";
    BufferedImage icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), photo = new BufferedImage(64, 48, BufferedImage.TYPE_INT_ARGB_PRE);
    for (BufferedImage s : new BufferedImage[] {icon, photo}) { Graphics2D g = s.createGraphics(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON); g.setColor(new Color(30, 80, 200)); g.fillOval(1, 1, s.getWidth() - 2, s.getHeight() - 2); g.setColor(new Color(240, 200, 40, 160)); g.fillRect(3, 3, s.getWidth() / 2, s.getHeight() / 2); g.dispose(); }
    for (int k = 0; k < names.length; k++) {
      double[] ms = new double[9];
      for (int rep = 0; rep < ms.length; rep++) {
        Graphics2D g = img.createGraphics(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE); g.fillRect(0, 0, W, H); rnd.setSeed(rep);
        long t0 = System.nanoTime();
        switch (k) {
          case 0: for (int i = 0; i < 2000; i++) { g.setColor(new Color(rnd.nextInt(0xffffff))); g.fill(new RoundRectangle2D.Double(rnd.nextInt(W - 30) + rnd.nextDouble(), rnd.nextInt(H - 30) + rnd.nextDouble(), 24, 24, 8, 8)); } break;
          case 1: for (int i = 0; i < 2000; i++) { g.setColor(new Color(rnd.nextInt(0xffffff))); g.fill(new Ellipse2D.Double(rnd.nextInt(W - 50) + rnd.nextDouble(), rnd.nextInt(H - 50), 40, 40)); } break;
          case 2: for (int i = 0; i < 500; i++) { g.setColor(new Color(rnd.nextInt(0xffffff))); double x = rnd.nextInt(W - 120), y = rnd.nextInt(H - 120); Path2D.Double p = new Path2D.Double(); p.moveTo(x, y + 60); p.curveTo(x + 20, y - 20, x + 100, y + 140, x + 120, y + 20); p.lineTo(x + 100, y + 110); p.closePath(); g.fill(p); } break;
          case 3: g.setStroke(new BasicStroke(2f)); for (int i = 0; i < 1000; i++) { g.setColor(new Color(rnd.nextInt(0xffffff))); g.draw(new RoundRectangle2D.Double(rnd.nextInt(W - 60) + rnd.nextDouble(), rnd.nextInt(H - 40), 50, 30, 6, 6)); } break;
          case 4: for (int i = 0; i < 2000; i++) { g.setColor(new Color(rnd.nextInt(0xffffff))); g.fill(new Rectangle2D.Double(rnd.nextInt(W - 40) + rnd.nextDouble(), rnd.nextInt(H - 40) + rnd.nextDouble(), 30, 20)); } break;
          case 5: for (int i = 0; i < 2000; i++) { double x = rnd.nextInt(W - 30), y = rnd.nextInt(H - 30); g.setPaint(new GradientPaint((float) x, (float) y, new Color(rnd.nextInt(0xffffff)), (float) x + 24, (float) y + 24, new Color(rnd.nextInt(0xffffff)))); g.fill(new RoundRectangle2D.Double(x, y, 24, 24, 8, 8)); } break;
          case 7: g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR); for (int i = 0; i < 2000; i++) { g.drawImage(icon, rnd.nextInt(W - 20), rnd.nextInt(H - 20), 20, 20, null); } break;
          case 8: g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR); for (int i = 0; i < 500; i++) { AffineTransform t = AffineTransform.getTranslateInstance(rnd.nextInt(W - 100), rnd.nextInt(H - 100)); t.rotate(rnd.nextDouble()); g.drawImage(photo, t, null); } break;
          case 9: g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC); for (int i = 0; i < 500; i++) { AffineTransform t = AffineTransform.getTranslateInstance(rnd.nextInt(W - 100), rnd.nextInt(H - 80)); t.scale(1.5, 1.5); g.drawImage(photo, t, null); } break;
          case 10: g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON); g.setFont(font); g.setColor(Color.BLACK); for (int i = 0; i < 400; i++) g.drawString(line, rnd.nextInt(W - 500), 16 + rnd.nextInt(H - 20)); break;
          case 11: g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB); g.setFont(font); g.setColor(Color.BLACK); for (int i = 0; i < 400; i++) g.drawString(line, rnd.nextInt(W - 500), 16 + rnd.nextInt(H - 20)); break;
          case 12: g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF); for (int i = 0; i < 2000; i++) { g.setColor(new Color(rnd.nextInt(0xffffff))); g.fill(new Ellipse2D.Double(rnd.nextInt(W - 50) + rnd.nextDouble(), rnd.nextInt(H - 50), 40, 40)); } break;
          case 13: g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF); for (int i = 0; i < 2000; i++) { g.setColor(new Color(rnd.nextInt(0xffffff))); g.fill(new RoundRectangle2D.Double(rnd.nextInt(W - 30) + rnd.nextDouble(), rnd.nextInt(H - 30) + rnd.nextDouble(), 24, 24, 8, 8)); } break;
          case 14: g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF); for (int i = 0; i < 2000; i++) { g.setColor(new Color(rnd.nextInt(0xffffff))); g.fillRect(rnd.nextInt(W - 40), rnd.nextInt(H - 40), 30, 20); } break;
          case 15: g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF); g.setStroke(new BasicStroke(2f)); for (int i = 0; i < 1000; i++) { g.setColor(new Color(rnd.nextInt(0xffffff))); g.draw(new RoundRectangle2D.Double(rnd.nextInt(W - 60) + rnd.nextDouble(), rnd.nextInt(H - 40), 50, 30, 6, 6)); } break;
          case 6: for (int i = 0; i < 500; i++) { double x = rnd.nextInt(W - 60), y = rnd.nextInt(H - 60); g.setPaint(new RadialGradientPaint(new java.awt.geom.Point2D.Double(x + 30, y + 30), 30f, new float[] {0f, 0.5f, 1f}, new Color[] {new Color(rnd.nextInt(0xffffff)), new Color(rnd.nextInt(0xffffff), true), new Color(rnd.nextInt(0xffffff))})); g.fill(new Ellipse2D.Double(x, y, 60, 60)); } break;
        }
        img.getRGB(0, 0);   // nothing async here, but keep the pattern
        ms[rep] = (System.nanoTime() - t0) / 1e6; g.dispose();
      }
      double[] tail = Arrays.copyOfRange(ms, 3, ms.length); Arrays.sort(tail);
      System.out.printf(Locale.ROOT, "%-34s %7.2f ms%n", names[k], tail[tail.length / 2]);
    }
  }
}
