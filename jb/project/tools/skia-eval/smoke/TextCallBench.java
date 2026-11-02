import java.awt.*;
import java.awt.font.TextAttribute;
import java.awt.image.*;
import java.text.AttributedString;
import java.util.Random;

/**
 * Per-call CPU cost of text: `strings` short strings per frame into a VolatileImage, the
 * frame completed by a one-pixel readback. Reports the caller-thread paint time (layout,
 * glyph images, queue encode on this thread) and the total frame time (plus the flusher's
 * work: native draw, Skia shaping/strike on the Skia routes) as medians, and µs per string
 * and per glyph of the total. Usage: TextCallBench [strings=400] [len=12] [distinct] [aci] [frames=200] [lcd] [colors]
 *   distinct: new random strings every frame (no repeated-string caches); default: the same set each frame
 *   aci:      drawString(AttributedCharacterIterator) with a FONT attribute (the paragraph route)
 */
public class TextCallBench {
    public static void main(String[] args) throws Exception {
        int strings = 400, len = 12, frames = 200, warmup = 50;
        boolean distinct = false, aci = false, lcd = false, colors = false;
        int palette = 0;   // colors: cycle over this many distinct colours (0 = one per string)
        int size = 13, scale = 1;   // font size in points and an extra graphics scale on top of the display's
        for (String a : args) {
            if (a.startsWith("strings=")) strings = Integer.parseInt(a.substring(8));
            else if (a.startsWith("len=")) len = Integer.parseInt(a.substring(4));
            else if (a.startsWith("frames=")) frames = Integer.parseInt(a.substring(7));
            else if (a.equals("distinct")) distinct = true;
            else if (a.equals("aci")) aci = true;
            else if (a.equals("lcd")) lcd = true;
            else if (a.equals("colors")) colors = true;   // a random colour per string (luminance classes under gray=cg)
            else if (a.startsWith("palette=")) { colors = true; palette = Integer.parseInt(a.substring(8)); }
            else if (a.startsWith("size=")) size = Integer.parseInt(a.substring(5));
            else if (a.startsWith("scale=")) scale = Integer.parseInt(a.substring(6));
        }
        final int W = 1600, H = 1000;
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        VolatileImage vi = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
        // a compatible VolatileImage's graphics carries the display's default transform (2 on a Retina display):
        // `scale` multiplies it, and the glyph pixel size is size x deviceScale
        final double deviceScale = gc.getDefaultTransform().getScaleX() * scale;
        BufferedImage sync = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Font font = new Font("Helvetica Neue", Font.PLAIN, size);
        Random rnd = new Random(42);
        String[] pool = new String[strings];
        for (int i = 0; i < strings; i++) pool[i] = word(rnd, len);
        Color[] colours = new Color[strings];
        for (int i = 0; i < strings; i++) colours[i] = new Color(rnd.nextInt(0x1000000));
        if (palette > 0) for (int i = 0; i < strings; i++) colours[i] = colours[i % palette];
        final int cellW = (int) (len * size * 0.62) + 16, lineH = size + 4;   // layout in user space; W/H are device pixels
        int cols = Math.max(1, W / scale / cellW), rows = (strings + cols - 1) / cols;
        long[] caller = new long[frames], total = new long[frames];
        long first = -1;
        final String[] counterNames = {"glyphsDrawnBySkia", "glyphsShapedBySkia", "paragraphsBySkia", "flushes"};
        long[] counters0 = counters(counterNames);
        long[] gcs0 = glyphCacheStats();
        long startNanos = System.nanoTime();
        for (int frame = 0; frame < frames + warmup; frame++) {
            if (distinct) for (int i = 0; i < strings; i++) pool[i] = word(rnd, len);
            long t0 = System.nanoTime();
            Graphics2D g = vi.createGraphics();
            g.setColor(Color.WHITE); g.fillRect(0, 0, W, H);
            if (scale != 1) g.scale(scale, scale);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    lcd ? RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB : RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setColor(Color.BLACK); g.setFont(font);
            for (int i = 0; i < strings; i++) {
                float x = 8 + (i % cols) * cellW, y = lineH + (i / cols) * lineH % (H / scale - lineH);
                if (colors) g.setColor(colours[i]);
                if (aci) {
                    AttributedString as = new AttributedString(pool[i]);
                    as.addAttribute(TextAttribute.FONT, font);
                    g.drawString(as.getIterator(), x, y);
                } else {
                    g.drawString(pool[i], x, y);
                }
            }
            g.dispose();
            long t1 = System.nanoTime();
            Graphics2D sg = sync.createGraphics();
            sg.drawImage(vi, 0, 0, 1, 1, 5, 5, 6, 6, null);
            sg.dispose();
            sync.getRGB(0, 0);
            long t2 = System.nanoTime();
            if (frame == 0) first = t2 - t0;
            if (frame == warmup) { counters0 = counters(counterNames); gcs0 = glyphCacheStats(); }
            if (frame >= warmup) { caller[frame - warmup] = t1 - t0; total[frame - warmup] = t2 - t0; }
        }
        java.util.Arrays.sort(caller); java.util.Arrays.sort(total);
        double medC = caller[frames / 2] / 1e6, medT = total[frames / 2] / 1e6, p90T = total[frames * 9 / 10] / 1e6;
        long glyphs = (long) strings * len;
        long[] counters1 = counters(counterNames);
        long[] gcs = glyphCacheStats();
        for (int i = 0; i < gcs.length && i < gcs0.length; i++) gcs[i] -= gcs0[i];
        StringBuilder cs = new StringBuilder();
        for (int i = 0; i < counterNames.length; i++) if (counters1[i] - counters0[i] != 0) cs.append(' ').append(counterNames[i]).append('+').append(counters1[i] - counters0[i]);
        if (gcs.length >= 5) cs.append(String.format(" | glyph cache per frame: cached %.0f staged %.0f evicted %.0f uploads %.0f uncached %.0f",
                gcs[0] / (double) frames, gcs[1] / (double) frames, gcs[2] / (double) frames, gcs[3] / (double) frames, gcs[4] / (double) frames));
        String rss = new String(new ProcessBuilder("ps", "-o", "rss=", "-p", Long.toString(ProcessHandle.current().pid())).start().getInputStream().readAllBytes()).trim();
        System.out.printf("strings=%d len=%d size=%d device-scale=%.0f (%.0f px glyphs) %s%s%s: caller median %.2f ms, total median %.2f ms (p90 %.2f), %.1f us/string, %.2f us/glyph; first frame %.1f ms; rss %d MB; over the %d measured frames:%s%n",
                strings, len, size, deviceScale, size * deviceScale, distinct ? "distinct" : "same", (aci ? " aci" : "") + (colors ? (palette > 0 ? " palette=" + palette : " colors") : ""), lcd ? " lcd" : "",
                medC, medT, p90T, medT * 1000 / strings, medT * 1000 / glyphs, first / 1e6, Long.parseLong(rss) / 1024, frames, cs.length() == 0 ? " no Skia text-path counter moved" : cs.toString());
    }

    /** The Skia backend's counters (SKEnvironment), zeros when the class or the export is missing. */
    static long[] counters(String[] names) {
        long[] out = new long[names.length];
        try {
            Class<?> env = Class.forName("sun.java2d.skia.SKEnvironment");
            for (int i = 0; i < names.length; i++) out[i] = ((Number) env.getMethod(names[i]).invoke(null)).longValue();
        } catch (Throwable t) {
            // not a Skia-capable image, or sun.java2d.skia not exported: all zero
        }
        return out;
    }

    static long[] glyphCacheStats() {
        try {
            return (long[]) Class.forName("sun.java2d.skia.SKEnvironment").getMethod("glyphCacheStats").invoke(null);
        } catch (Throwable t) {
            return new long[0];
        }
    }

    static String word(Random rnd, int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append((char) ('a' + rnd.nextInt(26)));
        if (len > 0) sb.setCharAt(0, Character.toUpperCase(sb.charAt(0)));
        return sb.toString();
    }
}
