import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Compares a {@code TextSheet} rendering with its software baseline cell by cell and writes the
 * review material: {@code stats.csv}, a heat map, magnified crops of the worst and of selected
 * cells, and {@code index.md}.
 * <pre>
 *   java TextSheetDiff out/2026-10-20/A2-twopass 2x [--crops N] [--ref out/.../A1-blend]
 * </pre>
 * {@code --ref} adds a third row to every crop (another arm at the same scale, e.g. the exact
 * blender), so the gamma approximation and the GPU rasterisation can be told apart by eye.
 */
public class TextSheetDiff {

    record Cell(String section, String font, String style, int size, String aa, String fg, String bg,
                String positioning, int contrast, float alpha, int x, int y, int w, int h) {
        String label() {
            return String.format("%s %s %s %d %s %s/%s %s c%d%s", section, font, style, size, aa, fg, bg, positioning,
                                 contrast, alpha < 1 ? " a" + alpha : "");
        }
    }

    record Stats(int max, double mean, int over8, int over32, int inkMissing, int inkExtra, double massRatio,
                 double fringeBase, double fringeArm, int textPixels) {
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        String tag = args[1];
        int crops = 24;
        File ref = null;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--crops" -> crops = Integer.parseInt(args[++i]);
                case "--ref" -> ref = new File(args[++i]);
                default -> throw new IllegalArgumentException(args[i]);
            }
        }
        BufferedImage arm = ImageIO.read(new File(dir, "sheet-" + tag + ".png"));
        BufferedImage base = ImageIO.read(new File(dir, "sheet-" + tag + "-software.png"));
        BufferedImage other = ref != null ? ImageIO.read(new File(ref, "sheet-" + tag + ".png")) : null;
        List<String> lines = Files.readAllLines(new File(dir, "cells-" + tag + ".csv").toPath());
        String header = lines.get(0);
        for (String token : header.split(" ")) {
            if (token.startsWith("scale=")) {
                scale = Integer.parseInt(token.substring(6));
            }
        }
        List<Cell> cells = new ArrayList<>();
        for (String line : lines.subList(2, lines.size())) {
            String[] f = line.split(",");
            cells.add(new Cell(f[0], f[1], f[2], Integer.parseInt(f[3]), f[4], f[5], f[6], f[7], Integer.parseInt(f[8]),
                               Float.parseFloat(f[9]), Math.round(Float.parseFloat(f[10])), Math.round(Float.parseFloat(f[11])),
                               Math.round(Float.parseFloat(f[12])), Math.round(Float.parseFloat(f[13]))));
        }

        BufferedImage heat = new BufferedImage(arm.getWidth(), arm.getHeight(), BufferedImage.TYPE_INT_RGB);
        Map<Cell, Stats> stats = new LinkedHashMap<>();
        for (Cell c : cells) {
            stats.put(c, compare(base, arm, c, heat));
        }
        ImageIO.write(heat, "png", new File(dir, "heat-" + tag + ".png"));

        File outDir = new File(dir, "review-" + tag);
        outDir.mkdirs();
        try (PrintWriter pw = new PrintWriter(new File(dir, "stats-" + tag + ".csv"))) {
            pw.println("section,font,style,size,aa,fg,bg,positioning,contrast,alpha,max,mean,over8,over32,inkMissing,inkExtra,massRatio,fringeBase,fringeArm,textPixels");
            for (Map.Entry<Cell, Stats> e : stats.entrySet()) {
                Cell c = e.getKey();
                Stats s = e.getValue();
                pw.printf("%s,%s,%s,%d,%s,%s,%s,%s,%d,%s,%d,%.2f,%d,%d,%d,%d,%.3f,%.2f,%.2f,%d%n", c.section, c.font, c.style,
                          c.size, c.aa, c.fg, c.bg, c.positioning, c.contrast, c.alpha, s.max, s.mean, s.over8, s.over32,
                          s.inkMissing, s.inkExtra, s.massRatio, s.fringeBase, s.fringeArm, s.textPixels);
            }
        }

        // worst cells by max difference, then crops
        List<Map.Entry<Cell, Stats>> worst = new ArrayList<>(stats.entrySet());
        worst.sort(Comparator.comparingInt((Map.Entry<Cell, Stats> e) -> e.getValue().max).reversed()
                             .thenComparingDouble(e -> -e.getValue().mean));
        List<String> cropFiles = new ArrayList<>();
        int n = 0;
        for (Map.Entry<Cell, Stats> e : worst) {
            if (n++ >= crops) {
                break;
            }
            cropFiles.add(writeCrop(outDir, base, arm, other, e.getKey(), e.getValue(), cells.indexOf(e.getKey())));
        }

        try (PrintWriter pw = new PrintWriter(new File(dir, "index-" + tag + ".md"))) {
            pw.println("# Text sheet " + dir.getName() + " at " + tag);
            pw.println();
            pw.println(header);
            pw.println();
            pw.println("Per section, against the software loops (text pixels only): max and mean channel difference, pixels beyond 8 and 32 levels, baseline ink pixels the arm lacks, ink mass of the arm over the baseline's (weight), LCD edge fringe baseline / arm.");
            pw.println();
            pw.println("| section | aa | cells | max | mean | >8 | >32 | ink missing | mass | fringe base / arm |");
            pw.println("|---|---|---|---|---|---|---|---|---|---|");
            Map<String, List<Stats>> groups = new LinkedHashMap<>();
            for (Map.Entry<Cell, Stats> e : stats.entrySet()) {
                groups.computeIfAbsent(e.getKey().section + " | " + e.getKey().aa, k -> new ArrayList<>()).add(e.getValue());
            }
            for (Map.Entry<String, List<Stats>> g : groups.entrySet()) {
                int max = 0, over8 = 0, over32 = 0, missing = 0;
                double mean = 0, mass = 0, fb = 0, fa = 0;
                for (Stats s : g.getValue()) {
                    max = Math.max(max, s.max);
                    over8 += s.over8;
                    over32 += s.over32;
                    missing += s.inkMissing;
                    mean += s.mean;
                    mass += s.massRatio;
                    fb += s.fringeBase;
                    fa += s.fringeArm;
                }
                int k = g.getValue().size();
                pw.printf("| %s | %d | %d | %.2f | %d | %d | %d | %.3f | %.2f / %.2f |%n", g.getKey(), k, max, mean / k,
                          over8, over32, missing, mass / k, fb / k, fa / k);
            }
            pw.println();
            pw.println("## Worst cells");
            pw.println();
            int i = 0;
            for (Map.Entry<Cell, Stats> e : worst) {
                if (i >= crops) {
                    break;
                }
                Stats s = e.getValue();
                pw.printf("### %s%n%nmax %d, mean %.2f, >8: %d, >32: %d, ink missing %d, mass %.3f%n%n![](%s)%n%n",
                          e.getKey().label(), s.max, s.mean, s.over8, s.over32, s.inkMissing, s.massRatio,
                          "review-" + tag + "/" + cropFiles.get(i));
                i++;
            }
        }
        System.out.println(dir.getName() + " " + tag + ": " + cells.size() + " cells, worst max "
                           + worst.get(0).getValue().max + " (" + worst.get(0).getKey().label() + "), index-" + tag + ".md");
    }

    /** Statistics of one cell; text pixels are where either image differs from the cell's background. */
    static Stats compare(BufferedImage base, BufferedImage arm, Cell c, BufferedImage heat) {
        int max = 0, over8 = 0, over32 = 0, missing = 0, extra = 0, textPixels = 0;
        long sum = 0;
        double massBase = 0, massArm = 0, fringeBase = 0, fringeArm = 0;
        int edgeBase = 0, edgeArm = 0;
        for (int y = c.y; y < c.y + c.h; y++) {
            for (int x = c.x; x < c.x + c.w; x++) {
                int p = base.getRGB(x, y), q = arm.getRGB(x, y);
                // each image against its own background (gradient and image backgrounds
                // render slightly differently per pipeline; that is not a text difference)
                int bgBase = bgAt(base, c, x, y), bgArm = bgAt(arm, c, x, y);
                int d = maxChannelDiff(p, q);
                boolean inkBase = maxChannelDiff(p, bgBase) > 24, inkArm = maxChannelDiff(q, bgArm) > 24;
                if (inkBase || inkArm) {
                    textPixels++;
                    sum += d;
                    max = Math.max(max, d);
                    if (d > 8) over8++;
                    if (d > 32) over32++;
                    if (inkBase && !inkArm && d > 32) missing++;   // ink substantially gone, not a threshold flip
                    if (inkArm && !inkBase && d > 32) extra++;
                    massBase += maxChannelDiff(p, bgBase);
                    massArm += maxChannelDiff(q, bgArm);
                    if (c.aa.equals("lcd")) {
                        if (inkBase) { fringeBase += Math.abs(((p >> 16) & 255) - (p & 255)); edgeBase++; }
                        if (inkArm) { fringeArm += Math.abs(((q >> 16) & 255) - (q & 255)); edgeArm++; }
                    }
                }
                heat.setRGB(x, y, d == 0 ? 0 : (0xFF0000 | (Math.min(255, d * 4) << 8)));
            }
        }
        return new Stats(max, textPixels == 0 ? 0 : sum / (double) textPixels, over8, over32, missing, extra,
                         massBase == 0 ? 1 : massArm / massBase, edgeBase == 0 ? 0 : fringeBase / edgeBase,
                         edgeArm == 0 ? 0 : fringeArm / edgeArm, textPixels);
    }

    /** Background colour under (x, y) in `img`: the cell's colour, or for gradient and image cells the pixel of the same column (and, for the image, the same row modulo the 64-px tile) in the cell's first rows, which the text never reaches. */
    static int bgAt(BufferedImage img, Cell c, int x, int y) {
        if (c.bg.equals("gradient") || c.bg.equals("image")) {
            return img.getRGB(x, c.y);   // both vary along x only; the first row is text-free
        }
        return 0xFF000000 | Integer.parseInt(c.bg, 16);
    }

    static int scale = 1;   // device pixels per logical pixel of the sheet (from the manifest header)

    static int maxChannelDiff(int p, int q) {
        int r = Math.abs(((p >> 16) & 255) - ((q >> 16) & 255));
        int g = Math.abs(((p >> 8) & 255) - ((q >> 8) & 255));
        int b = Math.abs((p & 255) - (q & 255));
        return Math.max(r, Math.max(g, b));
    }

    /** Baseline, arm (and reference) rows of the cell's first 260 device pixels, 4x nearest-neighbour, with a label and a heat row. */
    static String writeCrop(File outDir, BufferedImage base, BufferedImage arm, BufferedImage other, Cell c, Stats s, int index)
            throws Exception {
        int zoom = 4;
        int cw = Math.min(c.w, 300), ch = c.h;
        int rows = other != null ? 4 : 3;
        int labelH = 18;
        BufferedImage crop = new BufferedImage(cw * zoom, (ch * zoom + labelH) * rows, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = crop.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, crop.getWidth(), crop.getHeight());
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        String[] names = other != null ? new String[] { "software loops", "arm", "reference arm", "difference x4" }
                                       : new String[] { "software loops", "arm", "difference x4" };
        BufferedImage[] sources = other != null ? new BufferedImage[] { base, arm, other, null }
                                                : new BufferedImage[] { base, arm, null };
        for (int r = 0; r < rows; r++) {
            int top = r * (ch * zoom + labelH);
            g.setColor(Color.BLACK);
            g.drawString(names[r] + (r == 0 ? "   " + c.label() + String.format("   max %d mean %.1f mass %.2f", s.max, s.mean, s.massRatio) : ""),
                         4, top + 13);
            BufferedImage src = sources[r];
            if (src != null) {
                g.drawImage(src, 0, top + labelH, cw * zoom, top + labelH + ch * zoom, c.x, c.y, c.x + cw, c.y + ch, null);
            } else {
                for (int y = 0; y < ch; y++) {
                    for (int x = 0; x < cw; x++) {
                        int d = maxChannelDiff(base.getRGB(c.x + x, c.y + y), arm.getRGB(c.x + x, c.y + y));
                        int v = d == 0 ? 0xFFFFFF : (0xFF0000 | ((255 - Math.min(255, d * 4)) << 8) | (255 - Math.min(255, d * 4)));
                        g.setColor(new Color(v));
                        g.fillRect(x * zoom, top + labelH + y * zoom, zoom, zoom);
                    }
                }
            }
        }
        g.dispose();
        String name = String.format("cell-%03d-%s-%s-%s-%d-%s.png", index, c.section, c.font.replace(' ', '_'), c.style, c.size, c.aa);
        ImageIO.write(crop, "png", new File(outDir, name));
        return name;
    }
}
