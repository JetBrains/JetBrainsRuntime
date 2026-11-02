import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.List;

/**
 * Per-row pixel statistics of one sheet against another: the sheets are TextReference's
 * (rows of 28 user pixels, scaled by the sheet's scale factor), the labels come from rows.txt.
 *   RowStats <reference.png> <candidate.png> <rows.txt> <scale> [label]
 * Prints, per row, differing pixels, pixels off by more than 32 levels, the maximum and mean
 * difference, then the whole-sheet line in PixStats' format.
 */
public class RowStats {
    public static void main(String[] a) throws Exception {
        BufferedImage p = ImageIO.read(new File(a[0])), q = ImageIO.read(new File(a[1]));
        List<String> rows = Files.readAllLines(new File(a[2]).toPath());
        double scale = Double.parseDouble(a[3]);
        String label = a.length > 4 ? a[4] : a[1];
        int rowH = (int) Math.round(28 * scale);
        System.out.println("== " + label);
        long allN = 0, allSum = 0; int all32 = 0, allMax = 0;
        for (int r = 0; r < rows.size() && (r + 1) * rowH <= p.getHeight(); r++) {
            int n = 0, b32 = 0, max = 0; long sum = 0;
            for (int y = r * rowH; y < (r + 1) * rowH; y++) {
                for (int x = 0; x < p.getWidth(); x++) {
                    int c = p.getRGB(x, y), d = q.getRGB(x, y), m = 0;
                    for (int s = 0; s < 24; s += 8) m = Math.max(m, Math.abs(((c >> s) & 255) - ((d >> s) & 255)));
                    if (m > 0) { n++; sum += m; }
                    if (m > 32) b32++;
                    max = Math.max(max, m);
                }
            }
            System.out.printf("  %-32s differing %5d  >32: %4d  max %3d  mean %5.1f%n", rows.get(r), n, b32, max, n == 0 ? 0 : (double) sum / n);
            allN += n; allSum += sum; all32 += b32; allMax = Math.max(allMax, max);
        }
        System.out.printf("  %-32s differing %5d  >32: %4d  max %3d  mean %5.1f%n", "(whole sheet)", allN, all32, allMax, allN == 0 ? 0 : (double) allSum / allN);
    }
}
