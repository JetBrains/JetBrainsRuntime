import javax.imageio.ImageIO; import java.awt.image.BufferedImage; import java.io.File;
public class Ink { public static void main(String[] a) throws Exception { int rh = Integer.parseInt(a[0]); int[] rows = {0, 5, 10, 15}; String[] names = {"Inter", "JBMono", "Helvetica", "Menlo"};
  for (int k = 1; k < a.length; k++) { BufferedImage im = ImageIO.read(new File(a[k])); int bg = im.getRGB(2, 2) & 0xffffff; int bl = ((bg >> 16) & 255) + ((bg >> 8) & 255) + (bg & 255); StringBuilder sb = new StringBuilder(String.format("%-52s", a[k].replace(System.getProperty("user.dir") + "/", "")));
    for (int i = 0; i < rows.length; i++) { long ink = 0; for (int y = rows[i] * rh; y < (rows[i] + 1) * rh; y++) for (int x = 0; x < im.getWidth(); x++) { int p = im.getRGB(x, y); int l = ((p >> 16) & 255) + ((p >> 8) & 255) + (p & 255); ink += Math.abs(bl - l); } sb.append(String.format("  %s %7d", names[i], ink / 3)); }
    System.out.println(sb); } } }
