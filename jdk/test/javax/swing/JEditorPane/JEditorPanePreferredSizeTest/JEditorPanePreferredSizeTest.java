import sun.awt.SunToolkit;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.lang.reflect.InvocationTargetException;

/* @test
 * bug JRE-934
 * @summary Diff viewer errors are not visible on HiDPI Linux
 * @run main JEditorPanePreferredSizeTest
 */
public class JEditorPanePreferredSizeTest {
    private static JFrame frame;
    private static MyLabel label;

    public static void main(String[] args) throws InvocationTargetException, InterruptedException {
        SwingUtilities.invokeAndWait(JEditorPanePreferredSizeTest::show);

        ((SunToolkit)Toolkit.getDefaultToolkit()).realSync();

        final Dimension size = new Dimension(0, 0);
        SwingUtilities.invokeAndWait(() -> {
            size.setSize(label.getPreferredSize());
            frame.dispose();
        });
        if (size.width <= 0 || size.height <= 0) throw new RuntimeException("Test FAILED: bad preferred size: " + size);
        System.out.println("Test PASSED");
    }

    private static void show() {
        frame = new JFrame("frame");
        frame.setLayout(new FlowLayout());
        label = new MyLabel("text");
        JPanel panel = new JPanel() {
            @Override
            public Dimension getPreferredSize() {
                return label.getPreferredSize();
            }
        };
        panel.add(label);
        frame.add(panel);
        frame.pack();
        frame.setVisible(true);
    }
}

class MyLabel extends JLabel {
    MyLabel(String text) {
        super(text);
        setLayout(new BorderLayout());

        JEditorPane myEditorPane = new JEditorPane();
        myEditorPane.setContentType("text/html");
        myEditorPane.setText(getText());
        myEditorPane.setBorder(new EmptyBorder(0, 0, 0, 0));
        add(myEditorPane, BorderLayout.CENTER);

        // triggers initial BasicTextUI layout
        getPreferredSize();

        // triggers BasicTextUI.modelChanged()
        myEditorPane.setFont(getFont());
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension size = getLayout().preferredLayoutSize(this);
        System.out.println("pref size: " + size);
        return size;
    }
}
