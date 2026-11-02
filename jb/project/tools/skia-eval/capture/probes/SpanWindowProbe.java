import java.awt.*;
import java.awt.image.BufferedImage;
import javax.swing.*;
import java.io.File;
import javax.imageio.ImageIO;

/** The span probe's content painted by a Swing panel in a window (the IDE's path: Swing back buffer, then the window), captured by Robot, against the software loops. */
public class SpanWindowProbe {
    static final int W = 320, H = 60;
    public static void main(String[] args) throws Exception {
        BufferedImage sw = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        SpanProbe.paint(sw.createGraphics());
        JFrame f = new JFrame("span");
        f.setUndecorated(true);
        JPanel p = new JPanel() { protected void paintComponent(Graphics g) { SpanProbe.paint((Graphics2D) g.create()); } };
        p.setPreferredSize(new Dimension(W, H));
        f.add(p); f.pack(); f.setLocation(200, 200); f.setAlwaysOnTop(true); f.setVisible(true);
        Thread.sleep(1500);
        for (int i = 0; i < 5; i++) { SwingUtilities.invokeAndWait(p::repaint); Thread.sleep(200); }
        Thread.sleep(500);
        Point loc = p.getLocationOnScreen();
        BufferedImage hw = new Robot().createScreenCapture(new Rectangle(loc.x, loc.y, W, H));
        int max = 0, over = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int a = sw.getRGB(x, y), b = hw.getRGB(x, y), d = 0;
            for (int s = 0; s <= 16; s += 8) d = Math.max(d, Math.abs(((a >> s) & 255) - ((b >> s) & 255)));
            max = Math.max(max, d); if (d > 32) over++;
        }
        System.out.println("span window probe: max " + max + ", >32: " + over);
        ImageIO.write(hw, "png", new File("out/span-window.png"));
        System.exit(0);
    }
}
