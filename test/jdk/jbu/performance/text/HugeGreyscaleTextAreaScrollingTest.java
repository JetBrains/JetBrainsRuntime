package performance.text;

import org.junit.Test;
import performance.BaseScrollingPerformanceTest;

import javax.swing.*;
import java.awt.*;

public class HugeGreyscaleTextAreaScrollingTest extends BaseScrollingPerformanceTest {
    public HugeGreyscaleTextAreaScrollingTest() {
        super("##teamcity[buildStatisticValue key='HugeGreyscaleTextAreaScrolling' ");
    }

    @Test
    public void testTextAreaScrolling() {
        doTest(this, 4, 8);
    }

    protected JComponent createComponent() {
        JComponent textArea = createTextArea();
        textArea.putClientProperty(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        textArea.setFont(new Font("Dialog", Font.PLAIN, 50));
        return textArea;
    }

    @Override
    protected void configureScrollPane(JScrollPane scrollPane) {
        scrollPane.putClientProperty(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }
}
