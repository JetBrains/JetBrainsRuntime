import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** Pixels where the software LCD text has ink and the other rendering has none: lost glyph coverage. */
public class MissingInk {
    public static void main(String[] args) throws Exception {
        BufferedImage sw = ImageIO.read(new File(args[0]));
        BufferedImage hw = ImageIO.read(new File(args[1]));
        int bg = sw.getRGB(0, 0);
        int missing = 0, swInk = 0, hwInk = 0;
        for (int y = 0; y < sw.getHeight(); y++) {
            for (int x = 0; x < sw.getWidth(); x++) {
                boolean a = ink(sw.getRGB(x, y), bg), b = ink(hw.getRGB(x, y), bg);
                if (a) swInk++;
                if (b) hwInk++;
                if (a && !b) missing++;
            }
        }
        System.out.printf("ink: software=%d other=%d  software-only (missing)=%d%n", swInk, hwInk, missing);
    }

    private static boolean ink(int p, int bg) {
        int d = 0;
        for (int s = 0; s <= 16; s += 8) {
            d = Math.max(d, Math.abs(((p >> s) & 0xff) - ((bg >> s) & 0xff)));
        }
        return d > 48;
    }
}
