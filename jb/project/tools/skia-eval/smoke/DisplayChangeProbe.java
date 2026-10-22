import java.awt.*;
import java.awt.image.*;

/** Drives the display-change path twice: are the configurations new and is the Dawn device shared (trace lines)? */
public class DisplayChangeProbe {
    public static void main(String[] args) throws Exception {
        GraphicsEnvironment env = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsConfiguration gc0 = env.getDefaultScreenDevice().getDefaultConfiguration();
        VolatileImage vi = gc0.createCompatibleVolatileImage(64, 64);
        Graphics2D g = vi.createGraphics(); g.setColor(Color.RED); g.fillRect(0, 0, 64, 64); g.dispose();
        System.out.println("config0=" + System.identityHashCode(gc0) + " " + gc0.getClass().getSimpleName());
        for (int i = 1; i <= 2; i++) {
            ((sun.awt.Win32GraphicsDevice) env.getDefaultScreenDevice()).displayChanged();   // device only: the old image keeps its surface
            GraphicsConfiguration gc = env.getDefaultScreenDevice().getDefaultConfiguration();
            System.out.println("config" + i + "=" + System.identityHashCode(gc) + " validate(old image)="
                    + vi.validate(gc) + " (2 = IMAGE_INCOMPATIBLE)");
            // a blit of the old image into a new image: a cross-configuration source
            vi.validate(gc0); g = vi.createGraphics(); g.setColor(Color.RED); g.fillRect(0, 0, 64, 64); g.dispose();
            VolatileImage vi2 = gc.createCompatibleVolatileImage(64, 64);
            Graphics2D g2 = vi2.createGraphics(); g2.drawImage(vi, 0, 0, null); g2.dispose();
            System.out.println("  blit old->new centre=" + Integer.toHexString(vi2.getSnapshot().getRGB(32, 32)) + " (ffff0000 = drawn, else skipped)");
        }
        System.exit(0);
    }
}
