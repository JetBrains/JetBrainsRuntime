import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * Side-by-side review of several arms of a {@code TextSheet} run: for every selected cell one
 * image with the software loops on top and each arm below, 4x nearest-neighbour, each row
 * labelled with the arm's statistics against the loops; plus {@code compare-<tag>.md} with a
 * cross-arm section table.
 * <pre>
 *   java TextSheetCompare --out out/2026-10-20 --tag 1x --arms A1-blend A2-twopass A3-accumulate
 *                         [--cells representative|worst N|all|section S1-dark]
 * </pre>
 * Statistics come from each arm's {@code stats-<tag>.csv} ({@code TextSheetDiff} must have run);
 * the baseline image and the manifest are taken from the first arm.
 */
public class TextSheetCompare {

    record Cell(int index, String section, String font, String style, int size, String aa, String fg, String bg,
                String positioning, int contrast, float alpha, int x, int y, int w, int h) {
        String label() {
            return String.format("%s %s %s %d %s %s/%s %s c%d%s", section, font, style, size, aa, fg, bg, positioning,
                                 contrast, alpha < 1 ? " a" + alpha : "");
        }
        String group() {
            return section + " | " + aa;
        }
    }

    record Stats(int max, double mean, int over8, int over32, int inkMissing, double massRatio, double fringeBase,
                 double fringeArm) {
    }

    public static void main(String[] args) throws Exception {
        File out = new File("out");
        String tag = "1x";
        List<String> arms = new ArrayList<>();
        String selection = "representative";
        int worst = 12;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--out" -> out = new File(args[++i]);
                case "--tag" -> tag = args[++i];
                case "--arms" -> { while (i + 1 < args.length && !args[i + 1].startsWith("--")) arms.add(args[++i]); }
                case "--cells" -> {
                    selection = args[++i];
                    if (selection.equals("worst") || selection.equals("section")) selection += " " + args[++i];
                }
                default -> throw new IllegalArgumentException(args[i]);
            }
        }
        File first = new File(out, arms.get(0));
        BufferedImage base = ImageIO.read(new File(first, "sheet-" + tag + "-software.png"));
        List<Cell> cells = readCells(new File(first, "cells-" + tag + ".csv"));
        Map<String, BufferedImage> images = new LinkedHashMap<>();
        Map<String, List<Stats>> stats = new LinkedHashMap<>();
        for (String arm : arms) {
            File dir = new File(out, arm);
            images.put(arm, ImageIO.read(new File(dir, "sheet-" + tag + ".png")));
            stats.put(arm, readStats(new File(dir, "stats-" + tag + ".csv")));
        }

        // selection
        Set<Integer> selected = new LinkedHashSet<>();
        if (selection.equals("all")) {
            for (Cell c : cells) selected.add(c.index);
        } else if (selection.startsWith("section ")) {
            String s = selection.substring(8);
            for (Cell c : cells) if (c.section.equals(s)) selected.add(c.index);
        } else {
            // worst N over all arms
            int n = selection.startsWith("worst ") ? Integer.parseInt(selection.substring(6)) : worst;
            List<Integer> order = new ArrayList<>();
            for (Cell c : cells) order.add(c.index);
            order.sort((a, b) -> Integer.compare(maxOverArms(stats, b), maxOverArms(stats, a)));
            for (int i = 0; i < Math.min(n, order.size()); i++) selected.add(order.get(i));
            if (selection.equals("representative")) {
                // plus the worst cell of every section/aa group
                Map<String, Integer> perGroup = new LinkedHashMap<>();
                for (Cell c : cells) {
                    Integer cur = perGroup.get(c.group());
                    if (cur == null || maxOverArms(stats, c.index) > maxOverArms(stats, cur)) perGroup.put(c.group(), c.index);
                }
                selected.addAll(perGroup.values());
            }
        }

        File dir = new File(out, "compare-" + tag);
        dir.mkdirs();
        try (PrintWriter pw = new PrintWriter(new File(out, "compare-" + tag + ".md"))) {
            pw.println("# Arms side by side at " + tag + ": " + String.join(", ", arms));
            pw.println();
            pw.println("Against the software loops, text pixels only. Per section and arm: max / mean channel difference, ink-mass ratio (weight).");
            pw.println();
            pw.print("| section | aa | cells |");
            for (String arm : arms) pw.print(" " + arm + " max / mean / mass |");
            pw.println();
            pw.print("|---|---|---|");
            for (String ignored : arms) pw.print("---|");
            pw.println();
            Map<String, List<Integer>> groups = new LinkedHashMap<>();
            for (Cell c : cells) groups.computeIfAbsent(c.group(), k -> new ArrayList<>()).add(c.index);
            for (Map.Entry<String, List<Integer>> g : groups.entrySet()) {
                pw.printf("| %s | %d |", g.getKey(), g.getValue().size());
                for (String arm : arms) {
                    int max = 0; double mean = 0, mass = 0;
                    for (int i : g.getValue()) {
                        Stats s = stats.get(arm).get(i);
                        max = Math.max(max, s.max); mean += s.mean; mass += s.massRatio;
                    }
                    int k = g.getValue().size();
                    pw.printf(" %d / %.2f / %.3f |", max, mean / k, mass / k);
                }
                pw.println();
            }
            pw.println();
            pw.println("## Cells (" + selection + ")");
            pw.println();
            for (int index : selected) {
                Cell c = cells.get(index);
                String name = writeComparison(dir, base, images, stats, c, arms);
                pw.printf("### %s%n%n", c.label());
                for (String arm : arms) {
                    Stats s = stats.get(arm).get(index);
                    pw.printf("- %s: max %d, mean %.2f, >32: %d, ink missing %d, mass %.3f%n", arm, s.max, s.mean, s.over32,
                              s.inkMissing, s.massRatio);
                }
                pw.printf("%n![](compare-%s/%s)%n%n", tag, name);
            }
        }
        System.out.println("compare-" + tag + ".md: " + selected.size() + " cells, " + arms.size() + " arms");
    }

    static int maxOverArms(Map<String, List<Stats>> stats, int index) {
        int m = 0;
        for (List<Stats> l : stats.values()) m = Math.max(m, l.get(index).max);
        return m;
    }

    static List<Cell> readCells(File csv) throws Exception {
        List<String> lines = Files.readAllLines(csv.toPath());
        List<Cell> cells = new ArrayList<>();
        int i = 0;
        for (String line : lines.subList(2, lines.size())) {
            String[] f = line.split(",");
            cells.add(new Cell(i++, f[0], f[1], f[2], Integer.parseInt(f[3]), f[4], f[5], f[6], f[7], Integer.parseInt(f[8]),
                               Float.parseFloat(f[9]), Math.round(Float.parseFloat(f[10])), Math.round(Float.parseFloat(f[11])),
                               Math.round(Float.parseFloat(f[12])), Math.round(Float.parseFloat(f[13]))));
        }
        return cells;
    }

    static List<Stats> readStats(File csv) throws Exception {
        List<String> lines = Files.readAllLines(csv.toPath());
        List<Stats> stats = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split(",");
            // section,font,style,size,aa,fg,bg,positioning,contrast,alpha,max,mean,over8,over32,inkMissing,inkExtra,massRatio,fringeBase,fringeArm,textPixels
            stats.add(new Stats(Integer.parseInt(f[10]), Double.parseDouble(f[11]), Integer.parseInt(f[12]), Integer.parseInt(f[13]),
                                Integer.parseInt(f[14]), Double.parseDouble(f[16]), Double.parseDouble(f[17]), Double.parseDouble(f[18])));
        }
        return stats;
    }

    static String writeComparison(File dir, BufferedImage base, Map<String, BufferedImage> images, Map<String, List<Stats>> stats,
                                  Cell c, List<String> arms) throws Exception {
        int zoom = 4, labelH = 18;
        int cw = Math.min(c.w, 300), ch = c.h;
        int rows = 1 + arms.size();
        BufferedImage img = new BufferedImage(cw * zoom, (ch * zoom + labelH) * rows, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        for (int r = 0; r < rows; r++) {
            int top = r * (ch * zoom + labelH);
            String label;
            BufferedImage src;
            if (r == 0) {
                label = "software loops   " + c.label();
                src = base;
            } else {
                String arm = arms.get(r - 1);
                Stats s = stats.get(arm).get(c.index);
                label = String.format("%s   max %d  mean %.1f  >32: %d  mass %.3f", arm, s.max, s.mean, s.over32, s.massRatio);
                src = images.get(arm);
            }
            g.setColor(Color.BLACK);
            g.drawString(label, 4, top + 13);
            g.drawImage(src, 0, top + labelH, cw * zoom, top + labelH + ch * zoom, c.x, c.y, c.x + cw, c.y + ch, null);
        }
        g.dispose();
        String name = String.format("cell-%03d-%s-%s-%s-%d-%s.png", c.index, c.section, c.font.replace(' ', '_'), c.style, c.size, c.aa);
        ImageIO.write(img, "png", new File(dir, name));
        return name;
    }
}
