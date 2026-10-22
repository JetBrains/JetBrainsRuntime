import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.io.File;
import javax.imageio.ImageIO;

/** A keyword, then a same-colour run of more than 1024 distinct glyph images spanning lines back under the keyword: the atlas evicts while that batch is open. */
public class EvictProbe {
    static final int W = 1000, H = 400;
    public static void main(String[] args) throws Exception {
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        BufferedImage sw = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        paint(sw.createGraphics());
        VolatileImage vi = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
        paint(vi.createGraphics());
        BufferedImage hw = vi.getSnapshot();
        int max = 0, over = 0, kwOver = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int p = sw.getRGB(x, y), q = hw.getRGB(x, y), d = 0;
            for (int s = 0; s <= 16; s += 8) d = Math.max(d, Math.abs(((p >> s) & 255) - ((q >> s) & 255)));
            max = Math.max(max, d); if (d > 32) { over++; if (x < 70 && y < 24) kwOver++; }
        }
        System.out.println("evict probe: max " + max + ", >32: " + over + " (in the keyword: " + kwOver + ")");
        ImageIO.write(sw, "png", new File("out/evict-sw.png")); ImageIO.write(hw, "png", new File("out/evict-hw.png"));
    }
    static void paint(Graphics2D g) {
        g.setColor(new Color(0x1E1F22)); g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setFont(new Font("JetBrains Mono", Font.PLAIN, 13));
        g.setColor(new Color(0xCF8E6D)); g.drawString("public", 8, 18);
        g.setColor(new Color(0xBCBEC4));
        // 1300 distinct glyph images: many characters at several sizes, from x = 4 on the following lines
        int y = 40, x = 4, count = 0;
        for (int size = 10; size <= 20 && count < 1300; size++) {
            g.setFont(new Font("JetBrains Mono", Font.PLAIN, size));
            StringBuilder sb = new StringBuilder();
            for (char c = 0x21; c < 0x7F; c++) sb.append(c);
            for (char c = 0xA1; c < 0x100; c++) sb.append(c);
            String s = sb.toString();
            FontMetrics fm = g.getFontMetrics();
            int per = Math.max(1, (W - 8) / Math.max(1, fm.charWidth('M')));
            for (int i = 0; i < s.length(); i += per) {
                g.drawString(s.substring(i, Math.min(s.length(), i + per)), x, y);
                y += size + 4; count += Math.min(per, s.length() - i);
                if (y > H - 4) { y = 40; x += 1; }
            }
        }
        g.setColor(new Color(0x6AAB73)); g.drawString("\"end\"", 900, 18);   // flushes the big batch
        g.dispose();
    }
}
