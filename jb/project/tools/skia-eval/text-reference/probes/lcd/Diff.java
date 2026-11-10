import java.awt.image.*; import java.io.File; import javax.imageio.ImageIO;
public class Diff { public static void main(String[] a) throws Exception {
  BufferedImage x = ImageIO.read(new File(a[0])), y = ImageIO.read(new File(a[1]));
  int n = 0; StringBuilder sb = new StringBuilder();
  for (int j = 0; j < x.getHeight(); j++) for (int i = 0; i < x.getWidth(); i++) if (x.getRGB(i, j) != y.getRGB(i, j)) {
    if (n++ < 12) sb.append(String.format(" (%d,%d) %06x/%06x", i, j, x.getRGB(i, j) & 0xffffff, y.getRGB(i, j) & 0xffffff));
  }
  System.out.println(n + " pixels differ:" + sb);
} }
