package performance.text;

import org.junit.Test;
import performance.BaseScrollingPerformanceTest;

import javax.swing.*;
import java.awt.*;

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
        textArea.putClientProperty(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        return textArea;
    }

    @Override
    protected void configureScrollPane(JScrollPane scrollPane) {
        scrollPane.putClientProperty(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
    }
}
