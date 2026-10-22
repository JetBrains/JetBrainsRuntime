import java.awt.*;
import java.awt.image.*;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * A4 check: overlapping glyphs of one glyph list under the LCD blender (kerned
 * pairs, italics) on the accelerated pipeline vs the software loops. Prints the
 * differing pixels and the maximum channel difference, writes both renderings.
 */
public class LcdOverlap {
    static void paint(Graphics2D g, int w, int h) {
        g.setColor(Color.WHITE); g.fillRect(0, 0, w, h);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setColor(Color.BLACK);
        g.setFont(new Font("Segoe UI", Font.ITALIC, 11));
        g.drawString("AVfi To AVfi Way fjfl AWAY", 4, 14);
        g.setFont(new Font("Segoe UI", Font.ITALIC, 24));
        g.drawString("AVfi To AVfi Way", 4, 44);
        g.setFont(new Font("Dialog", Font.BOLD | Font.ITALIC, 16));
        g.drawString("ffi WAVE Tj fifl", 4, 70);
    }

    public static void main(String[] args) throws Exception {
        String tag = args.length > 0 ? args[0] : "skia";
        int w = 260, h = 80;
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        BufferedImage sw = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sw.createGraphics(); paint(g, w, h); g.dispose();
        VolatileImage vi = gc.createCompatibleVolatileImage(w, h);
        g = vi.createGraphics(); paint(g, w, h); g.dispose();
        BufferedImage hw = vi.getSnapshot();
        int diff = 0, max = 0;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int a = sw.getRGB(x, y), b = hw.getRGB(x, y);
            int d = Math.max(Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)),
                    Math.max(Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)), Math.abs((a & 255) - (b & 255))));
            if (d > 0) diff++;
            max = Math.max(max, d);
        }
        System.out.printf("%s vs software: differing=%d max=%d%n", tag, diff, max);
        ImageIO.write(sw, "png", new File("lcd-overlap-software.png"));
        ImageIO.write(hw, "png", new File("lcd-overlap-" + tag + ".png"));
    }
}
