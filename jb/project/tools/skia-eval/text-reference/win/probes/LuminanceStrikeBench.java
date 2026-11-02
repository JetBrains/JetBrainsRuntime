import java.awt.*; import java.awt.image.BufferedImage; import java.io.File; import java.lang.reflect.Field; import java.util.*;
/** Strikes and cold cost when glyph images depend on the text colour: the Dark theme's text colours x fonts.
 *  Warm-up with the first colour, then the first draw of every further colour is timed on its own (a new strike
 *  and its glyph images when the luminance class is new), plus a repeat draw. */
public class LuminanceStrikeBench {
    static final String TEXT = "The quick brown fox jumps over the lazy dog 0123456789 {}[]();:,.<>=+-*/&|!?\"'#%@~^_";
    static final int[] COLOURS = {0xBCBEC4, 0xCF8E6D, 0x56A8F5, 0x6AAB73, 0x2AACB8, 0xC77DBB, 0x7A7E85, 0xD5B778, 0xB3AE60, 0xF75464, 0xDFDFDF, 0x6F737A, 0x5F826B, 0xE8E8E8, 0x8C8C8C, 0xA9B7C6};
    public static void main(String[] a) throws Exception {
        File fonts = new File(System.getProperty("java.home"), "lib/fonts");
        Font[] faces = { Font.createFont(Font.TRUETYPE_FONT, new File(fonts, "JetBrainsMono-Regular.ttf")).deriveFont(13f),
                         Font.createFont(Font.TRUETYPE_FONT, new File(fonts, "Inter-Regular.otf")).deriveFont(13f),
                         new Font("Helvetica Neue", Font.PLAIN, 13) };
        BufferedImage im = new BufferedImage(1200, 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = im.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        // warm-up: every font once, the first colour, several times
        for (int r = 0; r < 20; r++) for (Font f : faces) { g.setFont(f); g.setColor(new Color(COLOURS[0])); g.drawString(TEXT, 4, 26); }
        java.util.List<Double> firsts = new ArrayList<>(), repeats = new ArrayList<>(); Set<Integer> classes = new TreeSet<>();
        for (Font f : faces) {
            g.setFont(f);
            for (int i = 1; i < COLOURS.length; i++) {
                Color c = new Color(COLOURS[i]); g.setColor(c);
                classes.add(((c.getRed() * 299 + c.getGreen() * 587 + c.getBlue() * 114) / 1000) >> 4);
                long t0 = System.nanoTime(); g.drawString(TEXT, 4, 26); long t1 = System.nanoTime(); g.drawString(TEXT, 4, 26); long t2 = System.nanoTime();
                firsts.add((t1 - t0) / 1e6); repeats.add((t2 - t1) / 1e6);
            }
        }
        Collections.sort(firsts); Collections.sort(repeats);
        int strikes = 0;
        Field cache = Class.forName("sun.font.Font2D").getDeclaredField("strikeCache"); cache.setAccessible(true);
        Class<?> fu = Class.forName("sun.font.FontUtilities");
        for (Font f : faces) strikes += ((Map<?, ?>) cache.get(fu.getMethod("getFont2D", Font.class).invoke(null, f))).size();
        System.out.printf("%-9s %d colours in %d luminance classes x %d fonts -> %d strikes; first draw of a colour: median %.2f ms, max %.2f ms (%d glyphs); repeat median %.3f ms%n",
            System.getProperty("sun.java2d.skia.text.gray", "platform"), COLOURS.length, classes.size() + 1, faces.length, strikes,
            firsts.get(firsts.size() / 2), firsts.get(firsts.size() - 1), TEXT.length(), repeats.get(repeats.size() / 2));
        System.exit(0);
    }
}
