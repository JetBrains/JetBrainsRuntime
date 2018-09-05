import javax.swing.*;
import java.awt.*;
import sun.awt.SunToolkit;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CountDownLatch;

/* @test
 * bug JRE-938
 * @summary Tests that Frame.setMaximizedBounds meets HiDPI.
 * @author Anton Tarasov
 * @requires (os.family == "windows")
 * @modules java.desktop/sun.awt
 * @run main/othervm -Dsun.java2d.uiScale.enabled=true
 *                   -Dsun.java2d.uiScale=2
 *                    SetMaximizedBoundsTest
 */
public class SetMaximizedBoundsTest {
    private static volatile JFrame frame;
    private static final Rectangle MAX_BOUNDS = new Rectangle(100, 100, 500, 500);
    private static volatile Rectangle testBounds;

    public static void main(String[] args) throws InterruptedException, InvocationTargetException {
        EventQueue.invokeAndWait(SetMaximizedBoundsTest::show);

        ((SunToolkit)Toolkit.getDefaultToolkit()).realSync();

        EventQueue.invokeAndWait(SetMaximizedBoundsTest::maximize);

        ((SunToolkit)Toolkit.getDefaultToolkit()).realSync();

        EventQueue.invokeAndWait(SetMaximizedBoundsTest::test);

        if (!MAX_BOUNDS.equals(testBounds)) {
            throw new RuntimeException("Test FAILED: bad bounds " + testBounds);
        }
        System.out.println("Test PASSED");
    }

    private static void show() {
        frame = new JFrame("frame");
        frame.setBounds(200, 200, 200, 200);
        frame.setVisible(true);
    }

    private static void maximize() {
        frame.setMaximizedBounds(MAX_BOUNDS);
        frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
    }

    private static void test() {
        testBounds = frame.getBounds();
        frame.dispose();
    }
}