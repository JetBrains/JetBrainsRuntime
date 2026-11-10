import java.awt.image.*; import java.io.File; import javax.imageio.ImageIO;
/** Pixel-wise: the Skia scaler's output against the JDK scaler's (same pipe, same text), binned by the JDK's value. */
public class Curve { public static void main(String[] a) throws Exception {
  BufferedImage j = ImageIO.read(new File(a[0])), s = ImageIO.read(new File(a[1]));
  double[] sum = new double[17]; int[] n = new int[17]; int aligned = 0, total = 0;
  for (int y = 0; y < j.getHeight(); y++) for (int x = 0; x < j.getWidth(); x++) {
    int vj = 255 - (j.getRGB(x, y) >> 8 & 255), vs = 255 - (s.getRGB(x, y) >> 8 & 255);
    if (vj == 0 && vs == 0) continue;
    total++; if (Math.abs(vj - vs) < 48) aligned++;
    int b = vj * 16 / 255; sum[b] += vs; n[b]++;
  }
  System.out.println("pixels with ink " + total + ", within 48 levels " + aligned);
  for (int b = 0; b <= 16; b++) if (n[b] > 0) System.out.printf("JDK %3d..%3d: Skia mean %6.1f  (n %d)%n", b * 255 / 16, (b + 1) * 255 / 16, sum[b] / n[b], n[b]);
} }
