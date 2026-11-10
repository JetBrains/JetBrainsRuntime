import java.awt.image.*; import java.io.File; import javax.imageio.ImageIO;
public class Fringe { public static void main(String[] a) throws Exception {
  for (String f : a) {
    BufferedImage x = ImageIO.read(new File(f)); int ink = 0, colour = 0;
    for (int j = 0; j < x.getHeight(); j++) for (int i = 0; i < x.getWidth(); i++) {
      int p = x.getRGB(i, j), r = p >> 16 & 255, g = p >> 8 & 255, b = p & 255;
      if (r < 250 || g < 250 || b < 250) ink++;
      if (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) > 8) colour++;
    }
    System.out.println(f + ": ink " + ink + ", coloured " + colour);
  } } }
