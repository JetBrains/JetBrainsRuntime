import java.awt.*;
import javax.swing.*;

/**
 * The IDE's kind of flush, as fast as the pipeline allows: a Swing frame whose panel repaints
 * itself continuously (each paint cycle ends in Swing's updateWindows, so every flush presents and
 * nothing is read back). Paints a small mix (fills, rects, lines, text) so the flush is not empty.
 *
 *   PresentProbe <seconds> [heavy] [big]
 *
 * `heavy` paints an editor-like load per cycle (2,400 glyphs in several colours, 400 filled and
 * outlined rects, 200 lines), so the flush carries about what an IDE repaint carries.
 * `big` maximizes the frame, so the destination has the IDE's size (with -Dsun.java2d.uiScale=2 the
 * IDE's 200 % as well).
 *
 * Prints paint cycles per second at the end. Run with -Dsun.java2d.uiScale=1; nothing is captured
 * and no input is sent.
 */
public class PresentProbe {
    static volatile int paints;
    static boolean heavy;

    public static void main(String[] args) throws Exception {
        int seconds = Integer.parseInt(args[0]);
        heavy = args.length > 1 && args[1].equals("heavy");
        boolean big = args.length > 2 && args[2].equals("big");
        SwingUtilities.invokeAndWait(() -> {
            JFrame f = new JFrame("PresentProbe");
            f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            f.add(new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g;
                    int n = paints++;
                    g2.setColor(new Color(0x2B2D30));
                    g2.fillRect(0, 0, getWidth(), getHeight());
                    for (int i = 0; i < 40; i++) {
                        g2.setColor(new Color((i * 37 + n) & 255, (i * 91) & 255, (i * 53 + n) & 255));
                        g2.fillRect(10 + (i % 8) * 90, 10 + (i / 8) * 40, 80, 30);
                        g2.drawRect(10 + (i % 8) * 90, 220 + (i / 8) * 40, 80, 30);
                        g2.drawLine(10, 440 + i * 3, 700, 450 + i * 2);
                    }
                    g2.setColor(Color.WHITE);
                    g2.setFont(new Font(Font.DIALOG, Font.PLAIN, 13));
                    for (int i = 0; i < 12; i++) {
                        g2.drawString("PresentProbe paint cycle " + n + " line " + i, 20, 600 + i * 18);
                    }
                    if (heavy) {
                        for (int i = 0; i < 400; i++) {
                            g2.setColor(new Color((i * 29 + n) & 255, (i * 71) & 255, (i * 13 + n) & 255));
                            g2.fillRect((i * 37) % 700, 20 + (i * 53) % 380, 24, 12);
                            g2.drawRect((i * 41) % 700, 420 + (i * 59) % 160, 30, 14);
                        }
                        for (int i = 0; i < 200; i++) {
                            g2.setColor(new Color((i * 17) & 255, (i * 23 + n) & 255, (i * 31) & 255));
                            g2.drawLine((i * 11) % 700, 600 + (i * 7) % 60, (i * 13 + 300) % 700, 600 + (i * 17) % 60);
                        }
                        g2.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
                        for (int i = 0; i < 40; i++) {
                            g2.setColor(new Color(0xA0 + (i % 5) * 10, 0xA0 + (i % 7) * 8, 0xC0 + (i % 3) * 16));
                            g2.drawString("line " + (n + i) + " of an editor-like scene with keywords, identifiers and numbers: 0x" + Integer.toHexString(n * 31 + i), 20, 680 + i * 4);
                        }
                    }
                    repaint();   // the next cycle, as soon as Swing gets to it
                }
            });
            f.setSize(760, 840);
            f.setLocation(60, 60);
            if (big) {
                f.setExtendedState(Frame.MAXIMIZED_BOTH);
            }
            f.setVisible(true);
        });
        Thread.sleep(1000);
        int before = paints;
        long start = System.nanoTime();
        Thread.sleep(seconds * 1000L);
        double secs = (System.nanoTime() - start) / 1e9;
        System.out.printf("PresentProbe: %.0f paint cycles per second over %.1f s%n", (paints - before) / secs, secs);
        System.exit(0);
    }
}
