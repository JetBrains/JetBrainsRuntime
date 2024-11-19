import javax.swing.*;
import java.awt.*;

import static javax.swing.WindowConstants.EXIT_ON_CLOSE;

/**
 * @test
 * @summary Verifies that the popup-style window can change its visibility
 * @requires os.family == "linux"
 * @key headful
 * @modules java.desktop/sun.awt
 * @run main/othervm WLPopupLocation
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.0 WLPopupLocation
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.25 WLPopupLocation
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=1.5 WLPopupLocation
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true -Dsun.java2d.uiScale=2.0 WLPopupLocation
 */
public class WLPopupLocation {

    private static JFrame frame;
    private static JWindow popup;

    private static void createAndShowUI() {
        frame = new JFrame("WLPopupResize Test");
        frame.setSize(300, 200);
        frame.setDefaultCloseOperation(EXIT_ON_CLOSE);
        frame.setVisible(true);
    }

    private static void initPopup() {
        JPanel popupContents = new JPanel();
        popupContents.add(new JLabel("test popup"));
        popup = new JWindow(frame);
        popup.setType(Window.Type.POPUP);
        sun.awt.AWTAccessor.getWindowAccessor().setPopupParent(popup, frame);
        popup.add(popupContents);
    }

    public static void main(String[] args) throws Exception {
        Toolkit toolkit = Toolkit.getDefaultToolkit();
        if (!toolkit.getClass().getName().equals("sun.awt.wl.WLToolkit")) {
            System.out.println("The test makes sense only for WLToolkit. Exiting...");
            return;
        }

        Robot robot = new Robot();

        SwingUtilities.invokeAndWait(WLPopupLocation::createAndShowUI);
        pause(robot);

        SwingUtilities.invokeAndWait(WLPopupLocation::initPopup);
        pause(robot);

        System.out.println("Action: locate to (0,0), set size (150, 200)");
        SwingUtilities.invokeAndWait(() -> {
            popup.setVisible(true);
            popup.setSize(150, 200);
            popup.setLocation(15, 20);
        });
        System.out.println("bounds1 = " + popup.getBounds());
        System.out.println("location1 = " + popup.getLocation());
        System.out.println("size1 = " + popup.getSize());
        pause(robot);
        System.out.println("bounds2 = " + popup.getBounds());
        System.out.println("location2 = " + popup.getLocation());
        System.out.println("size2 = " + popup.getSize());

        SwingUtilities.invokeAndWait(() -> {
            popup.setLocation(100, 100);
        });
        System.out.println("bounds2 = " + popup.getBounds());
        System.out.println("location3 = " + popup.getLocation());
        System.out.println("size3 = " + popup.getSize());
        pause(robot);
        System.out.println("bounds4 = " + popup.getBounds());
        System.out.println("location4 = " + popup.getLocation());
        System.out.println("size4 = " + popup.getSize());
        SwingUtilities.invokeAndWait(frame::dispose);
    }

    private static void pause(Robot robot) {
        robot.waitForIdle();
        robot.delay(500);
    }

}
