import java.awt.Color;
import java.awt.Graphics;
import java.awt.Window;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/**
 * Paint-to-screen latency of a Skia window: how long after a Swing paint cycle ends
 * (the invokeLater posted right after repaint() runs once the paint event has been
 * processed) the window's frame counter ({@code java2d.native.frames}, counted by the
 * native side at each present) moves. One repaint at a time, a pause between them, so
 * that no present waits behind another. Needs {@code -Dawt.window.counters=probe} (any
 * value enables the counters) and {@code --add-opens java.desktop/java.awt=ALL-UNNAMED}.
 *
 *   LatencyProbe [repaints] [pause ms]
 *
 * Prints the median, p90 and max over the repaints, and how many never showed within a second.
 */
public class LatencyProbe {
    static volatile int paints;

    public static void main(String[] args) throws Exception {
        int repaints = args.length > 0 ? Integer.parseInt(args[0]) : 200;
        int pauseMs = args.length > 1 ? Integer.parseInt(args[1]) : 30;
        JFrame[] frameHolder = new JFrame[1];
        JPanel[] panelHolder = new JPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            JFrame f = new JFrame("LatencyProbe");
            f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            JPanel p = new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    int n = paints++;
                    g.setColor(new Color((n * 37) & 255, (n * 91) & 255, (n * 53) & 255));
                    g.fillRect(0, 0, getWidth(), getHeight());
                    g.setColor(Color.WHITE);
                    g.drawString("LatencyProbe " + n, 20, 40);
                }
            };
            f.add(p);
            f.setSize(640, 480);
            f.setLocation(200, 200);
            f.setVisible(true);
            frameHolder[0] = f;
            panelHolder[0] = p;
        });
        JFrame frame = frameHolder[0];
        JPanel panel = panelHolder[0];
        Thread.sleep(1000);   // the first frames, the window settles

        Field countersField = Window.class.getDeclaredField("perfCounters");
        countersField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> counters = (Map<String, Object>) countersField.get(frame);
        Field valueField = null;

        long[] latencies = new long[repaints];
        int missed = 0;
        for (int i = 0; i < repaints; i++) {
            long before = frames(counters, valueField);
            CountDownLatch painted = new CountDownLatch(1);
            long[] t0 = new long[1];
            SwingUtilities.invokeLater(() -> {
                panel.repaint();
                SwingUtilities.invokeLater(() -> {   // runs once the paint cycle this repaint caused has ended
                    t0[0] = System.nanoTime();
                    painted.countDown();
                });
            });
            painted.await();
            long deadline = t0[0] + TimeUnit.SECONDS.toNanos(1);
            long now;
            while ((now = System.nanoTime()) < deadline && frames(counters, valueField) <= before) {
                Thread.onSpinWait();
            }
            if (now >= deadline) {
                missed++;
                latencies[i] = -1;
            } else {
                latencies[i] = now - t0[0];
            }
            Thread.sleep(pauseMs);
        }
        long[] seen = Arrays.stream(latencies).filter(l -> l >= 0).sorted().toArray();
        if (seen.length == 0) {
            System.out.println("LatencyProbe: no present was ever counted (are the counters enabled?)");
        } else {
            System.out.printf("LatencyProbe: %d repaints, present latency median %.2f ms, p90 %.2f ms, max %.2f ms, not shown within 1 s: %d%n",
                    repaints, seen[seen.length / 2] / 1e6, seen[(int) (seen.length * 0.9)] / 1e6, seen[seen.length - 1] / 1e6, missed);
        }
        System.exit(0);
    }

    /** The window's java2d.native.frames counter (0 before the first present). */
    static long frames(Map<String, Object> counters, Field valueField) throws Exception {
        Object counter;
        synchronized (counters) {
            counter = counters.get("java2d.native.frames");
        }
        if (counter == null) {
            return 0;
        }
        Field f = counter.getClass().getDeclaredField("value");
        f.setAccessible(true);
        return f.getLong(counter);
    }
}
