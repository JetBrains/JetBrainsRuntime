import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.io.File;
import java.lang.reflect.Method;
import javax.imageio.ImageIO;
import javax.swing.*;

/**
 * A window that keeps rendering through a GPU device loss (TDR): TdrProbe <seconds> <out dir>.
 * Animates at about 60 Hz through a cached VolatileImage and logs twice a second the frames painted,
 * the frame's surface class and its lost flags, the configuration's device-lost state, the
 * VolatileImage validate results, and what the screen shows in the window's centre (the probe paints
 * a pure green square there: GREEN, BLACK or OTHER). Captures the window every 5 s. Reads pixels
 * with Robot, never sends input. Run with -Dsun.java2d.uiScale=1.
 */
public class TdrProbe {
    static volatile int painted, restored, incompatible, contentsLost;
    static volatile String lastError = "";

    public static void main(String[] args) throws Exception {
        int seconds = Integer.parseInt(args[0]);
        File out = new File(args[1]);
        out.mkdirs();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> { lastError = e.toString(); e.printStackTrace(); });

        JFrame[] frame = new JFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            JFrame f = new JFrame("TdrProbe");
            f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            f.add(new Canvas());
            f.setSize(640, 480);
            f.setLocation(100, 100);
            f.setVisible(true);
            frame[0] = f;
        });
        new Timer(16, e -> frame[0].repaint()).start();

        Robot robot = new Robot();
        long start = System.currentTimeMillis();
        int lastPainted = 0;
        for (int tick = 0; tick < seconds * 2; tick++) {
            Thread.sleep(500);
            JFrame f = frame[0];
            Point p = f.getContentPane().getLocationOnScreen();
            Dimension d = f.getContentPane().getSize();
            Color centre = robot.getPixelColor(p.x + d.width / 2, p.y + d.height / 2);
            String seen = centre.getGreen() > 200 && centre.getRed() < 60 && centre.getBlue() < 60 ? "GREEN"
                    : centre.getRed() + centre.getGreen() + centre.getBlue() < 30 ? "BLACK" : "OTHER";
            int now = painted;
            System.out.printf("t=%6.1f fps=%3d %s surface=%s gc=%s deviceLost=%s vi[restored=%d incompatible=%d contentsLost=%d] %s%n",
                    (System.currentTimeMillis() - start) / 1000.0, (now - lastPainted) * 2, seen, surface(f),
                    Integer.toHexString(System.identityHashCode(f.getGraphicsConfiguration())), deviceLost(f),
                    restored, incompatible, contentsLost, lastError);
            lastPainted = now;
            if (tick % 10 == 0) {
                BufferedImage shot = robot.createScreenCapture(new Rectangle(p, d));
                ImageIO.write(shot, "png", new File(out, String.format("tdr-%03d.png", tick / 2)));
            }
        }
        System.exit(0);
    }

    static String surface(JFrame f) {
        try {
            Object peer = sun.awt.AWTAccessor.getComponentAccessor().getPeer(f);
            sun.java2d.SurfaceData sd = ((sun.awt.windows.WComponentPeer) peer).getSurfaceData();
            return sd == null ? "null" : sd.getClass().getSimpleName() + (sd.isSurfaceLost() ? "(lost)" : "");
        } catch (Throwable t) {
            return "?" + t;
        }
    }

    static String deviceLost(JFrame f) {
        try {
            GraphicsConfiguration gc = f.getGraphicsConfiguration();
            Method m = gc.getClass().getMethod("isDeviceLost");
            return String.valueOf(m.invoke(gc));
        } catch (NoSuchMethodException e) {
            return "n/a(" + f.getGraphicsConfiguration().getClass().getSimpleName() + ")";
        } catch (Throwable t) {
            return "?" + t;
        }
    }

    static class Canvas extends JPanel {
        VolatileImage cache;

        @Override
        protected void paintComponent(Graphics g) {
            painted++;
            GraphicsConfiguration gc = getGraphicsConfiguration();
            if (cache == null) {
                cache = gc.createCompatibleVolatileImage(96, 96);
                fillCache();
            }
            int v = cache.validate(gc);
            if (v == VolatileImage.IMAGE_INCOMPATIBLE) {
                incompatible++;
                cache = gc.createCompatibleVolatileImage(96, 96);
                fillCache();
            } else if (v == VolatileImage.IMAGE_RESTORED) {
                restored++;
                fillCache();
            }
            Graphics2D g2 = (Graphics2D) g;
            g2.setColor(Color.DARK_GRAY);
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            double a = (System.nanoTime() / 1e9) % (2 * Math.PI);
            g2.setColor(Color.ORANGE);
            g2.fillOval((int) (getWidth() / 2 + 150 * Math.cos(a)) - 30, (int) (getHeight() / 2 + 150 * Math.sin(a)) - 30, 60, 60);
            g2.setColor(Color.WHITE);
            g2.drawString("TdrProbe frame " + painted, 10, 20);
            g2.drawImage(cache, getWidth() / 2 - 48, getHeight() / 2 - 48, null);
            if (cache.contentsLost()) {
                contentsLost++;
            }
        }

        void fillCache() {
            Graphics2D cg = cache.createGraphics();
            cg.setColor(Color.GREEN);
            cg.fillRect(0, 0, 96, 96);
            cg.dispose();
        }
    }
}
