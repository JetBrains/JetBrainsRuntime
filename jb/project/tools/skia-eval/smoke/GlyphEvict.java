import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.io.File;

// One frame with more distinct glyphs than the atlas has cells (32 sizes x 94
// glyphs x 2 weights), so cells are evicted in the middle of glyph lists:
// software loops against the GPU pipeline. "missing" counts pixels with ink in
// the software image and none in the GPU image.
public class GlyphEvict {
    static final int W = 1400, H = 1000;
    static void paint(Graphics2D g, boolean lcd) {
        g.setColor(Color.WHITE); g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                lcd ? RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB : RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        String alphabet = "";
        for (char c = 33; c < 127; c++) alphabet += c;
        int y = 20;
        for (int i = 0; i < 32; i++) {
            int size = 8 + i;
            g.setFont(new Font("Helvetica", i % 2 == 0 ? Font.PLAIN : Font.BOLD, size));
            g.drawString(alphabet.substring(0, 60), 10, y);
            g.drawString(alphabet.substring(34), 10, y + size + 2);
            y += 2 * size + 8;
            if (y > H - 20) break;
        }
    }
    public static void main(String[] args) throws Exception {
        boolean lcd = args.length > 0 && args[0].equals("lcd");
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        BufferedImage sw = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sw.createGraphics(); paint(g, lcd); g.dispose();
        VolatileImage vi = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
        BufferedImage hw = null;
        for (int frame = 0; frame < 3; frame++) {   // the third frame draws with a warm, full atlas
            g = vi.createGraphics(); paint(g, lcd); g.dispose();
            hw = vi.getSnapshot();
        }
        int diff = 0, big = 0, missing = 0, max = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int p = sw.getRGB(x, y) & 0xffffff, q = hw.getRGB(x, y) & 0xffffff;
            int m = Math.max(Math.abs(((p >> 16) & 255) - ((q >> 16) & 255)), Math.max(Math.abs(((p >> 8) & 255) - ((q >> 8) & 255)), Math.abs((p & 255) - (q & 255))));
            if (m > 0) diff++; if (m > 32) big++; max = Math.max(max, m);
            if ((p & 255) < 128 && (q & 255) == 255) missing++;
        }
        System.out.printf("%s: differing=%d >32=%d max=%d missing-ink=%d surface=%s%n", lcd ? "lcd" : "gray", diff, big, max, missing,
                sun.java2d.SurfaceData.getPrimarySurfaceData(vi).getClass().getSimpleName());
        ImageIO.write(hw, "png", new File("glyphevict-" + (lcd ? "lcd" : "gray") + ".png")); ImageIO.write(sw, "png", new File("glyphevict-sw.png"));
    }
}
