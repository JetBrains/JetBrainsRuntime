import java.awt.image.*; import java.io.File; import javax.imageio.ImageIO;
/** The masks behind two LCD renderings (black on white, the loops' blend inverted with gamma g), and how they relate. */
public class Masks { public static void main(String[] a) throws Exception {
  BufferedImage j = ImageIO.read(new File(a[0])), s = ImageIO.read(new File(a[1]));
  double g = Double.parseDouble(a[2]);
  java.util.List<double[]> pairs = new java.util.ArrayList<>();
  for (int y = 0; y < j.getHeight(); y++) for (int x = 0; x < j.getWidth(); x++) {
    double oj = (j.getRGB(x, y) >> 8 & 255) / 255.0, os = (s.getRGB(x, y) >> 8 & 255) / 255.0;
    if (oj > 0.995 && os > 0.995) continue;
    pairs.add(new double[] {1 - Math.pow(oj, g), 1 - Math.pow(os, g)});
  }
  // candidates: mJ = mS^k ; report the best k and its rms, and the binned relation
  double best = 0, bestErr = 1e9;
  for (double k = 0.2; k <= 2.0; k += 0.01) { double e = 0; for (double[] p : pairs) { double d = p[0] - Math.pow(p[1], k); e += d * d; } if (e < bestErr) { bestErr = e; best = k; } }
  double e0 = 0; for (double[] p : pairs) e0 += (p[0] - p[1]) * (p[0] - p[1]);
  System.out.printf("gamma %.2f: identity rms %.4f; best mJ = mS^%.2f rms %.4f%n", g, Math.sqrt(e0 / pairs.size()), best, Math.sqrt(bestErr / pairs.size()));
  double[] sum = new double[11]; int[] n = new int[11];
  for (double[] p : pairs) { int b = (int) (p[1] * 10); sum[b] += p[0]; n[b]++; }
  for (int b = 0; b <= 10; b++) if (n[b] > 0) System.out.printf("  mS %.1f..%.1f: mJ mean %.3f (n %d)%n", b / 10.0, (b + 1) / 10.0, sum[b] / n[b], n[b]);
} }
