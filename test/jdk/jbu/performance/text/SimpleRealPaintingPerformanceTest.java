package performance.text;

import javax.swing.*;
import java.awt.*;
import java.awt.font.GlyphVector;
import java.awt.image.BufferedImage;
import java.util.concurrent.locks.LockSupport;

public class SimpleRealPaintingPerformanceTest {
    public static void main(String[] args) {
        Graphics2D g2d = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB).createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g2d.drawString("a", 10, 10);

        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame() {
                @Override
                public void paint(Graphics g) {
                    Graphics2D g2d = (Graphics2D) g;
                    g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
                    g2d.drawString("a", 10, 10);
                    super.paint(g);
                }
            };
            f.add(new MyComponent());
            f.setSize(1000, 1000);
            f.setLocationRelativeTo(null);
            f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            f.setVisible(true);
        });
    }

    private static class MyComponent extends JComponent {
        Font font = new Font("Menlo", Font.PLAIN, 12);
        String text = "abcdefghi abcdefghi abcdefghi abcdefghi abcdefghi abcdefghi abcdefghi abcdefghi abcdefghi abcdefghi ";

        @Override
        protected void paintComponent(Graphics g1) {
            Graphics2D g = (Graphics2D) g1;
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);

            GlyphVector gv = font.layoutGlyphVector(g.getFontRenderContext(), text.toCharArray(), 0, text.length(), Font.LAYOUT_LEFT_TO_RIGHT);

            while (true) {
                long t = System.currentTimeMillis();
                for (int i = 0; i < 10000; i++) {
                    g.drawGlyphVector(gv, 100, 100);
                }
                System.out.println(System.currentTimeMillis() - t);

                LockSupport.parkNanos(1_000_000_000L);
            }
        }
    }
}
