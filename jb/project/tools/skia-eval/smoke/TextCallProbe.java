import java.awt.*; import java.awt.image.*; import javax.imageio.ImageIO; import java.io.File;
public class TextCallProbe { public static void main(String[] a) throws Exception {
  int strings = Integer.parseInt(a[0]); int len = 12, W = 1600, H = 1000;
  GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
  VolatileImage vi = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
  Font font = new Font("Helvetica Neue", Font.PLAIN, 13); int cols = Math.max(1, W / (len * 8 + 16));
  for (int rep = 0; rep < 3; rep++) {
    long t0 = System.nanoTime(); Graphics2D g = vi.createGraphics(); g.setColor(Color.WHITE); g.fillRect(0,0,W,H);
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    g.setColor(Color.BLACK); g.setFont(font);
    for (int i = 0; i < strings; i++) { float x = 8 + (i % cols) * (len * 8 + 16), y = 16 + (i / cols) * 16 % (H - 20); g.drawString("Hamburgefonts" + i, x, y); }
    g.dispose(); long t1 = System.nanoTime(); BufferedImage snap = vi.getSnapshot(); long t2 = System.nanoTime();
    int ink = 0; for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) if ((snap.getRGB(x,y) & 0xffffff) != 0xffffff) ink++;
    System.out.printf("rep %d strings=%d: caller %.2f ms, +snapshot %.2f ms, ink px %d, gc %s%n", rep, strings, (t1-t0)/1e6, (t2-t1)/1e6, ink, gc.getClass().getSimpleName());
    if (rep == 2) ImageIO.write(snap, "png", new File("build/skia-smoke/out-perf/probe-" + strings + ".png"));
  } } }
