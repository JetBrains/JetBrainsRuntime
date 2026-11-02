import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * The slant of a glyph as rendered: horizontal shift of the ink centroid between the top and the
 * bottom quarter of the glyph's rows, for a family and style (PLAIN, ITALIC, BOLD, BOLDITALIC).
 *   java [scaler properties] SlantProbe <text> <family> <style>...
 */
public class SlantProbe {
    public static void main(String[] a) {
        String text = a[0];
        System.out.println("scaler " + System.getProperty("sun.java2d.font.scaler", "freetype"));
        for (int i = 1; i + 1 < a.length; i += 2) {
            int style = switch (a[i + 1].toUpperCase()) { case "ITALIC" -> Font.ITALIC; case "BOLD" -> Font.BOLD; case "BOLDITALIC" -> Font.BOLD | Font.ITALIC; default -> Font.PLAIN; };
            Font font = new Font(a[i], style, 48);
            System.out.printf("  %-22s %-10s -> %-28s canDisplay %-5b slant %.2f px over the glyph height%n", a[i], a[i + 1], font.getFontName(), font.canDisplay(text.codePointAt(0)), slant(font, text));
        }
    }

    static double slant(Font font, String text) {
        int w = 96, h = 96;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        g.setFont(font);
        g.drawString(text, 16, 72);
        g.dispose();
        int top = -1, bottom = -1;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) if ((image.getRGB(x, y) & 255) < 128) { if (top < 0) top = y; bottom = y; }
        if (top < 0) return Double.NaN;
        int quarter = Math.max(1, (bottom - top + 1) / 4);
        return centroid(image, top, top + quarter) - centroid(image, bottom - quarter + 1, bottom + 1);
    }

    static double centroid(BufferedImage image, int y0, int y1) {
        double sum = 0, weight = 0;
        for (int y = y0; y < y1; y++) for (int x = 0; x < image.getWidth(); x++) { double ink = 255 - (image.getRGB(x, y) & 255); sum += ink * x; weight += ink; }
        return weight == 0 ? 0 : sum / weight;
    }
}
