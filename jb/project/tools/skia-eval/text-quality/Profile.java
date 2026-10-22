import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Profile img1 img2 ... -- y x0 x1 : the RGB of each image along row y from x0 to x1, one column per line. */
public class Profile {
    public static void main(String[] a) throws Exception {
        int sep = java.util.Arrays.asList(a).indexOf("--");
        int y = Integer.parseInt(a[sep + 1]), x0 = Integer.parseInt(a[sep + 2]), x1 = Integer.parseInt(a[sep + 3]);
        BufferedImage[] imgs = new BufferedImage[sep];
        for (int i = 0; i < sep; i++) imgs[i] = ImageIO.read(new File(a[i]));
        StringBuilder h = new StringBuilder("   x ");
        for (int i = 0; i < sep; i++) h.append(String.format(" %-10s", new File(a[i]).getParentFile().getParentFile().getName()));
        System.out.println(h);
        for (int x = x0; x <= x1; x++) {
            StringBuilder sb = new StringBuilder(String.format("%4d ", x));
            for (BufferedImage img : imgs) sb.append(String.format(" %06x    ", img.getRGB(x, y) & 0xffffff));
            System.out.println(sb);
        }
    }
}
