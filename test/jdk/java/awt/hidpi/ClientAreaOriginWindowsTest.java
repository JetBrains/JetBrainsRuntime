import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;

/* @test
 * bug JRE-604
 * @summary Tests that the frame's client area origin is correctly positioned in the frame.
 * @author Anton Tarasov
 * @requires (os.family == "windows")
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true
 *                   -Dsun.java2d.uiScale=1.25
 *                   -Dsun.java2d.d3d=false
 *                    ClientAreaOriginWindowsTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true
 *                   -Dsun.java2d.uiScale=1.5
 *                   -Dsun.java2d.d3d=false
 *                    ClientAreaOriginWindowsTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true
 *                   -Dsun.java2d.uiScale=1.75
 *                   -Dsun.java2d.d3d=false
 *                    ClientAreaOriginWindowsTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true
 *                   -Dsun.java2d.uiScale=2.0
 *                   -Dsun.java2d.d3d=false
 *                    ClientAreaOriginWindowsTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true
 *                   -Dsun.java2d.uiScale=2.25
 *                   -Dsun.java2d.d3d=false
 *                    ClientAreaOriginWindowsTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true
 *                   -Dsun.java2d.uiScale=2.5
 *                   -Dsun.java2d.d3d=false
 *                    ClientAreaOriginWindowsTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true
 *                   -Dsun.java2d.uiScale=2.75
 *                   -Dsun.java2d.d3d=false
 *                    ClientAreaOriginWindowsTest
 */
//
// Notes:
// 1) -Dsun.java2d.d3d=false is the current IDEA (ver. 181) mode.
// 2) The JDK build should contain the fix for JRE-573 for the test to pass.
//
public class ClientAreaOriginWindowsTest {
    static final int F_WIDTH = 300;
    static final int F_HEIGHT = 200;

    static final Color COLOR_BG = Color.green;
    static final Color COLOR_OUTLINE = Color.red;
    static final Color COLOR_FG = Color.blue;

    static volatile JFrame frame;
    static volatile Timer timer;

    static volatile CountDownLatch latch = new CountDownLatch(1);
    static volatile boolean framePainted = false;

    public static void main(String[] args) throws InterruptedException {
        EventQueue.invokeLater(() -> show());

        timer = new Timer(100, (event) -> {
            Point loc;
            try {
                loc = frame.getContentPane().getLocationOnScreen();
            } catch (IllegalComponentStateException e) {
                latch.countDown();
                return;
            }
            Rectangle rect = new Rectangle(loc.x - 1, loc.y - 1, 6, 6);
            Robot robot;
            try {
                robot = new Robot();
            } catch (AWTException e) {
                throw new RuntimeException(e);
            }
            BufferedImage capture = robot.createScreenCapture(rect);
            int width = capture.getWidth();
            int height = capture.getHeight();

            // First, check the frame's client area is painted, otherwise bounce.
            Color fgPixel = new Color(capture.getRGB(width - 2, height - 2));
            if (!COLOR_FG.equals(fgPixel)) {
                latch.countDown();
                return;
            }
            framePainted = true;

            Function<Boolean, String> check = (isXaxis) -> {
                StringBuilder err = new StringBuilder();
                boolean hasOutline = false;
                boolean hasBg = false;
                boolean hasFg = false;

                for (int i = (isXaxis ? width - 1 : height - 1); i >= 0; i--) {
                    int x = isXaxis ? i : width - 1;
                    int y = isXaxis ? height - 1 : i;
                    Color c = new Color(capture.getRGB(x, y));
                    hasOutline = c.equals(COLOR_OUTLINE) || hasOutline;
                    // assuming the frame's border system color is not COLOR_BG/COLOR_BG_FALLBACK.
                    hasBg = c.equals(COLOR_BG) || hasBg;
                    hasFg = c.equals(COLOR_FG) || hasFg;
                }
                String axis = isXaxis ? "X-axis" : "Y-axis";
                if (!hasOutline) err.append("no outline pixel by " + axis);
                if (hasBg) err.append("; has background pixels by " + axis);
                if (!hasFg) err.append("; no foreground pixels by " + axis);
                return err.toString();
            };

            String xAxis = check.apply(true);
            String yAxis = check.apply(false);

            if (xAxis.length() > 0 || yAxis.length() > 0) {
                StringBuilder err = new StringBuilder().
                        append(xAxis).
                        append("; ").
                        append(yAxis);
                throw new RuntimeException("Test FAILED: " + err);
            }
            latch.countDown();
        });
        timer.setRepeats(false);

        latch.await();
        latch = new CountDownLatch(1);

        while (!framePainted) {
            timer.start();
            latch.await();
            if (!framePainted) latch = new CountDownLatch(1);
        }

        System.out.println("Test PASSED");
    }

    static void show() {
        frame = new JFrame("frame");
        frame.setLocationRelativeTo(null);
        frame.setBackground(COLOR_BG);
        frame.getContentPane().setBackground(COLOR_BG);

        JPanel panel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2d = (Graphics2D)g;
                AffineTransform tx = g2d.getTransform();
                double pixelX = 1 / tx.getScaleX();
                double pixelY = 1 / tx.getScaleY();

                g2d.setColor(COLOR_OUTLINE);
                g2d.fill(new Rectangle2D.Double(0, 0, F_WIDTH, F_HEIGHT));
                g2d.setColor(COLOR_FG);
                g2d.fill(new Rectangle2D.Double(pixelX, pixelY, F_WIDTH - pixelX * 2, F_HEIGHT - pixelY * 2));
            }

            @Override
            public Dimension getPreferredSize() {
                return new Dimension(F_WIDTH, F_HEIGHT);
            }
        };

        // Backs the main frame with black color.
        JFrame bgFrame = new JFrame("bg_frame");
        bgFrame.setUndecorated(true);
        bgFrame.setSize(F_WIDTH * 2, F_HEIGHT * 2);
        bgFrame.setLocationRelativeTo(null);
        bgFrame.setAlwaysOnTop(true);
        JPanel cp = new JPanel();
        cp.setOpaque(true);
        cp.setBackground(Color.black);
        bgFrame.setContentPane(cp);
        bgFrame.setVisible(true);

        frame.add(panel);
        frame.pack();
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLocationRelativeTo(null);
        frame.setAlwaysOnTop(true);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowActivated(WindowEvent e) {
                latch.countDown();
            }
        });
        frame.setVisible(true);
    }
}