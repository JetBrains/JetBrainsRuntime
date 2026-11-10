import java.awt.*;
import java.awt.font.*;
import java.awt.image.BufferedImage;
import java.text.AttributedString;
import java.util.*;

/** The layout attributes through Java's routes at 8x, as aktk2attributes.swift measures TextKit 2 (points; y down). */
public class JavaAttributes {
    static final int S = 8;
    public static void main(String[] args) {
        boolean paragraph = args[0].equals("paragraph");
        String what = args[1];
        switch (what) {
            case "super" -> {
                for (String family : new String[] {"Helvetica Neue", "Times New Roman", "Menlo", "Avenir Next"}) for (int level : new int[] {1, 2, -1, -2}) {
                    Font font = font(family, 24);
                    AttributedString as = new AttributedString("x  x");
                    face(as, family, 24, 0, 4);
                    as.addAttribute(TextAttribute.SUPERSCRIPT, level, 3, 4);
                    BufferedImage img = draw(as, paragraph);
                    double[][] c = components(img);
                    System.out.printf("%s 24\t%d\tscale %.3f\tshift %.3f%n", family, level, (c[1][3] - c[1][2]) / (c[0][3] - c[0][2]), c[1][3] - c[0][3]);
                }
            }
            case "background" -> {
                for (Object[] f : new Object[][] {{"Helvetica Neue", 13}, {"Times New Roman", 16}, {"Menlo", 12}}) for (boolean mixed : new boolean[] {false, true}) {
                    Font font = font((String) f[0], (int) f[1]), big = font((String) f[0], 2 * (int) f[1]);
                    AttributedString as = new AttributedString("abcdef");
                    face(as, (String) f[0], (int) f[1], 0, 6);
                    if (mixed) as.addAttribute(TextAttribute.SIZE, (float) (2 * (int) f[1]), 4, 6);
                    as.addAttribute(TextAttribute.BACKGROUND, Color.RED, 2, 4);
                    BufferedImage img = draw(as, paragraph);
                    int top = Integer.MAX_VALUE, bottom = -1, left = Integer.MAX_VALUE, right = -1;
                    for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < img.getWidth(); x++) {
                        int p = img.getRGB(x, y);
                        if ((p >> 16 & 0xff) > 230 && (p >> 8 & 0xff) < 25 && (p & 0xff) < 25) { top = Math.min(top, y); bottom = Math.max(bottom, y + 1); left = Math.min(left, x); right = Math.max(right, x + 1); }
                    }
                    TextLayout tl = new TextLayout(as.getIterator(), new FontRenderContext(null, true, true));
                    LineMetrics lm = font.getLineMetrics("x", new FontRenderContext(null, true, true));
                    System.out.printf("%s %d%s\tfill %.3f..%.3f x %.3f..%.3f\tcarets %.3f..%.3f\tascent %.3f descent %.3f leading %.3f%n", f[0], f[1], mixed ? " + big after" : "",
                            top / (double) S - 100, bottom / (double) S - 100, left / (double) S - 20, right / (double) S - 20,
                            tl.getCaretInfo(TextHitInfo.leading(2))[0], tl.getCaretInfo(TextHitInfo.leading(4))[0], lm.getAscent(), lm.getDescent(), lm.getLeading());
                }
            }
            case "decoration" -> {
                for (String family : new String[] {"Helvetica Neue", "Times New Roman"}) for (String variant : new String[] {"superscript", "subscript", "double size"}) for (String style : new String[] {"underline", "strike"}) {
                    Font font = font(family, 24);
                    String text = "x     x     x     ";
                    AttributedString as = new AttributedString(text), plain = new AttributedString(text);
                    for (AttributedString a : new AttributedString[] {as, plain}) {
                        face(a, family, 24, 0, text.length());
                        if (variant.equals("superscript")) a.addAttribute(TextAttribute.SUPERSCRIPT, 1, 6, 12);
                        if (variant.equals("subscript")) a.addAttribute(TextAttribute.SUPERSCRIPT, -1, 6, 12);
                        if (variant.equals("double size")) a.addAttribute(TextAttribute.SIZE, 48f, 6, 12);
                    }
                    as.addAttribute(style.equals("underline") ? TextAttribute.UNDERLINE : TextAttribute.STRIKETHROUGH, style.equals("underline") ? TextAttribute.UNDERLINE_ON : TextAttribute.STRIKETHROUGH_ON);
                    BufferedImage d = draw(as, paragraph), p = draw(plain, paragraph);
                    TextLayout tl = new TextLayout(plain.getIterator(), new FontRenderContext(null, true, true));
                    StringBuilder sb = new StringBuilder(family + " 24, run 2 " + variant + "\t" + style);
                    for (int r = 0; r < 2; r++) {
                        int x1 = (int) ((tl.getCaretInfo(TextHitInfo.leading(r * 6 + 2))[0] + 20) * S), x2 = (int) ((tl.getCaretInfo(TextHitInfo.leading(r * 6 + 5))[0] + 20) * S);
                        double sum = 0, moment = 0;
                        for (int y = 0; y < d.getHeight(); y++) {
                            double c = 0;
                            for (int x = x1; x < x2; x++) c += Math.max(0, ink(d, x, y) - ink(p, x, y));
                            c /= Math.max(1, x2 - x1);
                            sum += c; moment += c * (y + 0.5);
                        }
                        sb.append(String.format("\tcentre %.3f thickness %.3f", moment / sum / S - 100, sum / S));
                    }
                    System.out.println(sb);
                }
            }
            case "tracking" -> {
                Font font = font("Helvetica Neue", 24);
                for (double v : new double[] {0, 2, 6, -1}) {
                    AttributedString as = new AttributedString("abcd");
                    face(as, "Helvetica Neue", 24, 0, 4);
                    as.addAttribute(TextAttribute.TRACKING, (float) (v / 24), 1, 3);
                    TextLayout tl = new TextLayout(as.getIterator(), new FontRenderContext(null, true, true));
                    StringBuilder sb = new StringBuilder("tracking " + v + " pt\tcarets");
                    for (int i = 0; i <= 4; i++) sb.append(String.format(" %.3f", i < 4 ? tl.getCaretInfo(TextHitInfo.leading(i))[0] : tl.getAdvance()));
                    sb.append("\tink left edges");
                    for (double[] c : components(draw(as, paragraph))) sb.append(String.format(" %.3f", c[0]));
                    System.out.println(sb);
                }
            }
        }
    }
    /** FAMILY and SIZE (a FONT attribute would make TextLayout ignore the attributes beside it, §18a T10). */
    static void face(AttributedString as, String family, int size, int from, int to) {
        as.addAttribute(TextAttribute.FAMILY, family, from, to);
        as.addAttribute(TextAttribute.SIZE, (float) size, from, to);
    }
    static Font font(String family, int size) { return new Font(Map.of(TextAttribute.FAMILY, family, TextAttribute.SIZE, (float) size)); }
    static double ink(BufferedImage img, int x, int y) { return (255 - (img.getRGB(x, y) >> 8 & 0xff)) / 255.0; }
    static BufferedImage draw(AttributedString as, boolean paragraph) {
        BufferedImage img = new BufferedImage(600 * S, 200 * S, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, img.getWidth(), img.getHeight()); g.setColor(Color.BLACK);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.scale(S, S);
        if (paragraph) g.drawString(as.getIterator(), 20f, 100f);
        else new TextLayout(as.getIterator(), g.getFontRenderContext()).draw(g, 20f, 100f);
        g.dispose();
        return img;
    }
    /** Ink components along x: left, right, top, bottom in points from (20, 100). */
    static double[][] components(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        double[] col = new double[w];
        for (int x = 0; x < w; x++) for (int y = 0; y < h; y++) col[x] += ink(img, x, y);
        java.util.List<double[]> out = new ArrayList<>();
        for (int x = 0; x < w; ) {
            if (col[x] < 0.05) { x++; continue; }
            int start = x;
            while (x < w && col[x] >= 0.05) x++;
            double[] rows = new double[h];
            for (int y = 0; y < h; y++) for (int c = start; c < x; c++) rows[y] += ink(img, c, y);
            int first = -1, last = -1;
            for (int y = 0; y < h; y++) if (rows[y] > 0.2) { if (first < 0) first = y; last = y; }
            if (first < 0) continue;
            double body = Math.max(rows[Math.min(last, first + 2)], rows[Math.max(first, last - 2)]);
            double top = first + 1 - Math.min(1, rows[first] / body), bottom = last + Math.min(1, rows[last] / body);
            out.add(new double[] {start / (double) S - 20, x / (double) S - 20, top / S - 100, bottom / S - 100});
        }
        return out.toArray(new double[0][]);
    }
}
