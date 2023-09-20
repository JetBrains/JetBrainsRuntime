import java.awt.*;
import java.lang.reflect.InvocationTargetException;

/*
 * @test
 * @summary JBR-6060 Verify that the focus traverse to the next component if the current focused became invisible
 * @run main/othervm FocusTraversalOrderTest
 */
public class FocusTraversalOrderTest {

    private static Frame frame;
    private static Button button0, button1, button2, button3, button4;

    public static void main(String... args) throws InterruptedException, InvocationTargetException, AWTException {
        Robot robot = new Robot();
        try {
            EventQueue.invokeAndWait(() -> {
                frame = new Frame();
                frame.setLayout(new FlowLayout());
                frame.setFocusTraversalPolicy(new ContainerOrderFocusTraversalPolicy());
                button0 = new Button();
                button0.setFocusable(true);
                frame.add(button0);

                button1 = new Button();
                button1.setFocusable(true);
                frame.add(button1);

                button2 = new Button();
                button2.setFocusable(true);
                frame.add(button2);

                button3 = new Button();
                button3.setFocusable(true);
                frame.add(button3);

                button4 = new Button();
                button4.setFocusable(true);
                frame.add(button4);

                frame.pack();
                frame.setVisible(true);
            });
            frame.requestFocus();
            frame.toFront();
            robot.waitForIdle();
            button2.requestFocusInWindow();
            robot.waitForIdle();
            button2.setVisible(false);
            robot.waitForIdle();

            boolean isRightFocus = button3.isFocusOwner();
            if (!isRightFocus) {
                System.out.println("ERROR: button3 expected to be focus owner");
            }

            FocusTraversalPolicy focusTraversalPolicy = frame.getFocusTraversalPolicy();
            Component compAfterButton2 = focusTraversalPolicy.getComponentAfter(frame, button2);

            boolean isRightNextComp = compAfterButton2.equals(button3);
            if (!isRightNextComp) {
                System.out.println("ERROR: the next component after button2 expected to be button3, but got " + compAfterButton2.getName());
            }

            if (!(isRightFocus && isRightNextComp)) {
                throw new RuntimeException("TEST FAILED: the next component didn't gain the focus");
            } else {
                System.out.println("TEST PASSED");
            }
        } finally {
            EventQueue.invokeAndWait(() -> {
                if (frame != null) {
                    frame.dispose();
                }
            });
        }
    }

}