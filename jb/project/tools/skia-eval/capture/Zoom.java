import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Zoom a b out.png [x y w h]: a, b and their difference (red = large) magnified 4x, stacked. */
public class Zoom {
    public static void main(String[] args) throws Exception {
        BufferedImage a = ImageIO.read(new File(args[0])), b = ImageIO.read(new File(args[1]));
        int x = args.length > 3 ? Integer.parseInt(args[3]) : 0, y = args.length > 4 ? Integer.parseInt(args[4]) : 0;
        int w = args.length > 5 ? Integer.parseInt(args[5]) : a.getWidth(), h = args.length > 6 ? Integer.parseInt(args[6]) : a.getHeight();
        int z = 4;
        BufferedImage out = new BufferedImage(w * z, h * z * 3 + 8, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(a, 0, 0, w * z, h * z, x, y, x + w, y + h, null);
        g.drawImage(b, 0, h * z + 4, w * z, h * z * 2 + 4, x, y, x + w, y + h, null);
        int count = 0;
        for (int j = 0; j < h; j++) for (int i = 0; i < w; i++) {
            int p = a.getRGB(x + i, y + j), q = b.getRGB(x + i, y + j), d = 0;
            for (int s = 0; s <= 16; s += 8) d = Math.max(d, Math.abs(((p >> s) & 255) - ((q >> s) & 255)));
            if (d > 32) count++;
            g.setColor(d == 0 ? Color.WHITE : new Color(255, 255 - Math.min(255, d * 4), 255 - Math.min(255, d * 4)));
            g.fillRect(i * z, h * z * 2 + 8 + j * z, z, z);
        }
        g.dispose();
        ImageIO.write(out, "png", new File(args[2]));
        System.out.println(count + " pixels > 32");
    }
}
