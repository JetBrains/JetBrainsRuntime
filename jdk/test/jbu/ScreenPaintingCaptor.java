import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;
import javax.swing.*;

public class ScreenPaintingCaptor {
    public static BufferedImage capture(int width, int height, Consumer<Graphics2D> painter) throws Exception {
        JFrame[] f = new JFrame[1];
        Point[] p = new Point[1];
        SwingUtilities.invokeAndWait(() -> {
            f[0] = new JFrame();
            JComponent c = new MyComponent(painter);
            f[0].add(c);
            c.setSize(width + 2, height + 2);
            f[0].setSize(width + 100, height + 100); // giving some space for frame border effects, e.g. rounded frame
            c.setLocation(50, 50);
            f[0].setVisible(true);
            p[0]= c.getLocationOnScreen();
        });
        Robot r = new Robot();
        while (!Color.black.equals(r.getPixelColor(p[0].x, p[0].y))) {
            Thread.sleep(100);
        }
        BufferedImage i = r.createScreenCapture(new Rectangle(p[0].x + 1, p[0].y + 1, width, height));
        SwingUtilities.invokeAndWait(f[0]::dispose);
        return i;
    }

    private static class MyComponent extends JComponent {
        private final Consumer<Graphics2D> painter;

        private MyComponent(Consumer<Graphics2D> painter) {
            this.painter = painter;
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.translate(1, 1);
            Shape savedClip = g.getClip();
            g.clipRect(0, 0, getWidth() - 2, getHeight() - 2);
            painter.accept((Graphics2D)g);
            g.setClip(savedClip);
            g.setColor(Color.black);
            g.drawRect(-1, -1, getWidth() - 1, getHeight() - 1);
        }
    }
}
