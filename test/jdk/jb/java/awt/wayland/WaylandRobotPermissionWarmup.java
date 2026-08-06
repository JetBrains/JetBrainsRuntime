import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;

/*
  * @test
  @key headful
  @run main WaylandRobotPermissionWarmup
*/
public class WaylandRobotPermissionWarmup {
    private static final int STEP_DELAY_MS = 1500;
    private static final int BETWEEN_ROUNDS_MS = 2500;

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeLater(() -> {
            try {
                createAndShowUi();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void createAndShowUi() throws Exception {
        JFrame frame = new JFrame("Wayland Robot Permission Warmup");
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);

        JTextArea area = new JTextArea(12, 60);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setText("""
                This program uses java.awt.Robot to trigger all common Wayland permission requests.

                What will happen:
                1. The program will take a screenshot.
                2. It will move the mouse.
                3. It will click left, middle, and right mouse buttons.
                4. It will scroll the mouse wheel.
                5. It will type the letter A.

                Re-run the program afterwards to make sure no permission requests appear.
                """);

        JButton startButton = new JButton("Start");
        JLabel status = new JLabel("Ready");

        startButton.addActionListener(e -> {
            startButton.setEnabled(false);
            status.setText("Running...");
            new Thread(() -> {
                try {
                    runWarmup(frame, status, startButton);
                } catch (Exception e1) { throw new RuntimeException(e1); }
            }, "robot-warmup").start();
        });

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bottom.add(startButton);
        bottom.add(status);

        frame.add(new JScrollPane(area), BorderLayout.CENTER);
        frame.add(bottom, BorderLayout.SOUTH);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private static void runWarmup(JFrame frame, JLabel status, JButton startButton) throws Exception {
        try {
            Robot robot = new Robot();
            robot.setAutoDelay(150);
            robot.setAutoWaitForIdle(true);

            SwingUtilities.invokeAndWait(() ->
                    status.setText("Round 1: grant any Wayland permissions that appear"));

            runAllRobotActions(robot, frame);

            SwingUtilities.invokeLater(() ->
                    status.setText("Done"));
        } catch (AWTException ex) {
            SwingUtilities.invokeLater(() ->
                    status.setText("Failed to create Robot: " + ex.getMessage()));
            ex.printStackTrace();
        } finally {
            SwingUtilities.invokeLater(() -> startButton.setEnabled(true));
        }
    }

    private static void runAllRobotActions(Robot robot, JFrame frame) throws Exception {
        // Bring the app to front and give it focus.
        SwingUtilities.invokeAndWait(() -> {
            frame.toFront();
            frame.requestFocus();
        });
        sleep(STEP_DELAY_MS);

        // 1. Screenshot / pixel access
        Rectangle screenBounds = getVirtualScreenBounds();
        BufferedImage capture = robot.createScreenCapture(screenBounds);
        System.out.println("Captured screen: " + capture.getWidth() + "x" + capture.getHeight());
        sleep(STEP_DELAY_MS);

        // 2. Mouse move
        Point target = frame.getLocationOnScreen();
        int x = target.x + Math.min(100, frame.getWidth() / 2);
        int y = target.y + Math.min(100, frame.getHeight() / 2);
        robot.mouseMove(x, y);
        sleep(STEP_DELAY_MS);

        // 3. Mouse buttons
        click(robot, InputEvent.BUTTON1_DOWN_MASK);
        sleep(500);
        click(robot, InputEvent.BUTTON2_DOWN_MASK);
        sleep(500);
        click(robot, InputEvent.BUTTON3_DOWN_MASK);
        sleep(STEP_DELAY_MS);

        // 4. Mouse wheel
        robot.mouseWheel(1);
        sleep(500);
        robot.mouseWheel(-1);
        sleep(STEP_DELAY_MS);

        // 5. Keyboard typing: type 'A'
        robot.keyPress(KeyEvent.VK_SHIFT);
        robot.keyPress(KeyEvent.VK_A);
        robot.keyRelease(KeyEvent.VK_A);
        robot.keyRelease(KeyEvent.VK_SHIFT);
        sleep(STEP_DELAY_MS);
    }

    private static void click(Robot robot, int buttonMask) {
        robot.mousePress(buttonMask);
        robot.mouseRelease(buttonMask);
    }

    private static Rectangle getVirtualScreenBounds() {
        Rectangle allBounds = new Rectangle();
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        for (GraphicsDevice gd : ge.getScreenDevices()) {
            for (GraphicsConfiguration gc : gd.getConfigurations()) {
                allBounds = allBounds.union(gc.getBounds());
            }
        }
        return allBounds;
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
