import java.awt.*;
import java.awt.image.*;

/**
 * Frame time of text scenes that stress the accelerated glyph caches. Each frame draws
 * `distinct` different glyph images (size x char combinations) into a VolatileImage and
 * is completed by reading one pixel back. Usage: GlyphChurnBench <distinct glyphs per frame> [lcd]
 * Prints the median and mean frame time of 200 frames after 50 warm-up frames. `aci` draws each
 * line as an attributed string (the Skia paragraph route under text=paragraph).
 */
public class GlyphChurnBench {
    public static void main(String[] args) throws Exception {
        int distinct = Integer.parseInt(args[0]);
        boolean lcd = false, software = false, aci = false;
        for (String a : args) {
            if (a.equals("lcd")) lcd = true;
            else if (a.equals("software")) software = true;   // the loops into a BufferedImage
            else if (a.equals("aci")) aci = true;             // drawString(AttributedCharacterIterator): the paragraph route
        }
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        VolatileImage vi = software ? null : gc.createCompatibleVolatileImage(1600, 1000, Transparency.OPAQUE);
        BufferedImage bi = software ? new BufferedImage(1600, 1000, BufferedImage.TYPE_INT_RGB) : null;
        BufferedImage sync = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        String alphabet = "";
        for (char c = 33; c < 127; c++) alphabet += c;          // 94 glyphs per size
        int sizes = (distinct + 93) / 94;                       // sizes needed for `distinct` glyph images
        Font[] fonts = new Font[sizes];
        for (int i = 0; i < sizes; i++) fonts[i] = new Font("Helvetica", i % 2 == 0 ? Font.PLAIN : Font.BOLD, 9 + i);
        long[] times = new long[200];
        for (int frame = 0; frame < 250; frame++) {
            long t0 = System.nanoTime();
            Graphics2D g = software ? bi.createGraphics() : vi.createGraphics();
            g.setColor(Color.WHITE); g.fillRect(0, 0, 1600, 1000);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    lcd ? RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB : RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.BLACK);
            int y = 30, drawn = 0;
            for (int i = 0; i < sizes && drawn < distinct; i++) {
                g.setFont(fonts[i]);
                int n = Math.min(94, distinct - drawn);
                draw(g, fonts[i], alphabet.substring(0, Math.min(47, n)), 10, y, aci);
                if (n > 47) draw(g, fonts[i], alphabet.substring(47, n), 10, y + fonts[i].getSize() + 2, aci);
                drawn += n;
                y += 2 * fonts[i].getSize() + 6;
                if (y > 980) y = 30;
            }
            g.dispose();
            if (software) {
                bi.getRGB(5, 5);
            } else {   // completes the frame: a one-pixel readback instead of a full snapshot
                Graphics2D sg = sync.createGraphics();
                sg.drawImage(vi, 0, 0, 1, 1, 5, 5, 6, 6, null);
                sg.dispose();
                sync.getRGB(0, 0);
            }
            long dt = System.nanoTime() - t0;
            if (frame >= 50) times[frame - 50] = dt;
        }
        java.util.Arrays.sort(times);
        double mean = 0; for (long t : times) mean += t; mean /= times.length;
        System.out.printf("distinct=%d lcd=%b aci=%b sizes=%d: median %.2f ms, mean %.2f ms, p90 %.2f ms per frame%n",
                distinct, lcd, aci, sizes, times[100] / 1e6, mean / 1e6, times[180] / 1e6);
    }

    static void draw(Graphics2D g, Font font, String s, int x, int y, boolean aci) {
        if (aci) {
            java.text.AttributedString as = new java.text.AttributedString(s);
            as.addAttribute(java.awt.font.TextAttribute.FONT, font);
            g.drawString(as.getIterator(), x, y);
        } else {
            g.drawString(s, x, y);
        }
    }
}
