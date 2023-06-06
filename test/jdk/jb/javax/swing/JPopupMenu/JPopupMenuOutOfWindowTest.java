import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Arrays;

/*
 * @test
 * @summary Regression test for JBR-2870
 *          Opening a JPopupMenu that overlaps WM dock panel
 *           and checks that all menu items are clickable
 * @run main/othervm JPopupMenuOutOfWindowTest
 */
public class JPopupMenuOutOfWindowTest {

    private static JFrame frame;
    private static JPopupMenu menu;
    private static final int BUTTONS_COUNT = 10;
    private static final JMenuItem[] menuItems = new JMenuItem[BUTTONS_COUNT];
    private static final boolean[] buttonClicked = new boolean[BUTTONS_COUNT];

    public static void main(String... args) throws Exception {
        Robot robot = new Robot();
        Arrays.fill(buttonClicked, false);

        try {
            SwingUtilities.invokeAndWait(JPopupMenuOutOfWindowTest::initUI);

            robot.waitForIdle();
            Point clickLocation = calculateClickCoordinates(frame);
            System.out.println("Going to open popup menu at " + clickLocation);

            robot.mouseMove(clickLocation.x, clickLocation.y);
            robot.mousePress(InputEvent.BUTTON3_DOWN_MASK);
            robot.mouseRelease(InputEvent.BUTTON3_DOWN_MASK);
            robot.delay(1000);

            for (int i = 0; i < BUTTONS_COUNT; i++) {
                JMenuItem item = menuItems[i];
                System.out.println("Click to menu item " + i);
                Point location = item.getLocationOnScreen();
                final int x = location.x + item.getWidth() / 2;
                final int y = location.y + item.getHeight() / 2;
                robot.mouseMove(x, y);
                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
                robot.waitForIdle();
            }

            for (int i = 0; i < BUTTONS_COUNT; i++) {
                if (!buttonClicked[i]) {
                    throw new RuntimeException("TEST FAILED: menu item " + i + " didn't receive a click");
                }
            }
            System.out.println("TEST PASSED");
        } finally {
            SwingUtilities.invokeAndWait(JPopupMenuOutOfWindowTest::disposeUI);
        }
    }

    private static void initUI() {
        frame = new JFrame();
        frame.setBounds(calculateWindowBounds());

        JPanel panel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                Rectangle r = g.getClipBounds();
                g.setColor(Color.BLUE);
                g.fillRect(r.x, r.y, r.width, r.height);
            }
        };

        panel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.getButton() == 3) {
                    toggleMenu(e);
                }
            }
        });

        menu = new JPopupMenu();
        for (int i = 0; i < BUTTONS_COUNT; i++) {
            final int buttonNumber = i;
            final String name = "Item " + buttonNumber;
            JMenuItem item = new JMenuItem(name);
            item.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    System.out.println("Clicked to button " + buttonNumber);
                    buttonClicked[buttonNumber] = true;
                }
            });
            menu.add(item);
            menuItems[i] = item;
        }

        frame.add(menu);
        frame.setContentPane(panel);
        frame.setVisible(true);
    }

    /**
     * Change visibility of the popup menu.
     *  Show the menu at the mouse location if it's hidden
     * @param e MouseEvent
     */
    private static void toggleMenu(MouseEvent e) {
        menu.setLocation(e.getLocationOnScreen().x, e.getLocationOnScreen().y);
        menu.setVisible(!menu.isShowing());
    }

    private static Rectangle calculateWindowBounds() {
        GraphicsConfiguration config = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(config);
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();

        final int x = insets.left;
        final int w = screenSize.width - x - insets.right;
        final int y = insets.top;
        final int h = screenSize.height - y - insets.bottom;

        return new Rectangle(x, y, w, h);
    }

    private static Point calculateClickCoordinates(Window window) {
        GraphicsConfiguration config = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(config);
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();

        final int x = insets.left + (screenSize.height - insets.left - insets.right) / 2;
        final int y = screenSize.height - insets.bottom - window.getInsets().bottom - 10;
        return new Point(x, y);
    }

    private static void disposeUI() {
        if (frame != null) {
            frame.setVisible(false);
            frame.dispose();
        }
    }

}
