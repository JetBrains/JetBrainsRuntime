import java.awt.*;
import java.awt.image.*;

/** Clear composite + gradient paint + AA oval on a VolatileImage: which loops run, what comes out. */
public class ClearProbe {
    public static void main(String[] args) throws Exception {
        boolean aa = args.length == 0 || !args[0].equals("noaa");
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        VolatileImage vi = gc.createCompatibleVolatileImage(64, 64);
        Graphics2D g = vi.createGraphics();
        g.setColor(new Color(0xD0D0D0)); g.fillRect(0, 0, 64, 64);
        g.setColor(new Color(0x404040)); g.fillRect(0, 36, 64, 16);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setComposite(AlphaComposite.Clear);
        g.setPaint(new GradientPaint(8, 8, Color.RED, 56, 48, Color.BLUE));
        System.err.println("--- fillOval under Clear ---");
        g.fillOval(8, 8, 48, 40);
        g.dispose();
        BufferedImage snap = vi.getSnapshot();
        int center = snap.getRGB(32, 28), band = snap.getRGB(32, 44), corner = snap.getRGB(2, 2);
        System.out.printf("center=%08x band=%08x corner=%08x -> %s%n", center, band, corner,
                          (center & 0xffffff) == 0 ? "cleared" : "NOT CLEARED");
    }
}
