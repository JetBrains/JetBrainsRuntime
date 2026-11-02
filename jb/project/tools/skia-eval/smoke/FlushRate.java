import javax.swing.*;
import java.awt.*;
import java.awt.font.TextAttribute;
import java.lang.reflect.Method;
import java.text.AttributedString;
import java.util.concurrent.atomic.AtomicLong;

/** Flushes per second of a Swing panel (40 rounded rects + 40 strings, Timer 16 ms) on the Skia pipe; prints 5 one-second samples. */
public class FlushRate {
    static final String TEXT = "Hamburgefonts Quick fox jumps 1234567890";
    static final AtomicLong frames = new AtomicLong();

    public static void main(String[] args) throws Exception {
        final String mode = System.getProperty("sun.java2d.skia.text", "glyphs");
        final Method flushes = Class.forName("sun.java2d.skia.SKEnvironment").getMethod("flushes");
        final Font font = new Font("Helvetica Neue", Font.PLAIN, 12);
        SwingUtilities.invokeAndWait(() -> {
            JFrame f = new JFrame("FlushRate " + mode);
            JPanel p = new JPanel() {
                int tick;
                @Override protected void paintComponent(Graphics g0) {
                    Graphics2D g = (Graphics2D) g0;
                    g.setColor(Color.WHITE); g.fillRect(0, 0, getWidth(), getHeight());
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
                    tick++;
                    for (int i = 0; i < 40; i++) {
                        g.setColor(new Color(0xE0 - (i * 3) % 60, 0xE8, 0xF4));
                        g.fillRoundRect(10 + (i % 8) * 95, 10 + (i / 8) * 110 + (tick % 7), 85, 100, 12, 12);
                    }
                    g.setColor(Color.DARK_GRAY); g.setFont(font);
                    for (int i = 0; i < 40; i++) {
                        float x = 20 + (tick % 3), y = 30 + i * 13;
                        if (mode.equals("paragraph")) {
                            AttributedString as = new AttributedString(TEXT + " " + i);
                            as.addAttribute(TextAttribute.FONT, font);
                            g.drawString(as.getIterator(), x, y);
                        } else {
                            g.drawString(TEXT + " " + i, x, y);
                        }
                    }
                    frames.incrementAndGet();
                }
            };
            p.setPreferredSize(new Dimension(800, 600));
            f.add(p); f.pack(); f.setLocation(100, 100); f.setVisible(true);
            new Timer(16, e -> p.repaint()).start();
        });
        Thread.sleep(1500);   // warm-up
        for (int s = 0; s < 5; s++) {
            long f0 = ((Number) flushes.invoke(null)).longValue(), fr0 = frames.get();
            Thread.sleep(1000);
            long df = ((Number) flushes.invoke(null)).longValue() - f0, dfr = frames.get() - fr0;
            System.out.printf("%s: %d flushes/s, %d frames/s, %.2f flushes/frame%n", mode, df, dfr, dfr == 0 ? 0 : (double) df / dfr);
        }
        System.exit(0);
    }
}
