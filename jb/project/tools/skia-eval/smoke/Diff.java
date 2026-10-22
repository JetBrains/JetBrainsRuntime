import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

public class Diff {
    public static void main(String[] a) throws Exception {
        BufferedImage x = ImageIO.read(new File(a[0])), y = ImageIO.read(new File(a[1]));
        int w = Math.min(x.getWidth(), y.getWidth()), h = Math.min(x.getHeight(), y.getHeight());
        long diff = 0, big = 0; int maxd = 0;
        BufferedImage d = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int j = 0; j < h; j++) for (int i = 0; i < w; i++) {
            int p = x.getRGB(i, j), q = y.getRGB(i, j);
            int dr = Math.abs(((p>>16)&255)-((q>>16)&255)), dg = Math.abs(((p>>8)&255)-((q>>8)&255)), db = Math.abs((p&255)-(q&255));
            int m = Math.max(dr, Math.max(dg, db));
            if (m > 0) diff++; if (m > 32) big++; maxd = Math.max(maxd, m);
            d.setRGB(i, j, m == 0 ? 0 : (0xFF0000 | (Math.min(255, m * 4) << 8)));
        }
        System.out.printf("%s vs %s: %dx%d, differing=%d (%.2f%%), >32=%d, max=%d%n", a[0], a[1], w, h, diff, 100.0 * diff / (w * (double) h), big, maxd);
        ImageIO.write(d, "png", new File(a[2]));
    }
}
