import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;

public class ImageDbg {
    static final int W = 160, H = 120;
    public static void main(String[] a) {
        int type = Integer.parseInt(a[0]); Object interp = a[1].equals("n") ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR : a[1].equals("b") ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : RenderingHints.VALUE_INTERPOLATION_BICUBIC;
        float alpha = Float.parseFloat(a[2]); boolean shear = a[3].equals("shear");
        BufferedImage src = source(type);
        Consumer<Graphics2D> d = g -> { g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interp); g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)); if (a[3].equals("scale")) { g.drawImage(src, AffineTransform.getScaleInstance(1.5, 0.8), null); return; } if (shear) g.shear(0.3, 0.1); else g.rotate(0.35, 80, 60); g.drawImage(src, 20, 20, null); };
        BufferedImage skia = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB_PRE), loops = new BufferedImage(W, H, BufferedImage.TYPE_3BYTE_BGR);
        for (BufferedImage img : new BufferedImage[] {skia, loops}) { Graphics2D g = img.createGraphics(); g.setColor(Color.WHITE); g.fillRect(0, 0, W, H); d.accept(g); g.dispose(); }
        int n = 0, big = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int p = skia.getRGB(x, y), q = loops.getRGB(x, y);
            int dd = 0; for (int s = 0; s <= 16; s += 8) dd = Math.max(dd, Math.abs(((p >> s) & 255) - ((q >> s) & 255)));
            if (dd > 0) { n++; if (dd > 8) { big++; if (big <= 12) System.out.printf("(%d,%d) skia %08x loops %08x d %d%n", x, y, p, q, dd); } }
        }
        System.out.println("differing " + n + ", > 8: " + big);
        int x0 = Integer.parseInt(a[4]), y0 = Integer.parseInt(a[5]);
        for (int y = y0 - 1; y <= y0 + 1; y++) { StringBuilder sb = new StringBuilder(); for (int x = x0 - 2; x <= x0 + 2; x++) sb.append(String.format("%08x/%08x  ", skia.getRGB(x, y), loops.getRGB(x, y))); System.out.println(sb); }
    }
    static BufferedImage source(int type) {
        BufferedImage img = new BufferedImage(64, 48, type);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(30, 80, 200)); g.fillRect(0, 0, 64, 48);
        g.setColor(new Color(240, 200, 40)); g.fillRect(8, 8, 24, 16);
        g.setColor(new Color(200, 40, 40, type == BufferedImage.TYPE_INT_RGB ? 255 : 120)); g.fillOval(30, 10, 30, 30);
        if (type != BufferedImage.TYPE_INT_RGB) { g.setComposite(AlphaComposite.Clear); g.fillRect(50, 36, 14, 12); }
        g.dispose();
        return img;
    }
}
