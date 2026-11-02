import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;

/** One line of Helvetica Neue 13 pt, grayscale AA, black on white: ink and colour-fringe count, PNG saved. java [flags] SmoothInk <label> <config> <out-dir> [-AppleFontSmoothing N] */
public class SmoothInk {
    public static void main(String[] a) throws Exception {
        int W = 360, H = 26;
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font("Helvetica Neue", Font.PLAIN, 13)); g.setColor(Color.BLACK);
        g.drawString("Hamburgefonts 123 Quick fox", 4, 18);
        g.dispose();
        long ink = 0, fringes = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int p = img.getRGB(x, y), r = (p >> 16) & 255, gg = (p >> 8) & 255, b = p & 255;
            ink += 255 - (r + gg + b) / 3;
            if (r != b) fringes++;
        }
        System.out.println(a[0] + " " + a[1] + ": ink " + ink + " fringes " + fringes);
        ImageIO.write(img, "png", new File(a[2] + "/" + a[0] + "-" + a[1] + ".png"));
    }
}
