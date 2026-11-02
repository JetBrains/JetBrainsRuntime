import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.io.File;
import java.util.Arrays;

/**
 * Which rendering a glyph gets at a fractional device x, at a display scale: the glyph is drawn
 * at the given device offsets and each is matched against the renderings at the grid positions
 * k / steps (device pixels), so the pipe's rounding rule shows. Software loops (BufferedImage)
 * by default; -Dprobe.volatile=true draws into a VolatileImage (the accelerated pipe);
 * -Dprobe.lcd=true uses LCD antialiasing.
 *   java [scaler properties] VariantPick <font file> <char> <scale> <steps> <device offset>...
 */
public class VariantPick {
    static final boolean VOLATILE = Boolean.getBoolean("probe.volatile"), LCD = Boolean.getBoolean("probe.lcd");

    public static void main(String[] a) throws Exception {
        Font font = Font.createFont(Font.TRUETYPE_FONT, new File(a[0])).deriveFont(13f);
        String ch = a[1];
        double scale = Double.parseDouble(a[2]);
        int steps = Integer.parseInt(a[3]);
        System.out.println((VOLATILE ? "accelerated pipe" : "software loops") + (LCD ? ", LCD" : ", gray") + ", scale " + scale);
        int[][] grid = new int[steps + 1][];
        for (int k = 0; k <= steps; k++) grid[k] = render(font, ch, scale, 8 + (double) k / steps);
        for (int i = 4; i < a.length; i++) {
            double off = Double.parseDouble(a[i]);
            int[] img = render(font, ch, scale, 8 + off);
            String match = "none";
            for (int k = 0; k <= steps; k++) if (Arrays.equals(img, grid[k])) match = String.format("%.4f", (double) k / steps);
            System.out.printf("  %s at device x 8 + %.4f renders like the grid position %s%n", ch, off, match);
        }
    }

    static int[] render(Font font, String ch, double scale, double deviceX) {
        int w = (int) (40 * scale), h = (int) (30 * scale);
        if (!VOLATILE) {
            BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            paint(image.createGraphics(), font, ch, scale, deviceX, w, h);
            return image.getRGB(0, 0, w, h, null, 0, w);
        }
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        VolatileImage vi = gc.createCompatibleVolatileImage(w, h, Transparency.OPAQUE);
        BufferedImage snap;
        do {
            vi.validate(gc);
            paint(vi.createGraphics(), font, ch, scale, deviceX, w, h);
            snap = vi.getSnapshot();
        } while (vi.contentsLost());
        return snap.getRGB(0, 0, w, h, null, 0, w);
    }

    static void paint(Graphics2D g, Font font, String ch, double scale, double deviceX, int w, int h) {
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.scale(scale, scale);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, LCD ? RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB : RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setColor(Color.BLACK);
        g.setFont(font);
        g.drawString(ch, (float) (deviceX / scale), (float) (20 / scale));
        g.dispose();
    }
}
