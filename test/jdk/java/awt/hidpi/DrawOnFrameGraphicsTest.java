import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.util.concurrent.CountDownLatch;

/* @test
 * bug JRE-681
 * @summary Tests that drawing directly into frame's graphics doesn't shift relative to the frame's content.
 * @author Anton Tarasov
 * @requires (os.family == "windows")
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true
 *                   -Dsun.java2d.uiScale=1.25
 *                   -Dsun.java2d.d3d=false
 *                    DrawOnFrameGraphicsTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true
 *                   -Dsun.java2d.uiScale=1.5
 *                   -Dsun.java2d.d3d=false
 *                    DrawOnFrameGraphicsTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true
 *                   -Dsun.java2d.uiScale=1.75
 *                   -Dsun.java2d.d3d=false
 *                    DrawOnFrameGraphicsTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true
 *                   -Dsun.java2d.uiScale=2.0
 *                   -Dsun.java2d.d3d=false
 *                    DrawOnFrameGraphicsTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true
 *                   -Dsun.java2d.uiScale=2.25
 *                   -Dsun.java2d.d3d=false
 *                    DrawOnFrameGraphicsTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true
 *                   -Dsun.java2d.uiScale=2.5
 *                   -Dsun.java2d.d3d=false
 *                    DrawOnFrameGraphicsTest
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true
 *                   -Dsun.java2d.uiScale=2.75
 *                   -Dsun.java2d.d3d=false
 *                    DrawOnFrameGraphicsTest
 */
// Note: -Dsun.java2d.d3d=false is the current IDEA mode.
public class DrawOnFrameGraphicsTest {
    static final int F_WIDTH = 300;
    static final int F_HEIGHT = 200;

    static final Color FRAME_BG = Color.GREEN;
    static final Color RECT_COLOR_1 = Color.RED;
    static final Color RECT_COLOR_2 = Color.BLUE;
    static final int RECT_SIZE = 20;

    static final Rectangle rect = new Rectangle(F_WIDTH/2 - RECT_SIZE/2, F_HEIGHT/2 - RECT_SIZE/2, RECT_SIZE, RECT_SIZE);
    static JFrame frame;
    static JComponent comp;

    static volatile CountDownLatch latch = new CountDownLatch(1);
    static volatile boolean framePainted = false;

    static Robot robot;

    public static void main(String[] args) throws AWTException, InterruptedException {
        try {
            robot = new Robot();
        } catch (AWTException e) {
            throw new RuntimeException(e);
        }

        EventQueue.invokeLater(DrawOnFrameGraphicsTest::show);

        Timer timer = new Timer(100, (event) -> {
            Point loc;
            try {
                loc = frame.getContentPane().getLocationOnScreen();
            } catch (IllegalComponentStateException e) {
                latch.countDown();
                return;
            }
            BufferedImage capture = robot.createScreenCapture(
                new Rectangle(loc.x + 50, loc.y + 50, 1, 1));
            Color pixel = new Color(capture.getRGB(0, 0));
            framePainted = FRAME_BG.equals(pixel);

            latch.countDown();
            return;
        });

        timer.setRepeats(false);
        latch.await(); // wait for ACTIVATED
        latch = new CountDownLatch(1);

        //noinspection Duplicates
        while (!framePainted) {
            timer.start();
            latch.await();
            latch = new CountDownLatch(1);
        }

        //
        // Draw on the frame
        //
        EventQueue.invokeLater(DrawOnFrameGraphicsTest::draw);
        latch.await();

        //
        // Take the capture of the colored rect with some extra space
        //
        Point pt = comp.getLocationOnScreen();
        BufferedImage capture = robot.createScreenCapture(
            new Rectangle(pt.x + rect.x - 5, pt.y + rect.y - 5,
                    rect.width + 10, rect.height + 10));

        //
        // Test RECT_COLOR_1 is fully covered with RECT_COLOR_2
        //
        boolean hasRectColor2 = false;
        for (int x=0; x<capture.getWidth(); x++) {
            for (int y = 0; y < capture.getHeight(); y++) {
                Color pix = new Color(capture.getRGB(x, y));
                if (RECT_COLOR_1.equals(pix)) {
                    throw new RuntimeException("Test FAILED!");
                }
                hasRectColor2 |= RECT_COLOR_2.equals(pix);
            }
        }
        if (!hasRectColor2) {
            throw new RuntimeException("Test FAILED!");
        }
        System.out.println("Test PASSED");
    }

    static void show() {
        frame = new JFrame("frame");
        frame.setLocationRelativeTo(null);
        frame.setBackground(FRAME_BG);
        frame.getContentPane().setBackground(FRAME_BG);

        comp = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2d = (Graphics2D)g;
                g2d.setColor(RECT_COLOR_1);
                g2d.fill(rect);
            }

            @Override
            public Dimension getPreferredSize() {
                return new Dimension(F_WIDTH, F_HEIGHT);
            }
        };

        frame.add(comp);
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

    static void draw() {
        Graphics2D g2d = (Graphics2D)frame.getGraphics();
        g2d.setColor(RECT_COLOR_2);

        Point fpt = frame.getLocationOnScreen();
        Point cpt = comp.getLocationOnScreen();
        cpt.translate(-fpt.x, -fpt.y);

        g2d.fill(new Rectangle(cpt.x + rect.x, cpt.y + rect.y, rect.width, rect.height));
        latch.countDown();
    }
}