import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Text rendered through the software loops with the current font scaler, one sheet per run:
 *   java [-Dsun.java2d.font.scaler=skia] TextCompare render <label> [fontfile ...]
 *   java TextCompare compose <ref> <other>      -> text-compare.png and per-row stats
 * Rows: font x {gray, gray+fractional, LCD, aliased} x {1x, 2x}.
 */
public class TextCompare {
    static final int W = 420, H = 28;
    static final String TEXT = "Quick fox jumps 1234567890 {}[]();:,.";

    record Row(String label, Font font, Object aa, Object fm, double scale) {}

    static java.util.List<Row> rows(String[] files) throws Exception {
        java.util.List<Font> fonts = new java.util.ArrayList<>(java.util.List.of(
                new Font(Font.DIALOG, Font.PLAIN, 13), new Font("Menlo", Font.PLAIN, 12), new Font("Helvetica Neue", Font.PLAIN, 16)));
        for (String f : files) fonts.add(Font.createFont(Font.TRUETYPE_FONT, new File(f)).deriveFont(13f));
        java.util.List<Row> rows = new java.util.ArrayList<>();
        for (Font f : fonts) for (double s : new double[] {1, 2}) {
            rows.add(new Row(f.getFontName() + " gray " + (int) s + "x", f, RenderingHints.VALUE_TEXT_ANTIALIAS_ON, RenderingHints.VALUE_FRACTIONALMETRICS_OFF, s));
            rows.add(new Row(f.getFontName() + " gray fm " + (int) s + "x", f, RenderingHints.VALUE_TEXT_ANTIALIAS_ON, RenderingHints.VALUE_FRACTIONALMETRICS_ON, s));
            rows.add(new Row(f.getFontName() + " lcd " + (int) s + "x", f, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB, RenderingHints.VALUE_FRACTIONALMETRICS_OFF, s));
            rows.add(new Row(f.getFontName() + " aliased " + (int) s + "x", f, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF, RenderingHints.VALUE_FRACTIONALMETRICS_OFF, s));
        }
        return rows;
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("compose")) { compose(args[1], args[2]); return; }
        String label = args[1];
        String[] files = java.util.Arrays.copyOfRange(args, 2, args.length);
        java.util.List<Row> rows = rows(files);
        BufferedImage sheet = new BufferedImage(W * 2, H * 2 * rows.size(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        java.util.List<String> labels = new java.util.ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            Graphics2D c = (Graphics2D) g.create(0, i * H * 2, W * 2, H * 2);
            c.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, r.aa());
            c.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, r.fm());
            c.scale(r.scale(), r.scale());
            c.setColor(new Color(30, 30, 40)); c.setFont(r.font());
            c.drawString(TEXT, 4, 19);
            c.dispose();
            labels.add(r.label());
        }
        ImageIO.write(sheet, "png", new File("text-" + label + ".png"));
        java.nio.file.Files.write(new File("text-rows.txt").toPath(), labels);
        System.out.println("wrote text-" + label + ".png (" + rows.size() + " rows)");
    }

    static int maxDiff(int p, int q) { int m = 0; for (int s = 0; s < 24; s += 8) m = Math.max(m, Math.abs(((p >> s) & 255) - ((q >> s) & 255))); return m; }

    static void compose(String ref, String other) throws Exception {
        BufferedImage A = ImageIO.read(new File("text-" + ref + ".png")), B = ImageIO.read(new File("text-" + other + ".png"));
        java.util.List<String> labels = java.nio.file.Files.readAllLines(new File("text-rows.txt").toPath());
        int rows = labels.size(), RH = H * 2, LABEL = 200;
        BufferedImage out = new BufferedImage(LABEL + W * 2 * 3 + 8, rows * (RH + 2) + 20, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics(); g.setColor(Color.DARK_GRAY); g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.setColor(Color.WHITE); g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        g.drawString(ref, LABEL + 4, 14); g.drawString(other, LABEL + W * 2 + 8, 14); g.drawString("diff x4", LABEL + W * 4 + 12, 14);
        System.out.printf("%-34s %6s %8s %8s   %s%n", "row", "max", "px>32", "px>0", "ink platform/skia (green channel)");
        for (int r = 0; r < rows; r++) {
            int y0 = 20 + r * (RH + 2);
            g.drawString(labels.get(r), 4, y0 + RH / 2 + 4);
            int max = 0, beyond = 0, any = 0; long inkA = 0, inkB = 0;
            BufferedImage diff = new BufferedImage(W * 2, RH, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < RH; y++) for (int x = 0; x < W * 2; x++) {
                inkA += 255 - (A.getRGB(x, r * RH + y) >> 8 & 255); inkB += 255 - (B.getRGB(x, r * RH + y) >> 8 & 255);
                int d = maxDiff(A.getRGB(x, r * RH + y), B.getRGB(x, r * RH + y));
                if (d > 0) any++; if (d > 32) beyond++; max = Math.max(max, d);
                int v = Math.min(255, d * 4); diff.setRGB(x, y, d == 0 ? 0x202020 : (v << 16) | ((255 - v) / 2 << 8));
            }
            g.drawImage(A.getSubimage(0, r * RH, W * 2, RH), LABEL, y0, null);
            g.drawImage(B.getSubimage(0, r * RH, W * 2, RH), LABEL + W * 2 + 4, y0, null);
            g.drawImage(diff, LABEL + W * 4 + 8, y0, null);
            System.out.printf("%-34s %6d %8d %8d   ink %s/%s = %.3f%n", labels.get(r), max, beyond, any, inkA, inkB, inkB / (double) inkA);
        }
        ImageIO.write(out, "png", new File("text-compare.png"));
        System.out.println("wrote text-compare.png");
    }
}
