import java.awt.*;
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.io.File;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * The text sheets of one Java configuration, the same rows and sheets as ctref.swift (the platform's own
 * rendering). A sheet holds the rows family x style (regular, italic, bold, bold italic, regular
 * underlined); the rendering dimensions are one sheet each:
 * <pre>
 *   <out>/<aa>/<positioning>/<scale>/<paper>.png     aa = gray | lcd | aliased
 *                                                     positioning = whole-pixel | fractional
 *                                                     scale = 1x | 2x,  paper = black-on-white | white-on-black
 *   java [pipeline and scaler properties] TextReference <out> <rows.txt> <fonts-dir> [--volatile]
 * </pre>
 * A row cell is W*scale x H*scale px, the text at (4, 19) points scaled by the sheet's scale. {@code --volatile}
 * renders through the default screen configuration's pipeline (Metal, Skia) into a VolatileImage and
 * snapshots it; without it the software loops render into a BufferedImage. Run with
 * {@code -Dsun.java2d.uiScale=1} so the snapshot is not downsampled.
 */
public class TextReference {
    static final int W = 460, H = 28, BASE = 19, X = 4;
    static final String TEXT = "Quick fox jumps 1234567890 {}[]();:,. ∑∂Δ → ≠ ≤ ≥ äöü";
    static final String[] AA = {"gray", "lcd", "aliased"}, POSITIONING = {"whole-pixel", "fractional"}, SCALES = {"1x", "2x"}, PAPERS = {"black-on-white", "white-on-black"};
    static final String[] STYLES = {"regular", "italic", "bold", "bold italic", "regular underlined"};

    record Row(String label, Font font, boolean underlined) {}

    public static void main(String[] args) throws Exception {
        if (args.length < 3) { System.err.println("usage: TextReference <out> <rows.txt> <fonts-dir> [--volatile]"); System.exit(2); }
        File out = new File(args[0]), fontsDir = new File(args[2]);
        boolean volatileImage = args.length > 3 && args[3].equals("--volatile");
        List<Row> rows = rows(fontsDir);
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        System.out.println("pipeline: " + (volatileImage ? gc.getClass().getName() : "software loops (BufferedImage)"));
        for (String aa : AA) for (String pos : POSITIONING) for (String scale : SCALES) for (String paper : PAPERS) {
            File dir = new File(out, aa + "/" + pos + "/" + scale);
            dir.mkdirs();
            // plain black on white and white on black since 2026-10-26 (before: the IDE's editor colours,
            // (30,30,40) on white and (188,190,196) on (30,31,34)): the extremes at which the cg route is
            // bit-identical to AppKit, and one pairing for every platform's sheets
            boolean dark = paper.equals("white-on-black");
            Color ink = dark ? Color.WHITE : Color.BLACK;
            Color bg = dark ? Color.BLACK : Color.WHITE;
            if (System.getenv("TEXTREF_INK") != null) ink = new Color(Integer.parseInt(System.getenv("TEXTREF_INK"), 16));     // experiments: override both papers' colours
            if (System.getenv("TEXTREF_PAPER") != null) bg = new Color(Integer.parseInt(System.getenv("TEXTREF_PAPER"), 16));
            Object aaHint = aa.equals("aliased") ? RenderingHints.VALUE_TEXT_ANTIALIAS_OFF
                    : aa.equals("lcd") ? RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB : RenderingHints.VALUE_TEXT_ANTIALIAS_ON;
            Object fmHint = pos.equals("fractional") ? RenderingHints.VALUE_FRACTIONALMETRICS_ON : RenderingHints.VALUE_FRACTIONALMETRICS_OFF;
            int s = scale.equals("2x") ? 2 : 1;
            int sheetW = W * s, sheetH = H * s * rows.size();
            BufferedImage sheet;
            if (volatileImage) {
                VolatileImage vi = gc.createCompatibleVolatileImage(sheetW, sheetH, Transparency.OPAQUE);
                do {
                    vi.validate(gc);
                    Graphics2D g = vi.createGraphics();
                    paint(g, rows, sheetW, sheetH, aaHint, fmHint, s, ink, bg);
                    g.dispose();
                    sheet = vi.getSnapshot();
                } while (vi.contentsLost());
            } else {
                sheet = new BufferedImage(sheetW, sheetH, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = sheet.createGraphics();
                paint(g, rows, sheetW, sheetH, aaHint, fmHint, s, ink, bg);
                g.dispose();
            }
            BufferedImage rgb = new BufferedImage(sheetW, sheetH, BufferedImage.TYPE_INT_RGB);
            rgb.getGraphics().drawImage(sheet, 0, 0, null);
            ImageIO.write(rgb, "png", new File(dir, paper + ".png"));
        }
        List<String> labels = new ArrayList<>();
        for (Row r : rows) labels.add(r.label());
        java.nio.file.Files.write(new File(args[1]).toPath(), labels);
        System.out.println("wrote " + (AA.length * POSITIONING.length * SCALES.length * PAPERS.length) + " sheets of " + rows.size() + " rows under " + out);
        System.exit(0);
    }

    /** Families: Inter 13 and JetBrains Mono 13 from font files, Helvetica Neue 13 and Menlo 12 from the system. */
    static List<Row> rows(File fontsDir) throws Exception {
        List<Row> rows = new ArrayList<>();
        Font[] inter = files(fontsDir, 13f, "Inter-Regular.otf", "Inter-Italic.otf", "Inter-SemiBold.otf", "Inter-SemiBoldItalic.otf");
        Font[] mono = files(fontsDir, 13f, "JetBrainsMono-Regular.ttf", "JetBrainsMono-Italic.ttf", "JetBrainsMono-Bold.ttf", "JetBrainsMono-BoldItalic.ttf");
        Font[] helvetica = system("Helvetica Neue", 13), menlo = system("Menlo", 12);
        add(rows, "Inter 13", inter); add(rows, "JetBrains Mono 13", mono); add(rows, "Helvetica Neue 13", helvetica); add(rows, "Menlo 12", menlo);
        return rows;
    }
    static Font[] files(File dir, float size, String... names) throws Exception {
        Font[] f = new Font[names.length];
        for (int i = 0; i < names.length; i++) f[i] = Font.createFont(Font.TRUETYPE_FONT, new File(dir, names[i])).deriveFont(size);
        return f;
    }
    static Font[] system(String family, int size) {
        return new Font[] {new Font(family, Font.PLAIN, size), new Font(family, Font.ITALIC, size), new Font(family, Font.BOLD, size), new Font(family, Font.BOLD | Font.ITALIC, size)};
    }
    static void add(List<Row> rows, String family, Font[] faces) {
        for (int i = 0; i < 4; i++) rows.add(new Row(family + " " + STYLES[i], faces[i], false));
        rows.add(new Row(family + " " + STYLES[4], faces[0], true));
    }

    static void paint(Graphics2D g, List<Row> rows, int sheetW, int sheetH, Object aa, Object fm, int scale, Color ink, Color bg) {
        g.setColor(bg);
        g.fillRect(0, 0, sheetW, sheetH);
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            Graphics2D c = (Graphics2D) g.create(0, i * H * scale, W * scale, H * scale);
            c.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, aa);
            c.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, fm);
            c.scale(scale, scale);
            c.setColor(ink);
            c.setFont(r.font());
            if (r.underlined()) {
                AttributedString as = new AttributedString(TEXT);
                as.addAttribute(TextAttribute.FONT, r.font());
                as.addAttribute(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON);
                c.drawString(as.getIterator(), X, BASE);
            } else {
                c.drawString(TEXT, X, BASE);
            }
            c.dispose();
        }
    }
}
