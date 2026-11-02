import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Contact sheet: Contact out.png cols width img1 img2 ... (each scaled to `width`, labelled by file name). */
public class Contact {
    public static void main(String[] a) throws Exception {
        int cols = Integer.parseInt(a[1]), w = Integer.parseInt(a[2]);
        int n = a.length - 3;
        BufferedImage first = ImageIO.read(new File(a[3]));
        int h = w * first.getHeight() / first.getWidth(), rows = (n + cols - 1) / cols, label = 16;
        BufferedImage out = new BufferedImage(cols * w, rows * (h + label), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(Color.WHITE); g.fillRect(0, 0, out.getWidth(), out.getHeight());
        for (int i = 0; i < n; i++) {
            BufferedImage img = ImageIO.read(new File(a[3 + i]));
            int x = (i % cols) * w, y = (i / cols) * (h + label);
            g.setColor(Color.BLACK); g.drawString(new File(a[3 + i]).getName(), x + 4, y + 12);
            g.drawImage(img, x, y + label, w, h, null);
        }
        g.dispose();
        ImageIO.write(out, "png", new File(a[0]));
    }
}
