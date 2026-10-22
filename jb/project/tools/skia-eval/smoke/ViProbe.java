import java.awt.*;
import java.awt.image.*;

/** Which surface a compatible VolatileImage of a given size gets, before and after a render and a snapshot. */
public class ViProbe {
    public static void main(String[] args) throws Exception {
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        System.out.println("gc: " + gc.getClass().getName());
        for (String dim : args) {
            int w = Integer.parseInt(dim.split("x")[0]), h = Integer.parseInt(dim.split("x")[1]);
            VolatileImage vi = gc.createCompatibleVolatileImage(w, h, Transparency.OPAQUE);
            System.out.println(dim + " created: " + sun.java2d.SurfaceData.getPrimarySurfaceData(vi).getClass().getName());
            Graphics2D g = vi.createGraphics();
            g.setColor(Color.RED); g.fillRect(0, 0, w, h);
            g.setColor(Color.BLUE); g.fillOval(0, 0, w, h);
            g.setFont(new Font("Dialog", Font.BOLD, 24)); g.setColor(Color.WHITE);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
            g.drawString("AVfi To AVfi Way", 10, 40);
            g.setXORMode(Color.GREEN); g.fillRect(5, 5, 50, 50); g.setPaintMode();
            g.dispose();
            System.out.println(dim + " after draw: " + sun.java2d.SurfaceData.getPrimarySurfaceData(vi).getClass().getName() + " lost=" + vi.contentsLost());
            BufferedImage snap = vi.getSnapshot();
            System.out.println(dim + " after snapshot: " + sun.java2d.SurfaceData.getPrimarySurfaceData(vi).getClass().getName() + " lost=" + vi.contentsLost() + " px=" + Integer.toHexString(snap.getRGB(w / 2, h / 2)));
        }
    }
}
