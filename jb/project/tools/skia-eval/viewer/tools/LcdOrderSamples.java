import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Two small LCD text samples for the viewer's screen check: the same line with horizontal RGB and
 *  horizontal BGR sub-pixel order (Java's software loops, the platform scaler, black on white, 1x).
 *  Usage: java LcdOrderSamples <out-dir> */
public class LcdOrderSamples {
    public static void main(String[] args) throws Exception {
        File out = new File(args[0]); out.mkdirs();
        String text = "The quick brown fox jumps over the lazy dog 0123456789";
        Font[] fonts = { new Font("Inter", Font.PLAIN, 13), new Font(System.getProperty("os.name").startsWith("Mac") ? "Helvetica Neue" : "Segoe UI", Font.PLAIN, 13), new Font("JetBrains Mono", Font.PLAIN, 13) };
        Object[][] orders = { { "rgb", RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB }, { "bgr", RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HBGR } };
        for (Object[] o : orders) {
            BufferedImage img = new BufferedImage(340, 20 * fonts.length + 8, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setColor(Color.WHITE); g.fillRect(0, 0, img.getWidth(), img.getHeight());
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, o[1]);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setColor(Color.BLACK);
            int y = 18;
            for (Font f : fonts) { g.setFont(f); g.drawString(text, 6, y); y += 20; }
            g.dispose();
            ImageIO.write(img, "png", new File(out, "lcd-" + o[0] + ".png"));
        }
        System.out.println("wrote " + out);
    }
}
