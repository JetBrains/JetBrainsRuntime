import java.awt.*; import java.awt.image.BufferedImage; import javax.imageio.ImageIO; import java.io.File;
/** Before/after contact sheet: 1:1 crops side by side, a 4x detail below, labels. java LcdContact <before.png> <after.png> <out.png> <title> <cropX> <cropY> <cropW> <cropH> <detailX> <detailY> <detailW> <detailH> */
public class LcdContact {
    static int fringes(BufferedImage im, int x, int y, int w, int h) { int n = 0; for (int j = y; j < y + h; j++) for (int i = x; i < x + w; i++) { int p = im.getRGB(i, j); if (((p >> 16) & 255) != (p & 255)) n++; } return n; }
    public static void main(String[] a) throws Exception {
        BufferedImage b = ImageIO.read(new File(a[0])), f = ImageIO.read(new File(a[1]));
        int cx = Integer.parseInt(a[4]), cy = Integer.parseInt(a[5]), cw = Integer.parseInt(a[6]), ch = Integer.parseInt(a[7]);
        int dx = Integer.parseInt(a[8]), dy = Integer.parseInt(a[9]), dw = Integer.parseInt(a[10]), dh = Integer.parseInt(a[11]);
        cw = Math.min(cw, Math.min(b.getWidth(), f.getWidth()) - cx); ch = Math.min(ch, Math.min(b.getHeight(), f.getHeight()) - cy);
        int Z = Math.max(1, Math.min(4, cw / dw)), pad = 16, label = 22;   // the magnification that fits the column
        int W = pad + cw + pad + cw + pad, H = label + label + pad + ch + pad + label + dh * Z + pad;
        BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics(); g.setColor(new Color(0xf4f4f4)); g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.BLACK); g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        String beforeLabel = a.length > 12 ? a[12] : "before: grayscale (no pixel geometry on the surface)", afterLabel = a.length > 13 ? a[13] : "after: LCD (sub-pixel) masks";
        g.drawString(a[3], pad, 15);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        g.drawString(beforeLabel, pad, label + 15);
        g.drawString(afterLabel, pad + cw + pad, label + 15);
        int y1 = label + label + pad;
        g.drawImage(b.getSubimage(cx, cy, cw, ch), pad, y1, null);
        g.drawImage(f.getSubimage(cx, cy, cw, ch), pad + cw + pad, y1, null);
        int y2 = y1 + ch + pad;
        g.drawString(String.format("%dx detail of the framed %dx%d region — pixels with R != B: before %d, after %d", Z, dw, dh, fringes(b, dx, dy, dw, dh), fringes(f, dx, dy, dw, dh)), pad, y2 + 15);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(b.getSubimage(dx, dy, dw, dh), pad, y2 + label, dw * Z, dh * Z, null);
        g.drawImage(f.getSubimage(dx, dy, dw, dh), pad + cw + pad, y2 + label, dw * Z, dh * Z, null);
        g.setColor(new Color(0xd04040)); g.drawRect(pad + dx - cx, y1 + dy - cy, dw, dh); g.drawRect(pad + cw + pad + dx - cx, y1 + dy - cy, dw, dh);
        g.dispose(); ImageIO.write(out, "png", new File(a[2]));
        System.out.printf("%s: %dx%d, fringe pixels in crop before %d after %d%n", new File(a[2]).getName(), W, H, fringes(b, cx, cy, cw, ch), fringes(f, cx, cy, cw, ch));
    }
}
