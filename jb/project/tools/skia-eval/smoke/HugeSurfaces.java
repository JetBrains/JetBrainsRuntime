import java.awt.*;
import java.awt.image.*;
import javax.swing.*;
import sun.awt.AWTAccessor;
import sun.awt.windows.WComponentPeer;
import sun.java2d.windows.WindowsFlags;

/**
 * R10/R15 check: surfaces beyond the adapter's texture limit must fall back
 * (GDI for the window, the software backup for the VolatileImage) without a
 * Dawn error object silently taking their place; a normal window must be a
 * Skia surface even with -Dsun.java2d.d3d=true.
 */
public class HugeSurfaces {
    public static void main(String[] args) throws Exception {
        System.out.println("WindowsFlags.isD3DEnabled=" + WindowsFlags.isD3DEnabled());
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        System.out.println("default config: " + gc.getClass().getName());
        VolatileImage vi = gc.createCompatibleVolatileImage(20000, 64);
        Graphics2D g = vi.createGraphics();
        g.setColor(Color.RED); g.fillRect(0, 0, 20000, 64);
        g.dispose();
        BufferedImage snap = vi.getSnapshot();
        Object sd = Class.forName("sun.awt.image.SurfaceManager").getMethod("getManager", Image.class).invoke(null, vi);
        sd = sd.getClass().getMethod("getPrimarySurfaceData").invoke(sd);
        System.out.println("huge VolatileImage: surface=" + sd.getClass().getName() + " pixel=" + Integer.toHexString(snap.getRGB(19999, 63)));

        for (int w : new int[] { 400, 20000 }) {
            JFrame f = new JFrame("w=" + w);
            JPanel p = new JPanel() {
                @Override protected void paintComponent(Graphics gg) { gg.setColor(Color.GREEN); gg.fillRect(0, 0, getWidth(), getHeight()); }
            };
            f.getContentPane().add(p);
            f.setSize(w, 200);
            f.setLocation(0, 100);
            f.setAlwaysOnTop(true);
            f.setVisible(true);
            Thread.sleep(2000);
            WComponentPeer peer = (WComponentPeer) AWTAccessor.getComponentAccessor().getPeer(f);
            Point pt = p.getLocationOnScreen();
            Color c = new Robot().getPixelColor(pt.x + 10, pt.y + 10);
            System.out.println("window " + w + " px: surface=" + peer.getSurfaceData().getClass().getName()
                               + " bounds=" + f.getBounds() + " pixel=" + c);
            f.dispose();
        }
        System.exit(0);
    }
}
