import java.awt.*;

/**
 * @test
 * @key headful
 */
public class GetGraphicsStressTestSimplified {

    private static final int ITERATIONS = 100000;

    public static void main(String[] args) {
        Frame f = new Frame();
        f.setSize(100, 100);
        f.setLocationRelativeTo(null);
        f.setVisible(true);

        double average = 0;
        long min = Long.MAX_VALUE;
        long max = 0;

        for (int i = 0; i < ITERATIONS; i++) {
            long t1 = System.nanoTime();
            Graphics g = f.getGraphics();
            if (g != null) {
                g.drawLine(0, 0, 4, 4); // just in case...
                g.dispose();
            }
            long t2 = System.nanoTime();
            long t = t2 - t1;
            average = average + (t - average) / (i + 1);
            if (t < min) {
                min = t;
            }
            if (t > max) {
                max = t;
            }
        }

        System.out.println("Average: " + average + "ns");
        System.out.println("Min: " + min + "ns");
        System.out.println("Max: " + max + "ns");

        f.dispose();
    }

}
