import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.font.TextLayout;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;

/** Sweeps inlay-like text before a keyword line and reports the max difference of the keyword line against the software loops. */
public class AccumulateProbe {
    static final int W = 300, H = 60;

    public static void main(String[] args) throws Exception {
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        String[] aa = { "lcd", "gray" }, methods = { "drawString", "textLayout", "glyphVector" };
        int[] sizes = { 13, 11 };
        boolean[] fracs = { false, true };
        boolean[] boxes = { false, true };
        for (String inlayAA : aa) for (int size : sizes) for (String method : methods) for (boolean frac : fracs) for (boolean box : boxes) {
            BufferedImage sw = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
            paint(sw.createGraphics(), inlayAA, size, method, frac, box);
            VolatileImage vi = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
            paint(vi.createGraphics(), inlayAA, size, method, frac, box);
            BufferedImage hw = vi.getSnapshot();
            int max = 0, over = 0;
            for (int y = 24; y < H; y++) for (int x = 0; x < W; x++) {   // the keyword line only
                int p = sw.getRGB(x, y), q = hw.getRGB(x, y), d = 0;
                for (int s = 0; s <= 16; s += 8) d = Math.max(d, Math.abs(((p >> s) & 255) - ((q >> s) & 255)));
                max = Math.max(max, d); if (d > 32) over++;
            }
            System.out.printf("inlay %-4s size %d %-11s frac=%-5b box=%-5b : keyword line max %3d, >32: %d%n", inlayAA, size, method, frac, box, max, over);
        }
    }

    static void paint(Graphics2D g, String inlayAA, int size, String method, boolean frac, boolean box) {
        g.setColor(new Color(0x1E1F22)); g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, frac ? RenderingHints.VALUE_FRACTIONALMETRICS_ON : RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        // the inlay: its own AA, size, method, optional background box
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, inlayAA.equals("lcd") ? RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB : RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Font inlayFont = new Font("JetBrains Mono", Font.PLAIN, size);
        if (box) { g.setColor(new Color(0x2B2D30)); g.fillRect(6, 4, 70, 16); }
        g.setColor(new Color(0x7A7E85));
        draw(g, inlayFont, "/unused/", 8, 16, method);
        // the keyword line: LCD, 13 px, orange keyword then light identifier
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        Font code = new Font("JetBrains Mono", Font.PLAIN, 13);
        g.setColor(new Color(0xCF8E6D));
        draw(g, code, "public static void", 8, 36, method);
        g.setColor(new Color(0xBCBEC4));
        draw(g, code, "main(String[] args) {", 150, 36, method);
        g.dispose();
    }

    static void draw(Graphics2D g, Font f, String s, float x, float y, String method) {
        g.setFont(f);
        switch (method) {
            case "drawString" -> g.drawString(s, x, y);
            case "textLayout" -> new TextLayout(s, f, g.getFontRenderContext()).draw(g, x, y);
            case "glyphVector" -> { GlyphVector gv = f.createGlyphVector(g.getFontRenderContext(), s); g.drawGlyphVector(gv, x, y); }
        }
    }
}
