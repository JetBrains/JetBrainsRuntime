import javax.imageio.ImageIO; import java.awt.image.BufferedImage; import java.io.File;
/** LCD fringe statistics of sheets: pixels whose channel spread (max - min of R, G, B, after removing the paper's own spread)
 *  exceeds 48, their mean and maximum spread, and the ink (sum |pixel - paper| over the channel average).
 *  Usage: FringeStats <png>... */
public class FringeStats {
    public static void main(String[] a) throws Exception {
        System.out.printf("%-70s %9s %9s %6s %5s %10s%n", "sheet", "ink px", "fringe>48", "mean", "max", "ink sum");
        for (String f : a) {
            BufferedImage im = ImageIO.read(new File(f));
            int bg = im.getRGB(1, 1); int br = (bg >> 16) & 255, bgG = (bg >> 8) & 255, bb = bg & 255;
            int paperSpread = Math.max(br, Math.max(bgG, bb)) - Math.min(br, Math.min(bgG, bb));
            long inkPx = 0, fringe = 0, spreadSum = 0, inkSum = 0; int maxSpread = 0;
            for (int y = 0; y < im.getHeight(); y++) for (int x = 0; x < im.getWidth(); x++) {
                int p = im.getRGB(x, y); int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
                int d = Math.abs(r - br) + Math.abs(g - bgG) + Math.abs(b - bb);
                if (d == 0) continue;
                inkPx++; inkSum += d / 3;
                int spread = Math.max(0, Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) - paperSpread);
                if (spread > 48) { fringe++; spreadSum += spread; maxSpread = Math.max(maxSpread, spread); }
            }
            System.out.printf("%-70s %9d %9d %6.1f %5d %10d%n", f.replace(File.separatorChar, '/').replaceAll(".*out/", ""), inkPx, fringe, fringe == 0 ? 0 : (double) spreadSum / fringe, maxSpread, inkSum);
        }
    }
}
