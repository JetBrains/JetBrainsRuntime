import com.jetbrains.JBR;
import com.jetbrains.WindowDecorations;

import javax.swing.*;
import javax.swing.plaf.basic.BasicComboPopup;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;

/*
 * @test
 * @key headful
 * @requires (os.family == "windows" | os.family == "mac")
 * @summary JBR-4875 The test drag&drops a frame which contains an instance of JComboBox and checks that ComboBoxPopup
 * does not become detached from its' control. The test performs checking for both cases when
 * <code>apple.awt.transparentTitleBar</code> is not set and it is set to <code>true</code>.
 * It is expected that <code>x</code>-coordinates for <code>JFrame</code>, <code>JComboBox</code> and ComboBoxPopup
 * have the same values after dragging&dropping <code>JFrame</code>.
 * @run main/timeout=600 ComboBoxTransparentTittleBarTest
 */
public final class ComboBoxTransparentTittleBarTest {

    private static final int SIZE = 300;
    private static volatile Robot robot;
    private static volatile JComboBox<String> comboBox;
    private static volatile JFrame frame;
    private static final boolean[] titleBarTransparencies = {false, true};
    private static final int DRAG_X = 200;
    private static final StringBuilder errMessages= new StringBuilder();
    private static int errCount = 0;

    private static final float TITLE_BAR_HEIGHT = 54f;

    public static void main(final String[] args) throws Exception {
        robot = new Robot();
        robot.setAutoDelay(100);
        robot.setAutoWaitForIdle(true);

        for (boolean doTitleBarTransparent : titleBarTransparencies) {
            EventQueue.invokeAndWait(() -> {
                setup(doTitleBarTransparent);
            });
            robot.waitForIdle();
            test();
            robot.waitForIdle();
            dispose();
        }
        if (errCount > 0)
            throw new RuntimeException(String.valueOf(errMessages));
    }

    private static void test() {
        getLocationOnScreen(frame);
        getLocationOnScreen(comboBox);

        BasicComboPopup popup = (BasicComboPopup) comboBox.getUI().getAccessibleChild(comboBox, 0);
        popup.show();
        getLocationOnScreen(popup);
        popup.hide();

        int x_init = frame.getLocationOnScreen().x + frame.getWidth() / 2;;
        int y_init = frame.getLocationOnScreen().y + 5;
        System.out.print("Drag " + frame.getName() + " from: (" + x_init + ", " + y_init + ")");
        robot.mouseMove(x_init, y_init);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.waitForIdle();

        int x = x_init + DRAG_X;
        for (int j = x_init; j < x; j += 5) {
            robot.mouseMove(j, y_init);
            robot.waitForIdle();
        }

        System.out.println(" and drop to: (" + x + ", " + y_init + ")");
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        robot.waitForIdle();

        Point frameLocation = getLocationOnScreen(frame);
        Point comboBoxLocation = getLocationOnScreen(comboBox);

        int errCount_before = errCount;
        if (popup.isVisible()) {
            System.out.println("ComboPopup is visible");
            Point popupLocation = popup.getLocationOnScreen();
            if (frameLocation.x - comboBoxLocation.x != 0)
                appendErrMsg("***ComboBox location was not changed. Expected point: (" + frameLocation.x + ", " + comboBoxLocation.y + ")");
            if (frameLocation.x - popupLocation.x != 0)
                appendErrMsg("***ComboPopup location was not changed. Expected point: (" + frameLocation.x + ", " + popupLocation.y + ")");
        }
        if (errCount_before < errCount)
            System.out.println("***FAILED***");
        if (errCount_before == errCount)
            System.out.println("***PASSED***");
    }

    private static void appendErrMsg(String msg) {
        errMessages.append(msg);
        errCount += 1;
    }

    private static void dispose() throws Exception {
        EventQueue.invokeAndWait(() -> {
            if (frame != null) {
                frame.dispose();
            }
        });
    }

    private static Point getLocationOnScreen(Component component) {
        Point point = component.getLocationOnScreen();
        System.out.println(component.getName() + " location: (" + point.x + ", " + point.y + ")");
        return point;
    }
    private static void setup(boolean doTitleBarTransparent) {
        comboBox = new JComboBox<>();
        comboBox.setName("ComboBox" + comboBox.hashCode());
        for (int i = 1; i < 7; i++) {
            comboBox.addItem("some text in the item-" + i);
        }

        frame = new JFrame();
        frame.setAlwaysOnTop(true);
        frame.add(comboBox);
        frame.pack();
        frame.setSize(frame.getWidth(), SIZE);
        frame.setVisible(true);
        System.out.println("------------------------------------");
        System.out.println("The case transparentTitleBar = " + doTitleBarTransparent);
        if (doTitleBarTransparent) {
            WindowDecorations.CustomTitleBar titleBar = JBR.getWindowDecorations().createCustomTitleBar();
            titleBar.setHeight(TITLE_BAR_HEIGHT);
            JBR.getWindowDecorations().setCustomTitleBar(frame, titleBar);

            MouseListener mouseListener = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    titleBar.forceHitTest(false);
                }
            };
            for (int i = 0; i < comboBox.getComponents().length; i++) {
                comboBox.getComponent(i).addMouseListener(mouseListener);
            }

        }
    }
}
