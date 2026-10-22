import java.awt.*;
import java.awt.image.*;
import javax.swing.*;

/**
 * Pipeline-compile startup cost: the first frame of a primitive mix pays for
 * Graphite's pipeline compilation (and Dawn's shader translation); later
 * frames do not. Times frames 1..5 off-screen (VolatileImage, forced to the GPU
 * by getSnapshot) and the first on-screen present of a window.
 */
public class StartupCost {
    public static void main(String[] args) throws Exception {
        long t0 = System.nanoTime();
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        System.out.printf("config %s after %.0f ms%n", gc.getClass().getSimpleName(), (System.nanoTime() - t0) / 1e6);
        VolatileImage vi = gc.createCompatibleVolatileImage(620, 300);
        for (int frame = 1; frame <= 5; frame++) {
            long f0 = System.nanoTime();
            Graphics2D g = vi.createGraphics();
            SkiaRender.paintScene(g, 620, 300);
            g.dispose();
            vi.getSnapshot();   // waits for the GPU
            System.out.printf("off-screen frame %d: %.1f ms%n", frame, (System.nanoTime() - f0) / 1e6);
        }
        JFrame f = new JFrame("startup");
        JPanel p = new JPanel() {
            long first = 0;
            @Override protected void paintComponent(Graphics gg) {
                long s = System.nanoTime();
                SkiaRender.paintScene((Graphics2D) gg, getWidth(), getHeight());
                Toolkit.getDefaultToolkit().sync();
                if (first == 0) { first = s; System.out.printf("window first paint+sync: %.1f ms (%.0f ms after start)%n", (System.nanoTime() - s) / 1e6, (s - t0) / 1e6); }
                else System.out.printf("window paint+sync: %.1f ms%n", (System.nanoTime() - s) / 1e6);
            }
        };
        p.setPreferredSize(new Dimension(620, 300));
        f.getContentPane().add(p);
        f.pack(); f.setLocation(100, 100); f.setAlwaysOnTop(true); f.setVisible(true);
        for (int i = 0; i < 3; i++) { Thread.sleep(300); SwingUtilities.invokeAndWait(p::repaint); }
        Thread.sleep(500);
        System.exit(0);
    }
}
