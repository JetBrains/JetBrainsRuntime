import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.*;
import java.util.*;

/** Lists RenderMatrix cells matching a name filter with their three-way stats, worst Metal-vs-Skia first. */
public class MatrixList {
    static final int CELL = 64, COLS = 16;
    record S(int diff, int big, int max) {}
    static S st(BufferedImage a, BufferedImage b, int x0, int y0) {
        int d = 0, bg = 0, mx = 0;
        for (int y = y0 + 1; y < y0 + CELL; y++) for (int x = x0 + 1; x < x0 + CELL; x++) {
            int p = a.getRGB(x, y), q = b.getRGB(x, y);
            int m = Math.max(Math.abs(((p >> 16) & 255) - ((q >> 16) & 255)), Math.max(Math.abs(((p >> 8) & 255) - ((q >> 8) & 255)), Math.abs((p & 255) - (q & 255))));
            if (m > 0) d++; if (m > 32) bg++; mx = Math.max(mx, m);
        }
        return new S(d, bg, mx);
    }
    public static void main(String[] a) throws Exception {
        BufferedImage sw = ImageIO.read(new File("matrix-software.png")), mt = ImageIO.read(new File("matrix-metal.png")), sk = ImageIO.read(new File("matrix-skia.png"));
        List<String> names = Files.readAllLines(Path.of("matrix-cells.txt"));
        String filter = a[0]; int limit = Integer.parseInt(a[1]);
        record R(String n, S sm, S ss, S ms) {}
        List<R> rows = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            if (!names.get(i).contains(filter)) continue;
            int x0 = (i % COLS) * CELL, y0 = (i / COLS) * CELL;
            rows.add(new R(names.get(i), st(sw, mt, x0, y0), st(sw, sk, x0, y0), st(mt, sk, x0, y0)));
        }
        rows.sort(Comparator.comparingInt((R r) -> -r.ms.big));
        System.out.printf("%-46s %-16s %-16s %-16s%n", "cell [" + filter + "]", "sw-metal d/>32/mx", "sw-skia d/>32/mx", "metal-skia d/>32/mx");
        for (R r : rows.subList(0, Math.min(limit, rows.size())))
            System.out.printf("%-46s %5d/%4d/%3d   %5d/%4d/%3d   %5d/%4d/%3d%n", r.n, r.sm.diff, r.sm.big, r.sm.max, r.ss.diff, r.ss.big, r.ss.max, r.ms.diff, r.ms.big, r.ms.max);
    }
}
