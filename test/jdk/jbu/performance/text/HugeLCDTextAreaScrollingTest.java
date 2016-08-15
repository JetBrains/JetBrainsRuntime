package performance.text;

import org.junit.Test;
import performance.BaseScrollingPerformanceTest;

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
        textArea.putClientProperty(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        textArea.setFont(new Font("Dialog", Font.PLAIN, 50));
        return textArea;
    }

    @Override
    protected void configureScrollPane(JScrollPane scrollPane) {
        scrollPane.putClientProperty(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
    }
}
