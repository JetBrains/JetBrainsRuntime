import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.List;

/**
 * Per-row, per-channel statistics of one sheet against another (the ClearType-in-colour question:
 * is the per-channel difference visible?): for R, G, B the max and mean difference over the
 * differing pixels of the row, then the whole sheet; and the position of the worst pixel.
 *   ChannelStats <reference.png> <candidate.png> <rows.txt> <scale> [label]
 */
public class ChannelStats {
    public static void main(String[] a) throws Exception {
        BufferedImage p = ImageIO.read(new File(a[0])), q = ImageIO.read(new File(a[1]));
        List<String> rows = Files.readAllLines(new File(a[2]).toPath());
        double scale = Double.parseDouble(a[3]);
        int rowH = (int) Math.round(28 * scale);
        System.out.println("== " + (a.length > 4 ? a[4] : a[1]));
        long[] allN = new long[3], allSum = new long[3]; int[] allMax = new int[3]; int all32 = 0;
        for (int r = 0; r < rows.size() && (r + 1) * rowH <= p.getHeight(); r++) {
            long[] n = new long[3], sum = new long[3]; int[] max = new int[3]; int b32 = 0, worstX = 0, worstY = 0, worst = 0;
            for (int y = r * rowH; y < (r + 1) * rowH; y++) {
                for (int x = 0; x < p.getWidth(); x++) {
                    int c = p.getRGB(x, y), d = q.getRGB(x, y), m = 0;
                    for (int ch = 0; ch < 3; ch++) {
                        int s = 16 - 8 * ch, diff = Math.abs(((c >> s) & 255) - ((d >> s) & 255));
                        if (diff > 0) { n[ch]++; sum[ch] += diff; }
                        max[ch] = Math.max(max[ch], diff);
                        m = Math.max(m, diff);
                    }
                    if (m > 32) b32++;
                    if (m > worst) { worst = m; worstX = x; worstY = y; }
                }
            }
            System.out.printf("  %-36s R max %3d mean %5.1f | G max %3d mean %5.1f | B max %3d mean %5.1f | >32: %4d  worst at %d,%d%n", rows.get(r),
                              max[0], n[0] == 0 ? 0 : (double) sum[0] / n[0], max[1], n[1] == 0 ? 0 : (double) sum[1] / n[1], max[2], n[2] == 0 ? 0 : (double) sum[2] / n[2], b32, worstX, worstY);
            for (int ch = 0; ch < 3; ch++) { allN[ch] += n[ch]; allSum[ch] += sum[ch]; allMax[ch] = Math.max(allMax[ch], max[ch]); }
            all32 += b32;
        }
        System.out.printf("  %-36s R max %3d mean %5.1f | G max %3d mean %5.1f | B max %3d mean %5.1f | >32: %4d%n", "(whole sheet)",
                          allMax[0], allN[0] == 0 ? 0 : (double) allSum[0] / allN[0], allMax[1], allN[1] == 0 ? 0 : (double) allSum[1] / allN[1], allMax[2], allN[2] == 0 ? 0 : (double) allSum[2] / allN[2], all32);
    }
}
