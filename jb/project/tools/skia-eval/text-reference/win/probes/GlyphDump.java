import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * One glyph through the configured scaler route at a display scale, the baseline at device y 40,
 * printed as gray values (rows 8..47, columns 0..31) like dwref --glyph.
 *   java [scaler properties] GlyphDump <font file> <char> <device x> <scale>
 */
public class GlyphDump {
    public static void main(String[] a) throws Exception {
        Font font = Font.createFont(Font.TRUETYPE_FONT, new File(a[0])).deriveFont(13f);
        String ch = a[1];
        double x = Double.parseDouble(a[2]), scale = Double.parseDouble(a[3]);
        int w = 32, h = 48;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.scale(scale, scale);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setColor(Color.BLACK);
        g.setFont(font);
        g.drawString(ch, (float) (x / scale), (float) (40 / scale));
        g.dispose();
        System.out.printf("java scale %.2f, glyph at device x %.4f, baseline y 40:%n", scale, x);
        for (int y = 8; y < h; y++) {
            StringBuilder sb = new StringBuilder(String.format("%3d ", y));
            for (int i = 0; i < w; i++) sb.append(String.format("%4d", image.getRGB(i, y) & 255));
            System.out.println(sb);
        }
    }
}
