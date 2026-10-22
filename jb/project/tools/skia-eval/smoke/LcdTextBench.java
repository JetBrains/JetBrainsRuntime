import java.awt.*;
import java.awt.image.*;

/** Frame time of a drawString loop (LCD by default) on a compatible VolatileImage; a 1x1 snapshot per frame syncs the GPU. */
public class LcdTextBench {
    public static void main(String[] args) throws Exception {
        int strings = args.length > 0 ? Integer.parseInt(args[0]) : 500;
        int frames = args.length > 1 ? Integer.parseInt(args[1]) : 60;
        Object aa = args.length > 2 && args[2].equals("gray") ? RenderingHints.VALUE_TEXT_ANTIALIAS_ON : RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB;
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        VolatileImage vi = gc.createCompatibleVolatileImage(1024, 768, Transparency.OPAQUE);
        Font font = new Font("Segoe UI", Font.PLAIN, 13);
        String text = "The quick brown fox jumps over the lazy dog, AVfi To AVfi Way fjfl AWAY 0123456789";
        boolean cheapSync = args.length > 3 && args[3].equals("cheap");
        boolean grid = args.length > 4 && args[4].equals("grid");   // lines on a 2 x 45 grid: no overlap up to 90 lines
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        long[] t = new long[frames];
        for (int f = 0; f < frames; f++) {
            long t0 = System.nanoTime();
            Graphics2D g = vi.createGraphics();
            g.setColor(Color.WHITE); g.fillRect(0, 0, 1024, 768);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, aa);
            g.setFont(font); g.setColor(Color.BLACK);
            for (int i = 0; i < strings; i++) {
                if (grid) {
                    g.drawString(text, 4 + (i % 2) * 512, 15 + ((i / 2) % 45) * 17);
                } else {
                    g.drawString(text, (i * 37) % 400, 15 + (i * 17) % 740);
                }
            }
            g.dispose();
            if (cheapSync) {
                // flush + a 1x1 readback: the frame is complete, without copying it
                probe.createGraphics().drawImage(vi, 0, 0, 1, 1, 0, 0, 1, 1, null);
            } else {
                vi.getSnapshot().getRGB(0, 0);   // flush + readback of the whole frame
            }
            t[f] = System.nanoTime() - t0;
        }
        java.util.Arrays.sort(t, frames / 4, frames);   // drop the warm-up quarter for the median
        long med = t[frames / 4 + (frames - frames / 4) / 2];
        System.out.printf("surface=%s strings=%d frames=%d median=%.2f ms (first %.1f ms)%n",
                sun.java2d.SurfaceData.getPrimarySurfaceData(vi).getClass().getSimpleName(), strings, frames, med / 1e6, t[0] / 1e6);
    }
}
