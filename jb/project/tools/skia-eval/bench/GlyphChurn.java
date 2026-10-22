import java.awt.*;
import java.awt.image.*;
import javax.imageio.ImageIO;
import java.io.File;

/**
 * Drives the accelerated glyph caches through eviction: many frames of text in many sizes
 * (about 3,000 distinct glyph images, more than the caches hold), with one frame that needs
 * more distinct glyphs than the cache has cells. Writes the last frame and a middle frame
 * as PNGs for a byte comparison between two JDK images. Usage: GlyphChurn <tag> [lcd]
 */
public class GlyphChurn {
    public static void main(String[] args) throws Exception {
        String tag = args.length > 0 ? args[0] : "run";
        boolean lcd = args.length > 1 && args[1].equals("lcd");
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        VolatileImage vi = gc.createCompatibleVolatileImage(1400, 1000, Transparency.OPAQUE);
        String alphabet = "";
        for (char c = 33; c < 127; c++) alphabet += c;
        BufferedImage mid = null;
        for (int frame = 0; frame < 40; frame++) {
            Graphics2D g = vi.createGraphics();
            g.setColor(Color.WHITE); g.fillRect(0, 0, 1400, 1000);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    lcd ? RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB : RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.BLACK);
            // frame 20 draws every size at once (> 1024 distinct glyphs); the others cycle through subsets
            int sizes = frame == 20 ? 32 : 6;
            int y = 20;
            for (int i = 0; i < sizes; i++) {
                int size = 8 + ((frame * 3 + i) % 32);
                g.setFont(new Font("Helvetica", i % 2 == 0 ? Font.PLAIN : Font.BOLD, size));
                g.drawString(alphabet.substring(0, 60), 10, y);
                g.drawString(alphabet.substring(34), 10, y + size + 2);
                y += 2 * size + 8;
                if (y > 980) break;
            }
            g.dispose();
            if (frame == 25) mid = vi.getSnapshot();
        }
        BufferedImage last = vi.getSnapshot();
        ImageIO.write(mid, "png", new File("glyphchurn-" + tag + "-mid.png"));
        ImageIO.write(last, "png", new File("glyphchurn-" + tag + "-last.png"));
        System.out.println("wrote glyphchurn-" + tag + "-{mid,last}.png (lcd=" + lcd + ")");
    }
}
