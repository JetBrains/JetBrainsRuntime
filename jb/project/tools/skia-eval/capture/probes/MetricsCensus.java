import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import sun.font.Font2D;
import sun.font.FontUtilities;
import sun.font.SKFontScaler;

/**
 * Metrics census (work package 4 of the project brief): how far the Skia font scaler's strike
 * metrics and glyph advances are from today's scaler's, over real fonts, sizes, display scales,
 * with and without fractional metrics. Reports distributions, asserts nothing — the decision
 * whether a drift is acceptable is the design triage's.
 *
 * Per font and configuration: the ascent / descent / leading difference in device pixels, the
 * number of the 190 sampled characters (ASCII and Latin-1 letters) whose advance differs, how
 * many of those differ by a whole device pixel under whole-pixel metrics (the "flips" that move
 * text), and the width difference of three sample strings. The summary at the end gives, per
 * font, the worst metric drift and the flip rate over all configurations.
 *
 * Usage: java --add-opens java.desktop/sun.font=ALL-UNNAMED --add-exports java.desktop/sun.font=ALL-UNNAMED
 *             --add-exports java.desktop/sun.java2d.skia=ALL-UNNAMED MetricsCensus [family ...]
 * (default families: the IDE's bundled Inter and JetBrains Mono, the platform's UI and monospace
 * fonts, Arial and Times New Roman where installed). Needs a JDK built with the Skia font host.
 */
public class MetricsCensus {
    static final int[] SIZES = {8, 9, 10, 11, 12, 13, 14, 16, 18, 20, 24, 32};
    static final double[] SCALES = {1, 1.25, 1.5, 2};
    static final String[] SAMPLES = {"The quick brown fox jumps over the lazy dog", "public static void main(String[] args) {", "Settings | Editor | Font: 13 pt, line spacing 1.2"};

    public static void main(String[] args) throws Exception {
        boolean mac = System.getProperty("os.name", "").startsWith("Mac");
        List<String> families = new ArrayList<>();
        if (args.length > 0) families.addAll(List.of(args));
        else {
            families.add("Inter"); families.add("JetBrains Mono");
            if (mac) { families.add("Helvetica Neue"); families.add("Menlo"); families.add("SF Pro Text"); }
            else { families.add("Segoe UI"); families.add("Consolas"); }
            families.add("Arial"); families.add("Times New Roman");
        }
        List<String> installed = List.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        Method getStrike = accessible(Font2D.class.getDeclaredMethod("getStrike", Font.class, FontRenderContext.class));
        Method getMapper = accessible(Font2D.class.getDeclaredMethod("getMapper"));
        Class<?> strikeClass = Class.forName("sun.font.FontStrike");
        Method strikeMetrics = accessible(strikeClass.getDeclaredMethod("getFontMetrics"));
        Method strikeAdvance = accessible(strikeClass.getDeclaredMethod("getGlyphAdvance", int.class));
        Method scalerMetrics = accessible(SKFontScaler.class.getDeclaredMethod("getFontMetrics", long.class));
        Method scalerAdvance = accessible(SKFontScaler.class.getDeclaredMethod("getGlyphAdvance", long.class, int.class));
        Method deviceContext = accessible(SKFontScaler.class.getDeclaredMethod("createScalerContext", double[].class, int.class, int.class, float.class, float.class));
        Method userContext = accessible(SKFontScaler.class.getDeclaredMethod("createScalerContext", double[].class, double.class, int.class, int.class, float.class, float.class));
        Class<?> sm = Class.forName("sun.font.StrikeMetrics");
        Field[] fields = {accessible(sm.getDeclaredField("ascentY")), accessible(sm.getDeclaredField("descentY")), accessible(sm.getDeclaredField("leadingY"))};
        Class<?> cfont = classOrNull("sun.font.CFont");
        Method nativePtr = cfont != null ? accessible(cfont.getDeclaredMethod("getNativeFontPtr")) : null;
        Class<?> composite = classOrNull("sun.font.CompositeFont");
        int[] chars = new int[190]; int n = 0;
        for (int c = 32; c <= 126; c++) chars[n++] = c;
        for (int c = 0xA1; c <= 0xFF; c++) chars[n++] = c;

        System.out.println("font | size | scale | fm | ascent d | descent d | leading d | advances differing / 190 | whole-pixel flips | width d: fox / code / settings (device px)");
        for (String family : families) {
            if (!installed.contains(family)) { System.out.println("# " + family + ": not installed"); continue; }
            Font base = new Font(family, Font.PLAIN, 12);
            Font2D font2D = FontUtilities.getFont2D(base);
            if (composite != null && composite.isInstance(font2D)) {
                font2D = (Font2D) accessible(composite.getDeclaredMethod("getSlotFont", int.class)).invoke(font2D, 0);
            }
            SKFontScaler scaler; boolean userSpaceRounding;
            if (cfont != null && cfont.isInstance(font2D)) {
                scaler = SKFontScaler.forNativeFont(font2D, (long) nativePtr.invoke(font2D)); userSpaceRounding = true;
            } else {
                Field platName = accessible(Class.forName("sun.font.PhysicalFont").getDeclaredField("platName"));
                Field index = accessible(Class.forName("sun.font.TrueTypeFont").getDeclaredField("fontIndex"));
                scaler = SKFontScaler.forFile(font2D, (String) platName.get(font2D), index.getInt(font2D)); userSpaceRounding = false;
            }
            Object mapper = getMapper.invoke(font2D);
            Method charToGlyph = accessible(mapper.getClass().getMethod("charToGlyph", char.class));
            double worstMetric = 0; int configs = 0, flipsTotal = 0, differingTotal = 0; double worstWidth = 0;
            for (int size : SIZES) for (double scale : SCALES) for (boolean fm : new boolean[] {false, true}) {
                Font font = base.deriveFont((float) size);
                FontRenderContext frc = new FontRenderContext(AffineTransform.getScaleInstance(scale, scale), RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
                        fm ? RenderingHints.VALUE_FRACTIONALMETRICS_ON : RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
                Object strike = getStrike.invoke(font2D, font, frc);
                Object reference = strikeMetrics.invoke(strike);
                double[] matrix = {size * scale, 0, 0, size * scale};
                int fmHint = fm ? 2 : 1;
                long context = userSpaceRounding ? (long) userContext.invoke(scaler, matrix, scale, 2, fmHint, 1f, 0f)
                                                 : (long) deviceContext.invoke(scaler, matrix, 2, fmHint, 1f, 0f);
                Object skia = scalerMetrics.invoke(scaler, context);
                double[] md = new double[3];
                for (int i = 0; i < 3; i++) { md[i] = fields[i].getFloat(skia) - fields[i].getFloat(reference) * scale; worstMetric = Math.max(worstMetric, Math.abs(md[i])); }   // device px
                int differing = 0, flips = 0;
                float[] advRef = new float[chars.length], advSkia = new float[chars.length];
                for (int i = 0; i < chars.length; i++) {
                    int glyph = (int) charToGlyph.invoke(mapper, (char) chars[i]);
                    advRef[i] = (float) strikeAdvance.invoke(strike, glyph) * (float) scale;   // device px
                    advSkia[i] = (float) scalerAdvance.invoke(scaler, context, glyph);
                    if (Math.abs(advRef[i] - advSkia[i]) > 1e-3f) { differing++; if (!fm && Math.abs(advRef[i] - advSkia[i]) >= 0.999f) flips++; }
                }
                double[] wd = new double[SAMPLES.length];
                for (int s = 0; s < SAMPLES.length; s++) {
                    double a = 0, b = 0;
                    for (char c : SAMPLES[s].toCharArray()) { int glyph = (int) charToGlyph.invoke(mapper, c); a += (float) strikeAdvance.invoke(strike, glyph) * scale; b += (float) scalerAdvance.invoke(scaler, context, glyph); }
                    wd[s] = b - a; worstWidth = Math.max(worstWidth, Math.abs(wd[s]));
                }
                differingTotal += differing; flipsTotal += flips; configs++;
                System.out.printf(Locale.ROOT, "%s | %d | %.2f | %s | %+.3f | %+.3f | %+.3f | %d | %d | %+.2f / %+.2f / %+.2f%n", family, size, scale, fm ? "fractional" : "whole", md[0], md[1], md[2], differing, flips, wd[0], wd[1], wd[2]);
            }
            System.out.printf(Locale.ROOT, "## %s: %d configurations; worst metric drift %.3f device px; advances differing %d of %d (%.1f %%); whole-pixel flips %d of %d (%.2f %%); worst sample-string width drift %.2f device px%n%n",
                    family, configs, worstMetric, differingTotal, configs * chars.length, 100.0 * differingTotal / (configs * chars.length), flipsTotal, configs / 2 * chars.length, 100.0 * flipsTotal / (configs / 2 * chars.length), worstWidth);
        }
    }

    static Class<?> classOrNull(String name) { try { return Class.forName(name); } catch (ClassNotFoundException e) { return null; } }
    static <T extends AccessibleObject> T accessible(T m) { m.setAccessible(true); return m; }
}
