import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Deviation clusters: connected regions of pixels where an arm's scene capture differs from the
 * software arm by more than a threshold, ranked by weight (sum of differences), with a signature
 * (size, shape, max) that groups the same kind of deviation, and stacked crops for review.
 * <pre>
 *   java DeviationClusters --out scenes --theme dark --scale 2 --scene editor --arm A1-blend
 *                          [--threshold 4] [--top 24] [--ignore x,y,w,h]...
 * </pre>
 */
public class DeviationClusters {

    record Cluster(int minX, int minY, int maxX, int maxY, int count, int max, long weight) {
        int w() { return maxX - minX + 1; }
        int h() { return maxY - minY + 1; }
        /** A coarse kind: dot, horizontal line, vertical line, blob. */
        String kind() {
            if (count <= 2) return "dot";
            if (h() <= 2 && w() >= 6) return "h-line";
            if (w() <= 2 && h() >= 6) return "v-line";
            if (count < 0.25 * w() * h()) return "outline";
            return "blob";
        }
    }

    public static void main(String[] args) throws Exception {
        File out = new File("out");
        String theme = "dark", scale = "2", scene = "editor", arm = "A1-blend", base = "A0-software";
        int threshold = 4, top = 24;
        List<Rectangle> ignore = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--out" -> out = new File(args[++i]);
                case "--theme" -> theme = args[++i];
                case "--scale" -> scale = args[++i];
                case "--scene" -> scene = args[++i];
                case "--arm" -> arm = args[++i];
                case "--base" -> base = args[++i];
                case "--threshold" -> threshold = Integer.parseInt(args[++i]);
                case "--top" -> top = Integer.parseInt(args[++i]);
                case "--ignore" -> { String[] f = args[++i].split(","); ignore.add(new Rectangle(Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]))); }
                default -> throw new IllegalArgumentException(args[i]);
            }
        }
        String tag = theme + "-" + scale + "x";
        BufferedImage a = ImageIO.read(new File(out, base + "/" + tag + "/" + scene + ".png"));
        BufferedImage b = ImageIO.read(new File(out, arm + "/" + tag + "/" + scene + ".png"));
        int w = Math.min(a.getWidth(), b.getWidth()), h = Math.min(a.getHeight(), b.getHeight()) - 100;   // no taskbar
        int[] diff = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean skip = false;
                for (Rectangle r : ignore) if (r.contains(x, y)) { skip = true; break; }
                diff[y * w + x] = skip ? 0 : maxChannelDiff(a.getRGB(x, y), b.getRGB(x, y));
            }
        }
        // connected components (8-neighbourhood) of pixels above the threshold, bridged across one pixel
        int[] label = new int[w * h];
        List<Cluster> clusters = new ArrayList<>();
        int[] stack = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if (diff[i] <= threshold || label[i] != 0) continue;
                int id = clusters.size() + 1;
                int sp = 0; stack[sp++] = i; label[i] = id;
                int minX = x, minY = y, maxX = x, maxY = y, count = 0, max = 0; long weight = 0;
                while (sp > 0) {
                    int p = stack[--sp];
                    int px = p % w, py = p / w;
                    count++; max = Math.max(max, diff[p]); weight += diff[p];
                    minX = Math.min(minX, px); maxX = Math.max(maxX, px); minY = Math.min(minY, py); maxY = Math.max(maxY, py);
                    for (int dy = -2; dy <= 2; dy++) {
                        for (int dx = -2; dx <= 2; dx++) {
                            int nx = px + dx, ny = py + dy;
                            if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                            int n = ny * w + nx;
                            if (diff[n] > threshold && label[n] == 0) { label[n] = id; stack[sp++] = n; }
                        }
                    }
                }
                clusters.add(new Cluster(minX, minY, maxX, maxY, count, max, weight));
            }
        }
        clusters.sort((p, q) -> Long.compare(q.weight, p.weight));

        // signature histogram: kind x max bucket
        java.util.Map<String, int[]> kinds = new java.util.TreeMap<>();
        for (Cluster c : clusters) {
            String key = c.kind() + " max" + (c.max <= 8 ? "<=8" : c.max <= 32 ? "9-32" : ">32");
            int[] v = kinds.computeIfAbsent(key, k -> new int[2]);
            v[0]++; v[1] += c.count;
        }
        File dir = new File(out, "clusters-" + tag + "/" + scene + "-" + arm);
        dir.mkdirs();
        try (PrintWriter pw = new PrintWriter(new File(out, "clusters-" + tag + "-" + scene + "-" + arm + ".md"))) {
            pw.println("# Deviation clusters: " + arm + " vs " + base + ", " + scene + " " + tag + " (threshold " + threshold + ")");
            pw.println();
            pw.println("| kind | clusters | pixels |");
            pw.println("|---|---|---|");
            for (var e : kinds.entrySet()) pw.printf("| %s | %d | %d |%n", e.getKey(), e.getValue()[0], e.getValue()[1]);
            pw.println();
            pw.println("Top clusters by weight (sum of differences): position, size, pixels, max, kind.");
            pw.println();
            int n = 0;
            for (Cluster c : clusters) {
                if (n++ >= top) break;
                String crop = writeCrop(dir, a, b, diff, w, c, n, out, arm, tag, scene);
                pw.printf("### %d. (%d,%d) %dx%d, %d px, max %d, weight %d, %s%n%n![](clusters-%s/%s-%s/%s)%n%n", n, c.minX, c.minY, c.w(), c.h(),
                          c.count, c.max, c.weight, c.kind(), tag, scene, arm, crop);
            }
        }
        System.out.println(scene + " " + arm + " " + tag + ": " + clusters.size() + " clusters; kinds: " + kinds.keySet());
        int shown = 0;
        for (Cluster c : clusters) { if (shown++ >= Math.min(top, 12)) break; System.out.printf("  %2d. (%d,%d) %dx%d %d px max %d weight %d %s%n", shown, c.minX, c.minY, c.w(), c.h(), c.count, c.max, c.weight, c.kind()); }
    }

    /** Baseline, arm, other GPU arms if present, and heat, 4x, around the cluster with a margin. */
    static String writeCrop(File dir, BufferedImage a, BufferedImage b, int[] diff, int w, Cluster c, int n, File out, String arm, String tag, String scene) throws Exception {
        int margin = 12, zoom = 4;
        int x0 = Math.max(0, c.minX - margin), y0 = Math.max(0, c.minY - margin);
        int cw = Math.min(a.getWidth() - x0, Math.min(c.w() + 2 * margin, 220)), ch = Math.min(a.getHeight() - y0 - 100, Math.min(c.h() + 2 * margin, 120));
        List<String> names = new ArrayList<>(List.of("software", arm));
        List<BufferedImage> imgs = new ArrayList<>(List.of(a, b));
        for (String other : new String[] { "A2-twopass", "A3-accumulate" }) {
            File f = new File(out, other + "/" + tag + "/" + scene + ".png");
            if (!other.equals(arm) && f.exists()) { names.add(other); imgs.add(ImageIO.read(f)); }
        }
        int rows = imgs.size() + 1, labelH = 16;
        BufferedImage img = new BufferedImage(cw * zoom, (ch * zoom + labelH) * rows, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        for (int r = 0; r < rows; r++) {
            int top = r * (ch * zoom + labelH);
            g.setColor(Color.BLACK);
            g.drawString(r < imgs.size() ? names.get(r) + (r == 0 ? String.format("   #%d at %d,%d %dx%d max %d", n, c.minX, c.minY, c.w(), c.h(), c.max) : "") : "difference x4",
                         4, top + 12);
            if (r < imgs.size()) {
                g.drawImage(imgs.get(r), 0, top + labelH, cw * zoom, top + labelH + ch * zoom, x0, y0, x0 + cw, y0 + ch, null);
            } else {
                for (int y = 0; y < ch; y++) for (int x = 0; x < cw; x++) {
                    int d = diff[(y0 + y) * w + (x0 + x)];
                    int v = d == 0 ? 0xFFFFFF : (0xFF0000 | ((255 - Math.min(255, d * 4)) << 8) | (255 - Math.min(255, d * 4)));
                    g.setColor(new Color(v)); g.fillRect(x * zoom, top + labelH + y * zoom, zoom, zoom);
                }
            }
        }
        g.dispose();
        String name = String.format("c%02d-%d-%d.png", n, c.minX, c.minY);
        ImageIO.write(img, "png", new File(dir, name));
        return name;
    }

    static int maxChannelDiff(int p, int q) {
        int r = Math.abs(((p >> 16) & 255) - ((q >> 16) & 255));
        int g = Math.abs(((p >> 8) & 255) - ((q >> 8) & 255));
        int b = Math.abs((p & 255) - (q & 255));
        return Math.max(r, Math.max(g, b));
    }
}
