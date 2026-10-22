import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.TexturePaint;
import java.awt.Transparency;
import java.awt.font.TextAttribute;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Text rendering quality sheet (build/notes/text-quality-matrix-plan-2026-10-20.md, instrument 3a).
 * <p>
 * Renders a grid of text cells twice: into a compatible VolatileImage, owned by the pipeline the
 * JVM was started with (the arm under test), and into a BufferedImage with the software loops
 * (the baseline). Both are written as PNG in device pixels, together with a CSV manifest that
 * gives every cell's rectangle and attributes for {@code TextSheetDiff}.
 * <p>
 * The device pixel ratio is applied as a transform ({@code g.scale}), which is how
 * {@code sun.java2d.uiScale} works internally: the font strikes are the ones a display of that
 * scale would use. Run the JVM with {@code -Dsun.java2d.uiScale=1} so the real display's scale
 * does not add a second one.
 * <pre>
 *   java [pipeline properties] TextSheet --arm A2-twopass --scale 2 --out out/2026-10-20
 * </pre>
 * writes {@code out/2026-10-20/A2-twopass/sheet-2x.png}, {@code sheet-2x-software.png} and
 * {@code cells-2x.csv}.
 */
public class TextSheet {

    /** Logical width of the sheet; every cell is one line of text. */
    private static final int WIDTH = 1100;
    private static final int MARGIN = 8;
    /** Logical page height: a page at 2x stays below the 8192-pixel limit of WebGPU's default texture size. */
    private static final int PAGE_HEIGHT = 4000;

    private static final String PANGRAM = "Hamburgefonstiv ffi fl Wq AVfi Tj 0123 {}[]() -> != >= <=";
    private static final String CODE = "public static <T> List<T> of(T... xs) { return xs != null ? copy(xs) : List.of(); } // ffi";

    record Cell(String section, String font, String style, int size, String aa, String fg, String bg,
                String positioning, int contrast, String text, Map<TextAttribute, Object> attributes,
                float alpha, Rectangle2D.Float rect) {
        String csv() {
            return String.join(",", section, font, style, Integer.toString(size), aa, fg, bg, positioning,
                               Integer.toString(contrast), Float.toString(alpha), Float.toString(rect.x),
                               Float.toString(rect.y), Float.toString(rect.width), Float.toString(rect.height));
        }
    }

    // ---- the grid -------------------------------------------------------------------------

    static List<Cell> cells() {
        List<Cell> cells = new ArrayList<>();
        float y = MARGIN;

        // S1: fonts x styles x sizes, on the theme's default colours, LCD and grayscale
        String[][] themes = { { "light", "000000", "FFFFFF" }, { "dark", "BCBEC4", "1E1F22" } };
        for (String[] theme : themes) {
            for (String aa : new String[] { "lcd", "gray" }) {
                for (String font : new String[] { "Inter", "JetBrains Mono" }) {
                    int[] sizes = font.equals("Inter") ? new int[] { 11, 12, 13, 14, 16, 18 }
                                                       : new int[] { 11, 12, 13, 14, 16, 18, 24, 36 };
                    for (String style : new String[] { "plain", "bold", "italic", "bolditalic" }) {
                        for (int size : sizes) {
                            String text = font.equals("Inter") ? PANGRAM : (size <= 14 ? CODE : PANGRAM);
                            y = add(cells, y, "S1-" + theme[0], font, style, size, aa, theme[1], theme[2], "int", 140,
                                    text, null, 1f);
                        }
                    }
                }
            }
        }
        // S2: backgrounds and text colours at the default size (13), both AA modes
        String[][] pairs = {
            { "000000", "FFFFFF" }, { "000000", "F7F8FA" }, { "BCBEC4", "1E1F22" }, { "DFE1E5", "2B2D30" },
            { "FFFFFF", "1E1F22" }, { "FFFFFF", "808080" }, { "000000", "808080" }, { "000000", "A6D2FF" },
            { "BCBEC4", "214283" }, { "0033B3", "FFFFFF" }, { "CF8E6D", "1E1F22" }, { "6AAB73", "1E1F22" },
            { "8C8C8C", "FFFFFF" }, { "000000", "gradient" }, { "FFFFFF", "gradient" }, { "000000", "image" },
        };
        for (String aa : new String[] { "lcd", "gray" }) {
            for (String font : new String[] { "Inter", "JetBrains Mono" }) {
                for (String[] pair : pairs) {
                    y = add(cells, y, "S2", font, "plain", 13, aa, pair[0], pair[1], "int", 140,
                            font.equals("Inter") ? PANGRAM : CODE, null, 1f);
                }
            }
        }
        // S3: positioning and LCD contrast
        for (String font : new String[] { "Inter", "JetBrains Mono" }) {
            for (String aa : new String[] { "lcd", "gray" }) {
                for (String pos : new String[] { "int", "frac", "fracmetrics", "halfscroll" }) {
                    y = add(cells, y, "S3", font, "plain", 13, aa, "000000", "FFFFFF", pos, 140, PANGRAM, null, 1f);
                }
            }
            for (int contrast : new int[] { 100, 180, 250 }) {
                y = add(cells, y, "S3", font, "plain", 13, "lcd", "000000", "FFFFFF", "int", contrast, PANGRAM, null, 1f);
                y = add(cells, y, "S3", font, "plain", 13, "lcd", "BCBEC4", "1E1F22", "int", contrast, PANGRAM, null, 1f);
            }
        }
        // S4: fallback fonts, colour glyphs, ligatures, synthetic styles, alpha, no AA
        String cjk = "日本語のテキスト 中文文本 한국어 텍스트 Ünïcödé ∑∂∆ → ⇒ ≠";
        String emoji = "emoji 😀 🚀 ❤️ 🇩🇪 👨‍💻";
        String arabic = "مرحبا بالعالم नमस्ते दुनिया é ä";
        for (String aa : new String[] { "lcd", "gray" }) {
            for (String[] theme : themes) {
                y = add(cells, y, "S4-fallback", "Inter", "plain", 13, aa, theme[1], theme[2], "int", 140, cjk, null, 1f);
                y = add(cells, y, "S4-fallback", "JetBrains Mono", "plain", 13, aa, theme[1], theme[2], "int", 140, cjk, null, 1f);
                y = add(cells, y, "S4-emoji", "Inter", "plain", 13, aa, theme[1], theme[2], "int", 140, emoji, null, 1f);
                y = add(cells, y, "S4-shaping", "Inter", "plain", 13, aa, theme[1], theme[2], "int", 140, arabic, null, 1f);
                y = add(cells, y, "S4-system", "Segoe UI", "plain", 13, aa, theme[1], theme[2], "int", 140, PANGRAM, null, 1f);
                y = add(cells, y, "S4-system", "Consolas", "plain", 13, aa, theme[1], theme[2], "int", 140, CODE, null, 1f);
                y = add(cells, y, "S4-ligatures", "JetBrains Mono", "plain", 13, aa, theme[1], theme[2], "int", 140, CODE,
                        Map.of(TextAttribute.LIGATURES, TextAttribute.LIGATURES_ON), 1f);
                y = add(cells, y, "S4-synthetic", "Segoe Print", "bold", 13, aa, theme[1], theme[2], "int", 140, PANGRAM, null, 1f);
                y = add(cells, y, "S4-alpha", "Inter", "plain", 13, aa, theme[1], theme[2], "int", 140, PANGRAM, null, 0.5f);
            }
        }
        for (String[] theme : themes) {
            y = add(cells, y, "S4-noaa", "Inter", "plain", 13, "off", theme[1], theme[2], "int", 140, PANGRAM, null, 1f);
            y = add(cells, y, "S4-noaa", "JetBrains Mono", "plain", 12, "off", theme[1], theme[2], "int", 140, CODE, null, 1f);
        }
        return cells;
    }

    private static float add(List<Cell> cells, float y, String section, String font, String style, int size, String aa,
                             String fg, String bg, String positioning, int contrast, String text,
                             Map<TextAttribute, Object> attributes, float alpha) {
        float height = Math.round(size * 1.6f) + 4;
        if ((y % PAGE_HEIGHT) + height > PAGE_HEIGHT - MARGIN) {
            y = (float) (Math.floor(y / PAGE_HEIGHT) + 1) * PAGE_HEIGHT + MARGIN;   // next page
        }
        cells.add(new Cell(section, font, style, size, aa, fg, bg, positioning, contrast, text, attributes, alpha,
                           new Rectangle2D.Float(MARGIN, y, WIDTH - 2 * MARGIN, height)));
        return y + height;
    }

    /** The cells of one page, with their rectangles moved to the page's origin. */
    static List<Cell> page(List<Cell> cells, int page) {
        List<Cell> out = new ArrayList<>();
        for (Cell c : cells) {
            if ((int) (c.rect.y / PAGE_HEIGHT) == page) {
                out.add(new Cell(c.section, c.font, c.style, c.size, c.aa, c.fg, c.bg, c.positioning, c.contrast, c.text,
                                 c.attributes, c.alpha,
                                 new Rectangle2D.Float(c.rect.x, c.rect.y - page * PAGE_HEIGHT, c.rect.width, c.rect.height)));
            }
        }
        return out;
    }

    // ---- painting ---------------------------------------------------------------------------

    static void paint(Graphics2D g, List<Cell> cells, int scale, BufferedImage texture) {
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, WIDTH * scale, 100000);
        g.scale(scale, scale);
        for (Cell c : cells) {
            Rectangle2D.Float r = c.rect;
            // background
            switch (c.bg) {
                case "gradient" -> g.setPaint(new GradientPaint(r.x, r.y, new Color(0x1E1F22), r.x + r.width, r.y, Color.WHITE));
                case "image" -> g.setPaint(new TexturePaint(texture, new Rectangle2D.Float(r.x, r.y, 64, 64)));
                default -> g.setPaint(new Color(Integer.parseInt(c.bg, 16)));
            }
            g.fillRect(Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height));
            // text state
            Object aaHint = switch (c.aa) {
                case "lcd" -> RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB;
                case "gray" -> RenderingHints.VALUE_TEXT_ANTIALIAS_ON;
                default -> RenderingHints.VALUE_TEXT_ANTIALIAS_OFF;
            };
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, aaHint);
            g.setRenderingHint(RenderingHints.KEY_TEXT_LCD_CONTRAST, c.contrast);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                               c.positioning.equals("fracmetrics") ? RenderingHints.VALUE_FRACTIONALMETRICS_ON
                                                                  : RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
            int style = switch (c.style) {
                case "bold" -> Font.BOLD;
                case "italic" -> Font.ITALIC;
                case "bolditalic" -> Font.BOLD | Font.ITALIC;
                default -> Font.PLAIN;
            };
            Font font = new Font(c.font, style, c.size);
            if (c.attributes != null) {
                font = font.deriveFont(c.attributes);
            }
            g.setFont(font);
            g.setColor(new Color(Integer.parseInt(c.fg, 16)));
            g.setComposite(c.alpha < 1f ? AlphaComposite.getInstance(AlphaComposite.SRC_OVER, c.alpha) : AlphaComposite.SrcOver);
            float x = r.x + 4, baseline = r.y + c.size * 1.2f;
            switch (c.positioning) {
                case "frac" -> x += 0.3f;
                case "halfscroll" -> { x += 0.5f; baseline += 0.5f; }
                default -> { }
            }
            g.drawString(c.text, x, baseline);
            g.setComposite(AlphaComposite.SrcOver);
        }
        g.scale(1.0 / scale, 1.0 / scale);
    }

    /** A 64-px image whose colour varies along x only (a hue sweep): the text differ reads the background off the cell's first row, and pipelines that filter the texture differently at a scale differ by levels, not by edges. */
    static BufferedImage texture() {
        BufferedImage t = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 64; x++) {
            int v = Color.HSBtoRGB(x / 64f, 0.35f, 0.95f) & 0xFFFFFF;
            for (int y = 0; y < 64; y++) {
                t.setRGB(x, y, v);
            }
        }
        return t;
    }

    // ---- main -------------------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        String arm = "arm";
        int scale = 1;
        File out = new File("out");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--arm" -> arm = args[++i];
                case "--scale" -> scale = Integer.parseInt(args[++i]);
                case "--out" -> out = new File(args[++i]);
                default -> throw new IllegalArgumentException(args[i]);
            }
        }
        List<Cell> cells = cells();
        int pages = (int) (cells.get(cells.size() - 1).rect.y / PAGE_HEIGHT) + 1;
        int height = pages * PAGE_HEIGHT;
        int w = WIDTH * scale, h = height * scale, ph = PAGE_HEIGHT * scale;
        BufferedImage texture = texture();
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        if (gc.getDefaultTransform().getScaleX() != 1.0) {
            System.err.println("warning: the display is scaled (" + gc.getDefaultTransform().getScaleX()
                               + "); run with -Dsun.java2d.uiScale=1");
        }
        BufferedImage software = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        BufferedImage actual = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        String surface = null;
        for (int p = 0; p < pages; p++) {
            List<Cell> pageCells = page(cells, p);
            // baseline: the software loops
            BufferedImage sw = new BufferedImage(w, ph, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = sw.createGraphics();
            paint(g, pageCells, scale, texture);
            g.dispose();
            // the arm under test: a compatible volatile image of the default configuration
            VolatileImage vi = gc.createCompatibleVolatileImage(w, ph, Transparency.OPAQUE);
            BufferedImage snap;
            int attempts = 0;
            do {
                vi.validate(gc);
                g = vi.createGraphics();
                paint(g, pageCells, scale, texture);
                g.dispose();
                snap = vi.getSnapshot();
            } while (vi.contentsLost() && ++attempts < 3);
            String pageSurface = sun.java2d.SurfaceData.getPrimarySurfaceData(vi).getClass().getName();
            if (surface != null && !surface.equals(pageSurface)) {
                System.err.println("warning: page " + p + " rendered on " + pageSurface + ", page 0 on " + surface);
            }
            surface = pageSurface;
            vi.flush();
            Graphics2D gs = software.createGraphics();
            gs.drawImage(sw, 0, p * ph, null);
            gs.dispose();
            Graphics2D ga = actual.createGraphics();
            ga.drawImage(snap, 0, p * ph, null);
            ga.dispose();
        }

        File dir = new File(out, arm);
        dir.mkdirs();
        String tag = scale + "x";
        ImageIO.write(actual, "png", new File(dir, "sheet-" + tag + ".png"));
        ImageIO.write(software, "png", new File(dir, "sheet-" + tag + "-software.png"));
        try (PrintWriter pw = new PrintWriter(new File(dir, "cells-" + tag + ".csv"))) {
            pw.println("# arm=" + arm + " scale=" + scale + " surface=" + surface + " size=" + w + "x" + h
                       + " java=" + System.getProperty("java.runtime.version"));
            pw.println("section,font,style,size,aa,fg,bg,positioning,contrast,alpha,x,y,w,h");
            for (Cell c : cells) {
                Rectangle2D.Float r = c.rect;
                Cell device = new Cell(c.section, c.font, c.style, c.size, c.aa, c.fg, c.bg, c.positioning, c.contrast,
                                       c.text, c.attributes, c.alpha,
                                       new Rectangle2D.Float(r.x * scale, r.y * scale, r.width * scale, r.height * scale));
                pw.println(device.csv());
            }
        }
        System.out.println(arm + " " + tag + ": " + cells.size() + " cells, " + pages + " pages, " + w + "x" + h
                           + ", surface " + surface + " -> " + dir);
    }
}
