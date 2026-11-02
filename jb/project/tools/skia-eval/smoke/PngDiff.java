import java.awt.image.BufferedImage; import javax.imageio.ImageIO; import java.io.File;
public class PngDiff {
    public static void main(String[] a) throws Exception {
        BufferedImage x = ImageIO.read(new File(a[0])), y = ImageIO.read(new File(a[1]));
        int w = x.getWidth(), h = x.getHeight(), n = 0, big = 0, max = 0, x0 = w, y0 = h, x1 = -1, y1 = -1; int[] rows = new int[h];
        for (int j = 0; j < h; j++) for (int i = 0; i < w; i++) { int p = x.getRGB(i, j), q = y.getRGB(i, j); int d = 0; for (int s = 0; s <= 24; s += 8) d = Math.max(d, Math.abs(((p >> s) & 255) - ((q >> s) & 255))); if (d > 0) { n++; rows[j]++; if (d > 32) big++; max = Math.max(max, d); x0 = Math.min(x0, i); y0 = Math.min(y0, j); x1 = Math.max(x1, i); y1 = Math.max(y1, j); } }
        System.out.printf("%s: %dx%d type %d, differing %d, >32: %d, max %d, box (%d,%d)-(%d,%d)%n", new File(a[0]).getName(), w, h, x.getType(), n, big, max, x0, y0, x1, y1);
        // the worst rows, with a character map of one: '.' same, ':' <=32, '#' >32
        int worst = 0; for (int j = 0; j < h; j++) if (rows[j] > rows[worst]) worst = j;
        StringBuilder sb = new StringBuilder(); for (int i = 0; i < w; i++) { int p = x.getRGB(i, worst), q = y.getRGB(i, worst); int d = 0; for (int s = 0; s <= 24; s += 8) d = Math.max(d, Math.abs(((p >> s) & 255) - ((q >> s) & 255))); sb.append(d == 0 ? '.' : d <= 32 ? ':' : '#'); }
        System.out.println("row " + worst + ": " + sb);
        StringBuilder on = new StringBuilder(), off = new StringBuilder(); for (int i = 0; i < w; i++) { on.append((x.getRGB(i, worst) & 0xffffff) == 0xffffff ? '.' : (x.getRGB(i, worst) & 0xff) < 128 ? '#' : '+'); off.append((y.getRGB(i, worst) & 0xffffff) == 0xffffff ? '.' : (y.getRGB(i, worst) & 0xff) < 128 ? '#' : '+'); }
        System.out.println("on : " + on); System.out.println("off: " + off);
    }
}
