import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.io.File;
import javax.imageio.ImageIO;

/** A batch of one colour spanning two lines, whose bounds cover a keyword of another colour composited earlier. */
public class SpanProbe {
    static final int W = 320, H = 60;
    public static void main(String[] args) throws Exception {
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        BufferedImage sw = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        paint(sw.createGraphics());
        VolatileImage vi = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
        paint(vi.createGraphics());
        BufferedImage hw = vi.getSnapshot();
        int max = 0, over = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int p = sw.getRGB(x, y), q = hw.getRGB(x, y), d = 0;
            for (int s = 0; s <= 16; s += 8) d = Math.max(d, Math.abs(((p >> s) & 255) - ((q >> s) & 255)));
            max = Math.max(max, d); if (d > 32) over++;
        }
        System.out.println("span probe: max " + max + ", >32: " + over);
        ImageIO.write(sw, "png", new File("out/span-sw.png")); ImageIO.write(hw, "png", new File("out/span-hw.png"));
    }
    static void paint(Graphics2D g) {
        g.setColor(new Color(0x1E1F22)); g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setFont(new Font("JetBrains Mono", Font.PLAIN, 13));
        g.setColor(new Color(0xCF8E6D)); g.drawString("public", 8, 20);          // keyword: its own batch, composited when the next colour begins
        g.setColor(new Color(0xBCBEC4)); g.drawString("static void main(", 60, 20);   // identifier colour: batch opens
        g.drawString("List<Point> points =", 4, 40);                             // same colour on the next line: the batch spans both lines, its bounds cover "public"
        g.setColor(new Color(0x6AAB73)); g.drawString("\"x\"", 200, 40);          // another colour: flushes the spanning batch
        g.dispose();
    }
}
