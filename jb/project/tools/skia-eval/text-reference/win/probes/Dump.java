import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Gray values (blue channel) of a region of two sheets side by side, for looking at one glyph.
 *   Dump <a.png> <b.png> <x> <y> <w> <h>
 */
public class Dump {
    public static void main(String[] a) throws Exception {
        BufferedImage p = ImageIO.read(new File(a[0])), q = ImageIO.read(new File(a[1]));
        int x0 = Integer.parseInt(a[2]), y0 = Integer.parseInt(a[3]), w = Integer.parseInt(a[4]), h = Integer.parseInt(a[5]);
        for (int y = y0; y < y0 + h; y++) {
            StringBuilder sb = new StringBuilder(String.format("%4d ", y));
            for (int x = x0; x < x0 + w; x++) sb.append(String.format("%4d", p.getRGB(x, y) & 255));
            sb.append("  |");
            for (int x = x0; x < x0 + w; x++) sb.append(String.format("%4d", q.getRGB(x, y) & 255));
            System.out.println(sb);
        }
    }
}
