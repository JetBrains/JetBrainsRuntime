import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;

import sun.awt.AWTAccessor;
import sun.awt.windows.WComponentPeer;
import sun.java2d.SurfaceData;

/**
 * Which pipeline a run actually renders on, and whether it renders: the default configuration,
 * the surface of a VolatileImage and of a window, and the colour of a pixel drawn into each
 * (off-screen through getSnapshot, on-screen through Robot). For checking an arm before
 * measuring it.
 *
 *   java --add-exports java.desktop/sun.awt=ALL-UNNAMED --add-exports java.desktop/sun.awt.windows=ALL-UNNAMED
 *        --add-exports java.desktop/sun.java2d=ALL-UNNAMED <pipeline properties> PipelineProbe
 */
public class PipelineProbe {
    public static void main(String[] args) throws Exception {
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        System.out.println("configuration: " + gc.getClass().getName());

        VolatileImage vi = gc.createCompatibleVolatileImage(64, 64);
        vi.validate(gc);
        Graphics2D g = vi.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 64, 64);
        g.setColor(new Color(0x2050C0));
        g.fillRect(8, 8, 16, 16);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setFont(new Font(Font.DIALOG, Font.PLAIN, 12));
        g.setColor(Color.BLACK);
        g.drawString("Probe", 4, 50);
        g.dispose();
        BufferedImage snap = vi.getSnapshot();
        System.out.printf("volatile image: %s, pixel (12,12) = %06x (expected 2050c0)%n",
                SurfaceData.getPrimarySurfaceData(vi).getClass().getName(), snap.getRGB(12, 12) & 0xffffff);

        JFrame[] frame = new JFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            JFrame f = new JFrame("PipelineProbe");
            javax.swing.JPanel panel = new javax.swing.JPanel() {
                @Override
                protected void paintComponent(java.awt.Graphics pg) {
                    pg.setColor(new Color(0xC02050));
                    pg.fillRect(0, 0, getWidth(), getHeight());
                }
            };
            f.setContentPane(panel);
            new javax.swing.Timer(30, e -> panel.repaint()).start();   // Swing paint cycles, each ending in updateWindows
            f.setUndecorated(true);
            f.setAlwaysOnTop(true);   // Robot reads the screen: nothing may cover the window
            f.setBounds(100, 100, 200, 150);
            f.setVisible(true);
            frame[0] = f;
        });
        java.awt.Robot robot = new java.awt.Robot();
        robot.waitForIdle();
        Thread.sleep(2000);
        Object peer = AWTAccessor.getComponentAccessor().getPeer(frame[0]);
        SurfaceData sd = peer instanceof WComponentPeer w ? w.getSurfaceData() : null;
        Color c = robot.getPixelColor(200, 175);
        System.out.printf("window: %s, screen pixel = %02x%02x%02x (expected c02050)%n",
                sd == null ? "none" : sd.getClass().getName(), c.getRed(), c.getGreen(), c.getBlue());
        SwingUtilities.invokeAndWait(frame[0]::dispose);
        System.exit(0);
    }
}
