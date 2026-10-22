import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/** Compares RenderMatrix sheets per cell: software vs Metal vs Skia; classifies and lists suspects. */
public class MatrixDiff {
    static final int CELL = 64, COLS = 16;
    record Stat(int diff, int big, int max) {}
    static Stat stat(BufferedImage a, BufferedImage b, int x0, int y0) {
        int diff = 0, big = 0, max = 0;
        for (int y = y0 + 1; y < y0 + CELL; y++) for (int x = x0 + 1; x < x0 + CELL; x++) {   // skip the 1px cell border
            int p = a.getRGB(x, y), q = b.getRGB(x, y);
            int m = Math.max(Math.abs(((p >> 16) & 255) - ((q >> 16) & 255)), Math.max(Math.abs(((p >> 8) & 255) - ((q >> 8) & 255)), Math.abs((p & 255) - (q & 255))));
            if (m > 0) diff++; if (m > 32) big++; max = Math.max(max, m);
        }
        return new Stat(diff, big, max);
    }
    public static void main(String[] args) throws Exception {
        BufferedImage sw = ImageIO.read(new File("matrix-software.png")), mt = ImageIO.read(new File("matrix-metal.png")), sk = ImageIO.read(new File("matrix-skia.png"));
        List<String> names = Files.readAllLines(Path.of("matrix-cells.txt"));
        int filter = args.length > 0 ? Integer.parseInt(args[0]) : 40;   // "suspect" threshold on metal-vs-skia max
        record Row(int i, String name, Stat sm, Stat ss, Stat ms, String cls) {}
        List<Row> rows = new ArrayList<>();
        int pass = 0, aaLevel = 0, suspect = 0;
        BufferedImage heat = new BufferedImage(sk.getWidth(), sk.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < names.size(); i++) {
            int x0 = (i % COLS) * CELL, y0 = (i / COLS) * CELL;
            Stat sm = stat(sw, mt, x0, y0), ss = stat(sw, sk, x0, y0), ms = stat(mt, sk, x0, y0);
            for (int y = y0; y < y0 + CELL; y++) for (int x = x0; x < x0 + CELL; x++) {
                int p = mt.getRGB(x, y), q = sk.getRGB(x, y);
                int m = Math.max(Math.abs(((p >> 16) & 255) - ((q >> 16) & 255)), Math.max(Math.abs(((p >> 8) & 255) - ((q >> 8) & 255)), Math.abs((p & 255) - (q & 255))));
                heat.setRGB(x, y, m == 0 ? 0x101010 : (m <= 32 ? 0x404000 + (m * 6 << 8) : 0xFF0000));
            }
            String cls;
            if (ms.max <= 32) { cls = "pass"; pass++; }
            else if (ms.big <= Math.max(6, (int) (1.5 * sm.big)) ) { cls = "coverage"; aaLevel++; }   // Metal itself deviates from software comparably: AA/coverage-level
            else if (ss.big <= sm.big) { cls = "coverage"; aaLevel++; }   // Skia is at least as close to the software reference as Metal
            else if (ms.max < filter) { cls = "coverage"; aaLevel++; }
            else { cls = "SUSPECT"; suspect++; }
            rows.add(new Row(i, names.get(i), sm, ss, ms, cls));
        }
        ImageIO.write(heat, "png", new File("matrix-heat-metal-vs-skia.png"));
        System.out.printf("cells=%d  pass(max<=32)=%d  coverage-level=%d  SUSPECT=%d%n", names.size(), pass, aaLevel, suspect);
        long swSkia = rows.stream().filter(r -> r.ss.max <= 32).count(), swMetal = rows.stream().filter(r -> r.sm.max <= 32).count();
        long exSkia = rows.stream().filter(r -> r.ss.diff == 0).count(), exMetal = rows.stream().filter(r -> r.sm.diff == 0).count();
        System.out.printf("vs software (max<=32): skia=%d metal=%d   identical to software: skia=%d metal=%d%n", swSkia, swMetal, exSkia, exMetal);
        rows.sort(Comparator.comparingInt((Row r) -> -r.ms.big).thenComparingInt(r -> -r.ms.max));
        System.out.printf("%-52s %-18s %-18s %-18s %s%n", "cell", "sw-metal d/>32/max", "sw-skia d/>32/max", "metal-skia d/>32/max", "class");
        int shown = 0;
        for (Row r : rows) {
            if (!r.cls.equals("SUSPECT")) continue;
            System.out.printf("%-52s %5d/%4d/%3d      %5d/%4d/%3d      %5d/%4d/%3d      %s%n", r.name, r.sm.diff, r.sm.big, r.sm.max, r.ss.diff, r.ss.big, r.ss.max, r.ms.diff, r.ms.big, r.ms.max, r.cls);
            if (++shown >= 60) { System.out.println("..."); break; }
        }
        // contact sheet of the worst suspects: software | metal | skia, 3x
        List<Row> worst = rows.stream().filter(r -> r.cls.equals("SUSPECT")).limit(24).toList();
        if (!worst.isEmpty()) {
            int S = 3, rowH = CELL * S + 14;
            BufferedImage cs = new BufferedImage(3 * CELL * S + 8 + 420, worst.size() * rowH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = cs.createGraphics(); g.setColor(Color.WHITE); g.fillRect(0, 0, cs.getWidth(), cs.getHeight());
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.setFont(new Font("Dialog", Font.PLAIN, 11));
            for (int k = 0; k < worst.size(); k++) {
                Row r = worst.get(k); int x0 = (r.i % COLS) * CELL, y0 = (r.i / COLS) * CELL, yy = k * rowH;
                g.drawImage(sw.getSubimage(x0, y0, CELL, CELL), 0, yy, CELL * S, CELL * S, null);
                g.drawImage(mt.getSubimage(x0, y0, CELL, CELL), CELL * S + 4, yy, CELL * S, CELL * S, null);
                g.drawImage(sk.getSubimage(x0, y0, CELL, CELL), 2 * CELL * S + 8, yy, CELL * S, CELL * S, null);
                g.setColor(Color.BLACK); g.drawString(r.name, 3 * CELL * S + 16, yy + 14);
                g.drawString(String.format("metal-skia >32: %d  max %d   (sw-metal >32: %d)", r.ms.big, r.ms.max, r.sm.big), 3 * CELL * S + 16, yy + 30);
            }
            g.dispose(); ImageIO.write(cs, "png", new File("matrix-suspects.png"));
            System.out.println("wrote matrix-suspects.png (software | metal | skia), matrix-heat-metal-vs-skia.png");
        }
    }
}
