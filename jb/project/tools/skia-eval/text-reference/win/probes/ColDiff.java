import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Where along a sheet row two renderings differ: the row's columns in bands, with the maximum and
 * mean channel difference per band, to tell a position drift (growing along the text) from a
 * uniform raster difference.
 *   ColDiff <reference.png> <candidate.png> <row index> <row height px> <band width px> [label]
 */
public class ColDiff {
    public static void main(String[] a) throws Exception {
        BufferedImage p = ImageIO.read(new File(a[0])), q = ImageIO.read(new File(a[1]));
        int row = Integer.parseInt(a[2]), rowH = Integer.parseInt(a[3]), band = Integer.parseInt(a[4]);
        System.out.println("== " + (a.length > 5 ? a[5] : a[1]) + " row " + row);
        StringBuilder maxLine = new StringBuilder("  max  "), meanLine = new StringBuilder("  mean "), inkLine = new StringBuilder("  ink  ");
        for (int x0 = 0; x0 < p.getWidth(); x0 += band) {
            int max = 0, n = 0, ink = 0; long sum = 0;
            for (int y = row * rowH; y < (row + 1) * rowH; y++) {
                for (int x = x0; x < Math.min(x0 + band, p.getWidth()); x++) {
                    int c = p.getRGB(x, y), d = q.getRGB(x, y), m = 0;
                    for (int s = 0; s < 24; s += 8) m = Math.max(m, Math.abs(((c >> s) & 255) - ((d >> s) & 255)));
                    if (m > 0) { n++; sum += m; }
                    max = Math.max(max, m);
                    if ((c & 255) < 200) ink++;
                }
            }
            maxLine.append(String.format("%4d", max));
            meanLine.append(String.format("%4d", n == 0 ? 0 : Math.round((double) sum / n)));
            inkLine.append(String.format("%4d", ink));
        }
        System.out.println(inkLine);
        System.out.println(maxLine);
        System.out.println(meanLine);
    }
}
