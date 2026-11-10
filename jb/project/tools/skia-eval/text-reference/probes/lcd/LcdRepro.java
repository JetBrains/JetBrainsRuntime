import java.awt.*;
import java.awt.image.*;
import java.io.File;
import javax.imageio.ImageIO;

/** LCD + fractional-metrics text into an opaque image: a hash of the pixels, the image written for diffing. */
public class LcdRepro {
    public static void main(String[] args) throws Exception {
        boolean fm = !args[0].equals("nofm");
        BufferedImage img = new BufferedImage(900, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, 900, 400); g.setColor(Color.BLACK);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, fm ? RenderingHints.VALUE_FRACTIONALMETRICS_ON : RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        String[] fonts = {"Helvetica Neue", "Menlo", "Times New Roman", "Lucida Grande"};
        int y = 20;
        for (String f : fonts) for (int size : new int[] {11, 13, 16}) {
            g.setFont(new Font(f, Font.PLAIN, size));
            g.drawString("The quick brown fox jumps over the lazy dog 0123456789 il1|", 10.3f, y + 0.4f);
            y += size + 6;
        }
        g.dispose();
        long h = 1125899906842597L;
        for (int yy = 0; yy < 400; yy++) for (int x = 0; x < 900; x++) h = 31 * h + img.getRGB(x, yy);
        System.out.println(Long.toHexString(h));
        if (args.length > 1) ImageIO.write(img, "png", new File(args[1]));
    }
}
