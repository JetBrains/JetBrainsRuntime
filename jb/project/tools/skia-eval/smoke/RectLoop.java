import java.awt.*; import java.awt.geom.*; import java.awt.image.*; import java.util.*;
public class RectLoop { public static void main(String[] a) throws Exception {
  BufferedImage img = new BufferedImage(1200, 900, BufferedImage.TYPE_INT_ARGB_PRE); Random rnd = new Random(1);
  Graphics2D g = img.createGraphics(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
  long end = System.nanoTime() + 8_000_000_000L; long n = 0;
  while (System.nanoTime() < end) { g.setColor(new Color(rnd.nextInt(0xffffff))); g.fill(new Rectangle2D.Double(rnd.nextInt(1160) + rnd.nextDouble(), rnd.nextInt(860) + rnd.nextDouble(), 30, 20)); n++; }
  System.out.println(n / 8 + " rects per second"); } }
