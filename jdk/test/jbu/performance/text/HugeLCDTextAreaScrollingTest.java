package performance.text;

import org.junit.Test;
import performance.BaseScrollingPerformanceTest;
import sun.swing.SwingUtilities2;

import javax.swing.*;
import java.awt.*;

public class HugeLCDTextAreaScrollingTest extends BaseScrollingPerformanceTest {
    public HugeLCDTextAreaScrollingTest() {
        super("##teamcity[buildStatisticValue key='HugeLCDTextAreaScrolling' ");
    }

    @Test
    public void testTextAreaScrolling() {
        doTest(this, 4, 8);
    }

    protected JComponent createComponent() {
        JComponent textArea = createTextArea();
        textArea.putClientProperty(SwingUtilities2.AA_TEXT_PROPERTY_KEY, SUBPIXEL_HINT);
        textArea.setFont(new Font("Dialog", Font.PLAIN, 50));
        return textArea;
    }

    @Override
    protected void configureScrollPane(JScrollPane scrollPane) {
        scrollPane.putClientProperty(SwingUtilities2.AA_TEXT_PROPERTY_KEY, SUBPIXEL_HINT);
    }
}
