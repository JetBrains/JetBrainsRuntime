import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Compares IDE scene captures of several arms with the baseline arm (instrument 3b): per scene
 * the statistics of the differing pixels over the IDE frame (the taskbar row is excluded), a heat
 * map, and 3x crops of named regions with the arms stacked; {@code scenes-<theme>-<scale>.md}.
 * <pre>
 *   java SceneDiff --out out/2026-10-20/scenes --theme dark --scale 1 --base A0-software --arms A2-twopass A3-accumulate
 *                  [--regions regions-1x.txt]
 * </pre>
 * {@code regions} lines: {@code name x y w h} in device pixels of the capture; without the file the
 * crops are the eight most different 400x120 windows of each scene, found by a coarse grid scan.
 */
public class SceneDiff {

    static final int TASKBAR = 100;   // device pixels at the bottom of a capture that belong to Windows

    public static void main(String[] args) throws Exception {
        File out = new File("out");
        String theme = "dark", scale = "1", base = "A0-software";
        List<String> arms = new ArrayList<>();
        File regionsFile = null;
        List<Rectangle> ignore = new ArrayList<>();   // device-pixel rectangles left out of the statistics (the project badge)
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--out" -> out = new File(args[++i]);
                case "--theme" -> theme = args[++i];
                case "--scale" -> scale = args[++i];
                case "--base" -> base = args[++i];
                case "--arms" -> { while (i + 1 < args.length && !args[i + 1].startsWith("--")) arms.add(args[++i]); }
                case "--regions" -> regionsFile = new File(args[++i]);
                case "--ignore" -> { String[] f = args[++i].split(","); ignore.add(new Rectangle(Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]))); }
                default -> throw new IllegalArgumentException(args[i]);
            }
        }
        String tag = theme + "-" + scale + "x";
        File baseDir = new File(out, base + "/" + tag);
        File[] scenes = baseDir.listFiles((d, n) -> n.endsWith(".png"));
        if (scenes == null) throw new IllegalStateException("no captures in " + baseDir);
        java.util.Arrays.sort(scenes);
        Map<String, Rectangle> regions = new LinkedHashMap<>();
        if (regionsFile != null) {
            for (String line : Files.readAllLines(regionsFile.toPath())) {
                String[] f = line.trim().split("\\s+");
                if (f.length == 5 && !line.startsWith("#")) {
                    regions.put(f[0], new Rectangle(Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]), Integer.parseInt(f[4])));
                }
            }
        }
        File reviewDir = new File(out, "review-" + tag);
        reviewDir.mkdirs();
        try (PrintWriter pw = new PrintWriter(new File(out, "scenes-" + tag + ".md"))) {
            pw.println("# IDE scenes " + tag + ": " + String.join(", ", arms) + " against " + base);
            pw.println();
            pw.println("Per scene and arm over the IDE frame: pixels that differ, pixels beyond 32 levels, max, mean over differing pixels.");
            pw.println();
            pw.print("| scene |");
            for (String arm : arms) pw.print(" " + arm + " differing / >32 / max / mean |");
            pw.println();
            pw.print("|---|");
            for (String ignored : arms) pw.print("---|");
            pw.println();
            Map<String, List<String>> cropsPerScene = new LinkedHashMap<>();
            for (File sceneFile : scenes) {
                String scene = sceneFile.getName().replace(".png", "");
                BufferedImage b = ImageIO.read(sceneFile);
                Map<String, BufferedImage> imgs = new LinkedHashMap<>();
                pw.print("| " + scene + " |");
                for (String arm : arms) {
                    File f = new File(out, arm + "/" + tag + "/" + scene + ".png");
                    if (!f.exists()) { pw.print(" missing |"); continue; }
                    BufferedImage a = ImageIO.read(f);
                    imgs.put(arm, a);
                    int h = Math.min(a.getHeight(), b.getHeight()) - TASKBAR, w = Math.min(a.getWidth(), b.getWidth());
                    long differing = 0, over32 = 0, sum = 0; int max = 0;
                    BufferedImage heat = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                    for (int y = 0; y < h; y++) {
                        for (int x = 0; x < w; x++) {
                            if (ignored(ignore, x, y)) { heat.setRGB(x, y, 0x404040); continue; }
                            int d = maxChannelDiff(a.getRGB(x, y), b.getRGB(x, y));
                            if (d > 0) { differing++; sum += d; max = Math.max(max, d); }
                            if (d > 32) over32++;
                            heat.setRGB(x, y, d == 0 ? 0 : (0xFF0000 | (Math.min(255, d * 4) << 8)));
                        }
                    }
                    ImageIO.write(heat, "png", new File(reviewDir, scene + "-" + arm + "-heat.png"));
                    pw.printf(" %d / %d / %d / %.1f |", differing, over32, max, differing == 0 ? 0 : sum / (double) differing);
                }
                pw.println();
                // crops: named regions, or the most different windows of the first arm
                Map<String, Rectangle> use = regions.isEmpty() && !imgs.isEmpty() ? hotspots(b, imgs.values().iterator().next(), 8) : regions;
                List<String> files = new ArrayList<>();
                for (Map.Entry<String, Rectangle> r : use.entrySet()) {
                    files.add(writeCrop(reviewDir, scene, r.getKey(), r.getValue(), base, b, imgs));
                }
                cropsPerScene.put(scene, files);
            }
            pw.println();
            for (Map.Entry<String, List<String>> e : cropsPerScene.entrySet()) {
                pw.println("## " + e.getKey());
                pw.println();
                for (String f : e.getValue()) pw.println("![](review-" + tag + "/" + f + ")");
                pw.println();
            }
        }
        System.out.println("scenes-" + tag + ".md: " + scenes.length + " scenes, " + arms.size() + " arms");
    }

    /** The n most different 400x120 windows on a 200x60 grid, non-overlapping, above the taskbar. */
    static Map<String, Rectangle> hotspots(BufferedImage b, BufferedImage a, int n) {
        int w = Math.min(a.getWidth(), b.getWidth()), h = Math.min(a.getHeight(), b.getHeight()) - TASKBAR;
        int cw = 400, ch = 120, sx = 200, sy = 60;
        List<long[]> cells = new ArrayList<>();   // {score, x, y}
        for (int y = 0; y + ch <= h; y += sy) {
            for (int x = 0; x + cw <= w; x += sx) {
                long score = 0;
                for (int j = 0; j < ch; j += 2) {
                    for (int i = 0; i < cw; i += 2) {
                        score += maxChannelDiff(a.getRGB(x + i, y + j), b.getRGB(x + i, y + j));
                    }
                }
                cells.add(new long[] { score, x, y });
            }
        }
        cells.sort((p, q) -> Long.compare(q[0], p[0]));
        Map<String, Rectangle> out = new LinkedHashMap<>();
        List<Rectangle> taken = new ArrayList<>();
        for (long[] c : cells) {
            if (c[0] == 0 || out.size() >= n) break;
            Rectangle r = new Rectangle((int) c[1], (int) c[2], cw, ch);
            boolean overlaps = false;
            for (Rectangle t : taken) if (t.intersects(r)) { overlaps = true; break; }
            if (overlaps) continue;
            taken.add(r);
            out.put(String.format("hot%d-%d-%d", out.size() + 1, r.x, r.y), r);
        }
        return out;
    }

    static String writeCrop(File dir, String scene, String name, Rectangle r, String base, BufferedImage b,
                            Map<String, BufferedImage> imgs) throws Exception {
        int zoom = 3, labelH = 16;
        int rows = 1 + imgs.size();
        BufferedImage img = new BufferedImage(r.width * zoom, (r.height * zoom + labelH) * rows, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        int row = 0;
        for (Map.Entry<String, BufferedImage> e : prepend(base, b, imgs).entrySet()) {
            int top = row++ * (r.height * zoom + labelH);
            g.setColor(Color.BLACK);
            g.drawString(e.getKey() + "   " + scene + " " + name, 4, top + 12);
            g.drawImage(e.getValue(), 0, top + labelH, r.width * zoom, top + labelH + r.height * zoom, r.x, r.y, r.x + r.width, r.y + r.height, null);
        }
        g.dispose();
        String file = scene + "-" + name + ".png";
        ImageIO.write(img, "png", new File(dir, file));
        return file;
    }

    static Map<String, BufferedImage> prepend(String base, BufferedImage b, Map<String, BufferedImage> imgs) {
        Map<String, BufferedImage> m = new LinkedHashMap<>();
        m.put(base, b);
        m.putAll(imgs);
        return m;
    }

    static boolean ignored(List<Rectangle> ignore, int x, int y) {
        for (Rectangle r : ignore) if (r.contains(x, y)) return true;
        return false;
    }

    static int maxChannelDiff(int p, int q) {
        int r = Math.abs(((p >> 16) & 255) - ((q >> 16) & 255));
        int g = Math.abs(((p >> 8) & 255) - ((q >> 8) & 255));
        int bl = Math.abs((p & 255) - (q & 255));
        return Math.max(r, Math.max(g, bl));
    }
}
