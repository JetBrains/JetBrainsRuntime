import java.awt.*;
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.util.Map;

/** Two remaining IDE ingredients: (1) a partial repaint of the keyword's box over an existing frame, with a clip; (2) ligature text (positioned glyph lists). */
public class RepaintProbe {
    static final int W = 320, H = 60;
    public static void main(String[] args) throws Exception {
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        for (String mode : new String[] { "repaint", "ligatures", "repaint+ligatures" }) {
            BufferedImage sw = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
            paint(sw.createGraphics(), mode);
            VolatileImage vi = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
            paint(vi.createGraphics(), mode);
            BufferedImage hw = vi.getSnapshot();
            int max = 0, over = 0;
            for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
                int p = sw.getRGB(x, y), q = hw.getRGB(x, y), d = 0;
                for (int s = 0; s <= 16; s += 8) d = Math.max(d, Math.abs(((p >> s) & 255) - ((q >> s) & 255)));
                max = Math.max(max, d); if (d > 32) over++;
            }
            System.out.printf("%-18s max %3d, >32: %d%n", mode, max, over);
        }
    }
    static void paint(Graphics2D g0, String mode) {
        Font code = new Font("JetBrains Mono", Font.PLAIN, 13);
        if (mode.contains("ligatures")) code = code.deriveFont(Map.of(TextAttribute.LIGATURES, TextAttribute.LIGATURES_ON));
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setRenderingHint(RenderingHints.KEY_TEXT_LCD_CONTRAST, 120);
        g.setFont(code);
        // frame 1: the whole line
        g.setColor(new Color(0x1E1F22)); g.fillRect(0, 0, W, H);
        g.setColor(new Color(0xCF8E6D)); g.drawString("public", 8, 20);
        g.setColor(new Color(0xBCBEC4)); g.drawString("static -> x != y", 60, 20);
        g.drawString("List<Point> points =>", 4, 40);
        g.setColor(new Color(0x6AAB73)); g.drawString("\"x\"", 200, 40);
        if (mode.contains("repaint")) {
            // frame 2: only the keyword's box is repainted, as a partial repaint does: clip, background, the fragments that intersect
            Graphics2D r = (Graphics2D) g0.create();
            r.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
            r.setRenderingHint(RenderingHints.KEY_TEXT_LCD_CONTRAST, 120);
            r.setFont(code);
            r.setClip(6, 4, 60, 22);
            r.setColor(new Color(0x1E1F22)); r.fillRect(6, 4, 60, 22);
            r.setColor(new Color(0xCF8E6D)); r.drawString("public", 8, 20);
            r.setColor(new Color(0xBCBEC4)); r.drawString("static -> x != y", 60, 20);
            r.drawString("List<Point> points =>", 4, 40);
            r.dispose();
        }
        g.dispose();
    }
}
