import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Stacks the same region of several sheets, zoomed, into one image for looking at:
 *   Crop <out.png> <x> <y> <w> <h> <zoom> <sheet.png>...
 * The last panel is the amplified absolute difference of the first two sheets (x4, inverted).
 */
public class Crop {
    public static void main(String[] a) throws Exception {
        int x = Integer.parseInt(a[1]), y = Integer.parseInt(a[2]), w = Integer.parseInt(a[3]), h = Integer.parseInt(a[4]), z = Integer.parseInt(a[5]);
        int n = a.length - 6;
        BufferedImage out = new BufferedImage(w * z, (h * z + 4) * (n + 1), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        BufferedImage[] in = new BufferedImage[n];
        for (int i = 0; i < n; i++) {
            in[i] = ImageIO.read(new File(a[6 + i]));
            g.drawImage(in[i].getSubimage(x, y, w, h), 0, i * (h * z + 4), w * z, h * z, null);
        }
        BufferedImage diff = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int j = 0; j < h; j++) for (int i = 0; i < w; i++) {
            int c = in[0].getRGB(x + i, y + j), d = in[1].getRGB(x + i, y + j), m = 0;
            for (int s = 0; s < 24; s += 8) m = Math.max(m, Math.abs(((c >> s) & 255) - ((d >> s) & 255)));
            int v = 255 - Math.min(255, m * 4);
            diff.setRGB(i, j, (v << 16) | (v << 8) | v);
        }
        g.drawImage(diff, 0, n * (h * z + 4), w * z, h * z, null);
        g.dispose();
        ImageIO.write(out, "png", new File(a[0]));
    }
}
