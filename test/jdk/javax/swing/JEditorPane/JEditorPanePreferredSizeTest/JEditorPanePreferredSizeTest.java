import javax.swing.*;
import java.awt.*;
import java.lang.reflect.InvocationTargetException;

/* @test
 * bug JRE-934
 * @summary Diff viewer errors are not visible on HiDPI Linux (fixed by JDK-8231084)
 * @run main JEditorPanePreferredSizeTest
 */
public class JEditorPanePreferredSizeTest {
    public static void main(String[] args) throws InvocationTargetException, InterruptedException {
        final Dimension size = new Dimension(0, 0);
        SwingUtilities.invokeAndWait(() -> {
            JEditorPane myEditorPane = new JEditorPane("text/html", "text");
            myEditorPane.setBorder(null);
            // triggers initial BasicTextUI layout
            myEditorPane.getPreferredSize();
            // triggers BasicTextUI.modelChanged(), font here should be different from component's default font
            myEditorPane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

            size.setSize(myEditorPane.getPreferredSize());
        });
        if (size.width <= 0 || size.height <= 0) {
            throw new RuntimeException("Test FAILED: bad preferred size: " + size);
        }
        System.out.println("Test PASSED");
    }
}