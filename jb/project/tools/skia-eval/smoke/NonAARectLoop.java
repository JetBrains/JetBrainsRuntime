import java.awt.*; import java.awt.image.BufferedImage; import java.util.*;
/** Non-AA integer fillRect: microseconds per call, median of frames. java [-Dsun.java2d.skia.raster=true] NonAARectLoop [type] */
public class NonAARectLoop {
  public static void main(String[] a) {
    int type = a.length > 0 ? Integer.parseInt(a[0]) : BufferedImage.TYPE_INT_ARGB_PRE; int W = 1200, H = 900, N = 2000;
    BufferedImage img = new BufferedImage(W, H, type); double[] us = new double[a.length > 1 ? Integer.parseInt(a[1]) : 40]; Random rnd = new Random(1);
    for (int f = 0; f < us.length; f++) {
      Graphics2D g = img.createGraphics(); g.setColor(Color.WHITE); g.fillRect(0, 0, W, H); rnd.setSeed(f);
      long t0 = System.nanoTime();
      for (int i = 0; i < N; i++) { g.setColor(new Color(rnd.nextInt(0xffffff))); g.fillRect(rnd.nextInt(W - 40), rnd.nextInt(H - 40), 30, 20); }
      us[f] = (System.nanoTime() - t0) / 1e3 / N; g.dispose();
    }
    double[] t = Arrays.copyOfRange(us, 10, us.length); Arrays.sort(t);
    System.out.printf(Locale.ROOT, "%s: %.3f us per fillRect (median), min %.3f%n", System.getProperty("sun.java2d.skia.raster", "loops") + " probe=" + System.getenv().getOrDefault("J2D_SKIA_RASTER_PROBE", "-"), t[t.length / 2], t[0]);
  }
}
