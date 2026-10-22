import java.awt.*;
import java.awt.image.*;

/** LCD text over several backgrounds and text colours: Skia (current lcd mode) vs the software loops, max and mean channel difference per combination. */
public class LcdBackgrounds {
    public static void main(String[] args) throws Exception {
        Color[] bgs = { Color.WHITE, Color.BLACK, new Color(128, 128, 128), new Color(43, 43, 43), new Color(230, 240, 255), new Color(30, 60, 120) };
        String[] bgNames = { "white", "black", "gray128", "darcula43", "paleblue", "navy" };
        Color[] fgs = { Color.BLACK, Color.WHITE, new Color(187, 187, 187), new Color(204, 120, 50), Color.RED, new Color(80, 80, 80) };
        String[] fgNames = { "black", "white", "gray187", "orange", "red", "gray80" };
        int w = 320, h = 40;
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        VolatileImage vi = gc.createCompatibleVolatileImage(w, h, Transparency.OPAQUE);
        Font font = new Font("Segoe UI", Font.PLAIN, 13);
        String text = "The quick brown fox AVfi To AVfi Way fjfl 0123456789";
        System.out.printf("%-10s %-8s %5s %6s %8s%n", "background", "text", "max", "mean", ">8 px");
        for (int b = 0; b < bgs.length; b++) {
            for (int f = 0; f < fgs.length; f++) {
                BufferedImage sw = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                paint(sw.createGraphics(), bgs[b], fgs[f], font, text, w, h);
                paint(vi.createGraphics(), bgs[b], fgs[f], font, text, w, h);
                BufferedImage hw = vi.getSnapshot();
                int max = 0, over = 0; long sum = 0; int n = 0;
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                    int p = sw.getRGB(x, y), q = hw.getRGB(x, y);
                    int d = 0;
                    for (int s = 0; s <= 16; s += 8) d = Math.max(d, Math.abs(((p >> s) & 255) - ((q >> s) & 255)));
                    if (p != bgs[b].getRGB() || q != bgs[b].getRGB()) { sum += d; n++; }
                    if (d > 8) over++;
                    max = Math.max(max, d);
                }
                System.out.printf("%-10s %-8s %5d %6.2f %8d%n", bgNames[b], fgNames[f], max, n == 0 ? 0 : sum / (double) n, over);
            }
        }
    }

    static void paint(Graphics2D g, Color bg, Color fg, Font font, String text, int w, int h) {
        g.setColor(bg); g.fillRect(0, 0, w, h);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setFont(font); g.setColor(fg); g.drawString(text, 4, 15); g.drawString(text, 2, 32);
        g.dispose();
    }
}
