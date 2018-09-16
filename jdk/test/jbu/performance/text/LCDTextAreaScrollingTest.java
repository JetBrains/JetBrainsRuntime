package performance.text;

import org.junit.Test;
import performance.BaseScrollingPerformanceTest;
import sun.swing.SwingUtilities2;

import javax.swing.*;

public class LCDTextAreaScrollingTest extends BaseScrollingPerformanceTest {
    public LCDTextAreaScrollingTest() {
        super("##teamcity[buildStatisticValue key='LCDTextAreaScrolling' ");
    }

    @Test
    public void testTextAreaScrolling() {
        doTest(this, 200, 400);
    }

    protected JComponent createComponent() {
        JComponent textArea = createTextArea();
        textArea.putClientProperty(SwingUtilities2.AA_TEXT_PROPERTY_KEY, SUBPIXEL_HINT);
        return textArea;
    }

    @Override
    protected void configureScrollPane(JScrollPane scrollPane) {
        scrollPane.putClientProperty(SwingUtilities2.AA_TEXT_PROPERTY_KEY, SUBPIXEL_HINT);
    }
}
