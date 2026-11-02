import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Hot a b: bounding box and count of pixels differing by more than 32 levels, plus a coarse 100x100 histogram of where they are. */
public class Hot {
    public static void main(String[] s) throws Exception {
        BufferedImage a = ImageIO.read(new File(s[0])), b = ImageIO.read(new File(s[1]));
        int w = Math.min(a.getWidth(), b.getWidth()), h = Math.min(a.getHeight(), b.getHeight()) - 100;
        int x0 = w, y0 = h, x1 = 0, y1 = 0, n = 0;
        java.util.Map<String, Integer> cells = new java.util.TreeMap<>();
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int p = a.getRGB(x, y), q = b.getRGB(x, y), d = 0;
            for (int sh = 0; sh <= 16; sh += 8) d = Math.max(d, Math.abs(((p >> sh) & 255) - ((q >> sh) & 255)));
            if (d > 32) { n++; x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y); cells.merge((x / 100 * 100) + "," + (y / 100 * 100), 1, Integer::sum); }
        }
        System.out.println(n + " px >32, bbox " + x0 + "," + y0 + " - " + x1 + "," + y1);
        cells.entrySet().stream().sorted((e1, e2) -> e2.getValue() - e1.getValue()).limit(6).forEach(e -> System.out.println("  cell " + e.getKey() + ": " + e.getValue()));
    }
}
