import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.TreeSet;

/**
 * Distinct gray levels per sheet row (blue channel): how finely the rasterizer quantizes coverage.
 *   Levels <sheet.png> <rows.txt> <scale> [label]
 */
public class Levels {
    public static void main(String[] a) throws Exception {
        BufferedImage p = ImageIO.read(new File(a[0]));
        List<String> rows = Files.readAllLines(new File(a[1]).toPath());
        int scale = Integer.parseInt(a[2]), rowH = 28 * scale;
        System.out.println("== " + (a.length > 3 ? a[3] : a[0]));
        for (int r = 0; r < rows.size() && (r + 1) * rowH <= p.getHeight(); r++) {
            TreeSet<Integer> levels = new TreeSet<>();
            for (int y = r * rowH; y < (r + 1) * rowH; y++) for (int x = 0; x < p.getWidth(); x++) levels.add(p.getRGB(x, y) & 255);
            System.out.printf("  %-36s %2d levels: %s%n", rows.get(r), levels.size(), levels);
        }
    }
}
