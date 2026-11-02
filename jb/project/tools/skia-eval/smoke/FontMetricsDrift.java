import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

import sun.font.Font2D;
import sun.font.FontUtilities;

/**
 * Metrics drift of the Skia font scaler (platform host) against the platform's
 * own strike (CoreText on macOS), per font x size x scale x fractional metrics:
 * strike metrics (ascent, descent, leading) and the advances of ASCII 32..126.
 *   java --add-exports java.desktop/sun.font=ALL-UNNAMED --add-opens java.desktop/sun.font=ALL-UNNAMED
 *        -Dsun.java2d.skia=true FontMetricsDrift [fontfile|family ...]
 */
public class FontMetricsDrift {
    static final int[] SIZES = {8, 9, 10, 11, 12, 13, 14, 16, 18, 20, 24, 32};
    static final double[] SCALES = {1, 1.25, 1.5, 2};

    public static void main(String[] args) throws Exception {
        List<Font> fonts = new ArrayList<>();
        if (args.length == 0) args = new String[] {"Dialog", "Helvetica Neue", "Menlo"};
        for (String a : args) {
            if (new File(a).isFile()) fonts.add(Font.createFont(Font.TRUETYPE_FONT, new File(a)));
            else fonts.add(new Font(a, Font.PLAIN, 12));
        }
        Class<?> cfont = Class.forName("sun.font.CFont");
        Method nativePtr = cfont.getDeclaredMethod("getNativeFontPtr"); nativePtr.setAccessible(true);
        Class<?> scalerClass = Class.forName("sun.font.SKFontScaler");
        Method forNative = scalerClass.getMethod("forNativeFont", Font2D.class, long.class);
        Method createContext = scalerClass.getDeclaredMethod("createScalerContext", double[].class, int.class, int.class, float.class, float.class); createContext.setAccessible(true);
        Method createContextUser = scalerClass.getDeclaredMethod("createScalerContext", double[].class, double.class, int.class, int.class, float.class, float.class); createContextUser.setAccessible(true);
        Method skMetrics = scalerClass.getDeclaredMethod("getFontMetrics", long.class); skMetrics.setAccessible(true);
        Method skAdvance = scalerClass.getDeclaredMethod("getGlyphAdvance", long.class, int.class); skAdvance.setAccessible(true);
        Method getStrike = Font2D.class.getDeclaredMethod("getStrike", Font.class, FontRenderContext.class); getStrike.setAccessible(true);
        Class<?> strikeClass = Class.forName("sun.font.FontStrike");
        Method stMetrics = strikeClass.getDeclaredMethod("getFontMetrics"); stMetrics.setAccessible(true);
        Method stAdvance = strikeClass.getDeclaredMethod("getGlyphAdvance", int.class); stAdvance.setAccessible(true);
        Class<?> smClass = Class.forName("sun.font.StrikeMetrics");
        Field fAy = smClass.getDeclaredField("ascentY"), fDy = smClass.getDeclaredField("descentY"), fLy = smClass.getDeclaredField("leadingY");
        for (Field f : new Field[] {fAy, fDy, fLy}) f.setAccessible(true);

        System.out.printf("%-22s %-4s %5s %-3s | %8s %8s %8s | %8s %8s %6s %6s %6s%n", "font", "pt", "scale", "fm",
                          "dAscent", "dDescent", "dLeading", "advMax", "advMean", "devDiff", "usrDiff", "n");
        for (Font base : fonts) {
            Font2D font2D = FontUtilities.getFont2D(base);
            Object scaler; String engine;
            if (cfont.isInstance(font2D)) {
                scaler = forNative.invoke(null, font2D, (long) nativePtr.invoke(font2D)); engine = "CoreText";
            } else {
                Field platName = Class.forName("sun.font.PhysicalFont").getDeclaredField("platName"); platName.setAccessible(true);
                Field fIndex = Class.forName("sun.font.TrueTypeFont").getDeclaredField("fontIndex"); fIndex.setAccessible(true);
                scaler = scalerClass.getMethod("forFile", Font2D.class, String.class, int.class).invoke(null, font2D, (String) platName.get(font2D), fIndex.getInt(font2D));
                engine = "FreeType";
            }
            System.out.println("# " + base.getFontName() + ": reference strike engine " + engine + " (" + font2D.getClass().getSimpleName() + ")");
            Method getMapper = Font2D.class.getDeclaredMethod("getMapper"); getMapper.setAccessible(true);
            Object mapper = getMapper.invoke(font2D);
            Method charToGlyph = mapper.getClass().getMethod("charToGlyph", char.class); charToGlyph.setAccessible(true);
            int[] glyphs = new int[95]; for (int c = 32; c <= 126; c++) glyphs[c - 32] = (int) charToGlyph.invoke(mapper, (char) c);
            for (int size : SIZES) for (double scale : SCALES) for (boolean fm : new boolean[] {false, true}) {
                Font f = base.deriveFont((float) size);
                FontRenderContext frc = new FontRenderContext(AffineTransform.getScaleInstance(scale, scale),
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON, fm ? RenderingHints.VALUE_FRACTIONALMETRICS_ON : RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
                Object strike = getStrike.invoke(font2D, f, frc);
                Object sm = stMetrics.invoke(strike);
                double[] matrix = {size * scale, 0, 0, size * scale};
                // CoreText strikes round whole-pixel advances in user space, FreeType's in device space
                long ctx = engine.equals("CoreText") ? (long) createContextUser.invoke(scaler, matrix, scale, 2, fm ? 2 : 1, 1f, 0f)
                                                     : (long) createContext.invoke(scaler, matrix, 2 /*AA ON*/, fm ? 2 : 1, 1f, 0f);
                long ctxLinear = (long) createContext.invoke(scaler, matrix, 2, 2 /*fm on: unrounded*/, 1f, 0f);
                Object km = skMetrics.invoke(scaler, ctx);
                double dA = fAy.getFloat(km) / scale - fAy.getFloat(sm), dD = fDy.getFloat(km) / scale - fDy.getFloat(sm), dL = fLy.getFloat(km) / scale - fLy.getFloat(sm);
                double max = 0, sum = 0; int flips = 0, userRoundMisses = 0;
                for (int g : glyphs) {
                    float a = (float) stAdvance.invoke(strike, g), b = (float) ((float) skAdvance.invoke(scaler, ctx, g) / scale);
                    double d = Math.abs(a - b); max = Math.max(max, d); sum += d;
                    if (!fm && a != b) flips++;
                    // the alternative rule: round the unrounded advance in user space (CStrike's)
                    float linearUser = (float) ((float) skAdvance.invoke(scaler, ctxLinear, g) / scale);
                    if (!fm && a != Math.round(linearUser)) userRoundMisses++;
                }
                System.out.printf("%-22s %-4d %5.2f %-3s | %+8.3f %+8.3f %+8.3f | %8.3f %8.4f %6d %6d %6d%n", base.getFontName(), size, scale, fm ? "on" : "off",
                                  dA, dD, dL, max, sum / glyphs.length, flips, userRoundMisses, glyphs.length);
            }
        }
    }
}
