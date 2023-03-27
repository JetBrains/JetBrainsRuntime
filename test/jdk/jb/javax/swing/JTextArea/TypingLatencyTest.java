import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.text.DecimalFormat;
import java.util.Collections;
import java.util.PriorityQueue;

/**
 * @test
 * @summary Measure typing latency in JTextArea with various conditions (count of already added symbols)
 *  The test adds initial size of text in the JTextArea by specified <code>jtreg.test.initialSize</code> property.
 *  After that robot types predefined amount of english symbols in the beginning of the text in JTextArea.
 *  Amount of symbols is defined in <code>jtreg.test.sampleSize</code> property.
 * @run main/othervm -Djtreg.test.initialSize=0 -Djtreg.test.sampleSize=100 TypingLatencyTest
 * @run main/othervm -Djtreg.test.initialSize=1000 -Djtreg.test.sampleSize=50 TypingLatencyTest
 * @run main/othervm -Djtreg.test.initialSize=5000 -Djtreg.test.sampleSize=50 TypingLatencyTest
 * @run main/othervm -Djtreg.test.initialSize=10000 -Djtreg.test.sampleSize=50 TypingLatencyTest
 * @run main/othervm -Djtreg.test.initialSize=30000 -Djtreg.test.sampleSize=50 TypingLatencyTest
 */
public class TypingLatencyTest {

    private static final DecimalFormat df = new DecimalFormat("0.00");

    private static JFrame window;
    private static JTextArea textArea;
    private static Robot robot;

    public static void main(String... args) throws Exception {
        int initialSize = 0;
        String initialSizeValue = System.getProperty("jtreg.test.initialSize");
        if (initialSizeValue != null && !initialSizeValue.isBlank()) {
            initialSize = Integer.parseInt(initialSizeValue);
        }

        int sampleSize = 0;
        String sampleSizeValue = System.getProperty("jtreg.test.sampleSize");
        if (sampleSizeValue != null && !sampleSizeValue.isBlank()) {
            sampleSize = Integer.parseInt(sampleSizeValue);
        }

        System.out.println("Run test with: initialSize = " + initialSize + " sampleSize = " + sampleSize);

        try {
            robot = new Robot();
            SwingUtilities.invokeAndWait(TypingLatencyTest::createUI);
            robot.waitForIdle();
            boolean passed = typeAndMeasureLatency(initialSize, sampleSize);
            if (!passed) {
                throw new RuntimeException("TEST FAILED: Typing latency is too high.");
            }
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (window != null) {
                    window.dispose();
                }
            });
        }
    }

    private static void createUI() {
        window = new JFrame();
        window.setBounds(0, 0, 800, 600);

        JPanel panel = new JPanel();
        textArea = new JTextArea();
        textArea.setColumns(60);
        textArea.setLineWrap(true);
        textArea.setRows(30);

        panel.add(textArea);

        window.setContentPane(panel);
        window.setVisible(true);
    }

    private static boolean typeAndMeasureLatency(int initialTextSize, int sampleSize) {
        window.requestFocus();
        textArea.requestFocus();
        robot.waitForIdle();

        textArea.append(generateText(initialTextSize));

        long before, after, diff;
        int code;

        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        double average = 0;
        PriorityQueue<Long> small = new PriorityQueue<>(Collections.reverseOrder());
        PriorityQueue<Long> large = new PriorityQueue<>();
        boolean even = true;

        for (int i = 0; i < sampleSize; i++) {
            code = KeyEvent.getExtendedKeyCodeForChar(97 + Math.round(25 * (float) Math.random()));

            before = System.nanoTime();
            robot.keyPress(code);
            robot.keyRelease(code);
            robot.waitForIdle();
            after = System.nanoTime();
            diff = after - before;

            if (even) {
                large.offer(diff);
                small.offer(large.poll());
            } else {
                small.offer(diff);
                large.offer(small.poll());
            }
            even = !even;
            min = Math.min(min, diff);
            max = Math.max(max, diff);
            average = ((average * i) + diff) / (i + 1);
        }

        double median = even ? (small.peek() + large.peek()) / 2.0 : small.peek();

        System.out.println("Symbols added: " + sampleSize);
        System.out.println("min (ms): " + df.format((min / 1_000_000.0)));
        System.out.println("max (ms): " + df.format((max / 1_000_000.0)));
        System.out.println("average (ms): " + df.format((average / 1_000_000.0)));
        System.out.println("median (ms): " + df.format((median / 1_000_000.0)));

        return median < 500_000_000;
    }

    private static String generateText(int size) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < size; i++) {
            sb.append((char) (97 + Math.round(25 * (float) Math.random())));
        }
        return sb.toString();
    }

}
